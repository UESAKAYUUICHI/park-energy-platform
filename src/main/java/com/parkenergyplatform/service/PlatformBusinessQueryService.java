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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final RealtimeSnapshotQueryService realtimeSnapshotQueryService;
    private final DeviceCatalogService deviceCatalogService;
    private final ObjectMapper objectMapper;

    public PlatformBusinessQueryService(JdbcTemplate jdbcTemplate, BusinessDataAccessService accessService,
                                        DataScopeService dataScopeService, RemoteServiceClient remoteServiceClient,
                                        RealtimeSnapshotQueryService realtimeSnapshotQueryService,
                                        DeviceCatalogService deviceCatalogService, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessService = accessService;
        this.dataScopeService = dataScopeService;
        this.remoteServiceClient = remoteServiceClient;
        this.realtimeSnapshotQueryService = realtimeSnapshotQueryService;
        this.deviceCatalogService = deviceCatalogService;
        this.objectMapper = objectMapper;
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
        if (StpUtil.isLogin()) {
            Set<Long> visibleOrgIds = dataScopeService.visibleOrgIds(StpUtil.getLoginIdAsLong());
            if (visibleOrgIds != null) {
                if (visibleOrgIds.isEmpty()) {
                    return List.of();
                }
                List<Object> args = new ArrayList<>(visibleOrgIds);
                args.addAll(visibleOrgIds);
                String placeholders = String.join(",", java.util.Collections.nCopies(visibleOrgIds.size(), "?"));
                return jdbcTemplate.queryForList("""
                        SELECT id, parent_id, org_name, org_type, sort
                        FROM dev_org
                        WHERE id IN (%s)
                          AND (parent_id IS NULL OR parent_id = 0 OR parent_id NOT IN (%s))
                        ORDER BY sort, id
                        """.formatted(placeholders, placeholders), args.toArray());
            }
        }
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

        // First paint only needs the organization hierarchy; gateways and devices follow in a second request.
        if ("org".equalsIgnoreCase(params.get("depth")) && keyword.isBlank()) {
            return roots;
        }

        List<Object> gatewayArgs = new ArrayList<>();
        List<Map<String, Object>> gateways = jdbcTemplate.queryForList("""
                SELECT id, gateway_sn, gateway_name, org_id, online_status, status
                FROM dev_gateway
                WHERE 1 = 1
                """ + scopeSql("org_id", gatewayArgs) + " ORDER BY id", gatewayArgs.toArray());
        Map<Long, Map<String, Object>> gatewayNodes = new LinkedHashMap<>();
        for (Map<String, Object> gateway : gateways) {
            Map<String, Object> node = new LinkedHashMap<>(gateway);
            node.put("nodeType", "GATEWAY");
            node.put("children", new ArrayList<Map<String, Object>>());
            gatewayNodes.put(longValue(gateway.get("id")), node);
            Long gatewayOrgId = longOrNull(gateway.get("org_id"));
            Map<String, Object> org = gatewayOrgId == null ? null : orgNodes.get(gatewayOrgId);
            if (org != null) children(org).add(node);
        }

        List<Object> deviceArgs = new ArrayList<>();
        List<Map<String, Object>> devices = jdbcTemplate.queryForList("""
                SELECT d.id, d.device_sn, d.device_name, d.gateway_id, d.org_id, d.space_id,
                       d.device_type_id, d.model_version_id, d.protocol_addr, d.settlement_enabled, d.status,
                       d.online_status, d.last_online_time,
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
            Map<String, Object> parent = gatewayId == null ? null : gatewayNodes.get(gatewayId);
            if (parent == null) parent = orgNodes.get(longValue(device.get("org_id")));
            if (parent != null) children(parent).add(node);
        }
        return pruneTree(roots, keyword);
    }

    public PageResult<Map<String, Object>> deviceCards(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        where.append(scopeSql("d.org_id", args));
        appendIdListFilter(where, args, "d.org_id", params.get("orgIds"));
        appendIdListFilter(where, args, "d.gateway_id", params.get("gatewayIds"));
        appendKeyword(where, args, params.get("keyword"), "d.device_sn", "d.device_name", "o.org_name", "g.gateway_name", "m.model_name", "v.version_name");
        int pageNum = parsePositive(params.get("pageNum"), 1);
        int pageSize = Math.min(parsePositive(params.get("pageSize"), 24), 200);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dev_device d" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((pageNum - 1) * pageSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT d.id, d.device_sn, d.device_name, d.status, d.online_status, d.last_online_time,
                       d.gateway_id, d.org_id,
                       o.org_name, g.gateway_name, g.gateway_sn, t.type_name,
                       CONCAT_WS(' ', b.brand_name, m.model_name, v.version_name) AS model_display_name, m.image_object_key
                FROM dev_device d
                LEFT JOIN dev_org o ON o.id = d.org_id
                LEFT JOIN dev_gateway g ON g.id = d.gateway_id
                LEFT JOIN dev_device_type t ON t.id = d.device_type_id
                LEFT JOIN dev_device_model_version v ON v.id = d.model_version_id
                LEFT JOIN dev_device_model m ON m.id = v.model_id
                LEFT JOIN dev_product_series s ON s.id = m.series_id
                LEFT JOIN dev_brand b ON b.id = s.brand_id
                """ + where + " ORDER BY d.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        for (Map<String, Object> row : rows) {
            row.put("model_image_url", deviceCatalogService.modelImageUrl(Objects.toString(row.get("image_object_key"), "")));
        }
        return PageResult.of(rows, total == null ? 0 : total, pageNum, pageSize);
    }

    public Map<String, Object> deviceProfile(long deviceId) {
        accessService.assertDeviceAccess(deviceId);
        Map<String, Object> profile = new LinkedHashMap<>();
        Map<String, Object> device = single("""
                SELECT d.*, o.org_name, g.gateway_sn, g.gateway_name, g.online_status,sp.space_code,sp.space_name,sp.space_type,
                       t.type_code, t.type_name, t.protocol_type,
                       v.version_name AS model_version_name, v.status AS model_version_status,
                       m.id AS catalog_model_id, m.model_code AS catalog_model_code, m.model_name AS catalog_model_name,
                       m.image_object_key AS catalog_model_image_object_key,
                       s.series_name AS catalog_series_name, b.brand_name AS catalog_brand_name,
                       c.id AS catalog_category_id, c.category_name AS catalog_category_name
                FROM dev_device d
                LEFT JOIN dev_org o ON o.id = d.org_id
                LEFT JOIN dev_gateway g ON g.id = d.gateway_id
                LEFT JOIN park_space sp ON sp.id = d.space_id
                LEFT JOIN dev_device_type t ON t.id = d.device_type_id
                LEFT JOIN dev_device_model_version v ON v.id = d.model_version_id
                LEFT JOIN dev_device_model m ON m.id = v.model_id
                LEFT JOIN dev_product_series s ON s.id = m.series_id
                LEFT JOIN dev_brand b ON b.id = s.brand_id
                LEFT JOIN dev_device_category c ON c.id = s.category_id
                WHERE d.id = ?
                """, deviceId);
        String modelImageUrl = deviceCatalogService.modelImageUrl(Objects.toString(device.get("catalog_model_image_object_key"), ""));
        device.put("catalog_model_image_url", modelImageUrl);
        device.put("model_image_url", modelImageUrl);
        profile.put("device", device);
        Long modelVersionId = longOrNull(device.get("model_version_id"));
        profile.put("modelAttributes", modelVersionId == null ? List.of() : jdbcTemplate.queryForList("""
                SELECT a.id AS attribute_id,a.attribute_code,a.attribute_name,a.data_type,a.usage_type,a.unit,a.allow_override,
                       COALESCE(o.value_text,v.attribute_value,a.default_value) AS template_value,
                       COALESCE(o.value_text,v.attribute_value,a.default_value) AS attribute_value,
                       0 AS device_overridden,g.group_name
                FROM dev_attribute_definition a
                JOIN dev_attribute_group g ON g.id=a.group_id
                LEFT JOIN dev_model_attribute_value v ON v.attribute_id=a.id AND v.model_version_id=?
                LEFT JOIN dev_attribute_value_option o ON o.id=v.attribute_value_option_id
                WHERE a.enabled=1 AND a.value_mode='FIXED' AND (a.category_id IS NULL OR a.category_id=?)
                ORDER BY g.sort,g.id,a.sort,a.id
                """, modelVersionId, longOrNull(device.get("catalog_category_id"))));
        Long deviceTypeId = longOrNull(device.get("device_type_id"));
        Map<String, Object> pointProfile = deviceTypePoints(deviceTypeId == null ? 0L : deviceTypeId);
        profile.put("points", pointProfile);
        Map<String, Object> rawRealtime = realtimeOrEmpty(deviceId);
        profile.put("realtimeRaw", rawRealtime);
        profile.put("realtime", normalizeRealtime(rawRealtime,
                castRows(pointProfile.get("definitions")), castRows(pointProfile.get("bindings"))));
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
        Map<String, Object> points = castMap(profile.get("points"));
        List<Map<String, Object>> recentHistory = jdbcTemplate.queryForList("""
                SELECT id, device_id, device_type_id, org_id, point_code, stat_date,
                       start_value, end_value, usage_value, max_value, min_value, avg_value, data_complete_rate
                FROM stats_daily_point
                WHERE device_id = ?
                ORDER BY stat_date DESC, point_code
                LIMIT 12
                """, deviceId);
        List<Map<String, Object>> recentAlarms = jdbcTemplate.queryForList("""
                SELECT a.id, a.rule_id, a.device_id, a.org_id, a.alarm_type, a.alarm_level, a.point_code,
                       a.alarm_value, a.threshold_value, a.alarm_time, a.deal_status, a.deal_time, a.deal_user, a.deal_remark,
                       a.work_order_id, w.work_order_no, w.status AS work_order_status,
                       w.priority AS work_order_priority, w.assignee_name AS work_order_assignee,
                       w.title AS work_order_title
                FROM log_alarm a
                LEFT JOIN ops_work_order w ON w.id = a.work_order_id
                WHERE a.device_id = ?
                ORDER BY a.alarm_time DESC
                LIMIT 8
                """, deviceId);
        List<Map<String, Object>> inspectionRecords = jdbcTemplate.queryForList("""
                SELECT id, command_id, gateway_id, target_type, target_id, target_sn, command_type,
                       status, request_time, send_time, response_time, fail_reason, create_time
                FROM command_record
                WHERE target_type = 'DEVICE' AND target_id = ?
                ORDER BY request_time DESC
                LIMIT 8
                """, deviceId);
        profile.put("recentHistory", recentHistory);
        profile.put("recentAlarms", recentAlarms);
        profile.put("inspectionRecords", inspectionRecords);
        Map<String, String> trendParams = new LinkedHashMap<>();
        trendParams.put("deviceId", String.valueOf(deviceId));
        if (orgId != null) {
            trendParams.put("orgId", String.valueOf(orgId));
        }
        List<Map<String, Object>> energyTrend = energyTrend(trendParams);
        profile.put("energyTrend", energyTrend);
        Map<String, Object> realtime = castMap(profile.get("realtime"));
        long realtimePointCount = castRows(realtime.get("points")).stream()
                .filter(point -> point.get("value") != null)
                .count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("pointCount", ((List<?>) points.getOrDefault("definitions", List.of())).size());
        summary.put("historyCount", recentHistory.size());
        summary.put("alarmCount", recentAlarms.size());
        summary.put("commandCount", inspectionRecords.size());
        summary.put("energyTrendCount", energyTrend.size());
        summary.put("realtimeAvailable", realtime.getOrDefault("available", false));
        summary.put("realtimePointCount", realtimePointCount);
        summary.put("realtimeCollectTime", realtime.get("collectTime"));
        profile.put("summary", summary);
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
        List<Object> spaceArgs = new ArrayList<>();
        String spaceScope = accessService.orgFilterSql("org_id", orgId, true, spaceArgs);
        profile.put("spaceCount", queryLong("SELECT COUNT(*) FROM park_space WHERE 1 = 1" + spaceScope, spaceArgs));
        List<Object> contractArgs = new ArrayList<>();
        String contractScope = accessService.orgFilterSql("c.org_id", orgId, true, contractArgs);
        profile.put("activeContractCount", queryLong("SELECT COUNT(*) FROM leasing_contract c WHERE c.status = 'ACTIVE'" + contractScope, contractArgs));
        List<Object> tenantArgs = new ArrayList<>();
        String tenantScope = accessService.orgFilterSql("c.org_id", orgId, true, tenantArgs);
        profile.put("tenantCount", queryLong("SELECT COUNT(DISTINCT c.tenant_id) FROM leasing_contract c WHERE c.status = 'ACTIVE'" + tenantScope, tenantArgs));
        profile.put("spaces", jdbcTemplate.queryForList("SELECT * FROM park_space WHERE 1 = 1" + spaceScope + " ORDER BY space_type, space_code LIMIT 30", spaceArgs.toArray()));
        List<Object> onlineGatewayArgs = new ArrayList<>();
        String onlineGatewayScope = accessService.orgFilterSql("org_id", orgId, true, onlineGatewayArgs);
        profile.put("onlineGatewayCount", queryLong("""
                SELECT COUNT(*)
                FROM dev_gateway
                WHERE online_status = 1
                """ + onlineGatewayScope, onlineGatewayArgs));
        List<Map<String, Object>> recentAlarms = orgAlarms(orgId);
        List<Map<String, Object>> alarmTrend = orgAlarmTrend(orgId);
        List<Map<String, Object>> energyTrend = energyTrend(Map.of("orgId", String.valueOf(orgId)));
        List<Map<String, Object>> realtimeSnapshots = realtimeSnapshots(Map.of("orgId", String.valueOf(orgId)));
        profile.put("recentAlarms", recentAlarms);
        profile.put("alarmTrend", alarmTrend);
        profile.put("energyTrend", energyTrend);
        profile.put("realtimeSnapshots", realtimeSnapshots);
        profile.put("summary", Map.of(
                "deviceCount", profile.get("deviceCount"),
                "gatewayCount", profile.get("gatewayCount"),
                "onlineGatewayCount", profile.get("onlineGatewayCount"),
                "spaceCount", profile.get("spaceCount"),
                "tenantCount", profile.get("tenantCount"),
                "activeContractCount", profile.get("activeContractCount"),
                "alarmCount", recentAlarms.size(),
                "alarmTrendCount", alarmTrend.size(),
                "energyTrendCount", energyTrend.size(),
                "realtimeSnapshotCount", realtimeSnapshots.size()
        ));
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
        profile.put("onlineDeviceCount", queryLong("SELECT COUNT(*) FROM dev_device WHERE gateway_id = ? AND status = 1 AND online_status = 1", List.of(gatewayId)));
        List<Map<String, Object>> recentAlarms = gatewayAlarms(gatewayId);
        List<Map<String, Object>> alarmTrend = gatewayAlarmTrend(gatewayId);
        List<Map<String, Object>> energyTrend = gatewayEnergyTrend(gatewayId);
        List<Map<String, Object>> realtimeSnapshots = realtimeSnapshots(Map.of("gatewayId", String.valueOf(gatewayId)));
        profile.put("recentAlarms", recentAlarms);
        profile.put("alarmTrend", alarmTrend);
        profile.put("energyTrend", energyTrend);
        profile.put("realtimeSnapshots", realtimeSnapshots);
        profile.put("summary", Map.of(
                "deviceCount", profile.get("deviceCount"),
                "onlineDeviceCount", profile.get("onlineDeviceCount"),
                "alarmCount", recentAlarms.size(),
                "alarmTrendCount", alarmTrend.size(),
                "energyTrendCount", energyTrend.size(),
                "realtimeSnapshotCount", realtimeSnapshots.size()
        ));
        return profile;
    }

    public Map<String, Object> deviceTypePoints(long typeId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deviceType", singleOrNull("SELECT * FROM dev_device_type WHERE id = ?", typeId));
        List<Map<String, Object>> definitions = jdbcTemplate.queryForList("""
                SELECT *
                FROM dev_point_definition
                WHERE device_type_id = ?
                ORDER BY sort, id
                """, typeId);
        data.put("definitions", definitions);
        data.put("bindings", jdbcTemplate.queryForList("""
                SELECT b.*,f.field_code,f.field_name,f.document_address,f.value_type,f.raw_unit,
                       rb.function_code,rb.start_address,rb.register_count
                FROM dev_device_model_version v
                JOIN dev_model_point_binding b ON b.model_version_id=v.id
                JOIN dev_protocol_field f ON f.id=b.protocol_field_id
                JOIN dev_protocol_read_block rb ON rb.id=f.read_block_id
                WHERE v.device_type_id=? ORDER BY b.sort,b.id
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

    public Map<String, Object> realtimeBatch(List<Long> deviceIds) {
        return realtimeSnapshotQueryService.batch(deviceIds);
    }

    public List<Map<String, Object>> realtimeByOrg(long orgId) {
        return realtimeSnapshotQueryService.byOrg(orgId);
    }

    public List<Map<String, Object>> realtimeSnapshots(Map<String, String> params) {
        return realtimeSnapshotQueryService.snapshots(params);
    }

    public Map<String, Object> historySeries(long deviceId, List<String> pointCodes, String startTime, String endTime) {
        accessService.assertDeviceAccess(deviceId);
        Map<String, Object> data = new LinkedHashMap<>();
        for (String pointCode : pointCodes) {
            if (pointCode == null || pointCode.isBlank()) {
                continue;
            }
            Map<String, String> params = new LinkedHashMap<>();
            params.put("deviceId", String.valueOf(deviceId));
            params.put("pointCode", pointCode.trim());
            if (startTime != null && !startTime.isBlank()) params.put("startTime", startTime);
            if (endTime != null && !endTime.isBlank()) params.put("endTime", endTime);
            data.put(pointCode, remoteServiceClient.getDataPayload("/api/data/history", params));
        }
        return data;
    }

    public List<Map<String, Object>> energyRanking(Map<String, String> params) {
        if ("org".equalsIgnoreCase(params.get("dimension"))) {
            return organizationEnergyRanking(params);
        }
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT d.id AS device_id, d.device_sn, d.device_name, o.org_name,
                       ROUND(SUM(COALESCE(s.usage_value, 0)), 4) AS usage_value
                FROM stats_daily_point s
                JOIN dev_device d ON d.id = s.device_id
                JOIN dev_point_definition p ON p.device_type_id = s.device_type_id
                  AND p.point_code = s.point_code AND p.business_role = 'TOTAL_ACCUMULATED'
                LEFT JOIN dev_org o ON o.id = s.org_id
                WHERE 1 = 1
                """);
        appendOrgFilter(sql, args, "s.org_id", params);
        appendSpaceFilter(sql, args, "d.space_id", params);
        appendPointCodeFilter(sql, args, "s.point_code", params);
        appendDateRange(sql, args, "s.stat_date", params.get("startDate"), params.get("endDate"));
        sql.append(scopeSql("s.org_id", args));
        sql.append(" GROUP BY d.id, d.device_sn, d.device_name, o.org_name ORDER BY usage_value DESC LIMIT ?");
        args.add(parsePositive(params.get("limit"), 20));
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    private List<Map<String, Object>> organizationEnergyRanking(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT s.org_id, o.org_name,
                       ROUND(SUM(COALESCE(s.usage_value, 0)), 4) AS usage_value
                FROM stats_daily_point s
                JOIN dev_device d ON d.id = s.device_id
                JOIN dev_point_definition p ON p.device_type_id = s.device_type_id
                  AND p.point_code = s.point_code AND p.business_role = 'TOTAL_ACCUMULATED'
                LEFT JOIN dev_org o ON o.id = s.org_id
                WHERE 1 = 1
                """);
        appendOrgFilter(sql, args, "s.org_id", params);
        appendSpaceFilter(sql, args, "d.space_id", params);
        appendPointCodeFilter(sql, args, "s.point_code", params);
        appendDateRange(sql, args, "s.stat_date", params.get("startDate"), params.get("endDate"));
        sql.append(scopeSql("s.org_id", args));
        sql.append(" GROUP BY s.org_id, o.org_name ORDER BY usage_value DESC LIMIT ?");
        args.add(parsePositive(params.get("limit"), 20));
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> energyTrend(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        String group = "month".equalsIgnoreCase(params.get("groupBy")) ? "DATE_FORMAT(stat_date, '%Y-%m')" : "stat_date";
        StringBuilder sql = new StringBuilder("SELECT " + group + " AS stat_period, ROUND(SUM(COALESCE(s.usage_value, 0)), 4) AS usage_value FROM stats_daily_point s JOIN dev_device d ON d.id = s.device_id JOIN dev_point_definition p ON p.device_type_id = s.device_type_id AND p.point_code = s.point_code AND p.business_role = 'TOTAL_ACCUMULATED' WHERE 1 = 1");
        appendOrgFilter(sql, args, "s.org_id", params);
        appendSpaceFilter(sql, args, "d.space_id", params);
        appendEquals(sql, args, "s.device_id", params.get("deviceId"));
        appendPointCodeFilter(sql, args, "s.point_code", params);
        appendDateRange(sql, args, "s.stat_date", params.get("startDate"), params.get("endDate"));
        sql.append(scopeSql("s.org_id", args));
        sql.append(" GROUP BY stat_period ORDER BY stat_period");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /**
     * Builds one permission-scoped read model for the organization -> device -> point energy workbench.
     * Daily and hourly facts remain the single source of truth; this method only composes them for display.
     */
    public Map<String, Object> energyDrilldown(Map<String, String> requestParams) {
        Map<String, String> params = new LinkedHashMap<>(requestParams);
        LocalDate endDate = parseDateOrDefault(params.get("endDate"), LocalDate.now(), "endDate");
        LocalDate startDate = parseDateOrDefault(params.get("startDate"), endDate.minusDays(29), "startDate");
        if (startDate.isAfter(endDate)) {
            throw new BusinessException("开始日期不能晚于结束日期");
        }
        if (startDate.isBefore(endDate.minusYears(1))) {
            throw new BusinessException("单次下钻查询最多支持 366 天");
        }
        params.put("startDate", startDate.toString());
        params.put("endDate", endDate.toString());
        params.put("includeChildren", "true");
        params.put("limit", "500");

        Long orgId = longOrNull(params.get("orgId"));
        Long deviceId = longOrNull(params.get("deviceId"));
        String pointCode = Objects.toString(params.get("pointCode"), "").trim();
        Map<String, Object> selectedDevice = null;
        if (deviceId != null) {
            accessService.assertDeviceAccess(deviceId);
            selectedDevice = selectedDrilldownDevice(deviceId);
            Long selectedSpaceId = longOrNull(params.get("spaceId"));
            if (selectedSpaceId != null && !Objects.equals(selectedSpaceId, longOrNull(selectedDevice.get("space_id")))) {
                throw new BusinessException("所选设备不属于当前空间节点");
            }
            Long deviceOrgId = longOrNull(selectedDevice.get("org_id"));
            if (orgId == null) {
                orgId = deviceOrgId;
                params.put("orgId", String.valueOf(orgId));
            } else if (!accessService.orgSubtreeIds(orgId).contains(deviceOrgId)) {
                throw new BusinessException("所选设备不属于当前组织范围");
            }
        } else if (orgId != null && !accessService.hasOrgAccess(orgId)) {
            throw new BusinessException(403, "没有该组织的数据访问权限");
        }

        List<Map<String, Object>> quality = qualityStats(params);
        Map<String, String> rankingParams = new LinkedHashMap<>(params);
        rankingParams.remove("deviceId");
        rankingParams.remove("pointCode");
        List<Map<String, Object>> deviceRanking = energyRanking(rankingParams);
        Map<Long, Map<String, Object>> usageByDevice = indexByLong(deviceRanking, "device_id");
        Map<Long, Map<String, Object>> qualityByDevice = indexByLong(quality, "device_id");
        List<Map<String, Object>> devices = drilldownDevices(params, usageByDevice, qualityByDevice);
        List<Map<String, Object>> points = deviceId == null
                ? List.of()
                : drilldownPoints(deviceId, startDate, endDate);

        Map<String, Object> selectedPoint = points.stream()
                .filter(row -> pointCode.equals(Objects.toString(row.get("point_code"), "")))
                .findFirst().orElse(null);
        if (!pointCode.isBlank() && deviceId == null) {
            throw new BusinessException("选择测点前必须先选择设备");
        }
        if (!pointCode.isBlank() && selectedPoint == null) {
            throw new BusinessException("所选测点不属于当前设备或已停用");
        }

        List<Map<String, Object>> trend;
        String trendGranularity;
        if (deviceId != null && selectedPoint != null) {
            trend = pointHourlyTrend(deviceId, pointCode, startDate, endDate,
                    Objects.toString(selectedPoint.get("business_role"), ""));
            trendGranularity = "HOUR";
        } else {
            trend = energyTrend(params);
            trendGranularity = "month".equalsIgnoreCase(params.get("groupBy")) ? "MONTH" : "DAY";
        }

        List<Map<String, Object>> orgRanking = organizationEnergyRanking(rankingParams);
        Map<String, String> usageParams = new LinkedHashMap<>(params);
        usageParams.remove("pointCode");
        BigDecimal totalUsage = energyTrend(usageParams).stream()
                .map(row -> decimalOrDefault(row, BigDecimal.ZERO, "usage_value"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> overview = drilldownOverview(devices, points, totalUsage, quality, deviceId);
        Map<String, Object> selection = new LinkedHashMap<>();
        selection.put("orgId", orgId);
        selection.put("deviceId", deviceId);
        selection.put("pointCode", pointCode.isBlank() ? null : pointCode);
        selection.put("device", selectedDevice);
        selection.put("point", selectedPoint);
        selection.put("orgBreadcrumb", orgId == null ? List.of() : orgBreadcrumb(orgId));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startDate", startDate);
        result.put("endDate", endDate);
        result.put("selection", selection);
        result.put("organizations", orgTree());
        result.put("devices", devices);
        result.put("points", points);
        result.put("overview", overview);
        result.put("trendGranularity", trendGranularity);
        result.put("trend", trend);
        result.put("deviceRanking", deviceRanking);
        result.put("orgRanking", orgRanking);
        result.put("quality", quality);
        return result;
    }

    private Map<String, Object> selectedDrilldownDevice(long deviceId) {
        return single("""
                SELECT d.id, d.device_sn, d.device_name, d.org_id, d.space_id, sp.space_name,
                       d.device_type_id, d.gateway_id, d.status, o.org_name, t.type_name, g.gateway_sn
                FROM dev_device d
                LEFT JOIN dev_org o ON o.id = d.org_id
                LEFT JOIN park_space sp ON sp.id = d.space_id
                LEFT JOIN dev_device_type t ON t.id = d.device_type_id
                LEFT JOIN dev_gateway g ON g.id = d.gateway_id
                WHERE d.id = ?
                """, deviceId);
    }

    private List<Map<String, Object>> drilldownDevices(Map<String, String> params,
                                                        Map<Long, Map<String, Object>> usageByDevice,
                                                        Map<Long, Map<String, Object>> qualityByDevice) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT d.id, d.device_sn, d.device_name, d.org_id, o.org_name, d.space_id,
                       sp.space_name, d.device_type_id, t.type_name, d.gateway_id, g.gateway_sn, d.status
                FROM dev_device d
                LEFT JOIN dev_org o ON o.id = d.org_id
                LEFT JOIN park_space sp ON sp.id = d.space_id
                LEFT JOIN dev_device_type t ON t.id = d.device_type_id
                LEFT JOIN dev_gateway g ON g.id = d.gateway_id
                WHERE 1 = 1
                """);
        appendOrgFilter(sql, args, "d.org_id", params);
        appendSpaceFilter(sql, args, "d.space_id", params);
        sql.append(scopeSql("d.org_id", args));
        sql.append(" ORDER BY o.org_name, d.device_name, d.id LIMIT 500");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        for (Map<String, Object> row : rows) {
            Long id = longOrNull(row.get("id"));
            Map<String, Object> usage = usageByDevice.get(id);
            Map<String, Object> deviceQuality = qualityByDevice.get(id);
            row.put("usage_value", usage == null ? BigDecimal.ZERO : usage.get("usage_value"));
            row.put("avg_complete_rate", deviceQuality == null ? BigDecimal.ZERO : deviceQuality.get("avg_complete_rate"));
            row.put("quality_status", qualityStatus(deviceQuality));
            row.put("longest_gap_seconds", deviceQuality == null ? 0 : deviceQuality.get("longest_gap_seconds"));
        }
        return rows;
    }

    private List<Map<String, Object>> drilldownPoints(long deviceId, LocalDate startDate, LocalDate endDate) {
        return jdbcTemplate.queryForList("""
                SELECT p.id, p.point_code, p.point_name, p.unit, p.precision_scale, p.business_role,
                       p.billable, p.stat_enabled,
                       ROUND(COALESCE(SUM(CASE WHEN p.business_role = 'TOTAL_ACCUMULATED'
                         THEN COALESCE(s.usage_value, 0) ELSE 0 END), 0), 4) AS usage_value,
                       ROUND(AVG(s.avg_value), 4) AS avg_value,
                       ROUND(MAX(s.max_value), 4) AS max_value,
                       ROUND(MIN(s.min_value), 4) AS min_value,
                       COUNT(s.id) AS statistic_days
                FROM dev_device d
                JOIN dev_point_definition p ON p.device_type_id = d.device_type_id AND p.enabled = 1
                LEFT JOIN stats_daily_point s ON s.device_id = d.id AND s.point_code = p.point_code
                  AND s.stat_date >= ? AND s.stat_date <= ?
                WHERE d.id = ?
                GROUP BY p.id, p.point_code, p.point_name, p.unit, p.precision_scale,
                         p.business_role, p.billable, p.stat_enabled, p.sort
                ORDER BY p.sort, p.id
                """, Date.valueOf(startDate), Date.valueOf(endDate), deviceId);
    }

    private List<Map<String, Object>> pointHourlyTrend(long deviceId, String pointCode,
                                                        LocalDate startDate, LocalDate endDate,
                                                        String businessRole) {
        String valueColumn = "TOTAL_ACCUMULATED".equalsIgnoreCase(businessRole)
                ? "COALESCE(h.usage_value, 0)" : "h.avg_value";
        return jdbcTemplate.queryForList("""
                SELECT CONCAT(h.stat_date, ' ', LPAD(h.stat_hour, 2, '0'), ':00') AS stat_period,
                       h.stat_date, h.stat_hour, h.point_code,
                       ROUND(%s, 4) AS value,
                       h.usage_value, h.avg_value, h.max_value, h.min_value,
                       h.sample_count, h.expected_samples, h.data_complete_rate,
                       h.first_collect_time, h.last_collect_time
                FROM stats_hourly_point h
                WHERE h.device_id = ? AND h.point_code = ?
                  AND h.stat_date >= ? AND h.stat_date <= ?
                ORDER BY h.stat_date, h.stat_hour
                LIMIT 2000
                """.formatted(valueColumn), deviceId, pointCode, Date.valueOf(startDate), Date.valueOf(endDate));
    }

    private Map<String, Object> drilldownOverview(List<Map<String, Object>> devices,
                                                   List<Map<String, Object>> points,
                                                   BigDecimal totalUsage,
                                                   List<Map<String, Object>> quality,
                                                   Long selectedDeviceId) {
        long expected = quality.stream().mapToLong(row -> numberOrZero(row.get("expected_samples"))).sum();
        long received = quality.stream().mapToLong(row -> numberOrZero(row.get("received_samples"))).sum();
        BigDecimal completeRate = expected == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(received).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(expected), 2, java.math.RoundingMode.HALF_UP);
        long riskDevices = quality.stream().filter(row -> !"NORMAL".equals(qualityStatus(row))).count();
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("deviceCount", selectedDeviceId == null ? devices.size() : 1);
        overview.put("pointCount", points.size());
        overview.put("totalUsage", totalUsage);
        overview.put("completeRate", completeRate);
        overview.put("riskDeviceCount", riskDevices);
        return overview;
    }

    private Map<Long, Map<String, Object>> indexByLong(List<Map<String, Object>> rows, String key) {
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Long value = longOrNull(row.get(key));
            if (value != null) result.put(value, row);
        }
        return result;
    }

    private String qualityStatus(Map<String, Object> quality) {
        if (quality == null) return "NO_DATA";
        if (numberOrZero(quality.get("abnormal_days")) > 0) return "ABNORMAL";
        BigDecimal rate = decimalOrDefault(quality, BigDecimal.ZERO, "avg_complete_rate");
        return rate.compareTo(BigDecimal.valueOf(80)) > 0 ? "NORMAL" : "RISK";
    }

    private List<Map<String, Object>> orgBreadcrumb(long orgId) {
        List<Object> args = new ArrayList<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, parent_id, org_name, org_type FROM dev_org WHERE 1 = 1
                """ + scopeSql("id", args), args.toArray());
        Map<Long, Map<String, Object>> byId = indexByLong(rows, "id");
        List<Map<String, Object>> path = new ArrayList<>();
        Set<Long> visited = new LinkedHashSet<>();
        Long current = orgId;
        while (current != null && visited.add(current)) {
            Map<String, Object> row = byId.get(current);
            if (row == null) break;
            path.add(0, row);
            current = longOrNull(row.get("parent_id"));
        }
        return path;
    }

    private LocalDate parseDateOrDefault(String value, LocalDate defaultValue, String field) {
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException ex) {
            throw new BusinessException(field + " 日期格式应为 yyyy-MM-dd");
        }
    }

    private long numberOrZero(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    public List<Map<String, Object>> hourlyStats(Map<String, String> params) {
        Long deviceId = longOrNull(params.get("deviceId"));
        if (deviceId != null) {
            accessService.assertDeviceAccess(deviceId);
        }
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT h.stat_date, h.stat_hour, h.device_id, d.device_sn, d.device_name,
                       h.org_id, o.org_name, h.point_code,
                       h.start_value, h.end_value, h.usage_value,
                       h.max_value, h.min_value, h.avg_value,
                       h.sample_count, h.expected_samples, h.data_complete_rate,
                       h.first_collect_time, h.last_collect_time
                FROM stats_hourly_point h
                LEFT JOIN dev_device d ON d.id = h.device_id
                LEFT JOIN dev_org o ON o.id = h.org_id
                WHERE 1 = 1
                """);
        appendOrgFilter(sql, args, "h.org_id", params);
        appendEquals(sql, args, "h.device_id", params.get("deviceId"));
        appendPointCodeFilter(sql, args, "h.point_code", params);
        appendDateRange(sql, args, "h.stat_date", params.get("startDate"), params.get("endDate"));
        sql.append(scopeSql("h.org_id", args));
        sql.append(" ORDER BY h.stat_date DESC, h.stat_hour DESC, h.device_id, h.point_code LIMIT 2000");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> dailySummary(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT s.stat_date, s.device_id, s.org_id, o.org_name, s.point_code,
                       ROUND(SUM(COALESCE(s.usage_value, 0)), 4) AS usage_value,
                       ROUND(MAX(COALESCE(s.max_value, 0)), 4) AS max_value,
                       ROUND(MIN(COALESCE(s.min_value, 0)), 4) AS min_value,
                       ROUND(AVG(COALESCE(s.avg_value, 0)), 4) AS avg_value
                FROM stats_daily_point s
                LEFT JOIN dev_org o ON o.id = s.org_id
                WHERE 1 = 1
                """);
        appendOrgFilter(sql, args, "s.org_id", params);
        appendEquals(sql, args, "s.device_id", params.get("deviceId"));
        appendPointCodeFilter(sql, args, "s.point_code", params);
        appendDateRange(sql, args, "s.stat_date", params.get("startDate"), params.get("endDate"));
        sql.append(scopeSql("s.org_id", args));
        sql.append(" GROUP BY s.stat_date, s.device_id, s.org_id, o.org_name, s.point_code ORDER BY s.stat_date DESC, s.org_id, s.device_id LIMIT 500");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> monthlyStats(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT DATE_FORMAT(s.stat_date, '%Y-%m') AS stat_month, s.device_id, s.org_id, o.org_name, s.point_code,
                       ROUND(SUM(COALESCE(s.usage_value, 0)), 4) AS usage_value,
                       ROUND(MAX(COALESCE(s.max_value, 0)), 4) AS max_value,
                       ROUND(MIN(COALESCE(s.min_value, 0)), 4) AS min_value,
                       ROUND(AVG(COALESCE(s.avg_value, 0)), 4) AS avg_value
                FROM stats_daily_point s
                LEFT JOIN dev_org o ON o.id = s.org_id
                WHERE 1 = 1
                """);
        appendOrgFilter(sql, args, "s.org_id", params);
        appendEquals(sql, args, "s.device_id", params.get("deviceId"));
        appendPointCodeFilter(sql, args, "s.point_code", params);
        appendDateRange(sql, args, "s.stat_date", params.get("startDate"), params.get("endDate"));
        sql.append(scopeSql("s.org_id", args));
        sql.append(" GROUP BY stat_month, s.device_id, s.org_id, o.org_name, s.point_code ORDER BY stat_month DESC, s.org_id, s.device_id LIMIT 500");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> qualityStats(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT c.device_id, d.device_sn, d.device_name, c.org_id, o.org_name,
                       ROUND(COALESCE(SUM(c.received_samples) * 100.00 /
                         NULLIF(SUM(c.expected_samples), 0), 0), 2) AS avg_complete_rate,
                       MAX(c.longest_gap_seconds) AS longest_gap_seconds,
                       SUM(c.expected_samples) AS expected_samples,
                       SUM(c.received_samples) AS received_samples,
                       SUM(CASE WHEN c.quality_status = 'ABNORMAL' THEN 1 ELSE 0 END) AS abnormal_days,
                       SUM(CASE WHEN c.quality_status = 'INCOMPLETE' THEN 1 ELSE 0 END) AS incomplete_days,
                       MIN(c.stat_date) AS start_date, MAX(c.stat_date) AS end_date
                FROM stats_collection_daily c
                LEFT JOIN dev_device d ON d.id = c.device_id
                LEFT JOIN dev_org o ON o.id = c.org_id
                WHERE 1 = 1
                """);
        appendOrgFilter(sql, args, "c.org_id", params);
        appendSpaceFilter(sql, args, "d.space_id", params);
        appendEquals(sql, args, "c.device_id", params.get("deviceId"));
        appendDateRange(sql, args, "c.stat_date", params.get("startDate"), params.get("endDate"));
        sql.append(scopeSql("c.org_id", args));
        sql.append(" GROUP BY c.device_id, d.device_sn, d.device_name, c.org_id, o.org_name ORDER BY avg_complete_rate ASC LIMIT 500");
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

    public Map<String, Object> rebuildHourlyStats(Map<String, String> params) {
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
            results.add(remoteServiceClient.postData("/api/data/statistics/hourly/rebuild?statDate="
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
        appendEquals(where, args, "d.gateway_id", params.get("gatewayId"));
        appendEquals(where, args, "e.deal_status", params.get("dealStatus"));
        if ("OPEN".equalsIgnoreCase(params.get("eventStatus"))) {
            where.append(" AND e.event_status IN ('NEW','ACKNOWLEDGED','IN_PROGRESS','RECOVERED','SUPPRESSED')");
        } else {
            appendEquals(where, args, "e.event_status", params.get("eventStatus"));
        }
        appendEquals(where, args, "e.alarm_type", params.get("alarmType"));
        appendEquals(where, args, "e.alarm_level", params.get("alarmLevel"));
        appendKeyword(where, args, params.get("keyword"), "d.device_sn", "d.device_name", "e.point_code", "r.rule_name");
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
            case "SPACE" -> String.join(" ",
                    Objects.toString(node.get("space_name"), ""),
                    Objects.toString(node.get("space_code"), ""),
                    Objects.toString(node.get("space_type"), ""));
            case "GROUP" -> Objects.toString(node.get("group_name"), "");
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
        List<Map<String, Object>> totals = jdbcTemplate.queryForList("""
                SELECT
                  COALESCE(SUM(event_status IN ('NEW','ACKNOWLEDGED','IN_PROGRESS','SUPPRESSED')), 0) AS pending_count,
                  COALESCE(SUM(event_status = 'RECOVERED'), 0) AS recovered_count,
                  COALESCE(SUM(event_status IN ('CLOSED','FALSE_POSITIVE')), 0) AS closed_count
                FROM log_alarm WHERE 1 = 1
                """ + filter, args.toArray());
        Map<String, Object> total = totals.isEmpty() ? Map.of() : totals.get(0);

        // Three presentation groups, one network round-trip to the remote database.
        List<Object> groupArgs = new ArrayList<>();
        groupArgs.addAll(args);
        groupArgs.addAll(args);
        groupArgs.addAll(args);
        List<Map<String, Object>> grouped = jdbcTemplate.queryForList("""
                SELECT _utf8mb4'STATUS' COLLATE utf8mb4_unicode_ci AS group_kind,
                       CAST(event_status AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_unicode_ci AS group_value,
                       COUNT(*) AS count
                FROM log_alarm WHERE 1 = 1 %s GROUP BY event_status
                UNION ALL
                SELECT _utf8mb4'LEVEL' COLLATE utf8mb4_unicode_ci AS group_kind,
                       CAST(alarm_level AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_unicode_ci AS group_value,
                       COUNT(*) AS count
                FROM log_alarm WHERE 1 = 1 %s GROUP BY alarm_level
                UNION ALL
                SELECT _utf8mb4'TYPE' COLLATE utf8mb4_unicode_ci AS group_kind,
                       CAST(alarm_type AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_unicode_ci AS group_value,
                       COUNT(*) AS count
                FROM log_alarm WHERE 1 = 1 %s GROUP BY alarm_type
                """.formatted(filter, filter, filter), groupArgs.toArray());
        List<Map<String, Object>> byStatus = new ArrayList<>();
        List<Map<String, Object>> byLevel = new ArrayList<>();
        List<Map<String, Object>> byType = new ArrayList<>();
        for (Map<String, Object> row : grouped) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("count", numberOrZero(row.get("count")));
            switch (Objects.toString(row.get("group_kind"), "")) {
                case "STATUS" -> { item.put("event_status", row.get("group_value")); byStatus.add(item); }
                case "LEVEL" -> { item.put("alarm_level", row.get("group_value")); byLevel.add(item); }
                case "TYPE" -> { item.put("alarm_type", row.get("group_value")); byType.add(item); }
                default -> { }
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pendingCount", numberOrZero(total.get("pending_count")));
        data.put("recoveredCount", numberOrZero(total.get("recovered_count")));
        data.put("closedCount", numberOrZero(total.get("closed_count")));
        data.put("handledCount", data.get("closedCount"));
        data.put("byStatus", byStatus);
        data.put("byLevel", byLevel);
        data.put("byType", byType);
        Map<String, String> latestParams = new LinkedHashMap<>(params);
        latestParams.put("pageSize", "10");
        data.put("latest", alarmEvents(latestParams).records());
        return data;
    }

    private Map<String, Object> realtimeOrEmpty(long deviceId) {
        return realtimeSnapshotQueryService.realtimeOrEmpty(deviceId);
    }

    private Long alarmCountByStatus(int status, String filter, List<Object> filterArgs) {
        List<Object> args = new ArrayList<>();
        args.add(status);
        args.addAll(filterArgs);
        return queryLong("SELECT COUNT(*) FROM log_alarm WHERE deal_status = ?" + filter, args);
    }

    private Long alarmCountByStatuses(List<String> statuses, String filter, List<Object> filterArgs) {
        if (statuses.isEmpty()) return 0L;
        List<Object> args = new ArrayList<>(statuses);
        args.addAll(filterArgs);
        String placeholders = String.join(",", java.util.Collections.nCopies(statuses.size(), "?"));
        return queryLong("SELECT COUNT(*) FROM log_alarm WHERE event_status IN (" + placeholders + ")" + filter, args);
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
                Map.of("commandType", "DEVICE_COMMAND", "targetType", "DEVICE", "label", "执行设备协议命令"),
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

    private void appendIdListFilter(StringBuilder sql, List<Object> args, String column, String value) {
        List<Long> ids = Arrays.stream(Objects.toString(value, "").split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(this::longOrNull)
                .filter(Objects::nonNull)
                .distinct()
                .limit(100)
                .toList();
        if (ids.isEmpty()) return;
        sql.append(" AND ").append(column).append(" IN (")
                .append(String.join(",", java.util.Collections.nCopies(ids.size(), "?"))).append(")");
        args.addAll(ids);
    }

    private void appendPointCodeFilter(StringBuilder sql, List<Object> args, String column, Map<String, String> params) {
        List<String> pointCodes = Arrays.stream(Objects.toString(params.get("pointCodes"), "").split(","))
                .map(String::trim)
                .filter(code -> !code.isBlank())
                .distinct()
                .limit(100)
                .toList();
        if (pointCodes.isEmpty()) {
            appendEquals(sql, args, column, params.get("pointCode"));
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(pointCodes.size(), "?"));
        sql.append(" AND ").append(column).append(" IN (").append(placeholders).append(")");
        args.addAll(pointCodes);
    }

    private void appendOrgFilter(StringBuilder sql, List<Object> args, String column, Map<String, String> params) {
        Long orgId = longOrNull(params.get("orgId"));
        boolean includeChildren = Boolean.parseBoolean(Objects.toString(params.getOrDefault("includeChildren", "false")));
        sql.append(accessService.orgFilterSql(column, orgId, includeChildren, args));
    }

    private void appendSpaceFilter(StringBuilder sql, List<Object> args, String column, Map<String, String> params) {
        Long spaceId = longOrNull(params.get("spaceId"));
        if (spaceId != null) {
            sql.append(" AND ").append(column).append(" = ?");
            args.add(spaceId);
        }
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castRows(Object value) {
        if (!(value instanceof List<?> rows)) return List.of();
        return rows.stream().filter(Map.class::isInstance).map(row -> (Map<String, Object>) row).toList();
    }

    private Map<String, Object> normalizeRealtime(Map<String, Object> raw, List<Map<String, Object>> definitions,
                                                   List<Map<String, Object>> mappings) {
        Map<String, Map<String, Object>> mappingByCode = new LinkedHashMap<>();
        for (Map<String, Object> mapping : mappings) mappingByCode.put(text(mapping, "point_code", "pointCode"), mapping);
        JsonNode root = objectMapper.valueToTree(raw == null ? Map.of() : raw);
        JsonNode payload = unwrapRealtimePayload(root);
        List<Map<String, Object>> points = new ArrayList<>();
        for (Map<String, Object> definition : definitions) {
            String code = text(definition, "point_code", "pointCode");
            Map<String, Object> mapping = mappingByCode.get(code);
            Object rawValue = readMappedValue(payload, mapping, code);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("pointCode", code);
            point.put("pointName", text(definition, "point_name", "pointName"));
            point.put("unit", text(definition, "unit"));
            point.put("dataType", text(definition, "data_type", "dataType"));
            point.put("businessRole", text(definition, "business_role", "businessRole"));
            point.put("sourcePath", mapping == null ? null : text(mapping, "source_path", "sourcePath"));
            // Data service already resolved source paths and scale/offset. Platform only adds names and units.
            point.put("value", rawValue);
            points.add(point);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", !raw.isEmpty());
        result.put("collectTime", firstText(raw, "collectTime", "collect_time", "timestamp"));
        result.put("receiveTime", firstText(raw, "receiveTime", "receive_time"));
        result.put("delaySeconds", value(raw, "delaySeconds", "delay_seconds"));
        result.put("freshnessStatus", firstText(raw, "freshnessStatus", "freshness_status"));
        result.put("qualityStatus", firstText(raw, "qualityStatus", "quality_status"));
        result.put("points", points);
        return result;
    }

    private JsonNode unwrapRealtimePayload(JsonNode node) {
        JsonNode current = node;
        while (current != null && current.isObject() && current.has("data") && current.get("data").isObject()) current = current.get("data");
        return current == null ? objectMapper.createObjectNode() : current;
    }

    private Object readMappedValue(JsonNode payload, Map<String, Object> mapping, String pointCode) {
        if (mapping != null) {
            String path = text(mapping, "source_path", "sourcePath");
            JsonNode byPath = readJsonPath(payload, path);
            if (byPath != null) return jsonValue(byPath);
        }
        JsonNode direct = payload.path("points").path(pointCode);
        if (direct.isMissingNode() || direct.isNull()) direct = payload.path(pointCode);
        if (direct.isMissingNode() || direct.isNull()) direct = payload.path(toCamel(pointCode));
        return direct.isMissingNode() || direct.isNull() ? null : jsonValue(direct);
    }

    private JsonNode readJsonPath(JsonNode root, String path) {
        if (path == null || path.isBlank() || !path.startsWith("$")) return null;
        JsonNode current = root;
        String normalized = path.startsWith("$.") ? path.substring(2) : path.substring(1);
        if (normalized.isBlank()) return current;
        for (String segment : normalized.split("\\.")) {
            current = current == null ? null : current.path(segment);
            if (current == null || current.isMissingNode() || current.isNull()) return null;
        }
        return current;
    }

    private Object jsonValue(JsonNode node) {
        if (node.isBoolean()) return node.booleanValue();
        if (node.isNumber()) return node.decimalValue();
        return node.asText();
    }

    private Object applyPointTransform(Object raw, Map<String, Object> mapping) {
        if (raw == null || mapping == null || raw instanceof Boolean) return raw;
        try {
            BigDecimal value = new BigDecimal(raw.toString());
            BigDecimal scale = decimalOrDefault(mapping, BigDecimal.ONE, "scale_factor", "scaleFactor");
            BigDecimal offset = decimalOrDefault(mapping, BigDecimal.ZERO, "offset_value", "offsetValue");
            return value.multiply(scale).add(offset);
        } catch (NumberFormatException ex) {
            return raw;
        }
    }

    private String firstText(Map<String, Object> value, String... keys) {
        for (String key : keys) if (value != null && value.get(key) != null) return value.get(key).toString();
        return null;
    }

    private String toCamel(String value) {
        StringBuilder result = new StringBuilder();
        boolean uppercase = false;
        for (char c : value.toCharArray()) {
            if (c == '_') { uppercase = true; continue; }
            result.append(uppercase ? Character.toUpperCase(c) : Character.toLowerCase(c));
            uppercase = false;
        }
        return result.toString();
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
