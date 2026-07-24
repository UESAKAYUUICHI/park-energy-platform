package com.parkenergyplatform.service;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

import cn.dev33.satoken.stp.StpUtil;
import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.common.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformBusinessQueryService {
    private final JdbcTemplate jdbcTemplate;
    private final BusinessDataAccessService accessService;
    private final DataScopeService dataScopeService;
    private final RemoteServiceClient remoteServiceClient;

    public PlatformBusinessQueryService(JdbcTemplate jdbcTemplate, BusinessDataAccessService accessService,
                                        DataScopeService dataScopeService, RemoteServiceClient remoteServiceClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessService = accessService;
        this.dataScopeService = dataScopeService;
        this.remoteServiceClient = remoteServiceClient;
    }

    public List<Map<String, Object>> orgTree() {
        List<Object> args = new ArrayList<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT *
                FROM dev_org
                WHERE 1 = 1
                """ + scopeSql("id", args) + """
                ORDER BY parent_id, sort, id
                """, args.toArray());
        return tree(rows, "id", "parent_id", "children");
    }

    public List<Map<String, Object>> rootOrgs() {
        List<Object> args = new ArrayList<>();
        return jdbcTemplate.queryForList("""
                SELECT id, parent_id, org_name, org_type, sort
                FROM dev_org
                WHERE parent_id = 0
                """ + scopeSql("id", args) + """
                ORDER BY sort, id
                """, args.toArray());
    }

    public List<Map<String, Object>> deviceTree(Map<String, String> params) {
        Long rootOrgId = longOrNull(params.get("rootOrgId"));
        String keyword = Objects.toString(params.get("keyword"), "").trim();
        List<Object> orgArgs = new ArrayList<>();
        List<Map<String, Object>> orgs = jdbcTemplate.queryForList("""
                SELECT id, parent_id, org_name, org_type, sort
                FROM dev_org
                WHERE 1 = 1
                """ + scopeSql("id", orgArgs) + """
                ORDER BY parent_id, sort, id
                """, orgArgs.toArray());
        Map<Long, Map<String, Object>> orgNodes = new LinkedHashMap<>();
        for (Map<String, Object> org : orgs) {
            Map<String, Object> node = new LinkedHashMap<>(org);
            node.put("nodeType", "ORG");
            node.put("children", new ArrayList<Map<String, Object>>());
            orgNodes.put(longValue(org.get("id")), node);
        }
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> node : orgNodes.values()) {
            Long parentId = longOrNull(node.get("parent_id"));
            Map<String, Object> parent = parentId == null ? null : orgNodes.get(parentId);
            if (parent == null) {
                roots.add(node);
            } else {
                children(parent).add(node);
            }
        }
        if (rootOrgId != null && rootOrgId > 0 && orgNodes.containsKey(rootOrgId)) {
            roots = List.of(orgNodes.get(rootOrgId));
        }

        List<Object> gatewayArgs = new ArrayList<>();
        List<Map<String, Object>> gateways = jdbcTemplate.queryForList("""
                SELECT id, gateway_sn, gateway_name, org_id, online_status, status
                FROM dev_gateway
                WHERE 1 = 1
                """ + scopeSql("org_id", gatewayArgs) + """
                ORDER BY id
                """, gatewayArgs.toArray());
        Map<Long, Map<String, Object>> gatewayNodes = new LinkedHashMap<>();
        for (Map<String, Object> gateway : gateways) {
            Map<String, Object> node = new LinkedHashMap<>(gateway);
            node.put("nodeType", "GATEWAY");
            node.put("children", new ArrayList<Map<String, Object>>());
            gatewayNodes.put(longValue(gateway.get("id")), node);
            Map<String, Object> org = orgNodes.get(longValue(gateway.get("org_id")));
            if (org != null) {
                children(org).add(node);
            }
        }

        List<Object> deviceArgs = new ArrayList<>();
        List<Map<String, Object>> devices = jdbcTemplate.queryForList("""
                SELECT d.id, d.device_sn, d.device_name, d.gateway_id, d.org_id, d.device_type_id, d.status,
                       t.type_code, t.type_name
                FROM dev_device d
                LEFT JOIN dev_device_type t ON t.id = d.device_type_id
                WHERE 1 = 1
                """ + scopeSql("d.org_id", deviceArgs) + """
                ORDER BY d.id
                """, deviceArgs.toArray());
        for (Map<String, Object> device : devices) {
            Map<String, Object> node = new LinkedHashMap<>(device);
            node.put("nodeType", "DEVICE");
            Long gatewayId = longOrNull(device.get("gateway_id"));
            Map<String, Object> gateway = gatewayId == null ? null : gatewayNodes.get(gatewayId);
            if (gateway != null) {
                children(gateway).add(node);
            } else {
                Map<String, Object> org = orgNodes.get(longValue(device.get("org_id")));
                if (org != null) {
                    children(org).add(node);
                }
            }
        }
        return pruneTree(roots, keyword);
    }

    public Map<String, Object> deviceProfile(long deviceId) {
        accessService.assertDeviceAccess(deviceId);
        Map<String, Object> profile = new LinkedHashMap<>();
        Map<String, Object> device = single("""
                SELECT d.*, o.org_name, g.gateway_sn, g.gateway_name, g.online_status, t.type_code, t.type_name, t.protocol_type
                FROM dev_device d
                LEFT JOIN dev_org o ON o.id = d.org_id
                LEFT JOIN dev_gateway g ON g.id = d.gateway_id
                LEFT JOIN dev_device_type t ON t.id = d.device_type_id
                WHERE d.id = ?
                """, deviceId);
        profile.put("device", device);
        Long deviceTypeId = longOrNull(device.get("device_type_id"));
        profile.put("points", deviceTypePoints(deviceTypeId == null ? 0L : deviceTypeId));
        profile.put("realtime", remoteServiceClient.getData("/api/data/realtime/devices/" + deviceId));
        profile.put("latestStats", jdbcTemplate.queryForList("""
                SELECT *
                FROM stats_daily_point
                WHERE device_id = ?
                ORDER BY stat_date DESC, point_code
                LIMIT 50
                """, deviceId));
        return profile;
    }

    public Map<String, Object> deviceArchiveProfile(long deviceId) {
        Map<String, Object> profile = deviceProfile(deviceId);
        Map<String, Object> device = castMap(profile.get("device"));
        Long orgId = longOrNull(device.get("org_id"));
        profile.put("recentHistory", jdbcTemplate.queryForList("""
                SELECT id, device_id, device_type_id, org_id, point_code, stat_date,
                       start_value, end_value, usage_value, max_value, min_value, avg_value, data_complete_rate
                FROM stats_daily_point
                WHERE device_id = ?
                ORDER BY stat_date DESC, point_code
                LIMIT 12
                """, deviceId));
        profile.put("recentAlarms", jdbcTemplate.queryForList("""
                SELECT id, rule_id, device_id, org_id, alarm_type, alarm_level, point_code,
                       alarm_value, threshold_value, alarm_time, deal_status, deal_time, deal_user, deal_remark
                FROM log_alarm
                WHERE device_id = ?
                ORDER BY alarm_time DESC
                LIMIT 8
                """, deviceId));
        profile.put("inspectionRecords", jdbcTemplate.queryForList("""
                SELECT id, command_id, gateway_id, target_type, target_id, target_sn, command_type,
                       status, request_time, send_time, response_time, fail_reason, create_time
                FROM command_record
                WHERE target_type = 'DEVICE' AND target_id = ?
                ORDER BY request_time DESC
                LIMIT 8
                """, deviceId));
        Map<String, String> trendParams = new LinkedHashMap<>();
        trendParams.put("deviceId", String.valueOf(deviceId));
        if (orgId != null) {
            trendParams.put("orgId", String.valueOf(orgId));
        }
        profile.put("energyTrend", energyTrend(trendParams));
        return profile;
    }

    public Map<String, Object> orgArchiveProfile(long orgId) {
        if (!accessService.hasOrgAccess(orgId)) {
            throw new BusinessException(403, "没有该组织的数据访问权限");
        }
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("org", single("SELECT * FROM dev_org WHERE id = ?", orgId));
        profile.put("deviceCount", countDevicesByOrg(orgId));
        profile.put("gatewayCount", countGatewaysByOrg(orgId));
        List<Object> onlineGatewayArgs = new ArrayList<>();
        String onlineGatewayScope = accessService.orgFilterSql("org_id", orgId, true, onlineGatewayArgs);
        profile.put("onlineGatewayCount", queryLong("""
                SELECT COUNT(*)
                FROM dev_gateway
                WHERE online_status = 1
                """ + onlineGatewayScope, onlineGatewayArgs));
        profile.put("recentAlarms", orgAlarms(orgId));
        profile.put("alarmTrend", orgAlarmTrend(orgId));
        profile.put("energyTrend", energyTrend(Map.of("orgId", String.valueOf(orgId))));
        profile.put("realtimeSnapshots", realtimeSnapshots(Map.of("orgId", String.valueOf(orgId))));
        return profile;
    }

    public Map<String, Object> gatewayArchiveProfile(long gatewayId) {
        accessService.assertGatewayAccess(gatewayId);
        Map<String, Object> gateway = single("""
                SELECT g.*, o.org_name
                FROM dev_gateway g
                LEFT JOIN dev_org o ON o.id = g.org_id
                WHERE g.id = ?
                """, gatewayId);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("gateway", gateway);
        profile.put("deviceCount", queryLong("SELECT COUNT(*) FROM dev_device WHERE gateway_id = ?", List.of(gatewayId)));
        profile.put("onlineDeviceCount", queryLong("SELECT COUNT(*) FROM dev_device WHERE gateway_id = ? AND status = 1", List.of(gatewayId)));
        profile.put("recentAlarms", gatewayAlarms(gatewayId));
        profile.put("alarmTrend", gatewayAlarmTrend(gatewayId));
        profile.put("energyTrend", gatewayEnergyTrend(gatewayId));
        profile.put("realtimeSnapshots", realtimeSnapshots(Map.of("gatewayId", String.valueOf(gatewayId))));
        return profile;
    }

    public Map<String, Object> deviceTypePoints(long typeId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deviceType", singleOrNull("SELECT * FROM dev_device_type WHERE id = ?", typeId));
        data.put("definitions", jdbcTemplate.queryForList("""
                SELECT *
                FROM dev_point_definition
                WHERE device_type_id = ?
                ORDER BY sort, id
                """, typeId));
        data.put("mappings", jdbcTemplate.queryForList("""
                SELECT *
                FROM dev_point_mapping
                WHERE device_type_id = ?
                ORDER BY id
                """, typeId));
        return data;
    }

    private Long countDevicesByOrg(long orgId) {
        List<Object> args = new ArrayList<>();
        String scope = accessService.orgFilterSql("org_id", orgId, true, args);
        return queryLong("SELECT COUNT(*) FROM dev_device WHERE 1 = 1" + scope, args);
    }

    private Long countGatewaysByOrg(long orgId) {
        List<Object> args = new ArrayList<>();
        String scope = accessService.orgFilterSql("org_id", orgId, true, args);
        return queryLong("SELECT COUNT(*) FROM dev_gateway WHERE 1 = 1" + scope, args);
    }

    private List<Map<String, Object>> orgAlarms(long orgId) {
        List<Object> args = new ArrayList<>();
        String scope = accessService.orgFilterSql("e.org_id", orgId, true, args);
        return jdbcTemplate.queryForList("""
                SELECT e.*, d.device_sn, d.device_name
                FROM log_alarm e
                LEFT JOIN dev_device d ON d.id = e.device_id
                WHERE 1 = 1
                """ + scope + """
                ORDER BY e.alarm_time DESC
                LIMIT 10
                """, args.toArray());
    }

    private List<Map<String, Object>> orgAlarmTrend(long orgId) {
        List<Object> args = new ArrayList<>();
        String scope = accessService.orgFilterSql("org_id", orgId, true, args);
        return jdbcTemplate.queryForList("""
                SELECT DATE(alarm_time) AS alarm_date, COUNT(*) AS alarm_count
                FROM log_alarm
                WHERE 1 = 1
                """ + scope + """
                GROUP BY DATE(alarm_time)
                ORDER BY alarm_date DESC
                LIMIT 14
                """, args.toArray());
    }

    private List<Map<String, Object>> gatewayAlarms(long gatewayId) {
        return jdbcTemplate.queryForList("""
                SELECT e.*, d.device_sn, d.device_name
                FROM log_alarm e
                JOIN dev_device d ON d.id = e.device_id
                WHERE d.gateway_id = ?
                ORDER BY e.alarm_time DESC
                LIMIT 10
                """, gatewayId);
    }

    private List<Map<String, Object>> gatewayAlarmTrend(long gatewayId) {
        return jdbcTemplate.queryForList("""
                SELECT DATE(e.alarm_time) AS alarm_date, COUNT(*) AS alarm_count
                FROM log_alarm e
                JOIN dev_device d ON d.id = e.device_id
                WHERE d.gateway_id = ?
                GROUP BY DATE(e.alarm_time)
                ORDER BY alarm_date DESC
                LIMIT 14
                """, gatewayId);
    }

    private List<Map<String, Object>> gatewayEnergyTrend(long gatewayId) {
        return jdbcTemplate.queryForList("""
                SELECT s.stat_date AS stat_period, ROUND(SUM(COALESCE(s.usage_value, 0)), 4) AS usage_value
                FROM stats_daily_point s
                JOIN dev_device d ON d.id = s.device_id
                WHERE d.gateway_id = ?
                GROUP BY s.stat_date
                ORDER BY s.stat_date
                LIMIT 30
                """, gatewayId);
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> saveDeviceTypePoints(long typeId, Map<String, Object> request) {
        if (singleOrNull("SELECT * FROM dev_device_type WHERE id = ?", typeId) == null) {
            throw new BusinessException(404, "设备类型不存在: " + typeId);
        }
        List<Map<String, Object>> definitions = request.get("definitions") instanceof List<?> list
                ? list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList()
                : List.of();
        List<Map<String, Object>> mappings = request.get("mappings") instanceof List<?> list
                ? list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList()
                : List.of();
        jdbcTemplate.update("DELETE FROM dev_point_mapping WHERE device_type_id = ?", typeId);
        jdbcTemplate.update("DELETE FROM dev_point_definition WHERE device_type_id = ?", typeId);
        for (Map<String, Object> definition : definitions) {
            jdbcTemplate.update("""
                    INSERT INTO dev_point_definition
                      (device_type_id, point_code, point_name, data_type, unit, precision_scale,
                       business_role, billable, stat_enabled, sort, enabled)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, typeId,
                    text(definition, "pointCode", "point_code"),
                    text(definition, "pointName", "point_name"),
                    textOrDefault(definition, "dataType", "data_type", "DOUBLE"),
                    text(definition, "unit"),
                    integerOrDefault(definition, 2, "precisionScale", "precision_scale"),
                    textOrDefault(definition, "businessRole", "business_role", "INSTANT_VALUE"),
                    integerOrDefault(definition, 0, "billable"),
                    integerOrDefault(definition, 1, "statEnabled", "stat_enabled"),
                    integerOrDefault(definition, 0, "sort"),
                    integerOrDefault(definition, 1, "enabled"));
        }
        for (Map<String, Object> mapping : mappings) {
            jdbcTemplate.update("""
                    INSERT INTO dev_point_mapping
                      (device_type_id, point_code, protocol_type, source_path, function_code, register_address,
                       register_length, value_type, byte_order, scale_factor, offset_value, expression, required)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, typeId,
                    text(mapping, "pointCode", "point_code"),
                    textOrDefault(mapping, "protocolType", "protocol_type", "JSON"),
                    text(mapping, "sourcePath", "source_path"),
                    text(mapping, "functionCode", "function_code"),
                    integerOrNull(mapping, "registerAddress", "register_address"),
                    integerOrNull(mapping, "registerLength", "register_length"),
                    textOrDefault(mapping, "valueType", "value_type", "DOUBLE"),
                    text(mapping, "byteOrder", "byte_order"),
                    decimalOrDefault(mapping, BigDecimal.ONE, "scaleFactor", "scale_factor"),
                    decimalOrDefault(mapping, BigDecimal.ZERO, "offsetValue", "offset_value"),
                    text(mapping, "expression"),
                    integerOrDefault(mapping, 0, "required"));
        }
        return deviceTypePoints(typeId);
    }

    public Map<String, Object> realtimeBatch(List<Long> deviceIds) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (Long deviceId : deviceIds) {
            accessService.assertDeviceAccess(deviceId);
            data.put(String.valueOf(deviceId), remoteServiceClient.getData("/api/data/realtime/devices/" + deviceId));
        }
        return data;
    }

    public List<Map<String, Object>> realtimeByOrg(long orgId) {
        if (!accessService.hasOrgAccess(orgId)) {
            throw new BusinessException(403, "没有该组织的数据访问权限");
        }
        return realtimeSnapshots(Map.of("orgId", String.valueOf(orgId)));
    }

    public List<Map<String, Object>> realtimeSnapshots(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT d.id, d.device_sn, d.device_name, d.gateway_id, d.org_id, d.device_type_id, d.status,
                       o.org_name, g.gateway_sn, g.gateway_name, g.online_status, t.type_code, t.type_name
                FROM dev_device d
                LEFT JOIN dev_org o ON o.id = d.org_id
                LEFT JOIN dev_gateway g ON g.id = d.gateway_id
                LEFT JOIN dev_device_type t ON t.id = d.device_type_id
                WHERE 1 = 1
                """);
        appendOrgFilter(sql, args, "d.org_id", params);
        appendEquals(sql, args, "d.gateway_id", params.get("gatewayId"));
        appendEquals(sql, args, "d.device_type_id", params.get("deviceTypeId"));
        appendKeyword(sql, args, params.get("keyword"), "d.device_sn", "d.device_name", "g.gateway_sn", "o.org_name");
        sql.append(scopeSql("d.org_id", args));
        sql.append(" ORDER BY d.id DESC LIMIT 200");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        for (Map<String, Object> row : rows) {
            Long deviceId = longValue(row.get("id"));
            row.put("realtime", remoteServiceClient.getData("/api/data/realtime/devices/" + deviceId));
        }
        return rows;
    }

    public Map<String, Object> historySeries(long deviceId, List<String> pointCodes, String startTime, String endTime) {
        accessService.assertDeviceAccess(deviceId);
        Map<String, Object> data = new LinkedHashMap<>();
        for (String pointCode : pointCodes) {
            if (pointCode == null || pointCode.isBlank()) {
                continue;
            }
            String query = "?deviceId=" + deviceId + "&pointCode=" + encode(pointCode)
                    + optionalQuery("startTime", startTime) + optionalQuery("endTime", endTime);
            data.put(pointCode, remoteServiceClient.getData("/api/data/history" + query));
        }
        return data;
    }

    public List<Map<String, Object>> energyRanking(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT d.id AS device_id, d.device_sn, d.device_name, o.org_name,
                       ROUND(SUM(COALESCE(s.usage_value, 0)), 4) AS usage_value
                FROM stats_daily_point s
                JOIN dev_device d ON d.id = s.device_id
                LEFT JOIN dev_org o ON o.id = s.org_id
                WHERE 1 = 1
                """);
        appendOrgFilter(sql, args, "s.org_id", params);
        appendEquals(sql, args, "s.point_code", params.get("pointCode"));
        appendDateRange(sql, args, "s.stat_date", params.get("startDate"), params.get("endDate"));
        sql.append(scopeSql("s.org_id", args));
        sql.append(" GROUP BY d.id, d.device_sn, d.device_name, o.org_name ORDER BY usage_value DESC LIMIT ?");
        args.add(parsePositive(params.get("limit"), 20));
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> energyTrend(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        String group = "month".equalsIgnoreCase(params.get("groupBy")) ? "DATE_FORMAT(stat_date, '%Y-%m')" : "stat_date";
        StringBuilder sql = new StringBuilder("SELECT " + group + " AS stat_period, ROUND(SUM(COALESCE(usage_value, 0)), 4) AS usage_value FROM stats_daily_point WHERE 1 = 1");
        appendOrgFilter(sql, args, "org_id", params);
        appendEquals(sql, args, "device_id", params.get("deviceId"));
        appendEquals(sql, args, "point_code", params.get("pointCode"));
        appendDateRange(sql, args, "stat_date", params.get("startDate"), params.get("endDate"));
        sql.append(scopeSql("org_id", args));
        sql.append(" GROUP BY stat_period ORDER BY stat_period");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> dailySummary(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT s.stat_date, s.org_id, o.org_name, s.point_code,
                       ROUND(SUM(COALESCE(s.usage_value, 0)), 4) AS usage_value,
                       ROUND(MAX(COALESCE(s.max_value, 0)), 4) AS max_value,
                       ROUND(MIN(COALESCE(s.min_value, 0)), 4) AS min_value,
                       ROUND(AVG(COALESCE(s.avg_value, 0)), 4) AS avg_value
                FROM stats_daily_point s
                LEFT JOIN dev_org o ON o.id = s.org_id
                WHERE 1 = 1
                """);
        appendOrgFilter(sql, args, "s.org_id", params);
        appendEquals(sql, args, "s.point_code", params.get("pointCode"));
        appendDateRange(sql, args, "s.stat_date", params.get("startDate"), params.get("endDate"));
        sql.append(scopeSql("s.org_id", args));
        sql.append(" GROUP BY s.stat_date, s.org_id, o.org_name, s.point_code ORDER BY s.stat_date DESC, s.org_id LIMIT 500");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> monthlyStats(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT DATE_FORMAT(s.stat_date, '%Y-%m') AS stat_month, s.org_id, o.org_name, s.point_code,
                       ROUND(SUM(COALESCE(s.usage_value, 0)), 4) AS usage_value,
                       ROUND(MAX(COALESCE(s.max_value, 0)), 4) AS max_value,
                       ROUND(MIN(COALESCE(s.min_value, 0)), 4) AS min_value,
                       ROUND(AVG(COALESCE(s.avg_value, 0)), 4) AS avg_value
                FROM stats_daily_point s
                LEFT JOIN dev_org o ON o.id = s.org_id
                WHERE 1 = 1
                """);
        appendOrgFilter(sql, args, "s.org_id", params);
        appendEquals(sql, args, "s.point_code", params.get("pointCode"));
        appendDateRange(sql, args, "s.stat_date", params.get("startDate"), params.get("endDate"));
        sql.append(scopeSql("s.org_id", args));
        sql.append(" GROUP BY stat_month, s.org_id, o.org_name, s.point_code ORDER BY stat_month DESC, s.org_id LIMIT 500");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> qualityStats(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT s.device_id, d.device_sn, d.device_name, s.org_id, o.org_name, s.point_code,
                       ROUND(AVG(COALESCE(s.data_complete_rate, 0)), 2) AS avg_complete_rate,
                       MIN(s.stat_date) AS start_date, MAX(s.stat_date) AS end_date
                FROM stats_daily_point s
                LEFT JOIN dev_device d ON d.id = s.device_id
                LEFT JOIN dev_org o ON o.id = s.org_id
                WHERE 1 = 1
                """);
        appendOrgFilter(sql, args, "s.org_id", params);
        appendEquals(sql, args, "s.device_id", params.get("deviceId"));
        appendEquals(sql, args, "s.point_code", params.get("pointCode"));
        appendDateRange(sql, args, "s.stat_date", params.get("startDate"), params.get("endDate"));
        sql.append(scopeSql("s.org_id", args));
        sql.append(" GROUP BY s.device_id, d.device_sn, d.device_name, s.org_id, o.org_name, s.point_code ORDER BY avg_complete_rate ASC LIMIT 500");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> rebuildDailyStats(Map<String, String> params) {
        String statDate = Objects.toString(params.get("statDate"), "").trim();
        if (statDate.isBlank()) {
            throw new BusinessException("statDate 不能为空");
        }
        LocalDate.parse(statDate);
        Long deviceId = longOrNull(params.get("deviceId"));
        List<Long> deviceIds = deviceId == null ? rebuildDeviceIds(params) : List.of(deviceId);
        if (deviceId != null) {
            accessService.assertDeviceAccess(deviceId);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Long id : deviceIds) {
            results.add(remoteServiceClient.postData("/api/data/statistics/daily/rebuild?statDate="
                    + encode(statDate) + "&deviceId=" + id, Map.of()));
        }
        return Map.of("statDate", statDate, "deviceCount", deviceIds.size(), "results", results);
    }

    private List<Long> rebuildDeviceIds(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT id FROM dev_device WHERE status = 1");
        appendOrgFilter(sql, args, "org_id", params);
        sql.append(scopeSql("org_id", args));
        sql.append(" ORDER BY id");
        return jdbcTemplate.queryForList(sql.toString(), Long.class, args.toArray());
    }

    public PageResult<Map<String, Object>> alarmEvents(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        String from = """
                FROM log_alarm e
                LEFT JOIN dev_device d ON d.id = e.device_id
                LEFT JOIN dev_org o ON o.id = e.org_id
                LEFT JOIN alarm_rule r ON r.id = e.rule_id
                WHERE 1 = 1
                """;
        StringBuilder where = new StringBuilder();
        appendOrgFilter(where, args, "e.org_id", params);
        appendEquals(where, args, "e.device_id", params.get("deviceId"));
        appendEquals(where, args, "e.deal_status", params.get("dealStatus"));
        appendDateRange(where, args, "e.alarm_time", params.get("startTime"), params.get("endTime"));
        where.append(scopeSql("e.org_id", args));
        int pageNum = parsePositive(params.get("pageNum"), 1);
        int pageSize = Math.min(parsePositive(params.get("pageSize"), 20), 200);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) " + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((pageNum - 1) * pageSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT e.*, d.device_sn, d.device_name, o.org_name, r.rule_name
                """ + from + where + " ORDER BY e.alarm_time DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        return PageResult.of(rows, total == null ? 0 : total, pageNum, pageSize);
    }

    private List<Map<String, Object>> pruneTree(List<Map<String, Object>> nodes, String keyword) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            Map<String, Object> filtered = pruneNode(node, keyword);
            if (filtered != null) {
                result.add(filtered);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pruneNode(Map<String, Object> node, String keyword) {
        List<Map<String, Object>> children = new ArrayList<>();
        for (Map<String, Object> child : (List<Map<String, Object>>) node.getOrDefault("children", List.of())) {
            Map<String, Object> filtered = pruneNode(child, keyword);
            if (filtered != null) {
                children.add(filtered);
            }
        }
        boolean match = keyword == null || keyword.isBlank() || matches(node, keyword);
        if (!match && children.isEmpty()) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>(node);
        copy.put("children", children);
        return copy;
    }

    private boolean matches(Map<String, Object> node, String keyword) {
        String text = switch (Objects.toString(node.get("nodeType"), "")) {
            case "GATEWAY" -> String.join(" ",
                    Objects.toString(node.get("gateway_name"), ""),
                    Objects.toString(node.get("gateway_sn"), ""));
            case "DEVICE" -> String.join(" ",
                    Objects.toString(node.get("device_name"), ""),
                    Objects.toString(node.get("device_sn"), ""),
                    Objects.toString(node.get("type_name"), ""));
            default -> String.join(" ",
                    Objects.toString(node.get("org_name"), ""));
        };
        return text.toLowerCase(java.util.Locale.ROOT).contains(keyword.toLowerCase(java.util.Locale.ROOT));
    }

    public Map<String, Object> alarmSummary(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        StringBuilder filter = new StringBuilder();
        appendOrgFilter(filter, args, "org_id", params);
        filter.append(scopeSql("org_id", args));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pendingCount", alarmCountByStatus(0, filter.toString(), args));
        data.put("handledCount", alarmCountByStatus(1, filter.toString(), args));
        data.put("byLevel", jdbcTemplate.queryForList("SELECT alarm_level, COUNT(*) AS count FROM log_alarm WHERE 1 = 1" + filter + " GROUP BY alarm_level ORDER BY alarm_level", args.toArray()));
        data.put("byType", jdbcTemplate.queryForList("SELECT alarm_type, COUNT(*) AS count FROM log_alarm WHERE 1 = 1" + filter + " GROUP BY alarm_type ORDER BY alarm_type", args.toArray()));
        Map<String, String> latestParams = new LinkedHashMap<>(params);
        latestParams.put("pageSize", "10");
        data.put("latest", alarmEvents(latestParams).records());
        return data;
    }

    private Long alarmCountByStatus(int status, String filter, List<Object> filterArgs) {
        List<Object> args = new ArrayList<>();
        args.add(status);
        args.addAll(filterArgs);
        return queryLong("SELECT COUNT(*) FROM log_alarm WHERE deal_status = ?" + filter, args);
    }

    public Map<String, Object> alarmRuleProfile(long ruleId) {
        Map<String, Object> rule = single("SELECT * FROM alarm_rule WHERE id = ?", ruleId);
        Long orgId = longOrNull(rule.get("org_id"));
        Long deviceId = longOrNull(rule.get("device_id"));
        if (deviceId != null) {
            accessService.assertDeviceAccess(deviceId);
        } else if (orgId != null && !accessService.hasOrgAccess(orgId)) {
            throw new BusinessException(403, "没有该告警规则的数据访问权限");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rule", rule);
        data.put("org", orgId == null ? null : singleOrNull("SELECT * FROM dev_org WHERE id = ?", orgId));
        data.put("device", deviceId == null ? null : singleOrNull("SELECT * FROM dev_device WHERE id = ?", deviceId));
        data.put("point", jdbcTemplate.queryForList("SELECT * FROM dev_point_definition WHERE point_code = ? ORDER BY device_type_id", rule.get("point_code")));
        return data;
    }

    public PageResult<Map<String, Object>> commandPage(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        String from = """
                FROM command_record c
                LEFT JOIN dev_gateway g ON g.id = c.gateway_id
                LEFT JOIN dev_device d ON d.id = c.target_id
                LEFT JOIN dev_org o ON o.id = COALESCE(d.org_id, g.org_id)
                WHERE 1 = 1
                """;
        StringBuilder where = new StringBuilder();
        appendOrgFilter(where, args, "COALESCE(d.org_id, g.org_id)", params);
        appendEquals(where, args, "c.gateway_id", params.get("gatewayId"));
        appendEquals(where, args, "c.target_id", params.get("targetId"));
        appendEquals(where, args, "c.status", params.get("status"));
        appendKeyword(where, args, params.get("keyword"), "c.command_id", "c.target_sn", "c.command_type");
        where.append(scopeSql("COALESCE(d.org_id, g.org_id)", args));
        int pageNum = parsePositive(params.get("pageNum"), 1);
        int pageSize = Math.min(parsePositive(params.get("pageSize"), 20), 200);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) " + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((pageNum - 1) * pageSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT c.*, g.gateway_sn, g.gateway_name, d.device_sn, d.device_name, o.org_name
                """ + from + where + " ORDER BY c.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        return PageResult.of(rows, total == null ? 0 : total, pageNum, pageSize);
    }

    public Map<String, Object> commandTargets() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Object> gatewayArgs = new ArrayList<>();
        data.put("gateways", jdbcTemplate.queryForList("""
                SELECT id, gateway_sn, gateway_name, org_id, online_status, status
                FROM dev_gateway
                WHERE 1 = 1
                """ + scopeSql("org_id", gatewayArgs) + " ORDER BY id", gatewayArgs.toArray()));
        List<Object> deviceArgs = new ArrayList<>();
        data.put("devices", jdbcTemplate.queryForList("""
                SELECT id, device_sn, device_name, gateway_id, org_id, device_type_id, status
                FROM dev_device
                WHERE 1 = 1
                """ + scopeSql("org_id", deviceArgs) + " ORDER BY id", deviceArgs.toArray()));
        return data;
    }

    public List<Map<String, Object>> commandTypes() {
        return List.of(
                Map.of("commandType", "READ_NOW", "targetType", "DEVICE", "label", "立即读取"),
                Map.of("commandType", "SET_INTERVAL", "targetType", "GATEWAY", "label", "设置上报间隔"),
                Map.of("commandType", "REBOOT_GATEWAY", "targetType", "GATEWAY", "label", "重启网关")
        );
    }

    public PageResult<Map<String, Object>> operationLogs(Map<String, String> params, boolean onlyCurrentUser) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (onlyCurrentUser) {
            where.append(" AND user_id = ?");
            args.add(StpUtil.getLoginIdAsLong());
        }
        appendEquals(where, args, "user_id", params.get("userId"));
        appendEquals(where, args, "status", params.get("status"));
        appendEquals(where, args, "module", params.get("module"));
        appendDateRange(where, args, "create_time", params.get("startTime"), params.get("endTime"));
        appendKeyword(where, args, params.get("keyword"), "username", "operation", "request_url");
        int pageNum = parsePositive(params.get("pageNum"), 1);
        int pageSize = Math.min(parsePositive(params.get("pageSize"), 20), 200);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM log_operation" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((pageNum - 1) * pageSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM log_operation" + where + " ORDER BY id DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        return PageResult.of(rows, total == null ? 0 : total, pageNum, pageSize);
    }

    private List<Map<String, Object>> tree(List<Map<String, Object>> rows, String idColumn, String parentColumn, String childrenColumn) {
        Map<Long, Map<String, Object>> nodes = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> node = new LinkedHashMap<>(row);
            node.put(childrenColumn, new ArrayList<Map<String, Object>>());
            nodes.put(longValue(row.get(idColumn)), node);
        }
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> node : nodes.values()) {
            Long parentId = longOrNull(node.get(parentColumn));
            Map<String, Object> parent = parentId == null ? null : nodes.get(parentId);
            if (parent == null) {
                roots.add(node);
            } else {
                children(parent).add(node);
            }
        }
        return roots;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> children(Map<String, Object> node) {
        return (List<Map<String, Object>>) node.get("children");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String scopeSql(String column, List<Object> args) {
        return dataScopeService.inClause(column, dataScopeService.visibleOrgIds(StpUtil.getLoginIdAsLong()), args);
    }

    private void appendEquals(StringBuilder sql, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(" AND ").append(column).append(" = ?");
            args.add(value.trim());
        }
    }

    private void appendOrgFilter(StringBuilder sql, List<Object> args, String column, Map<String, String> params) {
        Long orgId = longOrNull(params.get("orgId"));
        boolean includeChildren = Boolean.parseBoolean(Objects.toString(params.getOrDefault("includeChildren", "false")));
        sql.append(accessService.orgFilterSql(column, orgId, includeChildren, args));
    }

    private void appendKeyword(StringBuilder sql, List<Object> args, String keyword, String... columns) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        StringJoiner joiner = new StringJoiner(" OR ", " AND (", ")");
        for (String column : columns) {
            joiner.add(column + " LIKE ?");
            args.add("%" + keyword.trim() + "%");
        }
        sql.append(joiner);
    }

    private void appendDateRange(StringBuilder sql, List<Object> args, String column, String start, String end) {
        if (start != null && !start.isBlank()) {
            sql.append(" AND ").append(column).append(" >= ?");
            args.add(start.trim());
        }
        if (end != null && !end.isBlank()) {
            sql.append(" AND ").append(column).append(" <= ?");
            args.add(end.trim());
        }
    }

    private Map<String, Object> single(String sql, Object... args) {
        Map<String, Object> row = singleOrNull(sql, args);
        if (row == null) {
            throw new BusinessException(404, "数据不存在");
        }
        return row;
    }

    private Map<String, Object> singleOrNull(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Long queryLong(String sql, List<Object> args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
        return value == null ? 0L : value;
    }

    private Long longValue(Object value) {
        Long result = longOrNull(value);
        if (result == null) {
            throw new BusinessException("ID 不能为空");
        }
        return result;
    }

    private Long longOrNull(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return Long.valueOf(value.toString());
    }

    private int parsePositive(String input, int defaultValue) {
        try {
            int value = Integer.parseInt(input);
            return value > 0 ? value : defaultValue;
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private String text(Map<String, Object> row, String... keys) {
        Object value = value(row, keys);
        return value == null ? null : value.toString();
    }

    private String textOrDefault(Map<String, Object> row, String key1, String key2, String defaultValue) {
        String value = text(row, key1, key2);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private Integer integerOrNull(Map<String, Object> row, String... keys) {
        Object value = value(row, keys);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return new BigDecimal(value.toString()).intValue();
    }

    private Integer integerOrDefault(Map<String, Object> row, int defaultValue, String... keys) {
        Integer value = integerOrNull(row, keys);
        return value == null ? defaultValue : value;
    }

    private BigDecimal decimalOrDefault(Map<String, Object> row, BigDecimal defaultValue, String... keys) {
        Object value = value(row, keys);
        if (value == null || value.toString().isBlank()) {
            return defaultValue;
        }
        return new BigDecimal(value.toString());
    }

    private Object value(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            if (row.containsKey(key)) {
                return row.get(key);
            }
        }
        return null;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String optionalQuery(String name, String value) {
        return value == null || value.isBlank() ? "" : "&" + encode(name) + "=" + encode(value);
    }
}
