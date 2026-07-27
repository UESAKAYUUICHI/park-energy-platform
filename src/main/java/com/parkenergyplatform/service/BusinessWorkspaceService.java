package com.parkenergyplatform.service;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Supplier;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkenergyplatform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class BusinessWorkspaceService {
    private final JdbcTemplate jdbcTemplate;
    private final RemoteServiceClient remoteServiceClient;
    private final ObjectMapper objectMapper;
    private final DataScopeService dataScopeService;

    public BusinessWorkspaceService(JdbcTemplate jdbcTemplate, RemoteServiceClient remoteServiceClient, ObjectMapper objectMapper,
                                    DataScopeService dataScopeService) {
        this.jdbcTemplate = jdbcTemplate;
        this.remoteServiceClient = remoteServiceClient;
        this.objectMapper = objectMapper;
        this.dataScopeService = dataScopeService;
    }

    public Map<String, Object> cockpit(Long rootOrgId) {
        Map<String, Object> data = new LinkedHashMap<>();
        putSafely(data, "metrics", () -> metrics(rootOrgId), Map.of());
        putSafely(data, "deviceHealth", () -> {
            List<Object> deviceHealthArgs = new ArrayList<>();
            return jdbcTemplate.queryForList("""
                SELECT d.id, d.device_sn, d.device_name, d.status, d.install_location,
                       g.gateway_sn, g.online_status, o.org_name, t.type_name
                FROM dev_device d
                LEFT JOIN dev_gateway g ON g.id = d.gateway_id
                LEFT JOIN dev_org o ON o.id = d.org_id
                LEFT JOIN dev_device_type t ON t.id = d.device_type_id
                WHERE 1 = 1
                """ + scopeSql("d.org_id", deviceHealthArgs, rootOrgId) + """
                ORDER BY d.id DESC
                LIMIT 12
                """, deviceHealthArgs.toArray());
        }, List.of());
        putSafely(data, "energyTrend", () -> {
            List<Object> trendArgs = new ArrayList<>();
            trendArgs.add(java.sql.Date.valueOf(LocalDate.now().minusDays(14)));
            return jdbcTemplate.queryForList("""
                SELECT stat_date, ROUND(SUM(COALESCE(usage_value, 0)), 4) AS usage_value
                FROM stats_daily_point
                WHERE stat_date >= ?
                """ + scopeSql("org_id", trendArgs, rootOrgId) + """
                GROUP BY stat_date
                ORDER BY stat_date
                """, trendArgs.toArray());
        }, List.of());
        putSafely(data, "latestAlarms", () -> alarmEvents(6, null, rootOrgId), List.of());
        putSafely(data, "latestBills", () -> {
            List<Object> billArgs = new ArrayList<>();
            return jdbcTemplate.queryForList("""
                SELECT b.*, a.account_name
                FROM billing_bill b
                LEFT JOIN billing_account a ON a.id = b.account_id
                WHERE 1 = 1
                """ + scopeSql("a.org_id", billArgs, rootOrgId) + """
                ORDER BY b.id DESC
                LIMIT 6
                """, billArgs.toArray());
        }, List.of());
        return data;
    }

    public Map<String, Object> archiveWorkspace() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Object> orgArgs = new ArrayList<>();
        data.put("orgs", jdbcTemplate.queryForList("SELECT * FROM dev_org WHERE 1 = 1" + scopeSql("id", orgArgs) + " ORDER BY parent_id, sort, id", orgArgs.toArray()));
        List<Object> gatewayArgs = new ArrayList<>();
        data.put("gateways", jdbcTemplate.queryForList("SELECT * FROM dev_gateway WHERE 1 = 1" + scopeSql("org_id", gatewayArgs) + " ORDER BY id DESC LIMIT 80", gatewayArgs.toArray()));
        data.put("deviceTypes", jdbcTemplate.queryForList("SELECT * FROM dev_device_type ORDER BY id DESC LIMIT 80"));
        data.put("devices", deviceRows());
        data.put("pointDefinitions", jdbcTemplate.queryForList("SELECT * FROM dev_point_definition ORDER BY device_type_id, sort, id"));
        data.put("pointMappings", jdbcTemplate.queryForList("SELECT * FROM dev_point_mapping ORDER BY device_type_id, id"));
        data.put("configChecks", configChecks());
        return data;
    }

    public Map<String, Object> monitorWorkspace(Long deviceId, String pointCode) {
        Long targetDeviceId = deviceId == null ? firstDeviceId() : deviceId;
        if (targetDeviceId != null) {
            assertDeviceAccess(targetDeviceId);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("devices", deviceRows());
        data.put("pointDefinitions", targetDeviceId == null ? List.of() : pointsForDevice(targetDeviceId));
        List<Object> dailyArgs = new ArrayList<>();
        dailyArgs.add(targetDeviceId);
        dailyArgs.add(targetDeviceId);
        dailyArgs.add(blankToNull(pointCode));
        dailyArgs.add(blankToNull(pointCode));
        data.put("dailyStats", jdbcTemplate.queryForList("""
                SELECT s.*, d.device_name, o.org_name
                FROM stats_daily_point s
                LEFT JOIN dev_device d ON d.id = s.device_id
                LEFT JOIN dev_org o ON o.id = s.org_id
                WHERE (? IS NULL OR s.device_id = ?) AND (? IS NULL OR s.point_code = ?)
                """ + scopeSql("s.org_id", dailyArgs) + """
                ORDER BY s.stat_date DESC, s.id DESC
                LIMIT 80
                """, dailyArgs.toArray()));
        data.put("realtime", targetDeviceId == null ? null : safeRemoteData("/api/data/realtime/devices/" + targetDeviceId));
        data.put("history", targetDeviceId == null ? null : safeRemoteData("/api/data/history" + historyQuery(targetDeviceId, pointCode)));
        return data;
    }

    public Map<String, Object> alarmWorkspace() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Object> ruleArgs = new ArrayList<>();
        data.put("rules", jdbcTemplate.queryForList("""
                SELECT r.*, d.device_name, o.org_name
                FROM alarm_rule r
                LEFT JOIN dev_device d ON d.id = r.device_id
                LEFT JOIN dev_org o ON o.id = r.org_id
                WHERE 1 = 1
                """ + scopeSql("r.org_id", ruleArgs) + """
                ORDER BY r.id DESC
                LIMIT 80
                """, ruleArgs.toArray()));
        data.put("events", alarmEvents(80, null));
        data.put("pendingCount", countWhereScoped("log_alarm", "org_id", "deal_status = 0"));
        data.put("handledCount", countWhereScoped("log_alarm", "org_id", "deal_status = 1"));
        return data;
    }

    public Map<String, Object> billingWorkspace() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Object> accountArgs = new ArrayList<>();
        data.put("accounts", jdbcTemplate.queryForList("""
                SELECT a.*, o.org_name
                FROM billing_account a
                LEFT JOIN dev_org o ON o.id = a.org_id
                WHERE 1 = 1
                """ + scopeSql("a.org_id", accountArgs) + """
                ORDER BY a.id DESC
                LIMIT 80
                """, accountArgs.toArray()));
        List<Object> ruleArgs = new ArrayList<>();
        data.put("rules", jdbcTemplate.queryForList("""
                SELECT r.*, a.account_name, t.type_name
                FROM billing_rule r
                LEFT JOIN billing_account a ON a.id = r.account_id
                LEFT JOIN dev_device_type t ON t.id = r.device_type_id
                WHERE 1 = 1
                """ + scopeSql("a.org_id", ruleArgs) + """
                ORDER BY r.id DESC
                LIMIT 80
                """, ruleArgs.toArray()));
        data.put("priceItems", jdbcTemplate.queryForList("SELECT * FROM billing_price_item ORDER BY rule_id, sort, id"));
        List<Object> billsArgs = new ArrayList<>();
        data.put("bills", jdbcTemplate.queryForList("""
                SELECT b.*, a.account_name
                FROM billing_bill b
                LEFT JOIN billing_account a ON a.id = b.account_id
                WHERE 1 = 1
                """ + scopeSql("a.org_id", billsArgs) + """
                ORDER BY b.id DESC
                LIMIT 80
                """, billsArgs.toArray()));
        List<Object> paymentArgs = new ArrayList<>();
        data.put("payments", jdbcTemplate.queryForList("""
                SELECT p.*
                FROM billing_payment p
                JOIN billing_bill b ON b.id = p.bill_id
                JOIN billing_account a ON a.id = b.account_id
                WHERE 1 = 1
                """ + scopeSql("a.org_id", paymentArgs) + """
                ORDER BY p.id DESC LIMIT 80
                """, paymentArgs.toArray()));
        return data;
    }

    public Map<String, Object> commandWorkspace() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Object> gatewayArgs = new ArrayList<>();
        data.put("gateways", jdbcTemplate.queryForList("SELECT id, gateway_sn, gateway_name, online_status, status FROM dev_gateway WHERE 1 = 1" + scopeSql("org_id", gatewayArgs) + " ORDER BY id", gatewayArgs.toArray()));
        List<Object> deviceArgs = new ArrayList<>();
        data.put("devices", jdbcTemplate.queryForList("SELECT id, device_sn, device_name, gateway_id, status FROM dev_device WHERE 1 = 1" + scopeSql("org_id", deviceArgs) + " ORDER BY id", deviceArgs.toArray()));
        List<Object> commandArgs = new ArrayList<>();
        data.put("commands", jdbcTemplate.queryForList("""
                SELECT c.*
                FROM command_record c
                JOIN dev_gateway g ON g.id = c.gateway_id
                WHERE 1 = 1
                """ + scopeSql("g.org_id", commandArgs) + """
                ORDER BY c.id DESC LIMIT 80
                """, commandArgs.toArray()));
        return data;
    }

    public Map<String, Object> sendDeviceCommand(Map<String, Object> request) {
        Long deviceId = longOrNull(request.get("targetId"));
        Map<String, Object> body = new LinkedHashMap<>(request);
        if (deviceId != null) {
            Map<String, Object> device = single("SELECT * FROM dev_device WHERE id = ?", deviceId);
            Long orgId = longOrNull(device.get("org_id"));
            if (orgId != null && !dataScopeService.hasOrgAccess(StpUtil.getLoginIdAsLong(), orgId)) {
                throw new BusinessException(403, "没有该设备的数据操作权限");
            }
            body.putIfAbsent("gatewayId", device.get("gateway_id"));
            body.putIfAbsent("targetType", "DEVICE");
            body.putIfAbsent("targetSn", device.get("device_sn"));
        }
        body.putIfAbsent("requestUserId", StpUtil.getLoginIdAsLong());
        body.putIfAbsent("requestUsername", currentUsername());
        return remoteServiceClient.postAccess("/api/access/commands", body);
    }

    public Map<String, Object> parseTest(Map<String, Object> request) {
        long deviceTypeId = requiredLong(request.get("deviceTypeId"), "deviceTypeId");
        Object samplePayload = request.get("samplePayload");
        if (samplePayload == null) {
            throw new BusinessException("samplePayload 不能为空");
        }
        JsonNode root = objectMapper.valueToTree(samplePayload);
        Map<String, Object> points = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> mappings = jdbcTemplate.queryForList(
                "SELECT * FROM dev_point_mapping WHERE device_type_id = ? ORDER BY id", deviceTypeId);
        for (Map<String, Object> mapping : mappings) {
            String pointCode = Objects.toString(mapping.get("point_code"), "");
            String sourcePath = Objects.toString(mapping.get("source_path"), "");
            Object raw = readJsonPath(root, sourcePath);
            if (raw == null) {
                if (Objects.equals(numberOrZero(mapping.get("required")).intValue(), 1)) {
                    errors.add(pointCode + " 缺少来源字段 " + sourcePath);
                }
                continue;
            }
            BigDecimal scaled = decimal(raw)
                    .multiply(decimal(mapping.get("scale_factor")))
                    .add(decimal(mapping.get("offset_value")));
            points.put(pointCode, scaled);
        }
        return Map.of("success", errors.isEmpty(), "points", points, "errors", errors);
    }

    private Map<String, Object> metrics(Long rootOrgId) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("orgCount", countScoped("dev_org", "id", rootOrgId));
        metrics.put("gatewayCount", countScoped("dev_gateway", "org_id", rootOrgId));
        metrics.put("onlineGatewayCount", countWhereScoped("dev_gateway", "org_id", "online_status = 1", rootOrgId));
        metrics.put("deviceCount", countScoped("dev_device", "org_id", rootOrgId));
        metrics.put("enabledDeviceCount", countWhereScoped("dev_device", "org_id", "status = 1", rootOrgId));
        metrics.put("pointCount", count("dev_point_definition"));
        metrics.put("billablePointCount", countWhere("dev_point_definition", "billable = 1"));
        metrics.put("pendingAlarmCount", countWhereScoped("log_alarm", "org_id", "deal_status = 0", rootOrgId));
        metrics.put("unpaidBillCount", billingBillCount("b.pay_status = 0", rootOrgId));
        metrics.put("totalReceivable", billingBillSum("b.pay_status IN (0,2)", rootOrgId));
        metrics.put("todayUsage", sumScoped("stats_daily_point", "usage_value", "org_id", "stat_date = CURDATE()", rootOrgId));
        return metrics;
    }

    private List<Map<String, Object>> deviceRows() {
        List<Object> args = new ArrayList<>();
        return jdbcTemplate.queryForList("""
                SELECT d.*, o.org_name, g.gateway_sn, g.gateway_name, g.online_status, t.type_code, t.type_name
                FROM dev_device d
                LEFT JOIN dev_org o ON o.id = d.org_id
                LEFT JOIN dev_gateway g ON g.id = d.gateway_id
                LEFT JOIN dev_device_type t ON t.id = d.device_type_id
                WHERE 1 = 1
                """ + scopeSql("d.org_id", args) + """
                ORDER BY d.id DESC
                LIMIT 120
                """, args.toArray());
    }

    private List<Map<String, Object>> configChecks() {
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(check("网关档案", countScoped("dev_gateway", "org_id") > 0, "至少维护一个启用网关，并让 gateway_sn 与模拟网关一致"));
        checks.add(check("设备档案", countScoped("dev_device", "org_id") > 0, "至少维护一个设备，并绑定网关、组织、设备类型"));
        checks.add(check("测点定义", count("dev_point_definition") > 0, "设备类型需要配置可展示、可统计、可计费的测点"));
        checks.add(check("测点映射", count("dev_point_mapping") > 0, "数据服务依赖映射把原始字段转换成标准 point_code"));
        checks.add(check("计费规则", count("billing_rule") > 0 && count("billing_price_item") > 0, "账单生成需要账号、规则、范围和价格"));
        checks.add(check("告警规则", count("alarm_rule") > 0, "告警规则启用后 data 服务才能生成告警事实"));
        return checks;
    }

    private Map<String, Object> check(String name, boolean passed, String message) {
        return Map.of("name", name, "passed", passed, "message", message);
    }

    private List<Map<String, Object>> alarmEvents(int limit, Integer dealStatus) {
        return alarmEvents(limit, dealStatus, null);
    }

    private List<Map<String, Object>> alarmEvents(int limit, Integer dealStatus, Long rootOrgId) {
        List<Object> args = new ArrayList<>();
        String where = "WHERE 1 = 1 ";
        if (dealStatus != null) {
            where += "AND e.deal_status = ? ";
            args.add(dealStatus);
        }
        where += scopeSql("e.org_id", args, rootOrgId);
        args.add(limit);
        return jdbcTemplate.queryForList("""
                SELECT e.*, d.device_name, o.org_name, r.rule_name
                FROM log_alarm e
                LEFT JOIN dev_device d ON d.id = e.device_id
                LEFT JOIN dev_org o ON o.id = e.org_id
                LEFT JOIN alarm_rule r ON r.id = e.rule_id
                """ + where + "ORDER BY e.alarm_time DESC LIMIT ?", args.toArray());
    }

    private List<Map<String, Object>> pointsForDevice(Long deviceId) {
        return jdbcTemplate.queryForList("""
                SELECT p.*
                FROM dev_point_definition p
                JOIN dev_device d ON d.device_type_id = p.device_type_id
                WHERE d.id = ?
                ORDER BY p.sort, p.id
                """, deviceId);
    }

    private Map<String, Object> safeRemoteData(String uri) {
        try {
            return remoteServiceClient.getData(uri);
        } catch (Exception ex) {
            return Map.of("code", 503, "message", "data 服务暂不可用: " + ex.getMessage());
        }
    }

    private void putSafely(Map<String, Object> data, String key, Supplier<Object> supplier, Object fallback) {
        try {
            data.put(key, supplier.get());
        } catch (Exception ex) {
            data.put(key, fallback);
        }
    }

    private String historyQuery(Long deviceId, String pointCode) {
        StringJoiner joiner = new StringJoiner("&", "?", "");
        joiner.add("deviceId=" + deviceId);
        joiner.add("pointCode=" + encode(StringUtils.hasText(pointCode) ? pointCode : defaultPointCode(deviceId)));
        joiner.add("startTime=" + encode(LocalDate.now().minusDays(1) + "T00:00:00"));
        joiner.add("endTime=" + encode(LocalDate.now().plusDays(1) + "T00:00:00"));
        return joiner.toString();
    }

    private String defaultPointCode(Long deviceId) {
        List<Map<String, Object>> points = pointsForDevice(deviceId);
        return points.isEmpty() ? "total_active_energy" : Objects.toString(points.get(0).get("point_code"));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private Object readJsonPath(JsonNode root, String path) {
        if (!StringUtils.hasText(path) || !path.startsWith("$.")) {
            return null;
        }
        JsonNode node = root;
        for (String segment : path.substring(2).split("\\.")) {
            node = node == null ? null : node.get(segment);
        }
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText();
    }

    private Map<String, Object> single(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        if (rows.isEmpty()) {
            throw new BusinessException(404, "数据不存在");
        }
        return rows.get(0);
    }

    private void assertDeviceAccess(Long deviceId) {
        Map<String, Object> device = single("SELECT id, org_id FROM dev_device WHERE id = ?", deviceId);
        Long orgId = longOrNull(device.get("org_id"));
        if (orgId != null && !dataScopeService.hasOrgAccess(StpUtil.getLoginIdAsLong(), orgId)) {
            throw new BusinessException(403, "没有该设备的数据访问权限");
        }
    }

    private Long firstDeviceId() {
        List<Object> args = new ArrayList<>();
        List<Long> rows = jdbcTemplate.queryForList("SELECT id FROM dev_device WHERE 1 = 1" + scopeSql("org_id", args) + " ORDER BY id LIMIT 1", Long.class, args.toArray());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
    }

    private long countScoped(String table, String orgColumn) {
        return countScoped(table, orgColumn, null);
    }

    private long countScoped(String table, String orgColumn, Long rootOrgId) {
        List<Object> args = new ArrayList<>();
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE 1 = 1" + scopeSql(orgColumn, args, rootOrgId), Long.class, args.toArray());
        return value == null ? 0 : value;
    }

    private long countWhere(String table, String where) {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Long.class);
        return value == null ? 0 : value;
    }

    private long countWhereScoped(String table, String orgColumn, String where) {
        return countWhereScoped(table, orgColumn, where, null);
    }

    private long countWhereScoped(String table, String orgColumn, String where, Long rootOrgId) {
        List<Object> args = new ArrayList<>();
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where + scopeSql(orgColumn, args, rootOrgId), Long.class, args.toArray());
        return value == null ? 0 : value;
    }

    private BigDecimal sum(String table, String column, String where) {
        BigDecimal value = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(" + column + "), 0) FROM " + table + " WHERE " + where, BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal sumScoped(String table, String column, String orgColumn, String where) {
        return sumScoped(table, column, orgColumn, where, null);
    }

    private BigDecimal sumScoped(String table, String column, String orgColumn, String where, Long rootOrgId) {
        List<Object> args = new ArrayList<>();
        BigDecimal value = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(" + column + "), 0) FROM " + table + " WHERE " + where + scopeSql(orgColumn, args, rootOrgId), BigDecimal.class, args.toArray());
        return value == null ? BigDecimal.ZERO : value;
    }

    private long billingBillCount(String where) {
        return billingBillCount(where, null);
    }

    private long billingBillCount(String where, Long rootOrgId) {
        List<Object> args = new ArrayList<>();
        Long value = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM billing_bill b
                JOIN billing_account a ON a.id = b.account_id
                WHERE """ + where + scopeSql("a.org_id", args, rootOrgId), Long.class, args.toArray());
        return value == null ? 0 : value;
    }

    private BigDecimal billingBillSum(String where) {
        return billingBillSum(where, null);
    }

    private BigDecimal billingBillSum(String where, Long rootOrgId) {
        List<Object> args = new ArrayList<>();
        BigDecimal value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(b.total_amount), 0)
                FROM billing_bill b
                JOIN billing_account a ON a.id = b.account_id
                WHERE """ + where + scopeSql("a.org_id", args, rootOrgId), BigDecimal.class, args.toArray());
        return value == null ? BigDecimal.ZERO : value;
    }

    private String scopeSql(String orgColumn, List<Object> args) {
        return scopeSql(orgColumn, args, null);
    }

    private String scopeSql(String orgColumn, List<Object> args, Long rootOrgId) {
        if (!StpUtil.isLogin()) {
            return "";
        }
        long userId = StpUtil.getLoginIdAsLong();
        Set<Long> visibleOrgIds;
        if (rootOrgId != null) {
            if (!dataScopeService.hasOrgAccess(userId, rootOrgId)) {
                throw new BusinessException(403, "没有该组织的数据访问权限");
            }
            visibleOrgIds = new java.util.LinkedHashSet<>(dataScopeService.orgSubtreeIds(rootOrgId));
        } else {
            visibleOrgIds = dataScopeService.visibleOrgIds(userId);
        }
        return dataScopeService.inClause(orgColumn, visibleOrgIds, args);
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private long requiredLong(Object value, String field) {
        Long number = longOrNull(value);
        if (number == null) {
            throw new BusinessException(field + " 不能为空");
        }
        return number;
    }

    private Long longOrNull(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return new BigDecimal(value.toString()).longValue();
    }

    private Number numberOrZero(Object value) {
        return value instanceof Number number ? number : value == null ? 0 : new BigDecimal(value.toString());
    }

    private BigDecimal decimal(Object value) {
        if (value == null || value.toString().isBlank()) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(value.toString());
    }

    private String currentUsername() {
        Object username = StpUtil.getSession().get("username");
        return username == null ? String.valueOf(StpUtil.getLoginId()) : username.toString();
    }
}
