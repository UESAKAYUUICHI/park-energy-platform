package com.parkenergyplatform.service;

import com.parkenergyplatform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ProtocolCatalogService {
    private final JdbcTemplate jdbc;

    public ProtocolCatalogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> list(String keyword) {
        String value = keyword == null ? "" : keyword.trim();
        return jdbc.queryForList("""
                SELECT p.*,v.id AS current_version_id,v.version_name,v.status AS version_status,
                       (SELECT COUNT(*) FROM dev_protocol_field f WHERE f.protocol_version_id=v.id) AS field_count,
                       (SELECT COUNT(*) FROM dev_device_model_version mv WHERE mv.protocol_profile_version_id=v.id) AS model_count
                FROM dev_protocol_profile p
                LEFT JOIN dev_protocol_profile_version v ON v.profile_id=p.id
                  AND v.version_no=(SELECT MAX(v2.version_no) FROM dev_protocol_profile_version v2 WHERE v2.profile_id=p.id)
                WHERE (?='' OR p.profile_code LIKE CONCAT('%',?,'%') OR p.profile_name LIKE CONCAT('%',?,'%')
                  OR COALESCE(p.manufacturer,'') LIKE CONCAT('%',?,'%'))
                ORDER BY p.enabled DESC,p.profile_name,p.id
                """, value, value, value, value);
    }

    public List<Map<String, Object>> publishedVersions() {
        return jdbc.queryForList("""
                SELECT v.id,p.id AS profile_id,p.profile_code,p.profile_name,p.manufacturer,p.transport_type,
                       v.version_no,v.version_name,v.status,
                       (SELECT COUNT(*) FROM dev_protocol_field f WHERE f.protocol_version_id=v.id) AS field_count
                FROM dev_protocol_profile_version v JOIN dev_protocol_profile p ON p.id=v.profile_id
                WHERE p.enabled=1 AND v.status='PUBLISHED' ORDER BY p.profile_name,v.version_no DESC
                """);
    }

    public Map<String, Object> detail(long versionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", one("""
                SELECT v.*,p.profile_code,p.profile_name,p.manufacturer,p.transport_type,p.description
                FROM dev_protocol_profile_version v JOIN dev_protocol_profile p ON p.id=v.profile_id WHERE v.id=?
                """, versionId));
        result.put("readBlocks", jdbc.queryForList("SELECT * FROM dev_protocol_read_block WHERE protocol_version_id=? ORDER BY sort,id", versionId));
        result.put("protocolAttributes", jdbc.queryForList("""
                SELECT t.*,a.attribute_code,a.attribute_name,a.data_type,a.usage_type,a.unit,a.default_value,
                       g.group_name,o.value_text AS option_text
                FROM dev_protocol_attribute_template t
                JOIN dev_attribute_definition a ON a.id=t.attribute_id
                JOIN dev_attribute_group g ON g.id=a.group_id
                LEFT JOIN dev_attribute_value_option o ON o.id=t.attribute_value_option_id
                WHERE t.protocol_version_id=? ORDER BY t.sort,t.id
                """, versionId));
        result.put("fields", jdbc.queryForList("""
                SELECT f.*,sp.point_code AS standard_point_code,sp.point_name AS standard_point_name,
                       sp.unit AS standard_point_unit,sp.data_type AS standard_point_data_type,
                       pg.group_name AS standard_point_group_name,b.block_code,b.block_name,b.function_code,b.start_address
                FROM dev_protocol_field f LEFT JOIN dev_protocol_read_block b ON b.id=f.read_block_id
                LEFT JOIN dev_standard_point sp ON sp.id=f.standard_point_id
                LEFT JOIN dev_standard_point_group pg ON pg.id=sp.group_id
                WHERE f.protocol_version_id=? ORDER BY f.sort,f.id
                """, versionId));
        result.put("commands", jdbc.queryForList("SELECT * FROM dev_protocol_command WHERE protocol_version_id=? ORDER BY sort,id", versionId));
        return result;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        String code = required(body, "profileCode").toUpperCase(Locale.ROOT);
        String name = required(body, "profileName");
        String transport = text(body.get("transportType"), "MODBUS_RTU").toUpperCase(Locale.ROOT);
        if (!List.of("MODBUS_RTU", "MODBUS_TCP").contains(transport)) throw new BusinessException("协议类型仅支持 MODBUS_RTU 或 MODBUS_TCP");
        long profileId = insert("INSERT INTO dev_protocol_profile(profile_code,profile_name,manufacturer,transport_type,description) VALUES(?,?,?,?,?)",
                code, name, text(body.get("manufacturer"), null), transport, text(body.get("description"), null));
        long versionId = insert("INSERT INTO dev_protocol_profile_version(profile_id,version_no,version_name,status,address_base,remark) VALUES(?,1,?,'DRAFT','PDU_ZERO_BASED',?)",
                profileId, text(body.get("versionName"), "V1"), text(body.get("remark"), null));
        return detail(versionId);
    }

    @Transactional
    public Map<String, Object> createVersion(long profileId, Map<String, Object> body) {
        one("SELECT id FROM dev_protocol_profile WHERE id=? AND enabled=1", profileId);
        Long sourceVersionId = nullableLong(body.get("sourceVersionId"));
        if (sourceVersionId == null) {
            sourceVersionId = jdbc.queryForObject(
                    "SELECT id FROM dev_protocol_profile_version WHERE profile_id=? ORDER BY version_no DESC LIMIT 1",
                    Long.class, profileId);
        }
        Map<String, Object> source = detail(sourceVersionId);
        Map<String, Object> sourceVersion = maps(List.of(source.get("version"))).get(0);
        if (((Number) sourceVersion.get("profile_id")).longValue() != profileId) {
            throw new BusinessException("源版本不属于当前协议");
        }
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no),0)+1 FROM dev_protocol_profile_version WHERE profile_id=?",
                Integer.class, profileId);
        int versionNo = next == null ? 1 : next;
        long versionId = insert("""
                INSERT INTO dev_protocol_profile_version(profile_id,version_no,version_name,status,address_base,source_file_name,remark)
                VALUES(?,?,?,'DRAFT',?,?,?)
                """, profileId, versionNo, text(body.get("versionName"), "V" + versionNo),
                text(sourceVersion.get("address_base"), "PDU_ZERO_BASED"), sourceVersion.get("source_file_name"),
                text(body.get("remark"), "Copied from " + sourceVersion.get("version_name")));

        List<Map<String, Object>> copiedBlocks = new java.util.ArrayList<>();
        for (Map<String, Object> row : maps(source.get("readBlocks"))) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("blockCode", row.get("block_code")); block.put("blockName", row.get("block_name"));
            block.put("functionCode", row.get("function_code")); block.put("startAddress", row.get("start_address"));
            block.put("registerCount", row.get("register_count")); block.put("pollMode", row.get("poll_mode"));
            block.put("intervalSeconds", row.get("interval_seconds")); block.put("required", row.get("required"));
            copiedBlocks.add(block);
        }
        List<Map<String, Object>> copiedAttributes = new java.util.ArrayList<>();
        for (Map<String, Object> row : maps(source.get("protocolAttributes"))) {
            Map<String, Object> attribute = new LinkedHashMap<>();
            attribute.put("attributeId", row.get("attribute_id"));
            attribute.put("attributeValueOptionId", row.get("attribute_value_option_id"));
            attribute.put("attributeValue", row.get("attribute_value"));
            attribute.put("required", row.get("required"));
            copiedAttributes.add(attribute);
        }
        List<Map<String, Object>> copiedFields = new java.util.ArrayList<>();
        for (Map<String, Object> row : maps(source.get("fields"))) {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("fieldCode", row.get("field_code")); field.put("fieldName", row.get("field_name"));
            field.put("standardPointId", row.get("standard_point_id"));
            field.put("blockCode", row.get("block_code")); field.put("documentAddress", row.get("document_address"));
            field.put("registerOffset", row.get("register_offset")); field.put("registerLength", row.get("register_length"));
            field.put("valueType", row.get("value_type")); field.put("byteOrder", row.get("byte_order"));
            field.put("bitOffset", row.get("bit_offset")); field.put("bitLength", row.get("bit_length"));
            field.put("decodeFactor", row.get("decode_factor")); field.put("decodeOffset", row.get("decode_offset"));
            field.put("rawUnit", row.get("raw_unit")); field.put("enumJson", row.get("enum_json"));
            field.put("accessMode", row.get("access_mode")); field.put("required", row.get("required"));
            copiedFields.add(field);
        }
        List<Map<String, Object>> copiedCommands = new java.util.ArrayList<>();
        for (Map<String, Object> row : maps(source.get("commands"))) {
            Map<String, Object> command = new LinkedHashMap<>();
            command.put("commandCode", row.get("command_code")); command.put("commandName", row.get("command_name"));
            command.put("functionCode", row.get("function_code")); command.put("registerAddress", row.get("register_address"));
            command.put("encodeType", row.get("encode_type")); command.put("valueType", row.get("value_type"));
            command.put("fixedValue", row.get("fixed_value")); command.put("parameterJson", row.get("parameter_json"));
            command.put("enabled", row.get("enabled")); copiedCommands.add(command);
        }
        return replaceDraft(versionId, Map.of("protocolAttributes", copiedAttributes, "readBlocks", copiedBlocks, "fields", copiedFields, "commands", copiedCommands));
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> replaceDraft(long versionId, Map<String, Object> body) {
        Map<String, Object> version = one("SELECT * FROM dev_protocol_profile_version WHERE id=?", versionId);
        if (!"DRAFT".equals(version.get("status"))) throw new BusinessException("只有草稿协议可以修改");
        List<Map<String, Object>> blocks = maps(body.get("readBlocks"));
        List<Map<String, Object>> protocolAttributes = maps(body.get("protocolAttributes"));
        List<Map<String, Object>> fields = maps(body.get("fields"));
        List<Map<String, Object>> commands = maps(body.get("commands"));
        jdbc.update("DELETE FROM dev_protocol_command WHERE protocol_version_id=?", versionId);
        jdbc.update("DELETE FROM dev_protocol_field WHERE protocol_version_id=?", versionId);
        jdbc.update("DELETE FROM dev_protocol_attribute_template WHERE protocol_version_id=?", versionId);
        jdbc.update("DELETE FROM dev_protocol_read_block WHERE protocol_version_id=?", versionId);
        int attributeSort = 0;
        for (Map<String, Object> item : protocolAttributes) {
            Long attributeId = nullableLong(value(item, "attributeId", "attribute_id"));
            if (attributeId == null) continue;
            Map<String, Object> attribute = one("SELECT * FROM dev_attribute_definition WHERE id=? AND enabled=1 AND value_mode='FIXED'", attributeId);
            Long optionId = nullableLong(value(item, "attributeValueOptionId", "attribute_value_option_id", "optionId", "option_id"));
            if (optionId != null) one("SELECT id FROM dev_attribute_value_option WHERE id=? AND attribute_id=? AND enabled=1", optionId, attributeId);
            String attributeValue = text(value(item, "attributeValue", "attribute_value"), text(attribute.get("default_value"), null));
            jdbc.update("""
                    INSERT INTO dev_protocol_attribute_template(protocol_version_id,attribute_id,attribute_value_option_id,attribute_value,required,sort)
                    VALUES(?,?,?,?,?,?)
                    """, versionId, attributeId, optionId, attributeValue, bool(value(item, "required"), false), attributeSort += 10);
        }
        Map<String, Long> blockIds = new LinkedHashMap<>();
        int sort = 0;
        for (Map<String, Object> block : blocks) {
            String blockCode = required(block, "blockCode");
            int fc = integer(block.get("functionCode"), 3);
            int start = integer(block.get("startAddress"), -1);
            int count = integer(block.get("registerCount"), 0);
            if (!List.of(3, 4).contains(fc) || start < 0 || start > 65535 || count < 1 || count > 125) {
                throw new BusinessException("读块 " + blockCode + " 的功能码或地址范围不合法");
            }
            long id = insert("""
                    INSERT INTO dev_protocol_read_block(protocol_version_id,block_code,block_name,function_code,start_address,
                      register_count,poll_mode,interval_seconds,required,sort) VALUES(?,?,?,?,?,?,?,?,?,?)
                    """, versionId, blockCode, text(block.get("blockName"), blockCode), fc, start, count,
                    text(block.get("pollMode"), "CYCLIC"), nullableInteger(block.get("intervalSeconds")), bool(block.get("required"), true), sort += 10);
            blockIds.put(blockCode, id);
        }
        sort = 0;
        for (Map<String, Object> field : fields) {
            Long standardPointId = nullableLong(value(field, "standardPointId", "standard_point_id"));
            Map<String, Object> standardPoint = standardPointId == null ? Map.of() : one("SELECT * FROM dev_standard_point WHERE id=? AND enabled=1 AND value_mode='REALTIME'", standardPointId);
            String blockCode = text(field.get("blockCode"), "");
            String fallbackCode = standardPoint.isEmpty() ? blockCode + "_FIELD_" + (sort + 10) : text(standardPoint.get("point_code"), "");
            String fallbackName = standardPoint.isEmpty() ? "协议字段" + (sort / 10 + 1) : text(standardPoint.get("point_name"), fallbackCode);
            String code = text(value(field, "fieldCode", "field_code"), fallbackCode);
            Long blockId = blockIds.get(text(field.get("blockCode"), ""));
            if (blockId == null) throw new BusinessException("字段 " + code + " 未绑定有效读块");
            String valueType = text(field.get("valueType"), "UINT16").toUpperCase(Locale.ROOT);
            int registerLength = integer(field.get("registerLength"), 1);
            String byteOrder = text(field.get("byteOrder"), registerLength == 1 ? "AB" : "ABCD").toUpperCase(Locale.ROOT);
            if (!List.of("UINT16", "INT16", "UINT32", "INT32", "FLOAT32", "FLOAT64", "BOOLEAN").contains(valueType)) {
                throw new BusinessException("字段 " + code + " 的数据类型不受支持");
            }
            int expectedLength = List.of("UINT16", "INT16", "BOOLEAN").contains(valueType) ? 1 : "FLOAT64".equals(valueType) ? 4 : 2;
            if (registerLength != expectedLength) throw new BusinessException("字段 " + code + " 的寄存器长度与数据类型不匹配");
            if (registerLength == 1 && !List.of("AB", "BA").contains(byteOrder)) throw new BusinessException("字段 " + code + " 的字节序不合法");
            if (registerLength > 1 && !List.of("ABCD", "BADC", "CDAB", "DCBA").contains(byteOrder)) throw new BusinessException("字段 " + code + " 的字节序不合法");
            Integer bitOffset = nullableInteger(field.get("bitOffset"));
            Integer bitLength = nullableInteger(field.get("bitLength"));
            if ((bitOffset != null || bitLength != null) && (!"BOOLEAN".equals(valueType) || bitOffset == null || bitOffset < 0 || bitOffset > 15 || !Integer.valueOf(1).equals(bitLength))) {
                throw new BusinessException("字段 " + code + " 的位域定义不合法");
            }
            jdbc.update("""
                    INSERT INTO dev_protocol_field(protocol_version_id,read_block_id,field_code,field_name,document_address,
                      standard_point_id,register_offset,register_length,value_type,byte_order,bit_offset,bit_length,decode_factor,decode_offset,
                      raw_unit,enum_json,access_mode,required,sort) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS JSON),?,?,?)
                    """, versionId, blockId, code, text(value(field, "fieldName", "field_name"), fallbackName), text(field.get("documentAddress"), null), standardPointId,
                    integer(field.get("registerOffset"), 0), registerLength, valueType,
                    byteOrder, bitOffset, bitLength,
                    decimal(field.get("decodeFactor"), BigDecimal.ONE), decimal(field.get("decodeOffset"), BigDecimal.ZERO), text(field.get("rawUnit"), null),
                    text(field.get("enumJson"), null), text(field.get("accessMode"), "R"), bool(field.get("required"), true), sort += 10);
        }
        sort = 0;
        for (Map<String, Object> command : commands) {
            int fc = integer(command.get("functionCode"), 6);
            Long fixedValue = nullableLong(command.get("fixedValue"));
            if (fc != 6 || !"FIXED".equalsIgnoreCase(text(command.get("encodeType"), "FIXED")) || fixedValue == null || fixedValue < 0 || fixedValue > 65535) {
                throw new BusinessException("当前网关仅支持 FC06 固定值白名单命令");
            }
            int registerAddress = integer(command.get("registerAddress"), -1);
            if (registerAddress < 0 || registerAddress > 65535) throw new BusinessException("写命令寄存器地址不合法");
            jdbc.update("""
                    INSERT INTO dev_protocol_command(protocol_version_id,command_code,command_name,function_code,register_address,
                      encode_type,value_type,fixed_value,parameter_json,enabled,sort) VALUES(?,?,?,?,?,?,?,?,CAST(? AS JSON),?,?)
                    """, versionId, required(command, "commandCode"), required(command, "commandName"), fc,
                    registerAddress, "FIXED",
                    text(command.get("valueType"), "UINT16"), nullableLong(command.get("fixedValue")),
                    text(command.get("parameterJson"), null), bool(command.get("enabled"), true), sort += 10);
        }
        return detail(versionId);
    }

    @Transactional
    public Map<String, Object> publish(long versionId) {
        Map<String, Object> check = validate(versionId);
        if (!(Boolean) check.get("valid")) throw new BusinessException("协议校验未通过：" + check.get("errors"));
        jdbc.update("UPDATE dev_protocol_profile_version SET status='PUBLISHED',published_time=NOW() WHERE id=? AND status='DRAFT'", versionId);
        return detail(versionId);
    }

    public Map<String, Object> validate(long versionId) {
        one("SELECT id FROM dev_protocol_profile_version WHERE id=?", versionId);
        List<String> errors = new java.util.ArrayList<>();
        Long blocks = jdbc.queryForObject("SELECT COUNT(*) FROM dev_protocol_read_block WHERE protocol_version_id=?", Long.class, versionId);
        Long fields = jdbc.queryForObject("SELECT COUNT(*) FROM dev_protocol_field WHERE protocol_version_id=?", Long.class, versionId);
        Long outside = jdbc.queryForObject("""
                SELECT COUNT(*) FROM dev_protocol_field f JOIN dev_protocol_read_block b ON b.id=f.read_block_id
                WHERE f.protocol_version_id=? AND (f.register_offset<0 OR f.register_offset+f.register_length>b.register_count)
                """, Long.class, versionId);
        if (blocks == null || blocks == 0) errors.add("至少配置一个读块");
        if (fields == null || fields == 0) errors.add("至少配置一个协议字段");
        if (outside != null && outside > 0) errors.add(outside + " 个字段超出所属读块范围");
        return Map.of("valid", errors.isEmpty(), "errors", errors, "readBlockCount", blocks == null ? 0 : blocks, "fieldCount", fields == null ? 0 : fields);
    }

    @Transactional
    public void delete(long profileId) {
        Long used = jdbc.queryForObject("SELECT COUNT(*) FROM dev_device_model_version mv JOIN dev_protocol_profile_version v ON v.id=mv.protocol_profile_version_id WHERE v.profile_id=?", Long.class, profileId);
        if (used != null && used > 0) throw new BusinessException("协议已被产品版本引用，不能删除");
        jdbc.update("DELETE FROM dev_protocol_profile WHERE id=?", profileId);
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) throw new BusinessException(404, "协议数据不存在");
        return rows.get(0);
    }

    private long insert(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, keys);
        if (keys.getKey() == null) throw new BusinessException("协议数据保存失败");
        return keys.getKey().longValue();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
    }

    private String required(Map<String, Object> body, String key) {
        String value = text(body.get(key), "");
        if (value.isBlank()) throw new BusinessException(key + " 不能为空");
        return value;
    }

    private String text(Object value, String fallback) { return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim(); }
    private Object value(Map<String, Object> body, String... keys) { for (String key : keys) if (body.containsKey(key)) return body.get(key); return null; }
    private int integer(Object value, int fallback) { try { return value == null || String.valueOf(value).isBlank() ? fallback : Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return fallback; } }
    private Integer nullableInteger(Object value) { return value == null || String.valueOf(value).isBlank() ? null : integer(value, 0); }
    private Long nullableLong(Object value) { try { return value == null || String.valueOf(value).isBlank() ? null : Long.valueOf(String.valueOf(value)); } catch (Exception ignored) { return null; } }
    private BigDecimal decimal(Object value, BigDecimal fallback) { try { return value == null || String.valueOf(value).isBlank() ? fallback : new BigDecimal(String.valueOf(value)); } catch (Exception ignored) { return fallback; } }
    private int bool(Object value, boolean fallback) { return value == null ? (fallback ? 1 : 0) : ("1".equals(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value)) ? 1 : 0); }
}
