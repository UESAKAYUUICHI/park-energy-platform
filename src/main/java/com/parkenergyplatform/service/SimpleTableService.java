package com.parkenergyplatform.service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.common.PageResult;
import com.parkenergyplatform.service.TableRegistry.TableDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SimpleTableService {
    private final JdbcTemplate jdbcTemplate;
    private final TableRegistry tableRegistry;
    private final ObjectMapper objectMapper;
    private final DataScopeService dataScopeService;
    private final BusinessDataAccessService accessService;
    private final DeviceCatalogService deviceCatalogService;
    private final DeviceProvisionService deviceProvisionService;

    public SimpleTableService(JdbcTemplate jdbcTemplate, TableRegistry tableRegistry, ObjectMapper objectMapper,
                              DataScopeService dataScopeService, BusinessDataAccessService accessService,
                              DeviceCatalogService deviceCatalogService, DeviceProvisionService deviceProvisionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.tableRegistry = tableRegistry;
        this.objectMapper = objectMapper;
        this.dataScopeService = dataScopeService;
        this.accessService = accessService;
        this.deviceCatalogService = deviceCatalogService;
        this.deviceProvisionService = deviceProvisionService;
    }

    public PageResult<Map<String, Object>> page(String resource, Map<String, String> params) {
        TableDefinition definition = tableRegistry.get(resource);
        int pageNum = parsePositive(params.get("pageNum"), 1);
        int pageSize = Math.min(parsePositive(params.get("pageSize"), 20), 200);
        List<Object> args = new ArrayList<>();
        String where = buildWhere(definition, params, args);
        where = appendDataScope(definition, where, args);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + definition.table() + where, Long.class, args.toArray());
        String sql = "spaces".equals(resource)
                ? "SELECT id, org_id, parent_id, space_code, space_name, space_type, status, create_time, "
                + "(SELECT COUNT(*) FROM leasing_contract_space cs JOIN leasing_contract c ON c.id=cs.contract_id WHERE cs.space_id=park_space.id AND c.status='ACTIVE') AS active_contract_count, "
                + "(SELECT COUNT(*) FROM dev_device d WHERE d.space_id=park_space.id) AS device_count "
                + "FROM park_space" + where + " ORDER BY id DESC LIMIT ? OFFSET ?"
                : "SELECT * FROM " + definition.table() + where + " ORDER BY id DESC LIMIT ? OFFSET ?";
        args.add(pageSize);
        args.add((pageNum - 1) * pageSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray());
        enrichDeviceModelImages(resource, rows);
        enrichBillingRuleConfigs(resource, rows);
        return PageResult.of(rows, total == null ? 0 : total, pageNum, pageSize);
    }

    public List<Map<String, Object>> options(String resource, Map<String, String> params) {
        TableDefinition definition = tableRegistry.get(resource);
        List<Object> args = new ArrayList<>();
        String where = appendDataScope(definition, buildWhere(definition, params, args), args);
        int limit = Math.min(parsePositive(params.get("limit"), 100), 200);
        String columns = optionColumns(resource);
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(limit);
        return jdbcTemplate.queryForList("SELECT " + columns + " FROM " + definition.table()
                + where + " ORDER BY id DESC LIMIT ?", queryArgs.toArray());
    }

    public Map<String, Object> get(String resource, long id) {
        TableDefinition definition = tableRegistry.get(resource);
        List<Object> args = new ArrayList<>();
        args.add(id);
        String where = appendDataScope(definition, " WHERE id = ?", args);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM " + definition.table() + where, args.toArray());
        if (rows.isEmpty()) {
            throw new BusinessException(404, "数据不存在: " + id);
        }
        enrichDeviceModelImages(resource, rows);
        enrichBillingRuleConfigs(resource, rows);
        return rows.get(0);
    }

    @Transactional
    public Map<String, Object> create(String resource, Map<String, Object> body) {
        if ("devices".equals(resource)) {
            throw new BusinessException("新设备必须从设备档案选择已发布型号创建，不能通过通用表单绕过产品目录");
        }
        TableDefinition definition = tableRegistry.get(resource);
        Map<String, Object> values = writableValues(definition, body, false);
        // Row-level audit ownership is controlled by the server, never by a client supplied form value.
        if (definition.hasColumn("create_by")) values.put("create_by", operatorName());
        if (definition.hasColumn("update_by")) values.put("update_by", operatorName());
        applyDefaultDeviceOrg(definition, values);
        applyDefaultCollectionPolicy(definition, values);
        if ("spaces".equals(resource) && values.get("parent_id") == null) values.put("parent_id", 0L);
        if ("spaces".equals(resource)
                && (values.get("space_type") == null || String.valueOf(values.get("space_type")).isBlank())) {
            // 空间表中的类型是必填字段，而空间管理表单允许用户不填写类型。
            // 默认按楼宇/园区空间处理，后续仍可通过编辑写入 FLOOR、ROOM 等类型。
            values.put("space_type", "BUILDING");
        }
        validateDeviceCollectionPolicy(definition, values);
        assertCatalogResourceWritable(resource, null, values);
        if (values.isEmpty()) {
            throw new BusinessException("没有可保存字段");
        }
        checkWritableDataScope(definition, values);
        StringJoiner columns = new StringJoiner(", ");
        StringJoiner placeholders = new StringJoiner(", ");
        List<Object> args = new ArrayList<>();
        values.forEach((column, value) -> {
            columns.add(column);
            placeholders.add("?");
            args.add(normalizeValue(value));
        });
        String sql = "INSERT INTO " + definition.table() + " (" + columns + ") VALUES (" + placeholders + ")";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if ("billing-rules".equals(resource) && key != null) {
            replaceBillingRuleConfigs(key.longValue(), body);
        }
        return key == null ? values : get(resource, key.longValue());
    }

    @Transactional
    public Map<String, Object> update(String resource, long id, Map<String, Object> body) {
        TableDefinition definition = tableRegistry.get(resource);
        Map<String, Object> current = get(resource, id);
        if ("devices".equals(resource)) {
            throw new BusinessException("设备档案必须通过设备上下文接口修改，不能通过通用表单绕过部署和计量校验");
        }
        assertCatalogResourceWritable(resource, id, current);
        Map<String, Object> values = writableValues(definition, body, true);
        // Keep the original creator immutable while recording the actual editor for every generic archive update.
        if (definition.hasColumn("update_by")) values.put("update_by", operatorName());
        if ("devices".equals(resource)) {
            Long currentTypeId = longOrNull(current.get("device_type_id"));
            Long requestedTypeId = longOrNull(values.get("device_type_id"));
            if (requestedTypeId != null && !Objects.equals(currentTypeId, requestedTypeId)) {
                throw new BusinessException("设备型号不能通过通用编辑修改，请使用产品目录版本变更流程");
            }
            Long currentVersionId = longOrNull(current.get("model_version_id"));
            Long requestedVersionId = longOrNull(values.get("model_version_id"));
            if (requestedVersionId != null && !Objects.equals(currentVersionId, requestedVersionId)) {
                throw new BusinessException("设备引用的型号版本不可通过通用编辑修改");
            }
            Long currentGatewayId = longOrNull(current.get("gateway_id"));
            if (values.containsKey("gateway_id") && !Objects.equals(currentGatewayId, longOrNull(values.get("gateway_id")))) {
                throw new BusinessException("网关部署关系不能通过通用编辑修改，请使用设备部署接口");
            }
            if (values.containsKey("protocol_addr")
                    && !Objects.equals(Objects.toString(current.get("protocol_addr"), ""), Objects.toString(values.get("protocol_addr"), ""))) {
                throw new BusinessException("协议地址不能通过通用编辑修改，请重新部署设备");
            }
            Long spaceId = longOrNull(values.get("space_id"));
            Long orgId = longOrNull(values.getOrDefault("org_id", current.get("org_id")));
            if (currentGatewayId != null) {
                List<Long> gatewayOrgs = jdbcTemplate.queryForList("SELECT org_id FROM dev_gateway WHERE id=?", Long.class, currentGatewayId);
                if (gatewayOrgs.isEmpty() || !Objects.equals(gatewayOrgs.get(0), orgId)) {
                    throw new BusinessException("已部署设备不能改到网关之外的组织，请先解绑设备");
                }
            }
            if (spaceId != null) {
                List<Long> spaceOrgs = jdbcTemplate.queryForList("SELECT org_id FROM park_space WHERE id=? AND status<>'DISABLED'", Long.class, spaceId);
                if (spaceOrgs.isEmpty() || !Objects.equals(spaceOrgs.get(0), orgId)) {
                    throw new BusinessException("安装空间必须属于设备管理组织且处于可用状态");
                }
            }
        }
        assertCatalogResourceWritable(resource, id, values);
        validateDeviceCollectionPolicy(definition, values);
        checkWritableDataScope(definition, values);
        if (values.isEmpty()) {
            return get(resource, id);
        }
        StringJoiner sets = new StringJoiner(", ");
        List<Object> args = new ArrayList<>();
        values.forEach((column, value) -> {
            sets.add(column + " = ?");
            args.add(normalizeValue(value));
        });
        args.add(id);
        jdbcTemplate.update("UPDATE " + definition.table() + " SET " + sets + " WHERE id = ?", args.toArray());
        if ("billing-rules".equals(resource)) {
            replaceBillingRuleConfigs(id, body);
        }
        return get(resource, id);
    }

    @SuppressWarnings("unchecked")
    private void replaceBillingRuleConfigs(long ruleId, Map<String, Object> body) {
        Object raw = body.get("device_configs");
        if (!(raw instanceof List<?> configs)) return;
        jdbcTemplate.update("DELETE FROM billing_rule_point_config WHERE rule_id = ?", ruleId);
        int sort = 1;
        for (Object value : configs) {
            if (!(value instanceof Map<?, ?> item)) continue;
            Object typeId = item.get("deviceTypeId");
            Object rawPoints = item.get("pointCodes");
            if (typeId == null || !(rawPoints instanceof List<?> pointCodes)) continue;
            for (Object point : pointCodes) {
                String code = Objects.toString(point, "").trim();
                if (code.isBlank()) continue;
                List<String> pointNames = jdbcTemplate.queryForList(
                        "SELECT point_name FROM dev_point_definition WHERE device_type_id=? AND point_code=? LIMIT 1",
                        String.class, Long.parseLong(String.valueOf(typeId)), code);
                String pointName = pointNames.isEmpty() ? code : pointNames.get(0);
                jdbcTemplate.update("""
                        INSERT INTO billing_rule_point_config
                          (rule_id, device_type_id, point_code, point_name, sort_no, enabled)
                        VALUES (?, ?, ?, ?, ?, 1)
                        """, ruleId, Long.parseLong(String.valueOf(typeId)), code, pointName, sort++);
            }
        }
    }

    private void enrichBillingRuleConfigs(String resource, List<Map<String, Object>> rows) {
        if (!"billing-rules".equals(resource)) return;
        List<Long> ruleIds = rows.stream()
                .map(row -> longOrNull(row.get("id")))
                .filter(Objects::nonNull)
                .toList();
        if (ruleIds.isEmpty()) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(ruleIds.size(), "?"));
        List<Map<String, Object>> allConfigs = jdbcTemplate.queryForList("""
                SELECT c.rule_id, c.device_type_id, t.type_name AS device_type_name, c.point_code, c.point_name
                FROM billing_rule_point_config c
                LEFT JOIN dev_device_type t ON t.id=c.device_type_id
                WHERE c.rule_id IN (%s) AND c.enabled=1
                ORDER BY c.rule_id, c.sort_no, c.id
                """.formatted(placeholders), ruleIds.toArray());
        Map<Long, List<Map<String, Object>>> configsByRuleId = new LinkedHashMap<>();
        for (Map<String, Object> config : allConfigs) {
            Long ruleId = longOrNull(config.get("rule_id"));
            if (ruleId != null) configsByRuleId.computeIfAbsent(ruleId, ignored -> new ArrayList<>()).add(config);
        }
        for (Map<String, Object> row : rows) {
            Long ruleId = longOrNull(row.get("id"));
            if (ruleId == null) continue;
            List<Map<String, Object>> configs = configsByRuleId.getOrDefault(ruleId, List.of());
            row.put("device_configs", configs);
            if (!configs.isEmpty()) {
                row.put("device_type_name", configs.stream().map(x -> Objects.toString(x.get("device_type_name"), "—")).distinct().reduce((a, b) -> a + "、" + b).orElse("—"));
                row.put("metric_point_code", configs.stream().map(x -> Objects.toString(x.get("point_code"), "")).filter(x -> !x.isBlank()).distinct().reduce((a, b) -> a + "," + b).orElse(""));
            }
        }
    }

    private String optionColumns(String resource) {
        return switch (resource) {
            case "orgs" -> "id, parent_id, org_name, org_type";
            case "gateways" -> "id, gateway_sn, gateway_name, org_id, status, online_status";
            case "devices" -> "id, device_sn, device_name, gateway_id, org_id, space_id, device_type_id, status";
            case "device-types" -> "id, type_code, type_name, protocol_type, enabled";
            case "spaces" -> "id, org_id, parent_id, space_code, space_name, space_type, status";
            case "point-definitions" -> "id, device_type_id, point_code, point_name, unit, enabled";
            case "billing-accounts" -> "id, account_name, org_id, status";
            case "billing-rules" -> "id, account_id, rule_name, device_type_id, metric_point_code, enabled";
            default -> "id";
        };
    }

    @Transactional
    public void delete(String resource, long id) {
        TableDefinition definition = tableRegistry.get(resource);
        Map<String, Object> current = get(resource, id);
        assertCatalogResourceWritable(resource, id, current);
        assertDeleteChain(resource, id);
        // 计费空间的设备范围映射属于空间的业务子记录。数据库外键默认不级联，
        // 删除空间前必须先清理映射，否则会被 billing_space_scope 外键拦截并返回 500。
        if ("spaces".equals(resource)) {
            jdbcTemplate.update("DELETE FROM billing_space_scope WHERE space_id = ?", id);
        }
        jdbcTemplate.update("DELETE FROM " + definition.table() + " WHERE id = ?", id);
    }

    private void assertCatalogResourceWritable(String resource, Long id, Map<String, Object> values) {
        if ("device-types".equals(resource)) {
            if (id != null) deviceCatalogService.assertDeviceTypeWritable(id);
            return;
        }
        if (!Set.of("point-definitions", "point-mappings").contains(resource)) return;
        Long deviceTypeId = longOrNull(values.get("device_type_id"));
        if (deviceTypeId != null) deviceCatalogService.assertDeviceTypeWritable(deviceTypeId);
    }

    @Transactional
    public Map<String, Object> copy(String resource, long id) {
        TableDefinition definition = tableRegistry.get(resource);
        if (!Set.of("orgs", "gateways", "devices").contains(resource)) {
            throw new BusinessException(400, "当前资源不支持复制");
        }
        Map<String, Object> current = get(resource, id);
        Map<String, Object> values = writableValues(definition, current, false);
        if ("gateways".equals(resource)) {
            values.put("gateway_sn", uniqueCopyCode(Objects.toString(current.get("gateway_sn"), "GW"), id));
        } else if ("devices".equals(resource)) {
            values.put("device_sn", uniqueCopyCode(Objects.toString(current.get("device_sn"), "DEV"), id));
            values.put("quality_gate_start_date", LocalDate.now());
        }
        return create(resource, values);
    }

    private String operatorName() {
        if (!StpUtil.isLogin()) return "system";
        try {
            Long userId = StpUtil.getLoginIdAsLong();
            List<Map<String, Object>> users = jdbcTemplate.queryForList(
                    "SELECT nickname, username FROM sys_user WHERE id=?", userId);
            if (users.isEmpty()) return "user-" + userId;
            Map<String, Object> user = users.get(0);
            String nickname = Objects.toString(user.get("nickname"), "").trim();
            return nickname.isBlank() ? Objects.toString(user.get("username"), "user-" + userId) : nickname;
        } catch (RuntimeException ignored) {
            // Background calls and isolated tests have no request-bound Sa-Token context.
            return "system";
        }
    }

    @Transactional
    public List<Map<String, Object>> bindDevicesToGateway(long gatewayId, Map<String, Object> body) {
        accessService.assertGatewayAccess(gatewayId);
        Map<String, Object> gateway = single("SELECT * FROM dev_gateway WHERE id = ?", gatewayId);
        Long orgId = longOrNull(gateway.get("org_id"));
        List<Long> ids = deviceIds(body.get("ids"));
        if (ids.isEmpty()) {
            throw new BusinessException("请选择需要绑定的设备");
        }
        for (Long deviceId : ids) {
            Map<String, Object> device = single("""
                    SELECT d.id,d.org_id,d.gateway_id,d.protocol_addr,t.protocol_type
                    FROM dev_device d JOIN dev_device_type t ON t.id=d.device_type_id WHERE d.id=?
                    """, deviceId);
            Long currentOrgId = longOrNull(device.get("org_id"));
            if (currentOrgId != null) {
                assertOrgAccess(currentOrgId);
            }
            Long currentGatewayId = longOrNull(device.get("gateway_id"));
            if (currentGatewayId != null && !Objects.equals(currentGatewayId, gatewayId)) {
                accessService.assertGatewayAccess(currentGatewayId);
            }
            if (!Objects.equals(currentOrgId, orgId)) {
                throw new BusinessException("批量绑定不能改变设备管理组织，请先在设备档案调整组织归属");
            }
            if (Objects.toString(device.get("protocol_type"), "").toUpperCase(Locale.ROOT).startsWith("MODBUS")
                    && Objects.toString(device.get("protocol_addr"), "").isBlank()) {
                throw new BusinessException("MODBUS 设备必须在设备档案中填写从站地址后单独部署");
            }
            deviceProvisionService.deploy(deviceId, Map.of(
                    "gatewayId", gatewayId,
                    "protocolAddr", Objects.toString(device.get("protocol_addr"), ""),
                    "remark", "从接入拓扑树批量绑定"));
        }
        String placeholders = placeholders(ids.size());
        List<Object> args = new ArrayList<>(ids);
        return jdbcTemplate.queryForList("""
                SELECT d.*, g.gateway_name, g.gateway_sn, o.org_name, t.type_name, t.type_code
                FROM dev_device d
                LEFT JOIN dev_gateway g ON g.id = d.gateway_id
                LEFT JOIN dev_org o ON o.id = d.org_id
                LEFT JOIN dev_device_type t ON t.id = d.device_type_id
                WHERE d.id IN (
                """ + placeholders + ")", args.toArray());
    }

    private String uniqueCopyCode(String base, long id) {
        String cleaned = base == null ? "" : base.trim();
        return cleaned + "-COPY-" + id + "-" + Long.toString(System.currentTimeMillis(), 36).toUpperCase(Locale.ROOT);
    }

    private void assertDeleteChain(String resource, long id) {
        switch (resource) {
            case "orgs" -> {
                if (count("SELECT COUNT(*) FROM dev_org WHERE parent_id = ?", id) > 0) {
                    throw new BusinessException("该组织下存在子组织，请先删除子组织");
                }
                if (count("SELECT COUNT(*) FROM dev_gateway WHERE org_id = ?", id) > 0) {
                    throw new BusinessException("该组织下存在网关，请先删除网关");
                }
            }
            case "gateways" -> {
                if (count("SELECT COUNT(*) FROM dev_device WHERE gateway_id = ?", id) > 0) {
                    throw new BusinessException("该网关下存在设备，请先删除设备");
                }
            }
            case "devices" -> {
                if (count("SELECT COUNT(*) FROM log_alarm WHERE device_id = ?", id) > 0
                        || count("SELECT COUNT(*) FROM command_record WHERE target_type = 'DEVICE' AND target_id = ?", id) > 0
                        || count("SELECT COUNT(*) FROM ops_work_order WHERE device_id = ?", id) > 0
                        || count("SELECT COUNT(*) FROM billing_bill_detail WHERE device_id = ?", id) > 0
                        || count("SELECT COUNT(*) FROM billing_meter_change_order WHERE source_device_id = ? OR target_device_id = ?", id, id) > 0) {
                    throw new BusinessException("设备已有告警、指令、工单、账单或计量变更记录，不能删除；请改为停用设备");
                }
            }
            case "spaces" -> {
                if (count("SELECT COUNT(*) FROM park_space WHERE parent_id = ?", id) > 0
                        || count("SELECT COUNT(*) FROM dev_device WHERE space_id = ?", id) > 0
                        || count("SELECT COUNT(*) FROM leasing_contract_space WHERE space_id = ?", id) > 0
                        || count("SELECT COUNT(*) FROM alarm_rule WHERE space_id = ?", id) > 0) {
                    throw new BusinessException("该空间仍被子空间、设备、合同或告警规则引用，不能删除；请保留并停用空间");
                }
            }
            default -> {
            }
        }
    }

    private long count(String sql, long id) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, id);
        return value == null ? 0L : value;
    }

    private long count(String sql, long firstId, long secondId) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, firstId, secondId);
        return value == null ? 0L : value;
    }

    private Map<String, Object> single(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        if (rows.isEmpty()) {
            throw new BusinessException(404, "数据不存在");
        }
        return rows.get(0);
    }

    private String appendDataScope(TableDefinition definition, String where, List<Object> args) {
        if (!StpUtil.isLogin()) {
            return where;
        }
        Set<Long> visibleOrgIds = dataScopeService.visibleOrgIds(StpUtil.getLoginIdAsLong());
        if (visibleOrgIds == null) {
            return where;
        }
        String scopeSql = relatedDataScopeSql(definition, visibleOrgIds, args);
        if (scopeSql == null && definition.dataScopeColumn() != null) {
            scopeSql = dataScopeService.inClause(definition.table() + "." + definition.dataScopeColumn(), visibleOrgIds, args);
        }
        if (scopeSql == null) {
            return where;
        }
        if (scopeSql.isBlank()) {
            return where;
        }
        if (where == null || where.isBlank()) {
            return " WHERE " + scopeSql.substring(" AND ".length());
        }
        return where + scopeSql;
    }

    private void checkWritableDataScope(TableDefinition definition, Map<String, Object> values) {
        if (!StpUtil.isLogin()) {
            return;
        }
        if (definition.dataScopeColumn() != null && values.containsKey(definition.dataScopeColumn())) {
            Long orgId = longOrNull(values.get(definition.dataScopeColumn()));
            assertOrgAccess(orgId);
        }
        checkRelatedWritableDataScope(definition, values);
    }

    private void applyDefaultDeviceOrg(TableDefinition definition, Map<String, Object> values) {
        if (!"devices".equals(definition.resource()) || longOrNull(values.get("org_id")) != null || !StpUtil.isLogin()) {
            return;
        }
        Set<Long> visibleOrgIds = dataScopeService.visibleOrgIds(StpUtil.getLoginIdAsLong());
        if (visibleOrgIds != null && !visibleOrgIds.isEmpty()) {
            values.put("org_id", visibleOrgIds.iterator().next());
            return;
        }
        List<Long> orgIds = jdbcTemplate.queryForList("SELECT id FROM dev_org ORDER BY parent_id, sort, id LIMIT 1", Long.class);
        if (!orgIds.isEmpty()) {
            values.put("org_id", orgIds.get(0));
        }
    }

    private void applyDefaultCollectionPolicy(TableDefinition definition, Map<String, Object> values) {
        if (!"devices".equals(definition.resource())) return;
        values.putIfAbsent("collect_interval_seconds", 300);
        values.putIfAbsent("quality_threshold_pct", java.math.BigDecimal.valueOf(95));
        values.putIfAbsent("quality_gate_start_date", LocalDate.now());
    }

    private void validateDeviceCollectionPolicy(TableDefinition definition, Map<String, Object> values) {
        if (!"devices".equals(definition.resource())) return;
        if (values.containsKey("collect_interval_seconds")) {
            Long interval = longOrNull(values.get("collect_interval_seconds"));
            if (interval == null || interval < 10 || interval > 86_400) {
                throw new BusinessException("采集周期必须在 10 至 86400 秒之间");
            }
        }
        if (values.containsKey("quality_threshold_pct")) {
            try {
                java.math.BigDecimal threshold = new java.math.BigDecimal(String.valueOf(values.get("quality_threshold_pct")));
                if (threshold.compareTo(java.math.BigDecimal.ONE) < 0 || threshold.compareTo(java.math.BigDecimal.valueOf(100)) > 0) {
                    throw new BusinessException("日结算最低完整率必须在 1 至 100 之间");
                }
            } catch (NumberFormatException exception) {
                throw new BusinessException("日结算最低完整率格式不正确");
            }
        }
    }

    private String relatedDataScopeSql(TableDefinition definition, Set<Long> visibleOrgIds, List<Object> args) {
        return switch (definition.resource()) {
            case "alarm-rules" -> alarmRuleScopeSql(visibleOrgIds, args);
            case "billing-rules" -> existsSql("""
                    SELECT 1
                    FROM billing_account a
                    WHERE a.id = billing_rule.account_id
                    """, "a.org_id", visibleOrgIds, args);
            case "billing-rule-scopes" -> billingRuleScopeSql(visibleOrgIds, args);
            case "billing-price-items" -> existsSql("""
                    SELECT 1
                    FROM billing_rule r
                    JOIN billing_account a ON a.id = r.account_id
                    WHERE r.id = billing_price_item.rule_id
                    """, "a.org_id", visibleOrgIds, args);
            case "billing-bills" -> existsSql("""
                    SELECT 1
                    FROM billing_account a
                    WHERE a.id = billing_bill.account_id
                    """, "a.org_id", visibleOrgIds, args);
            case "billing-bill-details" -> existsSql("""
                    SELECT 1
                    FROM billing_bill b
                    JOIN billing_account a ON a.id = b.account_id
                    WHERE b.id = billing_bill_detail.bill_id
                    """, "a.org_id", visibleOrgIds, args);
            case "billing-payments" -> existsSql("""
                    SELECT 1
                    FROM billing_bill b
                    JOIN billing_account a ON a.id = b.account_id
                    WHERE b.id = billing_payment.bill_id
                    """, "a.org_id", visibleOrgIds, args);
            case "command-records" -> commandScopeSql(visibleOrgIds, args);
            default -> null;
        };
    }

    private String alarmRuleScopeSql(Set<Long> visibleOrgIds, List<Object> args) {
        String orgScope = dataScopeService.inClause("alarm_rule.org_id", visibleOrgIds, args);
        String deviceScope = existsSql("""
                SELECT 1
                FROM dev_device d
                WHERE d.id = alarm_rule.device_id
                """, "d.org_id", visibleOrgIds, args);
        return " AND (alarm_rule.rule_scope = 1"
                + (orgScope.isBlank() ? "" : orgScope.replaceFirst(" AND ", " OR "))
                + (deviceScope.isBlank() ? "" : deviceScope.replaceFirst(" AND ", " OR "))
                + ")";
    }

    private String billingRuleScopeSql(Set<Long> visibleOrgIds, List<Object> args) {
        String accountScope = existsSql("""
                SELECT 1
                FROM billing_rule r
                JOIN billing_account a ON a.id = r.account_id
                WHERE r.id = billing_rule_scope.rule_id
                """, "a.org_id", visibleOrgIds, args);
        String orgScope = dataScopeService.inClause("billing_rule_scope.scope_id", visibleOrgIds, args);
        String deviceScope = existsSql("""
                SELECT 1
                FROM dev_device d
                WHERE d.id = billing_rule_scope.scope_id
                """, "d.org_id", visibleOrgIds, args);
        if (accountScope.isBlank()) {
            return accountScope;
        }
        return accountScope
                + " AND (billing_rule_scope.scope_type NOT IN ('ORG', 'DEVICE')"
                + (orgScope.isBlank() ? "" : orgScope.replaceFirst(" AND ", " OR (billing_rule_scope.scope_type = 'ORG' AND ") + ")")
                + (deviceScope.isBlank() ? "" : deviceScope.replaceFirst(" AND ", " OR (billing_rule_scope.scope_type = 'DEVICE' AND ") + ")")
                + ")";
    }

    private String commandScopeSql(Set<Long> visibleOrgIds, List<Object> args) {
        String gatewayScope = existsSql("""
                SELECT 1
                FROM dev_gateway g
                WHERE g.id = command_record.gateway_id
                """, "g.org_id", visibleOrgIds, args);
        String deviceScope = existsSql("""
                SELECT 1
                FROM dev_device d
                WHERE d.id = command_record.target_id
                """, "d.org_id", visibleOrgIds, args);
        if (gatewayScope.isBlank() && deviceScope.isBlank()) {
            return " AND 1 = 0";
        }
        return " AND ("
                + (gatewayScope.isBlank() ? "1 = 0" : gatewayScope.substring(" AND ".length()))
                + (deviceScope.isBlank() ? "" : " OR " + deviceScope.substring(" AND ".length()))
                + ")";
    }

    private String existsSql(String existsBody, String orgColumn, Set<Long> visibleOrgIds, List<Object> args) {
        String scope = dataScopeService.inClause(orgColumn, visibleOrgIds, args);
        if (scope.isBlank()) {
            return scope;
        }
        return " AND EXISTS (" + existsBody + scope + ")";
    }

    private void checkRelatedWritableDataScope(TableDefinition definition, Map<String, Object> values) {
        switch (definition.resource()) {
            case "orgs" -> assertParentOrgAccess(values);
            case "devices" -> assertGatewayFieldAccess(values);
            case "alarm-rules" -> assertAlarmRuleWritableAccess(values);
            case "billing-rules", "billing-bills" -> assertAccountFieldAccess(values);
            case "billing-rule-scopes" -> assertBillingRuleScopeWritableAccess(values);
            case "billing-price-items" -> assertRuleFieldAccess(values);
            case "billing-bill-details" -> {
                assertBillFieldAccess(values);
                assertDeviceFieldAccess(values);
            }
            case "billing-payments" -> assertBillFieldAccess(values);
            case "command-records" -> {
                assertGatewayFieldAccess(values);
                assertDeviceTargetFieldAccess(values);
            }
            default -> {
            }
        }
    }

    private void assertParentOrgAccess(Map<String, Object> values) {
        if (values.containsKey("parent_id")) {
            Long parentId = longOrNull(values.get("parent_id"));
            if (parentId != null && parentId > 0) {
                assertOrgAccess(parentId);
            }
        }
    }

    private void assertAlarmRuleWritableAccess(Map<String, Object> values) {
        if (values.containsKey("org_id")) {
            Long orgId = longOrNull(values.get("org_id"));
            if (orgId != null) {
                assertOrgAccess(orgId);
            }
        }
        if (values.containsKey("device_id")) {
            Long deviceId = longOrNull(values.get("device_id"));
            if (deviceId != null) {
                accessService.assertDeviceAccess(deviceId);
            }
        }
    }

    private void assertBillingRuleScopeWritableAccess(Map<String, Object> values) {
        assertRuleFieldAccess(values);
        String scopeType = Objects.toString(values.get("scope_type"), "");
        Long scopeId = longOrNull(values.get("scope_id"));
        if ("ORG".equalsIgnoreCase(scopeType) && scopeId != null) {
            assertOrgAccess(scopeId);
        } else if ("DEVICE".equalsIgnoreCase(scopeType) && scopeId != null) {
            accessService.assertDeviceAccess(scopeId);
        }
    }

    private void assertGatewayFieldAccess(Map<String, Object> values) {
        if (values.containsKey("gateway_id")) {
            Long gatewayId = longOrNull(values.get("gateway_id"));
            if (gatewayId != null) {
                accessService.assertGatewayAccess(gatewayId);
            }
        }
    }

    private void assertDeviceFieldAccess(Map<String, Object> values) {
        if (values.containsKey("device_id")) {
            accessService.assertDeviceAccess(longOrNull(values.get("device_id")));
        }
    }

    private void assertDeviceTargetFieldAccess(Map<String, Object> values) {
        String targetType = Objects.toString(values.get("target_type"), "");
        if ("DEVICE".equalsIgnoreCase(targetType) && values.containsKey("target_id")) {
            accessService.assertDeviceAccess(longOrNull(values.get("target_id")));
        }
    }

    private void assertAccountFieldAccess(Map<String, Object> values) {
        if (values.containsKey("account_id")) {
            accessService.assertBillingAccountAccess(longOrNull(values.get("account_id")));
        }
    }

    private void assertRuleFieldAccess(Map<String, Object> values) {
        if (values.containsKey("rule_id")) {
            Long accountId = queryLong("""
                    SELECT account_id
                    FROM billing_rule
                    WHERE id = ?
                    """, longOrNull(values.get("rule_id")));
            accessService.assertBillingAccountAccess(accountId);
        }
    }

    private void assertBillFieldAccess(Map<String, Object> values) {
        if (values.containsKey("bill_id")) {
            accessService.assertBillAccess(longOrNull(values.get("bill_id")));
        }
    }

    private void assertOrgAccess(Long orgId) {
        if (orgId != null && !dataScopeService.hasOrgAccess(StpUtil.getLoginIdAsLong(), orgId)) {
            throw new BusinessException(403, "没有该组织的数据操作权限");
        }
    }

    private Long queryLong(String sql, Long id) {
        if (id == null) {
            throw new BusinessException("关联数据 ID 不能为空");
        }
        List<Long> rows = jdbcTemplate.queryForList(sql, Long.class, id);
        if (rows.isEmpty()) {
            throw new BusinessException(404, "关联数据不存在: " + id);
        }
        return rows.get(0);
    }

    private String buildWhere(TableDefinition definition, Map<String, String> params, List<Object> args) {
        StringJoiner where = new StringJoiner(" AND ");
        params.forEach((key, value) -> {
            if (value == null || value.isBlank() || isReservedParam(key)) {
                return;
            }
            if ("orgId".equals(key) && definition.dataScopeColumn() != null) {
                return;
            }
            String column = toSnake(key);
            if (definition.hasColumn(column)) {
                where.add(column + " = ?");
                args.add(value.trim());
            }
        });
        String keyword = params.get("keyword");
        if (keyword != null && !keyword.isBlank() && !definition.keywordColumns().isEmpty()) {
            StringJoiner keywordWhere = new StringJoiner(" OR ", "(", ")");
            for (String column : definition.keywordColumns()) {
                keywordWhere.add(column + " LIKE ?");
                args.add("%" + keyword.trim() + "%");
            }
            where.add(keywordWhere.toString());
        }
        String text = where.toString();
        String orgFilter = explicitOrgFilter(definition, params, args);
        if (text.isBlank() && orgFilter.isBlank()) {
            return "";
        }
        String base = text.isBlank() ? " WHERE 1 = 1" : " WHERE " + text;
        return base + orgFilter;
    }

    private String explicitOrgFilter(TableDefinition definition, Map<String, String> params, List<Object> args) {
        if (definition.dataScopeColumn() == null) {
            return "";
        }
        Long orgId = longOrNull(params.get("orgId"));
        boolean includeChildren = Boolean.parseBoolean(Objects.toString(params.getOrDefault("includeChildren", "false")));
        List<Long> orgIds = commaSeparatedLongs(params.get("orgIds"));
        if (!orgIds.isEmpty()) {
            return accessService.orgFilterSql(definition.table() + "." + definition.dataScopeColumn(), orgIds, includeChildren, args);
        }
        return accessService.orgFilterSql(definition.table() + "." + definition.dataScopeColumn(), orgId, includeChildren, args);
    }

    private Map<String, Object> writableValues(TableDefinition definition, Map<String, Object> body, boolean update) {
        Map<String, Object> values = new LinkedHashMap<>();
        body.forEach((key, value) -> {
            String column = toSnake(key);
            if (!definition.hasColumn(column) || "id".equals(column) || "create_time".equals(column) || "update_time".equals(column)) {
                return;
            }
            values.put(column, value);
        });
        return values;
    }

    private Object normalizeValue(Object value) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (JsonProcessingException ex) {
                throw new BusinessException("JSON 字段序列化失败");
            }
        }
        return value;
    }

    private boolean isReservedParam(String key) {
        return "pageNum".equals(key) || "pageSize".equals(key) || "keyword".equals(key)
                || "includeChildren".equals(key) || "orgIds".equals(key);
    }

    private List<Long> commaSeparatedLongs(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(this::longOrNull)
                .filter(Objects::nonNull)
                .distinct()
                .limit(100)
                .toList();
    }

    private int parsePositive(String input, int defaultValue) {
        try {
            int value = Integer.parseInt(input);
            return value > 0 ? value : defaultValue;
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private Long longOrNull(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return Long.valueOf(value.toString());
    }

    private List<Long> deviceIds(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(this::longOrNull).filter(Objects::nonNull).toList();
        }
        if (value == null || value.toString().isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.toString().split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(Long::valueOf)
                .toList();
    }

    private String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    private void enrichDeviceModelImages(String resource, List<Map<String, Object>> rows) {
        if (!"devices".equals(resource) || rows.isEmpty()) return;
        Set<Long> versionIds = new java.util.LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            Long versionId = longOrNull(row.get("model_version_id"));
            if (versionId != null) versionIds.add(versionId);
        }
        if (versionIds.isEmpty()) return;
        List<Map<String, Object>> images = jdbcTemplate.queryForList("""
                SELECT v.id AS model_version_id, m.image_object_key
                FROM dev_device_model_version v
                JOIN dev_device_model m ON m.id = v.model_id
                WHERE v.id IN (
                """ + placeholders(versionIds.size()) + ")", versionIds.toArray());
        Map<String, String> imageByVersion = new LinkedHashMap<>();
        for (Map<String, Object> image : images) {
            imageByVersion.put(Objects.toString(image.get("model_version_id"), ""),
                    deviceCatalogService.modelImageUrl(Objects.toString(image.get("image_object_key"), "")));
        }
        for (Map<String, Object> row : rows) {
            row.put("model_image_url", imageByVersion.getOrDefault(Objects.toString(row.get("model_version_id"), ""), ""));
        }
    }

    private String toSnake(String input) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isUpperCase(c)) {
                out.append('_').append(Character.toLowerCase(c));
            } else {
                out.append(c == '-' ? '_' : Character.toLowerCase(c));
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
