package com.parkenergyplatform.service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.net.URL;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.config.ParkCosProperties;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.DeleteObjectRequest;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DeviceCatalogService {
    private static final String DRAFT = "DRAFT";
    private static final String PUBLISHED = "PUBLISHED";
    private static final String DISABLED = "DISABLED";
    private static final Set<String> PROTOCOL_TYPES = Set.of("JSON", "MODBUS_RTU", "MODBUS_TCP");
    private static final Set<String> POINT_TYPES = Set.of("DOUBLE", "INTEGER", "LONG", "DECIMAL", "STRING", "BOOLEAN");
    private static final Set<String> IMAGE_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<COSClient> cosClientProvider;
    private final ParkCosProperties cosProperties;

    public DeviceCatalogService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                ObjectProvider<COSClient> cosClientProvider, ParkCosProperties cosProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.cosClientProvider = cosClientProvider;
        this.cosProperties = cosProperties;
    }

    public List<Map<String, Object>> tree(String keyword) {
        return tree(keyword, null);
    }

    public List<Map<String, Object>> tree(String keyword, String depth) {
        CompletableFuture<List<Map<String, Object>>> categoriesFuture = CompletableFuture.supplyAsync(() -> jdbcTemplate.queryForList("""
                SELECT id, parent_id, category_code, category_name, description, enabled
                FROM dev_device_category
                WHERE enabled = 1
                ORDER BY parent_id, sort, id
                """));
        CompletableFuture<List<Map<String, Object>>> seriesFuture = CompletableFuture.supplyAsync(() -> jdbcTemplate.queryForList("""
                SELECT s.id, s.category_id, s.brand_id, s.series_code, s.series_name, s.description,
                       b.brand_code, b.brand_name
                FROM dev_product_series s
                JOIN dev_brand b ON b.id = s.brand_id
                WHERE s.enabled = 1 AND b.enabled = 1
                ORDER BY b.brand_name, s.series_name, s.id
                """));
        CompletableFuture<List<Map<String, Object>>> categoryBrandsFuture = CompletableFuture.supplyAsync(() -> jdbcTemplate.queryForList("""
                SELECT cb.category_id,b.id AS brand_id,b.brand_code,b.brand_name,b.description
                FROM dev_device_category_brand cb
                JOIN dev_brand b ON b.id=cb.brand_id
                WHERE b.enabled=1
                ORDER BY cb.category_id,b.brand_name,b.id
                """));
        CompletableFuture<List<Map<String, Object>>> modelsFuture = CompletableFuture.supplyAsync(() -> jdbcTemplate.queryForList("""
                SELECT m.id, m.series_id, m.model_code, m.model_name, m.description, m.image_object_key, m.status,
                       m.current_published_version_id
                FROM dev_device_model m
                ORDER BY m.model_name, m.id
                """));
        CompletableFuture<List<Map<String, Object>>> versionsFuture = CompletableFuture.supplyAsync(() -> jdbcTemplate.queryForList("""
                SELECT id,model_id,version_no,version_name,device_type_id,status
                FROM dev_device_model_version ORDER BY model_id,version_no DESC
                """));
        CompletableFuture.allOf(categoriesFuture, seriesFuture, categoryBrandsFuture, modelsFuture, versionsFuture).join();
        List<Map<String, Object>> categories = categoriesFuture.join();
        List<Map<String, Object>> seriesRows = seriesFuture.join();
        List<Map<String, Object>> categoryBrandRows = categoryBrandsFuture.join();
        List<Map<String, Object>> models = modelsFuture.join();
        List<Map<String, Object>> versions = versionsFuture.join();

        Map<Long, Map<String, Object>> categoryNodes = new LinkedHashMap<>();
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> row : categories) {
            Map<String, Object> node = node("CATEGORY", longValue(row.get("id")), text(row.get("category_name")),
                    text(row.get("category_code")), null);
            node.put("parent_id", row.get("parent_id"));
            node.put("description", row.get("description"));
            categoryNodes.put(longValue(row.get("id")), node);
        }
        for (Map<String, Object> row : categories) {
            Map<String, Object> node = categoryNodes.get(longValue(row.get("id")));
            Long parentId = longValue(row.get("parent_id"));
            Map<String, Object> parent = parentId == null || parentId == 0 ? null : categoryNodes.get(parentId);
            if (parent == null) roots.add(node); else children(parent).add(node);
        }
        if ("category".equalsIgnoreCase(depth) && !StringUtils.hasText(keyword)) return roots;

        Map<Long, Map<String, Object>> seriesNodes = new LinkedHashMap<>();
        Map<String, Map<String, Object>> brandNodes = new LinkedHashMap<>();
        for (Map<String, Object> row : categoryBrandRows) {
            Long categoryId = longValue(row.get("category_id"));
            Map<String, Object> category = categoryNodes.get(categoryId);
            if (category == null) continue;
            Long brandId = longValue(row.get("brand_id"));
            String brandKey = categoryId + ":" + brandId;
            brandNodes.computeIfAbsent(brandKey, ignored -> {
                Map<String, Object> created = node("BRAND", brandId, text(row.get("brand_name")), text(row.get("brand_code")), null);
                created.put("categoryId", categoryId);
                created.put("description", row.get("description"));
                children(category).add(created);
                return created;
            });
        }
        for (Map<String, Object> row : seriesRows) {
            Long categoryId = longValue(row.get("category_id"));
            Map<String, Object> category = categoryNodes.get(categoryId);
            if (category == null) continue;
            Long brandId = longValue(row.get("brand_id"));
            String brandKey = categoryId + ":" + brandId;
            Map<String, Object> brand = brandNodes.computeIfAbsent(brandKey, ignored -> {
                Map<String, Object> created = node("BRAND", brandId, text(row.get("brand_name")), text(row.get("brand_code")), null);
                created.put("categoryId", categoryId);
                created.put("description", row.get("description"));
                children(category).add(created);
                return created;
            });
            Long seriesId = longValue(row.get("id"));
            Map<String, Object> series = node("SERIES", seriesId, text(row.get("series_name")), text(row.get("series_code")), null);
            series.put("categoryId", categoryId);
            series.put("brandId", brandId);
            series.put("description", row.get("description"));
            children(brand).add(series);
            seriesNodes.put(seriesId, series);
        }
        Map<Long, Map<String, Object>> modelNodes = new LinkedHashMap<>();
        for (Map<String, Object> row : models) {
            Map<String, Object> series = seriesNodes.get(longValue(row.get("series_id")));
            if (series == null) continue;
            Map<String, Object> model = node("MODEL", longValue(row.get("id")), text(row.get("model_name")),
                    text(row.get("model_code")), text(row.get("status")));
            model.put("seriesId", row.get("series_id"));
            model.put("description", row.get("description"));
            model.put("image_object_key", row.get("image_object_key"));
            model.put("versionId", row.get("current_published_version_id"));
            children(series).add(model);
            modelNodes.put(longValue(row.get("id")), model);
        }
        for (Map<String, Object> row : versions) {
            Map<String, Object> model = modelNodes.get(longValue(row.get("model_id")));
            if (model == null) continue;
            Map<String, Object> version = node("VERSION", longValue(row.get("id")), text(row.get("version_name")),
                    "V" + row.get("version_no"), text(row.get("status")));
            version.put("modelId", row.get("model_id"));
            version.put("versionId", row.get("id"));
            version.put("versionStatus", row.get("status"));
            version.put("deviceTypeId", row.get("device_type_id"));
            children(model).add(version);
        }
        return StringUtils.hasText(keyword) ? filterNodes(roots, keyword.trim().toLowerCase(Locale.ROOT)) : roots;
    }

    public List<Map<String, Object>> attributeTree() {
        List<Map<String, Object>> groups = jdbcTemplate.queryForList("""
                SELECT id, parent_id, group_code, group_name
                FROM dev_attribute_group WHERE enabled = 1 ORDER BY parent_id, sort, id
                """);
        List<Map<String, Object>> attributes = jdbcTemplate.queryForList("""
                SELECT id, group_id, attribute_code, attribute_name, data_type, usage_type, unit, required, allow_override, default_value, value_mode
                FROM dev_attribute_definition WHERE enabled = 1 AND value_mode = 'FIXED' ORDER BY group_id, sort, id
                """);
        List<Map<String, Object>> values = jdbcTemplate.queryForList("""
                SELECT id,attribute_id,value_code,value_text,sort,enabled
                FROM dev_attribute_value_option WHERE enabled=1 ORDER BY attribute_id,sort,id
                """);
        Map<Long, Map<String, Object>> nodes = new LinkedHashMap<>();
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> row : groups) {
            nodes.put(longValue(row.get("id")), node("ATTRIBUTE_GROUP", longValue(row.get("id")),
                    text(row.get("group_name")), text(row.get("group_code")), null));
        }
        for (Map<String, Object> row : groups) {
            Map<String, Object> current = nodes.get(longValue(row.get("id")));
            Map<String, Object> parent = nodes.get(longValue(row.get("parent_id")));
            if (parent == null) roots.add(current); else children(parent).add(current);
        }
        Map<Long, Map<String, Object>> attributeNodes = new LinkedHashMap<>();
        for (Map<String, Object> row : attributes) {
            Map<String, Object> parent = nodes.get(longValue(row.get("group_id")));
            if (parent == null) continue;
            Map<String, Object> attribute = node("ATTRIBUTE", longValue(row.get("id")),
                    text(row.get("attribute_name")), text(row.get("attribute_code")), null);
            attribute.putAll(row);
            children(parent).add(attribute);
            attributeNodes.put(longValue(row.get("id")), attribute);
        }
        for (Map<String, Object> row : values) {
            Map<String, Object> parent = attributeNodes.get(longValue(row.get("attribute_id")));
            if (parent == null) continue;
            Map<String, Object> option = node("ATTRIBUTE_VALUE", longValue(row.get("id")),
                    text(row.get("value_text")), text(row.get("value_code")), null);
            option.putAll(row);
            children(parent).add(option);
        }
        return roots;
    }

    public List<Map<String, Object>> standardPointTree() {
        List<Map<String, Object>> categories = jdbcTemplate.queryForList("""
                SELECT id, parent_id, group_code, group_name FROM dev_standard_point_group
                WHERE enabled=1 ORDER BY parent_id, sort,id
                """);
        List<Map<String, Object>> points = jdbcTemplate.queryForList("""
                SELECT id, group_id, point_code, point_name, data_type, unit, business_role,
                       value_mode, realtime_key, description, enabled
                FROM dev_standard_point WHERE enabled=1 AND value_mode='REALTIME' ORDER BY group_id,point_name,id
                """);
        List<Map<String, Object>> roots = new ArrayList<>();
        Map<Long, Map<String, Object>> categoryNodes = new LinkedHashMap<>();
        for (Map<String, Object> row : categories) {
            Map<String, Object> node = node("POINT_GROUP", longValue(row.get("id")), text(row.get("group_name")), text(row.get("group_code")), null);
            node.putAll(row);
            categoryNodes.put(longValue(row.get("id")), node);
            roots.add(node);
        }
        Map<String, Object> common = node("POINT_CATEGORY", 0L, "通用测点", "COMMON", null);
        for (Map<String, Object> row : points) {
            Map<String, Object> parent = categoryNodes.get(longValue(row.get("group_id")));
            if (parent == null) continue;
            Map<String, Object> point = node("STANDARD_POINT", longValue(row.get("id")), text(row.get("point_name")), text(row.get("point_code")), null);
            point.putAll(row);
            children(parent).add(point);
        }
        return roots;
    }

    public Map<String, Object> lookups() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("categories", jdbcTemplate.queryForList("SELECT id, parent_id, category_code, category_name FROM dev_device_category WHERE enabled=1 ORDER BY sort,id"));
        result.put("brands", jdbcTemplate.queryForList("SELECT id, brand_code, brand_name FROM dev_brand WHERE enabled=1 ORDER BY brand_name,id"));
        result.put("series", jdbcTemplate.queryForList("SELECT id, category_id, brand_id, series_code, series_name FROM dev_product_series WHERE enabled=1 ORDER BY series_name,id"));
        result.put("attributeGroups", jdbcTemplate.queryForList("SELECT id, parent_id, group_code, group_name FROM dev_attribute_group WHERE enabled=1 ORDER BY parent_id,sort,id"));
        result.put("attributes", jdbcTemplate.queryForList("SELECT id, group_id, category_id, attribute_code, attribute_name, data_type, usage_type, unit, required, allow_override, default_value, enum_options, validation_rule, value_mode FROM dev_attribute_definition WHERE enabled=1 AND value_mode='FIXED' ORDER BY group_id,sort,id"));
        result.put("attributeValues", jdbcTemplate.queryForList("SELECT id,attribute_id,value_code,value_text,sort FROM dev_attribute_value_option WHERE enabled=1 ORDER BY attribute_id,sort,id"));
        result.put("standardPointGroups", jdbcTemplate.queryForList("SELECT id,parent_id,group_code,group_name FROM dev_standard_point_group WHERE enabled=1 ORDER BY parent_id,sort,id"));
        result.put("standardPoints", jdbcTemplate.queryForList("SELECT id,group_id,point_code,point_name,data_type,unit,business_role,value_mode,realtime_key,description FROM dev_standard_point WHERE enabled=1 AND value_mode='REALTIME' ORDER BY group_id,point_name,id"));
        return result;
    }

    @Transactional
    public Map<String, Object> createCategory(Map<String, Object> body) {
        require(body, "categoryCode", "分类编码不能为空");
        require(body, "categoryName", "分类名称不能为空");
        long id = insert("INSERT INTO dev_device_category(parent_id,category_code,category_name,description,sort,enabled) VALUES(?,?,?,?,?,1)",
                longOrDefault(value(body, "parentId", "parent_id"), 0), text(value(body, "categoryCode", "category_code")),
                text(value(body, "categoryName", "category_name")), text(value(body, "description")),
                longOrDefault(value(body, "sort"), 0));
        return single("SELECT * FROM dev_device_category WHERE id=?", id);
    }

    @Transactional
    public Map<String, Object> createBrand(Map<String, Object> body) {
        require(body, "brandCode", "品牌编码不能为空");
        require(body, "brandName", "品牌名称不能为空");
        String brandCode = text(value(body, "brandCode", "brand_code"));
        String brandName = text(value(body, "brandName", "brand_name"));
        Long categoryId = requiredLong(body, "categoryId", "品牌必须绑定设备分类");
        assertEnabledCategory(categoryId);
        List<Map<String, Object>> existing = jdbcTemplate.queryForList("SELECT * FROM dev_brand WHERE brand_code=?", brandCode);
        long id;
        if (existing.isEmpty()) {
            id = insert("INSERT INTO dev_brand(brand_code,brand_name,description,enabled) VALUES(?,?,?,1)",
                    brandCode, brandName, text(value(body, "description")));
        } else {
            id = longValue(existing.get(0).get("id"));
            if (boolInt(existing.get(0).get("enabled")) != 1) throw new BusinessException("该品牌已停用，不能重新绑定");
        }
        if (categoryId != null) bindBrandCategory(categoryId, id);
        Map<String, Object> result = single("SELECT * FROM dev_brand WHERE id=?", id);
        result.put("categories", jdbcTemplate.queryForList("""
                SELECT c.id,c.category_code,c.category_name
                FROM dev_device_category_brand cb JOIN dev_device_category c ON c.id=cb.category_id
                WHERE cb.brand_id=? ORDER BY c.sort,c.id
                """, id));
        return result;
    }

    @Transactional
    public Map<String, Object> createSeries(Map<String, Object> body) {
        Long categoryId = requiredLong(body, "categoryId", "设备分类不能为空");
        Long brandId = requiredLong(body, "brandId", "品牌不能为空");
        assertEnabledCategory(categoryId);
        assertEnabledBrand(brandId);
        bindBrandCategory(categoryId, brandId);
        require(body, "seriesCode", "系列编码不能为空");
        require(body, "seriesName", "系列名称不能为空");
        long id = insert("INSERT INTO dev_product_series(category_id,brand_id,series_code,series_name,description,enabled) VALUES(?,?,?,?,?,1)",
                categoryId, brandId, text(value(body, "seriesCode", "series_code")), text(value(body, "seriesName", "series_name")), text(value(body, "description")));
        return single("SELECT * FROM dev_product_series WHERE id=?", id);
    }

    @Transactional
    public Map<String, Object> createAttributeGroup(Map<String, Object> body) {
        require(body, "groupCode", "属性分类编码不能为空");
        require(body, "groupName", "属性分类名称不能为空");
        long id = insert("INSERT INTO dev_attribute_group(parent_id,group_code,group_name,sort,enabled) VALUES(?,?,?,?,1)",
                longOrDefault(value(body, "parentId", "parent_id"), 0), text(value(body, "groupCode", "group_code")),
                text(value(body, "groupName", "group_name")), longOrDefault(value(body, "sort"), 0));
        return single("SELECT * FROM dev_attribute_group WHERE id=?", id);
    }

    @Transactional
    public Map<String, Object> createPointGroup(Map<String, Object> body) {
        require(body, "groupCode", "测点组编码不能为空");
        require(body, "groupName", "测点组名称不能为空");
        long id = insert("INSERT INTO dev_standard_point_group(parent_id,group_code,group_name,sort,enabled) VALUES(?,?,?,?,1)",
                longOrDefault(value(body, "parentId", "parent_id"), 0), text(value(body, "groupCode", "group_code")),
                text(value(body, "groupName", "group_name")), longOrDefault(value(body, "sort"), 0));
        return single("SELECT * FROM dev_standard_point_group WHERE id=?", id);
    }

    @Transactional
    public Map<String, Object> createAttribute(Map<String, Object> body) {
        Long groupId = requiredLong(body, "groupId", "属性分类不能为空");
        require(body, "attributeCode", "属性编码不能为空");
        require(body, "attributeName", "属性名称不能为空");
        long id = insert("""
                INSERT INTO dev_attribute_definition
                (group_id,category_id,attribute_code,attribute_name,data_type,usage_type,unit,required,allow_override,default_value,enum_options,validation_rule,sort,enabled,value_mode)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,1,'FIXED')
                """, groupId, longValue(value(body, "categoryId", "category_id")), text(value(body, "attributeCode", "attribute_code")), text(value(body, "attributeName", "attribute_name")),
                normalizeUpper(value(body, "dataType", "data_type"), "STRING"), normalizeUpper(value(body, "usageType", "usage_type"), "SPEC"),
                text(value(body, "unit")), boolInt(value(body, "required")),
                0, text(value(body, "defaultValue", "default_value")),
                jsonText(value(body, "enumOptions", "enum_options")), jsonText(value(body, "validationRule", "validation_rule")),
                longOrDefault(value(body, "sort"), 0));
        return single("SELECT * FROM dev_attribute_definition WHERE id=?", id);
    }

    @Transactional
    public Map<String, Object> createAttributeValue(Map<String, Object> body) {
        Long attributeId = requiredLong(body, "attributeId", "属性不能为空");
        require(body, "valueCode", "固定值编码不能为空");
        require(body, "valueText", "固定值不能为空");
        if (count("SELECT COUNT(*) FROM dev_attribute_definition WHERE id=? AND enabled=1", attributeId) == 0) {
            throw new BusinessException("属性不存在或已停用");
        }
        long id = insert("INSERT INTO dev_attribute_value_option(attribute_id,value_code,value_text,sort,enabled) VALUES(?,?,?,?,1)",
                attributeId, text(value(body, "valueCode", "value_code")), text(value(body, "valueText", "value_text")),
                longOrDefault(value(body, "sort"), 0));
        return single("SELECT * FROM dev_attribute_value_option WHERE id=?", id);
    }

    @Transactional
    public Map<String, Object> createStandardPoint(Map<String, Object> body) {
        Long groupId = requiredLong(body, "groupId", "测点组不能为空");
        require(body, "pointCode", "标准测点编码不能为空");
        require(body, "pointName", "标准测点名称不能为空");
        long id = insert("""
                INSERT INTO dev_standard_point
                (group_id,point_code,point_name,data_type,unit,business_role,description,enabled,value_mode)
                VALUES(?,?,?,?,?,?,?,1,'REALTIME')
                """, groupId, text(value(body, "pointCode", "point_code")),
                text(value(body, "pointName", "point_name")), textOr(value(body, "dataType", "data_type"), "DOUBLE"),
                text(value(body, "unit")), text(value(body, "businessRole", "business_role")), text(value(body, "description")));
        return single("SELECT * FROM dev_standard_point WHERE id=?", id);
    }

    @Transactional
    public Map<String, Object> updateNode(String nodeType, long id, Map<String, Object> body) {
        switch (nodeType.toUpperCase(Locale.ROOT)) {
            case "CATEGORY" -> {
                require(body, "categoryCode", "分类编码不能为空");
                require(body, "categoryName", "分类名称不能为空");
                Long parentId = longOrDefault(value(body, "parentId", "parent_id"), 0);
                if (Objects.equals(parentId, id)) throw new BusinessException("分类不能设置为自身的上级");
                if (parentId != null && parentId > 0) assertEnabledCategory(parentId);
                jdbcTemplate.update("UPDATE dev_device_category SET parent_id=?,category_code=?,category_name=?,description=? WHERE id=?",
                        parentId, text(value(body, "categoryCode", "category_code")), text(value(body, "categoryName", "category_name")),
                        text(value(body, "description")), id);
                return single("SELECT * FROM dev_device_category WHERE id=?", id);
            }
            case "BRAND" -> {
                require(body, "brandCode", "品牌编码不能为空");
                require(body, "brandName", "品牌名称不能为空");
                Long categoryId = requiredLong(body, "categoryId", "品牌必须绑定设备分类");
                assertEnabledCategory(categoryId);
                jdbcTemplate.update("UPDATE dev_brand SET brand_code=?,brand_name=?,description=? WHERE id=?",
                        text(value(body, "brandCode", "brand_code")), text(value(body, "brandName", "brand_name")),
                        text(value(body, "description")), id);
                bindBrandCategory(categoryId, id);
                return single("SELECT * FROM dev_brand WHERE id=?", id);
            }
            case "SERIES" -> {
                require(body, "seriesCode", "系列编码不能为空");
                require(body, "seriesName", "系列名称不能为空");
                Long categoryId = requiredLong(body, "categoryId", "设备分类不能为空");
                Long brandId = requiredLong(body, "brandId", "品牌不能为空");
                assertEnabledCategory(categoryId);
                assertEnabledBrand(brandId);
                bindBrandCategory(categoryId, brandId);
                jdbcTemplate.update("UPDATE dev_product_series SET category_id=?,brand_id=?,series_code=?,series_name=?,description=? WHERE id=?",
                        categoryId, brandId, text(value(body, "seriesCode", "series_code")), text(value(body, "seriesName", "series_name")),
                        text(value(body, "description")), id);
                return single("SELECT * FROM dev_product_series WHERE id=?", id);
            }
            case "MODEL" -> {
                require(body, "modelCode", "型号编码不能为空");
                require(body, "modelName", "型号名称不能为空");
                Long seriesId = requiredLong(body, "seriesId", "产品系列不能为空");
                if (count("SELECT COUNT(*) FROM dev_product_series WHERE id=? AND enabled=1", seriesId) == 0) {
                    throw new BusinessException("产品系列不存在或已停用");
                }
                jdbcTemplate.update("UPDATE dev_device_model SET series_id=?,model_code=?,model_name=?,description=?,image_object_key=? WHERE id=?",
                        seriesId, text(value(body, "modelCode", "model_code")), text(value(body, "modelName", "model_name")),
                        text(value(body, "description")), text(value(body, "imageObjectKey", "image_object_key")), id);
                return single("SELECT * FROM dev_device_model WHERE id=?", id);
            }
            case "ATTRIBUTE_GROUP" -> {
                require(body, "groupCode", "属性组编码不能为空");
                require(body, "groupName", "属性组名称不能为空");
                jdbcTemplate.update("UPDATE dev_attribute_group SET parent_id=?,group_code=?,group_name=?,sort=? WHERE id=?",
                        longOrDefault(value(body, "parentId", "parent_id"), 0), text(value(body, "groupCode", "group_code")),
                        text(value(body, "groupName", "group_name")), longOrDefault(value(body, "sort"), 0), id);
                return single("SELECT * FROM dev_attribute_group WHERE id=?", id);
            }
            case "ATTRIBUTE" -> {
                require(body, "attributeCode", "属性编码不能为空");
                require(body, "attributeName", "属性名称不能为空");
                jdbcTemplate.update("""
                        UPDATE dev_attribute_definition SET group_id=?,category_id=?,attribute_code=?,attribute_name=?,
                        data_type=?,usage_type=?,unit=?,required=?,allow_override=0,default_value=?,enum_options=?,validation_rule=?,sort=?,value_mode='FIXED'
                        WHERE id=?
                        """, requiredLong(body, "groupId", "属性分组不能为空"), longValue(value(body, "categoryId", "category_id")),
                        text(value(body, "attributeCode", "attribute_code")), text(value(body, "attributeName", "attribute_name")),
                        normalizeUpper(value(body, "dataType", "data_type"), "STRING"), normalizeUpper(value(body, "usageType", "usage_type"), "SPEC"),
                        text(value(body, "unit")), boolInt(value(body, "required")),
                        text(value(body, "defaultValue", "default_value")), jsonText(value(body, "enumOptions", "enum_options")),
                        jsonText(value(body, "validationRule", "validation_rule")), longOrDefault(value(body, "sort"), 0), id);
                return single("SELECT * FROM dev_attribute_definition WHERE id=?", id);
            }
            case "ATTRIBUTE_VALUE" -> {
                require(body, "valueCode", "固定值编码不能为空");
                require(body, "valueText", "固定值不能为空");
                jdbcTemplate.update("UPDATE dev_attribute_value_option SET value_code=?,value_text=?,sort=? WHERE id=?",
                        text(value(body, "valueCode", "value_code")), text(value(body, "valueText", "value_text")),
                        longOrDefault(value(body, "sort"), 0), id);
                return single("SELECT * FROM dev_attribute_value_option WHERE id=?", id);
            }
            case "POINT_GROUP" -> {
                require(body, "groupCode", "测点组编码不能为空");
                require(body, "groupName", "测点组名称不能为空");
                jdbcTemplate.update("UPDATE dev_standard_point_group SET parent_id=?,group_code=?,group_name=?,sort=? WHERE id=?",
                        longOrDefault(value(body, "parentId", "parent_id"), 0), text(value(body, "groupCode", "group_code")),
                        text(value(body, "groupName", "group_name")), longOrDefault(value(body, "sort"), 0), id);
                return single("SELECT * FROM dev_standard_point_group WHERE id=?", id);
            }
            case "STANDARD_POINT" -> {
                require(body, "pointCode", "标准测点编码不能为空");
                require(body, "pointName", "标准测点名称不能为空");
                jdbcTemplate.update("""
                        UPDATE dev_standard_point SET group_id=?,point_code=?,point_name=?,data_type=?,unit=?,business_role=?,
                        description=?,value_mode='REALTIME' WHERE id=?
                        """, requiredLong(body, "groupId", "测点组不能为空"), text(value(body, "pointCode", "point_code")),
                        text(value(body, "pointName", "point_name")), textOr(value(body, "dataType", "data_type"), "DOUBLE"), text(value(body, "unit")),
                        text(value(body, "businessRole", "business_role")), text(value(body, "description")), id);
                return single("SELECT * FROM dev_standard_point WHERE id=?", id);
            }
            default -> throw new BusinessException("不支持编辑的目录节点: " + nodeType);
        }
    }

    @Transactional
    public Map<String, Object> deleteNode(String nodeType, long id) {
        switch (nodeType.toUpperCase(Locale.ROOT)) {
            case "CATEGORY" -> {
                if (count("SELECT COUNT(*) FROM dev_device_category WHERE parent_id=?", id) > 0) throw new BusinessException("分类存在下级分类，不能删除");
                if (count("SELECT COUNT(*) FROM dev_product_series WHERE category_id=?", id) > 0) throw new BusinessException("分类下存在产品系列，不能删除");
                if (count("SELECT COUNT(*) FROM dev_attribute_definition WHERE category_id=?", id) > 0) throw new BusinessException("分类下存在专属属性，不能删除");
                jdbcTemplate.update("DELETE FROM dev_device_category_brand WHERE category_id=?", id);
                jdbcTemplate.update("DELETE FROM dev_device_category WHERE id=?", id);
            }
            case "BRAND" -> {
                if (count("SELECT COUNT(*) FROM dev_product_series WHERE brand_id=?", id) > 0) throw new BusinessException("品牌下存在产品系列，不能删除");
                jdbcTemplate.update("DELETE FROM dev_device_category_brand WHERE brand_id=?", id);
                jdbcTemplate.update("DELETE FROM dev_brand WHERE id=?", id);
            }
            case "SERIES" -> {
                if (count("SELECT COUNT(*) FROM dev_device_model WHERE series_id=?", id) > 0) throw new BusinessException("系列下存在型号，不能删除");
                jdbcTemplate.update("DELETE FROM dev_product_series WHERE id=?", id);
            }
            case "MODEL" -> {
                List<Long> versionIds = jdbcTemplate.queryForList("SELECT id FROM dev_device_model_version WHERE model_id=? ORDER BY id", Long.class, id);
                for (Long versionId : versionIds) deleteVersion(versionId);
                if (count("SELECT COUNT(*) FROM dev_device_model WHERE id=?", id) > 0) {
                    jdbcTemplate.update("DELETE FROM dev_device_model WHERE id=?", id);
                }
            }
            case "ATTRIBUTE_GROUP" -> {
                if (count("SELECT COUNT(*) FROM dev_attribute_group WHERE parent_id=?", id) > 0 || count("SELECT COUNT(*) FROM dev_attribute_definition WHERE group_id=?", id) > 0) {
                    throw new BusinessException("属性组存在下级节点或属性，不能删除");
                }
                jdbcTemplate.update("DELETE FROM dev_attribute_group WHERE id=?", id);
            }
            case "ATTRIBUTE" -> {
                if (count("SELECT COUNT(*) FROM dev_model_attribute_value WHERE attribute_id=?", id) > 0) throw new BusinessException("属性已被产品版本引用，不能删除");
                if (count("SELECT COUNT(*) FROM dev_attribute_value_option WHERE attribute_id=?", id) > 0) throw new BusinessException("属性存在固定值，不能删除");
                jdbcTemplate.update("DELETE FROM dev_attribute_definition WHERE id=?", id);
            }
            case "ATTRIBUTE_VALUE" -> {
                if (count("SELECT COUNT(*) FROM dev_model_attribute_value WHERE attribute_value_option_id=?", id) > 0) throw new BusinessException("固定值已被产品版本引用，不能删除");
                jdbcTemplate.update("DELETE FROM dev_attribute_value_option WHERE id=?", id);
            }
            case "POINT_GROUP" -> {
                if (count("SELECT COUNT(*) FROM dev_standard_point_group WHERE parent_id=?", id) > 0 || count("SELECT COUNT(*) FROM dev_standard_point WHERE group_id=?", id) > 0) {
                    throw new BusinessException("测点组存在下级节点或测点，不能删除");
                }
                jdbcTemplate.update("DELETE FROM dev_standard_point_group WHERE id=?", id);
            }
            case "STANDARD_POINT" -> {
                if (count("SELECT COUNT(*) FROM dev_point_definition WHERE standard_point_id=?", id) > 0) throw new BusinessException("测点已被产品版本引用，不能删除");
                jdbcTemplate.update("DELETE FROM dev_standard_point WHERE id=?", id);
            }
            default -> throw new BusinessException("不支持删除的目录节点: " + nodeType);
        }
        return Map.of("deleted", true, "nodeType", nodeType, "id", id);
    }

    @Transactional
    public Map<String, Object> createModel(Map<String, Object> body) {
        Long seriesId = requiredLong(body, "seriesId", "产品系列不能为空");
        require(body, "modelCode", "型号编码不能为空");
        require(body, "modelName", "型号名称不能为空");
        Map<String, Object> series = single("""
                SELECT s.*, c.category_code, b.brand_code
                FROM dev_product_series s
                JOIN dev_device_category c ON c.id=s.category_id
                JOIN dev_brand b ON b.id=s.brand_id
                WHERE s.id=?
                """, seriesId);
        String modelCode = text(value(body, "modelCode", "model_code"));
        String modelName = text(value(body, "modelName", "model_name"));
        Long protocolVersionId = longValue(value(body, "protocolProfileVersionId", "protocol_profile_version_id"));
        String protocolType = textOr(value(body, "protocolType", "protocol_type"), "JSON");
        if (protocolVersionId != null) {
            Map<String, Object> protocol = single("""
                    SELECT v.id,p.transport_type FROM dev_protocol_profile_version v
                    JOIN dev_protocol_profile p ON p.id=v.profile_id
                    WHERE v.id=? AND v.status='PUBLISHED' AND p.enabled=1
                    """, protocolVersionId);
            protocolType = text(protocol.get("transport_type"));
        }
        long modelId = insert("INSERT INTO dev_device_model(series_id,model_code,model_name,description,image_object_key,status) VALUES(?,?,?,?,?,?)",
                seriesId, modelCode, modelName, text(value(body, "description")),
                text(value(body, "imageObjectKey", "image_object_key")), DRAFT);
        String technicalCode = technicalTypeCode(seriesId, modelCode, 1);
        long deviceTypeId = insert("INSERT INTO dev_device_type(type_code,type_name,protocol_type,description,enabled) VALUES(?,?,?,?,0)",
                technicalCode, modelName + " V1", protocolType,
                "Catalog model " + modelCode + " draft V1");
        long versionId = insert("""
                INSERT INTO dev_device_model_version
                (model_id,version_no,version_name,device_type_id,protocol_profile_version_id,status,collect_interval_seconds,quality_threshold_pct,remark)
                VALUES(?,1,'V1',?,?,'DRAFT',?,?,?)
                """, modelId, deviceTypeId, protocolVersionId, longOrDefault(value(body, "collectIntervalSeconds"), 300),
                decimalText(value(body, "qualityThresholdPct"), "80"), text(value(body, "remark")));
        Map<String, Object> result;
        if (protocolVersionId == null) {
            result = detail(modelId, versionId);
        } else {
            applyProtocolTemplate(versionId, Map.of("protocolProfileVersionId", protocolVersionId));
            result = publish(versionId);
        }
        result.put("series", series);
        return result;
    }

    public Map<String, Object> detail(long modelId, Long versionId) {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> model = single("""
                SELECT m.*, s.series_code, s.series_name, b.id AS brand_id, b.brand_code, b.brand_name,
                       c.id AS category_id, c.category_code, c.category_name
                FROM dev_device_model m
                JOIN dev_product_series s ON s.id=m.series_id
                JOIN dev_brand b ON b.id=s.brand_id
                JOIN dev_device_category c ON c.id=s.category_id
                WHERE m.id=?
                """, modelId);
        model.put("image_url", modelImagePreviewUrl(text(model.get("image_object_key"))));
        List<Map<String, Object>> versions = jdbcTemplate.queryForList("""
                SELECT v.*, t.type_code, t.type_name, t.protocol_type,
                       (SELECT COUNT(*) FROM dev_device d WHERE d.model_version_id=v.id) AS reference_count
                FROM dev_device_model_version v
                JOIN dev_device_type t ON t.id=v.device_type_id
                WHERE v.model_id=? ORDER BY v.version_no DESC
                """, modelId);
        if (versions.isEmpty()) throw new BusinessException(404, "型号没有可用版本");
        Map<String, Object> selected = versionId == null
                ? versions.stream().filter(item -> DRAFT.equals(text(item.get("status")))).findFirst().orElse(versions.get(0))
                : versions.stream().filter(item -> Objects.equals(longValue(item.get("id")), versionId)).findFirst()
                    .orElseThrow(() -> new BusinessException(404, "型号版本不存在"));
        long selectedVersionId = longValue(selected.get("id"));
        long deviceTypeId = longValue(selected.get("device_type_id"));
        data.put("model", model);
        data.put("versions", versions);
        data.put("version", selected);
        data.put("attributes", jdbcTemplate.queryForList("""
                SELECT a.id AS attribute_id, a.attribute_code, a.attribute_name, a.data_type, a.usage_type, a.unit,
                       a.required, a.allow_override, a.default_value, a.enum_options, a.validation_rule, a.value_mode,
                       g.group_name, v.attribute_value_option_id, COALESCE(o.value_text,v.attribute_value,a.default_value) AS attribute_value
                FROM dev_attribute_definition a
                JOIN dev_attribute_group g ON g.id=a.group_id
                LEFT JOIN dev_model_attribute_value v ON v.attribute_id=a.id AND v.model_version_id=?
                LEFT JOIN dev_attribute_value_option o ON o.id=v.attribute_value_option_id
                WHERE a.enabled=1 AND (a.category_id IS NULL OR a.category_id=?)
                ORDER BY g.sort,g.id,a.sort,a.id
                """, selectedVersionId, longValue(model.get("category_id"))));
        data.put("points", jdbcTemplate.queryForList("""
                SELECT p.*, pg.group_name AS point_group_name,b.protocol_field_id,b.canonical_factor,b.canonical_offset,
                       b.display_factor,b.display_unit,b.required,f.field_code,f.field_name,f.document_address,
                       f.value_type,f.raw_unit,rb.function_code,rb.start_address,rb.register_count
                FROM dev_point_definition p
                LEFT JOIN dev_standard_point sp ON sp.id=p.standard_point_id
                LEFT JOIN dev_standard_point_group pg ON pg.id=sp.group_id
                LEFT JOIN dev_device_model_version mv ON mv.device_type_id=p.device_type_id
                LEFT JOIN dev_model_point_binding b ON b.model_version_id=mv.id AND BINARY b.point_code=BINARY p.point_code
                LEFT JOIN dev_protocol_field f ON f.id=b.protocol_field_id
                LEFT JOIN dev_protocol_read_block rb ON rb.id=f.read_block_id
                WHERE p.device_type_id=? ORDER BY p.sort,p.id
                """, deviceTypeId));
        data.put("devices", jdbcTemplate.queryForList("""
                SELECT d.id,d.device_sn,d.device_name,d.org_id,d.gateway_id,d.edge_channel_id,d.protocol_addr,
                       d.status,o.org_name,g.gateway_name,g.gateway_sn,gc.channel_name,gc.serial_port
                FROM dev_device d
                LEFT JOIN dev_org o ON o.id=d.org_id
                LEFT JOIN dev_gateway g ON g.id=d.gateway_id
                LEFT JOIN dev_gateway_channel gc ON gc.gateway_id=d.gateway_id AND BINARY gc.channel_id=BINARY d.edge_channel_id
                WHERE d.model_version_id=? ORDER BY d.id DESC LIMIT 100
                """, selectedVersionId));
        return data;
    }

    @Transactional
    public Map<String, Object> modelImageUploadUrl(long modelId, Map<String, Object> body) {
        Map<String, Object> model = single("""
                SELECT m.*, s.series_code, s.series_name, b.brand_code, b.brand_name, c.category_code, c.category_name
                FROM dev_device_model m
                JOIN dev_product_series s ON s.id=m.series_id
                JOIN dev_brand b ON b.id=s.brand_id
                JOIN dev_device_category c ON c.id=s.category_id
                WHERE m.id=?
                """, modelId);
        String fileName = text(value(body, "filename", "fileName"));
        String contentType = text(value(body, "contentType", "content_type"));
        long size = longOrDefault(value(body, "size", "fileSize"), 0);
        requireText(fileName, "文件名不能为空");
        requireText(contentType, "文件类型不能为空");
        if (!IMAGE_CONTENT_TYPES.contains(contentType)) throw new BusinessException("仅支持 JPEG、PNG、WebP、GIF 图片");
        if (size <= 0) throw new BusinessException("图片大小不能为空");
        if (size > 5L * 1024 * 1024) throw new BusinessException("图片不能超过 5MB");
        String ext = imageExtension(fileName, contentType);
        String objectKey = normalizedObjectPrefix() + modelId + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
        java.util.Date expiration = java.util.Date.from(Instant.now().plusSeconds(Math.max(60L, cosProperties.urlExpireSeconds())));
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(cosProperties.bucket(), objectKey, HttpMethodName.PUT);
        request.setExpiration(expiration);
        URL uploadUrl = cosClient().generatePresignedUrl(request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", model);
        result.put("objectKey", objectKey);
        result.put("uploadUrl", uploadUrl.toString());
        result.put("previewUrl", modelImagePreviewUrl(objectKey));
        result.put("contentType", contentType);
        result.put("expiresAt", expiration.toInstant().toString());
        result.put("maxSizeBytes", 5L * 1024 * 1024);
        return result;
    }

    @Transactional
    public Map<String, Object> uploadModelImage(long modelId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException("图片文件不能为空");
        String contentType = text(file.getContentType());
        if (!IMAGE_CONTENT_TYPES.contains(contentType)) throw new BusinessException("仅支持 JPEG、PNG、WebP、GIF 图片");
        if (file.getSize() > 5L * 1024 * 1024) throw new BusinessException("图片不能超过 5MB");
        Map<String, Object> current = single("SELECT image_object_key FROM dev_device_model WHERE id=?", modelId);
        String objectKey = normalizedObjectPrefix() + modelId + "/" + UUID.randomUUID().toString().replace("-", "")
                + "." + imageExtension(textOr(file.getOriginalFilename(), "model-image"), contentType);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.getSize());
        metadata.setContentType(contentType);
        try {
            cosClient().putObject(new PutObjectRequest(cosProperties.bucket(), objectKey, file.getInputStream(), metadata));
        } catch (IOException exception) {
            throw new BusinessException("读取图片文件失败");
        } catch (RuntimeException exception) {
            throw new BusinessException("图片上传对象存储失败: " + exception.getMessage());
        }
        jdbcTemplate.update("UPDATE dev_device_model SET image_object_key=? WHERE id=?", objectKey, modelId);
        String oldObjectKey = text(current.get("image_object_key"));
        if (!Objects.equals(oldObjectKey, objectKey)) deleteObjectQuietly(oldObjectKey);
        return detail(modelId, null);
    }

    @Transactional
    public Map<String, Object> saveModelImage(long modelId, Map<String, Object> body) {
        String imageObjectKey = text(value(body, "imageObjectKey", "image_object_key"));
        requireText(imageObjectKey, "图片对象键不能为空");
        Map<String, Object> current = single("SELECT image_object_key FROM dev_device_model WHERE id=?", modelId);
        jdbcTemplate.update("UPDATE dev_device_model SET image_object_key=? WHERE id=?", imageObjectKey, modelId);
        String oldObjectKey = text(current.get("image_object_key"));
        if (!Objects.equals(oldObjectKey, imageObjectKey)) deleteObjectQuietly(oldObjectKey);
        return detail(modelId, null);
    }

    @Transactional
    public Map<String, Object> clearModelImage(long modelId) {
        Map<String, Object> current = single("SELECT image_object_key FROM dev_device_model WHERE id=?", modelId);
        jdbcTemplate.update("UPDATE dev_device_model SET image_object_key=NULL WHERE id=?", modelId);
        deleteObjectQuietly(text(current.get("image_object_key")));
        return detail(modelId, null);
    }

    @Transactional
    public Map<String, Object> updateVersion(long versionId, Map<String, Object> body) {
        Map<String, Object> version = requireDraft(versionId);
        jdbcTemplate.update("""
                UPDATE dev_device_model_version
                SET collect_interval_seconds=?, quality_threshold_pct=?, remark=?
                WHERE id=?
                """, longOrDefault(value(body, "collectIntervalSeconds", "collect_interval_seconds"),
                        longOrDefault(version.get("collect_interval_seconds"), 300)),
                decimalText(value(body, "qualityThresholdPct", "quality_threshold_pct"), text(version.get("quality_threshold_pct"))),
                text(value(body, "remark")), versionId);
        if (value(body, "protocolType", "protocol_type") != null) {
            String protocolType = normalizeProtocol(value(body, "protocolType", "protocol_type"));
            jdbcTemplate.update("UPDATE dev_device_type SET protocol_type=? WHERE id=?",
                    protocolType, longValue(version.get("device_type_id")));
        }
        if (value(body, "protocolProfileVersionId", "protocol_profile_version_id") != null) {
            Object rawProtocolVersionId = value(body, "protocolProfileVersionId", "protocol_profile_version_id");
            Long protocolVersionId = longValue(rawProtocolVersionId);
            if (protocolVersionId == null) {
                jdbcTemplate.update("UPDATE dev_device_model_version SET protocol_profile_version_id=NULL WHERE id=?", versionId);
            } else {
                Map<String, Object> protocol = single("""
                        SELECT v.id,p.transport_type FROM dev_protocol_profile_version v
                        JOIN dev_protocol_profile p ON p.id=v.profile_id
                        WHERE v.id=? AND v.status='PUBLISHED' AND p.enabled=1
                        """, protocolVersionId);
                jdbcTemplate.update("UPDATE dev_device_model_version SET protocol_profile_version_id=? WHERE id=?", protocolVersionId, versionId);
                jdbcTemplate.update("UPDATE dev_device_type SET protocol_type=? WHERE id=?",
                        protocol.get("transport_type"), longValue(version.get("device_type_id")));
            }
        }
        return detail(longValue(version.get("model_id")), versionId);
    }

    @Transactional
    public Map<String, Object> applyProtocolTemplate(long versionId, Map<String, Object> body) {
        Map<String, Object> version = requireDraft(versionId);
        Long protocolVersionId = requiredLong(body, "protocolProfileVersionId", "请选择协议模板");
        Map<String, Object> protocol = single("""
                SELECT v.id,p.transport_type FROM dev_protocol_profile_version v
                JOIN dev_protocol_profile p ON p.id=v.profile_id
                WHERE v.id=? AND v.status='PUBLISHED' AND p.enabled=1
                """, protocolVersionId);
        jdbcTemplate.update("UPDATE dev_device_model_version SET protocol_profile_version_id=? WHERE id=?", protocolVersionId, versionId);
        jdbcTemplate.update("UPDATE dev_device_type SET protocol_type=? WHERE id=?",
                protocol.get("transport_type"), longValue(version.get("device_type_id")));

        List<Map<String, Object>> attributes = jdbcTemplate.queryForList("""
                SELECT t.attribute_id AS attributeId,t.attribute_value_option_id AS attributeValueOptionId,
                       COALESCE(o.value_text,t.attribute_value,a.default_value,'') AS attributeValue
                FROM dev_protocol_attribute_template t
                JOIN dev_attribute_definition a ON a.id=t.attribute_id AND a.enabled=1
                LEFT JOIN dev_attribute_value_option o ON o.id=t.attribute_value_option_id
                WHERE t.protocol_version_id=?
                ORDER BY t.sort,t.id
                """, protocolVersionId);
        replaceAttributes(versionId, attributes);

        List<Map<String, Object>> fields = jdbcTemplate.queryForList("""
                SELECT f.id AS protocolFieldId,f.standard_point_id AS standardPointId,
                       sp.point_code AS pointCode,sp.point_name AS pointName,sp.data_type AS dataType,
                       COALESCE(sp.unit,f.raw_unit,'') AS unit,sp.business_role AS businessRole,
                       f.decode_factor AS canonicalFactor,f.decode_offset AS canonicalOffset,
                       1 AS displayFactor,COALESCE(sp.unit,f.raw_unit,'') AS displayUnit,
                       f.required AS required,f.sort AS sort
                FROM dev_protocol_field f
                JOIN dev_standard_point sp ON sp.id=f.standard_point_id AND sp.enabled=1
                WHERE f.protocol_version_id=?
                ORDER BY f.sort,f.id
                """, protocolVersionId);
        replacePoints(versionId, fields);
        return detail(longValue(version.get("model_id")), versionId);
    }

    @Transactional
    public Map<String, Object> replaceAttributes(long versionId, List<Map<String, Object>> attributes) {
        Map<String, Object> version = requireDraft(versionId);
        Long categoryId = jdbcTemplate.queryForObject("""
                SELECT s.category_id FROM dev_device_model_version v
                JOIN dev_device_model m ON m.id=v.model_id JOIN dev_product_series s ON s.id=m.series_id
                WHERE v.id=?
                """, Long.class, versionId);
        Set<Long> seen = new HashSet<>();
        List<Map<String, Object>> verified = new ArrayList<>();
        for (Map<String, Object> item : attributes == null ? List.<Map<String, Object>>of() : attributes) {
            Long attributeId = longValue(value(item, "attributeId", "attribute_id"));
            if (attributeId == null) continue;
            if (!seen.add(attributeId)) throw new BusinessException("属性不能重复绑定: " + attributeId);
            Map<String, Object> definition = single("""
                    SELECT * FROM dev_attribute_definition
                    WHERE id=? AND enabled=1 AND (category_id IS NULL OR category_id=?)
                    """, attributeId, categoryId);
            Long optionId = longValue(value(item, "attributeValueOptionId", "attribute_value_option_id", "optionId", "option_id"));
            String rawAttributeValue = text(value(item, "attributeValue", "attribute_value"));
            Map<String, Object> option = optionId == null
                    ? resolveAttributeOption(attributeId, rawAttributeValue, text(definition.get("default_value")))
                    : single("SELECT * FROM dev_attribute_value_option WHERE id=? AND attribute_id=? AND enabled=1", optionId, attributeId);
            if (option != null) optionId = longValue(option.get("id"));
            String attributeValue = option == null ? rawAttributeValue : text(option.get("value_text"));
            if (option == null && count("SELECT COUNT(*) FROM dev_attribute_value_option WHERE attribute_id=? AND enabled=1", attributeId) > 0) {
                throw new BusinessException(text(definition.get("attribute_name")) + " 必须选择一个固定值");
            }
            String error = StringUtils.hasText(attributeValue) ? validateAttributeValue(definition, attributeValue) : null;
            if (error != null) throw new BusinessException(text(definition.get("attribute_name")) + ": " + error);
            Map<String, Object> binding = new LinkedHashMap<>();
            binding.put("attributeId", attributeId);
            binding.put("optionId", optionId);
            binding.put("attributeValue", attributeValue == null ? "" : attributeValue);
            verified.add(binding);
        }
        jdbcTemplate.update("DELETE FROM dev_model_attribute_value WHERE model_version_id=?", versionId);
        for (Map<String, Object> item : verified) {
            Long attributeId = longValue(item.get("attributeId"));
            jdbcTemplate.update("INSERT INTO dev_model_attribute_value(model_version_id,attribute_id,attribute_value_option_id,attribute_value) VALUES(?,?,?,?)",
                    versionId, attributeId, longValue(item.get("optionId")), text(item.get("attributeValue")));
        }
        return detail(longValue(version.get("model_id")), versionId);
    }

    private Map<String, Object> resolveAttributeOption(Long attributeId, String value, String fallbackValue) {
        List<Map<String, Object>> options = jdbcTemplate.queryForList(
                "SELECT * FROM dev_attribute_value_option WHERE attribute_id=? AND enabled=1 ORDER BY sort,id",
                attributeId);
        if (options.isEmpty()) return null;
        String normalizedValue = normalizeOptionText(value);
        String normalizedFallback = normalizeOptionText(fallbackValue);
        for (Map<String, Object> option : options) {
            if (Objects.equals(normalizedValue, normalizeOptionText(option.get("value_text")))) return option;
        }
        if (!StringUtils.hasText(value)) {
            for (Map<String, Object> option : options) {
                if (Objects.equals(normalizedFallback, normalizeOptionText(option.get("value_text")))) return option;
            }
        }
        return null;
    }

    private String normalizeOptionText(Object value) {
        String text = text(value);
        if (!StringUtils.hasText(text)) return "";
        return text.toLowerCase(Locale.ROOT)
                .replace('×', 'x')
                .replaceAll("\\s+", "");
    }

    @Transactional
    public Map<String, Object> replacePoints(long versionId, List<Map<String, Object>> points) {
        Map<String, Object> version = requireDraft(versionId);
        long deviceTypeId = longValue(version.get("device_type_id"));
        boolean protocolSelected = version.get("protocol_profile_version_id") != null;
        jdbcTemplate.update("DELETE FROM dev_model_point_binding WHERE model_version_id=?", versionId);
        jdbcTemplate.update("DELETE FROM dev_point_definition WHERE device_type_id=?", deviceTypeId);
        int sort = 0;
        Set<String> pointCodes = new HashSet<>();
        for (Map<String, Object> point : points == null ? List.<Map<String, Object>>of() : points) {
            String code = text(value(point, "pointCode", "point_code"));
            String name = text(value(point, "pointName", "point_name"));
            if (!StringUtils.hasText(code) || !StringUtils.hasText(name)) continue;
            code = code.trim();
            if (!pointCodes.add(code.toUpperCase(Locale.ROOT))) throw new BusinessException("测点编码重复: " + code);
            String pointType = normalizeUpper(value(point, "dataType", "data_type"), "DOUBLE");
            if (!POINT_TYPES.contains(pointType)) throw new BusinessException("测点 " + code + " 的数据类型不受支持");
            Long standardPointId = longValue(value(point, "standardPointId", "standard_point_id"));
            if (standardPointId != null && count("SELECT COUNT(*) FROM dev_standard_point WHERE id=? AND enabled=1", standardPointId) == 0) {
                throw new BusinessException("引用的全域标准测点不存在或已停用");
            }
            jdbcTemplate.update("""
                    INSERT INTO dev_point_definition
                    (device_type_id,standard_point_id,point_code,point_name,data_type,unit,precision_scale,business_role,billable,stat_enabled,sort,enabled)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                    """, deviceTypeId, standardPointId, code, name, pointType,
                    text(value(point, "unit")), longOrDefault(value(point, "precisionScale", "precision_scale"), 2),
                    text(value(point, "businessRole", "business_role")), boolInt(value(point, "billable")),
                    value(point, "statEnabled", "stat_enabled") == null ? 1 : boolInt(value(point, "statEnabled", "stat_enabled")),
                    longOrDefault(value(point, "sort"), ++sort),
                    value(point, "enabled") == null ? 1 : boolInt(value(point, "enabled")));
            Long fieldId = longValue(value(point, "protocolFieldId", "protocol_field_id"));
            if (fieldId == null && protocolSelected) throw new BusinessException("测点 " + code + " 必须绑定协议字段");
            if (fieldId == null) continue;
            Long fieldExists = count("""
                    SELECT COUNT(*) FROM dev_protocol_field f
                    JOIN dev_device_model_version v ON v.protocol_profile_version_id=f.protocol_version_id
                    WHERE v.id=? AND f.id=?
                    """, versionId, fieldId);
            if (fieldExists == 0) throw new BusinessException("测点 " + code + " 绑定的字段不属于当前协议版本");
            jdbcTemplate.update("""
                    INSERT INTO dev_model_point_binding(model_version_id,point_code,protocol_field_id,canonical_factor,
                      canonical_offset,display_factor,display_unit,required,sort) VALUES(?,?,?,?,?,?,?,?,?)
                    """, versionId, code, fieldId,
                    decimalText(value(point, "canonicalFactor", "canonical_factor"), "1"),
                    decimalText(value(point, "canonicalOffset", "canonical_offset"), "0"),
                    decimalText(value(point, "displayFactor", "display_factor"), "1"),
                    text(value(point, "displayUnit", "display_unit", "unit")),
                    value(point, "required") == null ? 1 : boolInt(value(point, "required")), sort * 10);
        }
        return detail(longValue(version.get("model_id")), versionId);
    }

    public Map<String, Object> validate(long versionId) {
        List<Map<String, Object>> checks = new ArrayList<>();
        Map<String, Object> catalogState = single("""
                SELECT c.enabled AS category_enabled,b.enabled AS brand_enabled,s.enabled AS series_enabled,
                       m.status AS model_status,v.status AS version_status,t.protocol_type,v.collect_interval_seconds,v.quality_threshold_pct,
                       v.device_type_id,v.protocol_profile_version_id,s.category_id
                FROM dev_device_model_version v JOIN dev_device_type t ON t.id=v.device_type_id
                JOIN dev_device_model m ON m.id=v.model_id JOIN dev_product_series s ON s.id=m.series_id
                JOIN dev_brand b ON b.id=s.brand_id JOIN dev_device_category c ON c.id=s.category_id WHERE v.id=?
                """, versionId);
        long deviceTypeId = longValue(catalogState.get("device_type_id"));
        boolean allowDisabledModel = DISABLED.equals(text(catalogState.get("version_status")));
        boolean catalogEnabled = boolInt(catalogState.get("category_enabled")) == 1
                && boolInt(catalogState.get("brand_enabled")) == 1 && boolInt(catalogState.get("series_enabled")) == 1
                && (allowDisabledModel || !DISABLED.equals(text(catalogState.get("model_status"))));
        checks.add(check("型号基本信息", catalogEnabled, catalogEnabled ? "分类、品牌、系列和型号均可用" : "目录节点存在停用项"));
        String protocolType = normalizeUpper(catalogState.get("protocol_type"), "");
        boolean protocolValid = PROTOCOL_TYPES.contains(protocolType);
        checks.add(check("版本采集参数", protocolValid && longOrDefault(catalogState.get("collect_interval_seconds"), 0) >= 10
                        && decimal(catalogState.get("quality_threshold_pct"), BigDecimal.ZERO).compareTo(BigDecimal.ONE) >= 0
                        && decimal(catalogState.get("quality_threshold_pct"), BigDecimal.ZERO).compareTo(new BigDecimal("100")) <= 0,
                protocolValid ? "协议和采集参数合法" : "仅支持 JSON、MODBUS_RTU、MODBUS_TCP"));
        Long categoryId = longValue(catalogState.get("category_id"));
        Long requiredAttributes = count("SELECT COUNT(*) FROM dev_attribute_definition WHERE enabled=1 AND required=1 AND (category_id IS NULL OR category_id=?)", categoryId);
        Long missingAttributes = count("""
                SELECT COUNT(*) FROM dev_attribute_definition a
                LEFT JOIN dev_model_attribute_value v ON v.attribute_id=a.id AND v.model_version_id=?
                WHERE a.enabled=1 AND a.required=1 AND (a.category_id IS NULL OR a.category_id=?)
                  AND (v.attribute_value IS NULL OR TRIM(v.attribute_value)='')
                """, versionId, categoryId);
        checks.add(check("必填属性", missingAttributes == 0,
                requiredAttributes == 0 ? "当前没有全局必填属性" : missingAttributes == 0 ? "必填属性完整" : "缺少 " + missingAttributes + " 个必填属性"));
        List<Map<String, Object>> configuredAttributes = jdbcTemplate.queryForList("""
                SELECT a.*,av.attribute_value FROM dev_model_attribute_value av
                JOIN dev_attribute_definition a ON a.id=av.attribute_id WHERE av.model_version_id=?
                """, versionId);
        long invalidAttributes = configuredAttributes.stream().filter(item -> boolInt(item.get("enabled")) != 1
                || validateAttributeValue(item, text(item.get("attribute_value"))) != null).count();
        checks.add(check("属性值类型", invalidAttributes == 0,
                invalidAttributes == 0 ? "已配置属性值类型合法" : "有 " + invalidAttributes + " 个属性值不合法或已停用"));
        Long pointCount = count("SELECT COUNT(*) FROM dev_point_definition WHERE device_type_id=? AND enabled=1", deviceTypeId);
        checks.add(check("测点定义", pointCount > 0, pointCount > 0 ? "已配置 " + pointCount + " 个测点" : "至少需要一个启用测点"));
        Long missingMappings = count("""
                SELECT COUNT(*) FROM dev_point_definition p
                LEFT JOIN dev_model_point_binding b ON b.model_version_id=? AND BINARY b.point_code=BINARY p.point_code
                WHERE p.device_type_id=? AND p.enabled=1 AND b.id IS NULL
                """, versionId, deviceTypeId);
        Long protocolVersionId = longValue(catalogState.get("protocol_profile_version_id"));
        boolean protocolBound = protocolVersionId != null && count("SELECT COUNT(*) FROM dev_protocol_profile_version WHERE id=? AND status='PUBLISHED'", protocolVersionId) == 1;
        boolean fieldbus = "MODBUS_RTU".equals(protocolType) || "MODBUS_TCP".equals(protocolType);
        checks.add(check("厂商协议", !fieldbus || protocolBound,
                !fieldbus ? "JSON 设备直接上报标准 points" : protocolBound ? "已绑定已发布协议版本" : "请选择并绑定已发布的厂商协议"));
        checks.add(check("测点绑定", !fieldbus || (missingMappings == 0 && pointCount > 0),
                !fieldbus ? "JSON 测点无需字段总线绑定" : missingMappings == 0 ? "业务测点均已绑定协议字段" : "有 " + missingMappings + " 个测点缺少协议字段绑定"));
        Long invalidBillable = count("""
                SELECT COUNT(*) FROM dev_point_definition
                WHERE device_type_id=? AND billable=1
                  AND (UPPER(data_type) NOT IN ('DOUBLE','INTEGER','LONG','DECIMAL')
                       OR UPPER(COALESCE(business_role,'')) <> 'TOTAL_ACCUMULATED'
                       OR stat_enabled <> 1 OR enabled <> 1)
                """, deviceTypeId);
        checks.add(check("计费能力", invalidBillable == 0, invalidBillable == 0
                ? "可计费测点均为启用并参与统计的累计数值测点"
                : "可计费测点必须启用、参与统计，并使用 TOTAL_ACCUMULATED 数值累计量角色"));
        boolean ready = checks.stream().allMatch(item -> Boolean.TRUE.equals(item.get("passed")));
        return Map.of("ready", ready, "checks", checks, "versionId", versionId);
    }

    @Transactional
    public Map<String, Object> publish(long versionId) {
        Map<String, Object> version = version(versionId);
        String status = text(version.get("status"));
        if (!DRAFT.equals(status) && !DISABLED.equals(status)) throw new BusinessException("只有草稿或已停用版本可以发布");
        Map<String, Object> validation = validate(versionId);
        if (!Boolean.TRUE.equals(validation.get("ready"))) {
            throw new BusinessException(400, "型号版本未通过发布校验");
        }
        Long userId = StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        jdbcTemplate.update("UPDATE dev_device_model_version SET status=?,published_by=?,published_time=? WHERE id=?",
                PUBLISHED, userId, LocalDateTime.now(), versionId);
        jdbcTemplate.update("UPDATE dev_device_type SET enabled=1 WHERE id=?", longValue(version.get("device_type_id")));
        jdbcTemplate.update("UPDATE dev_device_model SET status=?,current_published_version_id=? WHERE id=?",
                PUBLISHED, versionId, longValue(version.get("model_id")));
        return detail(longValue(version.get("model_id")), versionId);
    }

    @Transactional
    public Map<String, Object> createVersion(long modelId, Long sourceVersionId) {
        Map<String, Object> model = single("SELECT * FROM dev_device_model WHERE id=?", modelId);
        Map<String, Object> source = sourceVersionId == null
                ? single("SELECT * FROM dev_device_model_version WHERE model_id=? ORDER BY version_no DESC LIMIT 1", modelId)
                : version(sourceVersionId);
        if (!Objects.equals(longValue(source.get("model_id")), modelId)) throw new BusinessException("源版本不属于当前型号");
        Integer next = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM dev_device_model_version WHERE model_id=?", Integer.class, modelId);
        int versionNo = next == null ? 1 : next;
        long newTypeId = insert("INSERT INTO dev_device_type(type_code,type_name,protocol_type,description,enabled) VALUES(?,?,?,?,0)",
                technicalTypeCode(longValue(model.get("series_id")), text(model.get("model_code")), versionNo),
                text(model.get("model_name")) + " V" + versionNo, "JSON",
                "Catalog model " + model.get("model_code") + " draft V" + versionNo);
        long versionId = insert("""
                INSERT INTO dev_device_model_version
                (model_id,version_no,version_name,device_type_id,protocol_profile_version_id,status,collect_interval_seconds,quality_threshold_pct,source_version_id,remark)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, modelId, versionNo, "V" + versionNo, newTypeId, null, DRAFT, source.get("collect_interval_seconds"),
                source.get("quality_threshold_pct"), longValue(source.get("id")), source.get("remark"));
        return detail(modelId, versionId);
    }

    @Transactional
    public Map<String, Object> disable(long versionId) {
        Map<String, Object> version = version(versionId);
        if (!PUBLISHED.equals(text(version.get("status")))) throw new BusinessException("只有已发布版本可以停用");
        jdbcTemplate.update("UPDATE dev_device_model_version SET status=?,disabled_time=? WHERE id=?", DISABLED, LocalDateTime.now(), versionId);
        Long modelId = longValue(version.get("model_id"));
        Map<String, Object> model = single("SELECT * FROM dev_device_model WHERE id=?", modelId);
        if (Objects.equals(longValue(model.get("current_published_version_id")), versionId)) {
            List<Long> alternatives = jdbcTemplate.queryForList("""
                    SELECT id FROM dev_device_model_version
                    WHERE model_id=? AND status='PUBLISHED' AND id<>? ORDER BY version_no DESC LIMIT 1
                    """, Long.class, modelId, versionId);
            Long replacement = alternatives.isEmpty() ? null : alternatives.get(0);
            jdbcTemplate.update("UPDATE dev_device_model SET current_published_version_id=?,status=? WHERE id=?",
                    replacement, replacement == null ? DISABLED : PUBLISHED, modelId);
        }
        return detail(modelId, versionId);
    }

    @Transactional
    public Map<String, Object> deleteVersion(long versionId) {
        Map<String, Object> version = version(versionId);
        String status = text(version.get("status"));
        if (!DRAFT.equals(status) && !DISABLED.equals(status)) throw new BusinessException("只有草稿或已停用版本可以删除");
        Long modelId = longValue(version.get("model_id"));
        Map<String, Object> model = single("SELECT id,current_published_version_id FROM dev_device_model WHERE id=?", modelId);
        if (Objects.equals(longValue(model.get("current_published_version_id")), versionId)) {
            throw new BusinessException("当前发布版本不能删除，请先停用下架");
        }
        Long references = count("SELECT COUNT(*) FROM dev_device WHERE model_version_id=? OR device_type_id=?", versionId, longValue(version.get("device_type_id")));
        if (references > 0) throw new BusinessException("该版本已有设备引用，不能删除");
        long deviceTypeId = longValue(version.get("device_type_id"));
        jdbcTemplate.update("DELETE FROM dev_point_definition WHERE device_type_id=?", deviceTypeId);
        jdbcTemplate.update("DELETE FROM dev_model_attribute_value WHERE model_version_id=?", versionId);
        jdbcTemplate.update("DELETE FROM dev_device_model_version WHERE id=?", versionId);
        jdbcTemplate.update("DELETE FROM dev_device_type WHERE id=?", deviceTypeId);
        Long remainingVersions = count("SELECT COUNT(*) FROM dev_device_model_version WHERE model_id=?", modelId);
        boolean modelDeleted = remainingVersions == null || remainingVersions == 0;
        if (modelDeleted) {
            jdbcTemplate.update("DELETE FROM dev_device_model WHERE id=?", modelId);
        }
        return Map.of("deleted", true, "modelId", modelId, "versionId", versionId, "modelDeleted", modelDeleted);
    }

    public List<Map<String, Object>> publishedOptions() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT v.id AS model_version_id,v.version_name,v.device_type_id,v.collect_interval_seconds,v.quality_threshold_pct,t.protocol_type,
                       m.id AS model_id,m.model_code,m.model_name,m.image_object_key,s.series_name,b.brand_name,c.category_name,
                       (SELECT COUNT(*) FROM dev_model_attribute_value av WHERE av.model_version_id=v.id) AS attribute_count,
                       (SELECT COUNT(*) FROM dev_point_definition pd WHERE pd.device_type_id=v.device_type_id AND pd.enabled=1) AS point_count,
                       CONCAT(c.category_name,' / ',b.brand_name,' / ',s.series_name,' / ',m.model_name,' ',v.version_name) AS display_name
                FROM dev_device_model_version v
                JOIN dev_device_type t ON t.id=v.device_type_id
                JOIN dev_device_model m ON m.id=v.model_id
                JOIN dev_product_series s ON s.id=m.series_id
                JOIN dev_brand b ON b.id=s.brand_id
                JOIN dev_device_category c ON c.id=s.category_id
                WHERE v.status='PUBLISHED' AND m.status<>'DISABLED' AND s.enabled=1 AND b.enabled=1 AND c.enabled=1
                ORDER BY c.category_name,b.brand_name,s.series_name,m.model_name,v.version_no DESC
                """);
        rows.forEach(row -> row.put("image_url", modelImageUrl(text(row.get("image_object_key")))));
        return rows;
    }

    public String modelImageUrl(String objectKey) {
        return modelImagePreviewUrl(objectKey);
    }

    /**
     * Legacy device types that are not managed by the catalog remain writable.
     * Once a type belongs to a catalog version, only its DRAFT version may be changed.
     */
    public boolean isDeviceTypeWritable(long deviceTypeId) {
        List<String> statuses = jdbcTemplate.queryForList(
                "SELECT status FROM dev_device_model_version WHERE device_type_id=?",
                String.class, deviceTypeId);
        return statuses.isEmpty() || statuses.stream().allMatch(DRAFT::equals);
    }

    public void assertDeviceTypeWritable(long deviceTypeId) {
        if (!isDeviceTypeWritable(deviceTypeId)) {
            throw new BusinessException("已发布或已停用的型号版本不可原地修改，请从产品目录创建新版本");
        }
    }

    private Map<String, Object> requireDraft(long versionId) {
        Map<String, Object> version = version(versionId);
        if (!DRAFT.equals(text(version.get("status")))) throw new BusinessException("已发布或已停用版本不可原地修改，请创建新版本");
        return version;
    }

    private Map<String, Object> version(long versionId) {
        return single("SELECT * FROM dev_device_model_version WHERE id=?", versionId);
    }

    private Map<String, Object> check(String name, boolean passed, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("passed", passed);
        result.put("message", message);
        return result;
    }

    private Map<String, Object> node(String type, Long id, String label, String code, String status) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("key", type + ":" + id);
        node.put("nodeType", type);
        node.put("id", id);
        node.put("label", label);
        node.put("code", code);
        node.put("status", status);
        node.put("children", new ArrayList<Map<String, Object>>());
        return node;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> children(Map<String, Object> node) {
        return (List<Map<String, Object>>) node.get("children");
    }

    private List<Map<String, Object>> filterNodes(List<Map<String, Object>> nodes, String keyword) {
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            List<Map<String, Object>> matchingChildren = filterNodes(children(node), keyword);
            String haystack = (text(node.get("label")) + " " + text(node.get("code"))).toLowerCase(Locale.ROOT);
            if (haystack.contains(keyword) || !matchingChildren.isEmpty()) {
                Map<String, Object> copy = new LinkedHashMap<>(node);
                copy.put("children", matchingChildren.isEmpty() && haystack.contains(keyword) ? children(node) : matchingChildren);
                copy.put("searchExpanded", true);
                filtered.add(copy);
            }
        }
        return filtered;
    }

    private long insert(String sql, Object... args) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) throw new BusinessException("数据创建失败，未返回主键");
        return key.longValue();
    }

    private Map<String, Object> single(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        if (rows.isEmpty()) throw new BusinessException(404, "关联数据不存在");
        return rows.get(0);
    }

    private Long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private void assertEnabledCategory(long categoryId) {
        Long count = count("SELECT COUNT(*) FROM dev_device_category WHERE id=? AND enabled=1", categoryId);
        if (count == 0) throw new BusinessException("设备分类不存在或已停用");
    }

    private void assertEnabledBrand(long brandId) {
        Long count = count("SELECT COUNT(*) FROM dev_brand WHERE id=? AND enabled=1", brandId);
        if (count == 0) throw new BusinessException("品牌不存在或已停用");
    }

    private void bindBrandCategory(long categoryId, long brandId) {
        jdbcTemplate.update("""
                INSERT INTO dev_device_category_brand(category_id,brand_id)
                VALUES(?,?) ON DUPLICATE KEY UPDATE category_id=VALUES(category_id)
                """, categoryId, brandId);
    }

    private void require(Map<String, Object> body, String key, String message) {
        if (!StringUtils.hasText(text(value(body, key, camelToSnake(key))))) throw new BusinessException(message);
    }

    private Long requiredLong(Map<String, Object> body, String key, String message) {
        Long value = longValue(value(body, key, camelToSnake(key)));
        if (value == null) throw new BusinessException(message);
        return value;
    }

    private Object value(Map<String, Object> body, String... keys) {
        for (String key : keys) if (body.containsKey(key)) return body.get(key);
        return null;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String textOr(Object value, String fallback) {
        String text = text(value);
        return StringUtils.hasText(text) ? text : fallback;
    }

    private Long longValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private long longOrDefault(Object value, long fallback) {
        Long parsed = longValue(value);
        return parsed == null ? fallback : parsed;
    }

    private int boolInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Boolean bool) return bool ? 1 : 0;
        String text = String.valueOf(value);
        return "1".equals(text) || "true".equalsIgnoreCase(text) ? 1 : 0;
    }

    private String decimalText(Object value, String fallback) {
        String parsed = text(value);
        return StringUtils.hasText(parsed) ? parsed : fallback;
    }

    private BigDecimal decimal(Object value, BigDecimal fallback) {
        if (value == null || !StringUtils.hasText(text(value))) return fallback;
        try {
            return new BigDecimal(text(value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String normalizeUpper(Object value, String fallback) {
        return textOr(value, fallback).toUpperCase(Locale.ROOT);
    }

    private String normalizeProtocol(Object value) {
        String protocol = normalizeUpper(value, "JSON");
        if ("MODBUS".equals(protocol)) protocol = "MODBUS_RTU";
        if (!PROTOCOL_TYPES.contains(protocol)) throw new BusinessException("不支持的协议类型: " + protocol);
        return protocol;
    }

    private String jsonText(Object value) {
        if (value == null || !StringUtils.hasText(text(value))) return null;
        try {
            if (value instanceof String string) {
                objectMapper.readTree(string);
                return string;
            }
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new BusinessException("JSON 规则格式不合法");
        }
    }

    public String validateAttributeValue(Map<String, Object> definition, String attributeValue) {
        if (!StringUtils.hasText(attributeValue)) {
            return boolInt(definition.get("required")) == 1 ? "必填值不能为空" : null;
        }
        String dataType = normalizeUpper(definition.get("data_type"), "STRING");
        try {
            if ("NUMBER".equals(dataType)) new BigDecimal(attributeValue);
            else if ("BOOLEAN".equals(dataType)
                    && !Set.of("true", "false", "1", "0").contains(attributeValue.toLowerCase(Locale.ROOT))) {
                return "布尔值只能是 true、false、1 或 0";
            } else if ("ENUM".equals(dataType)) {
                JsonNode options = jsonNode(definition.get("enum_options"));
                if (options == null || !options.isArray()) return "枚举属性未配置候选项";
                boolean matched = false;
                for (JsonNode option : options) {
                    String candidate = option.isObject() ? text(option.path("value").asText()) : option.asText();
                    if (Objects.equals(candidate, attributeValue)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) return "值不在枚举候选项中";
            }
            JsonNode rule = jsonNode(definition.get("validation_rule"));
            if (rule != null && rule.isObject()) {
                if (rule.has("min") && new BigDecimal(attributeValue).compareTo(rule.get("min").decimalValue()) < 0) return "值小于允许下限";
                if (rule.has("max") && new BigDecimal(attributeValue).compareTo(rule.get("max").decimalValue()) > 0) return "值大于允许上限";
                if (rule.has("maxLength") && attributeValue.length() > rule.get("maxLength").asInt()) return "文本长度超过限制";
            }
            return null;
        } catch (Exception exception) {
            return "值与 " + dataType + " 类型或校验规则不匹配";
        }
    }

    private JsonNode jsonNode(Object value) {
        if (value == null || !StringUtils.hasText(text(value))) return null;
        try {
            return objectMapper.readTree(text(value));
        } catch (Exception exception) {
            return null;
        }
    }

    private String technicalTypeCode(long seriesId, String modelCode, int versionNo) {
        String normalized = textOr(modelCode, "MODEL").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_-]", "_");
        String code = "CAT_" + seriesId + "_" + normalized + "_V" + versionNo;
        return code.length() > 64 ? code.substring(0, 64) : code;
    }

    private COSClient cosClient() {
        COSClient client = cosClientProvider.getIfAvailable();
        if (client == null) throw new BusinessException("COS 未启用，请先配置 park.cos.enabled=true 和密钥");
        return client;
    }

    private String normalizedObjectPrefix() {
        String prefix = textOr(cosProperties.objectPrefix(), "device-models/");
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    private String imageExtension(String fileName, String contentType) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "jpg";
        if (lower.endsWith(".png")) return "png";
        if (lower.endsWith(".webp")) return "webp";
        if (lower.endsWith(".gif")) return "gif";
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> throw new BusinessException("不支持的图片类型");
        };
    }

    private String modelImagePreviewUrl(String objectKey) {
        if (!StringUtils.hasText(objectKey)) return null;
        try {
            java.util.Date expiration = java.util.Date.from(Instant.now().plusSeconds(Math.max(60L, cosProperties.urlExpireSeconds())));
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(cosProperties.bucket(), objectKey, HttpMethodName.GET);
            request.setExpiration(expiration);
            return cosClient().generatePresignedUrl(request).toString();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void deleteObjectQuietly(String objectKey) {
        if (!StringUtils.hasText(objectKey)) return;
        try {
            cosClient().deleteObject(new DeleteObjectRequest(cosProperties.bucket(), objectKey));
        } catch (RuntimeException ignored) {
        }
    }

    private void requireText(Object value, String message) {
        if (!StringUtils.hasText(text(value))) throw new BusinessException(message);
    }

    private String camelToSnake(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
