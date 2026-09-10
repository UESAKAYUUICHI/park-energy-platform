package com.parkenergyplatform.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.service.BusinessDataAccessService;
import com.parkenergyplatform.service.PlatformBusinessQueryService;
import com.parkenergyplatform.service.EnergyEfficiencyService;
import com.parkenergyplatform.service.RemoteServiceClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/energy")
public class EnergyProxyController {
    private final RemoteServiceClient remoteServiceClient;
    private final BusinessDataAccessService accessService;
    private final PlatformBusinessQueryService queryService;
    private final JdbcTemplate jdbcTemplate;
    private final EnergyEfficiencyService efficiencyService;

    public EnergyProxyController(RemoteServiceClient remoteServiceClient, BusinessDataAccessService accessService,
                                 PlatformBusinessQueryService queryService, JdbcTemplate jdbcTemplate,
                                 EnergyEfficiencyService efficiencyService) {
        this.remoteServiceClient = remoteServiceClient;
        this.accessService = accessService;
        this.queryService = queryService;
        this.jdbcTemplate = jdbcTemplate;
        this.efficiencyService = efficiencyService;
    }

    /** Resolves the external device SN only after applying the caller's organization data scope. */
    @GetMapping("/devices/resolve")
    @SaCheckPermission("energy:view")
    public ApiResponse<Map<String, Object>> resolveDeviceBySn(@RequestParam String deviceSn) {
        return ApiResponse.success(authorizedDeviceBySn(deviceSn));
    }

    @PostMapping("/parse-preview")
    @SaCheckPermission(value = {"archive:edit", "energy:view"}, mode = cn.dev33.satoken.annotation.SaMode.OR)
    public ApiResponse<Object> parsePreview(@RequestBody Map<String, Object> body) {
        Object deviceId = body.get("deviceId");
        if (deviceId == null) throw new BusinessException("deviceId 不能为空");
        accessService.assertDeviceAccess(Long.parseLong(String.valueOf(deviceId)));
        Map<String, Object> response = remoteServiceClient.postData("/api/data/parse-preview", body);
        if (Boolean.FALSE.equals(response.get("success"))) {
            throw new BusinessException(String.valueOf(response.getOrDefault("message", "样例报文解析失败")));
        }
        return ApiResponse.success(response.get("data"));
    }

    @GetMapping("/realtime/devices/{deviceId}")
    @SaCheckPermission("energy:view")
    public ApiResponse<Object> realtime(@PathVariable Long deviceId) {
        accessService.assertDeviceAccess(deviceId);
        return ApiResponse.success(remoteServiceClient.getDataPayload("/api/data/realtime/devices/" + deviceId));
    }

    @GetMapping("/realtime/devices")
    @SaCheckPermission("energy:view")
    public ApiResponse<Map<String, Object>> realtimeBatch(HttpServletRequest request) {
        String deviceIds = request.getParameter("deviceIds");
        if (deviceIds == null || deviceIds.isBlank()) {
            throw new BusinessException("deviceIds 不能为空");
        }
        List<Long> ids = Arrays.stream(deviceIds.split(","))
                .filter(value -> !value.isBlank())
                .map(value -> Long.valueOf(value.trim()))
                .toList();
        return ApiResponse.success(queryService.realtimeBatch(ids));
    }

    @GetMapping("/realtime/orgs/{orgId}")
    @SaCheckPermission("energy:view")
    public ApiResponse<Object> realtimeByOrg(@PathVariable long orgId) {
        return ApiResponse.success(queryService.realtimeByOrg(orgId));
    }

    @GetMapping("/realtime/snapshots")
    @SaCheckPermission("energy:view")
    public ApiResponse<Object> realtimeSnapshots(HttpServletRequest request) {
        return ApiResponse.success(queryService.realtimeSnapshots(queryParams(request)));
    }

    @GetMapping("/history")
    @SaCheckPermission("energy:view")
    public ApiResponse<Object> history(HttpServletRequest request) {
        accessService.assertDeviceAccess(requiredLongParam(request, "deviceId"));
        return ApiResponse.success(remoteServiceClient.getDataPayload("/api/data/history" + queryString(request)));
    }

    @GetMapping("/history/series")
    @SaCheckPermission("energy:view")
    public ApiResponse<Map<String, Object>> historySeries(HttpServletRequest request) {
        Long deviceId = requiredLongParam(request, "deviceId");
        String pointCodes = request.getParameter("pointCodes");
        if (pointCodes == null || pointCodes.isBlank()) {
            throw new BusinessException("pointCodes 不能为空");
        }
        return ApiResponse.success(queryService.historySeries(deviceId,
                Arrays.stream(pointCodes.split(",")).map(String::trim).toList(),
                request.getParameter("startTime"), request.getParameter("endTime")));
    }

    @GetMapping("/collection-windows")
    @SaCheckPermission("energy:view")
    public ApiResponse<Object> collectionWindows(HttpServletRequest request) {
        accessService.assertDeviceAccess(requiredLongParam(request, "deviceId"));
        return ApiResponse.success(remoteServiceClient.getDataPayload(
                "/api/data/collection-windows" + queryString(request)));
    }

    @GetMapping("/ranking")
    @SaCheckPermission("energy:view")
    public ApiResponse<Object> ranking(HttpServletRequest request) {
        return ApiResponse.success(queryService.energyRanking(queryParams(request)));
    }

    @GetMapping("/trend")
    @SaCheckPermission("energy:view")
    public ApiResponse<Object> trend(HttpServletRequest request) {
        return ApiResponse.success(queryService.energyTrend(queryParams(request)));
    }

    @GetMapping("/drilldown")
    @SaCheckPermission("energy:view")
    public ApiResponse<Map<String, Object>> drilldown(HttpServletRequest request) {
        return ApiResponse.success(queryService.energyDrilldown(queryParams(request)));
    }

    @GetMapping("/efficiency/overview")
    @SaCheckPermission("energy:view")
    public ApiResponse<Map<String, Object>> efficiencyOverview(HttpServletRequest request) {
        return ApiResponse.success(efficiencyService.overview(queryParams(request)));
    }

    @GetMapping("/statistics/daily")
    @SaCheckPermission("energy:view")
    public ApiResponse<Object> daily(HttpServletRequest request) {
        Long deviceId = optionalLongParam(request, "deviceId");
        if (deviceId != null) {
            accessService.assertDeviceAccess(deviceId);
            return ApiResponse.success(remoteServiceClient.getDataPayload("/api/data/statistics/daily" + queryString(request)));
        }
        return ApiResponse.success(localDailyStats(request));
    }

    @GetMapping("/alarms")
    @SaCheckPermission("energy:view")
    public ApiResponse<Object> alarms(HttpServletRequest request) {
        Long deviceId = optionalLongParam(request, "deviceId");
        if (deviceId != null) {
            accessService.assertDeviceAccess(deviceId);
            return ApiResponse.success(remoteServiceClient.getDataPayload("/api/data/alarms" + queryString(request)));
        }
        return ApiResponse.success(localAlarms(request));
    }

    private Map<String, Object> localDailyStats(HttpServletRequest request) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT id, device_id, device_type_id, org_id, point_code, stat_date,
                       start_value, end_value, usage_value, max_value, min_value, avg_value, data_complete_rate
                FROM stats_daily_point
                WHERE 1 = 1
                """);
        appendOrgFilter(sql, args, "org_id", request);
        appendText(sql, args, "point_code", request.getParameter("pointCode"));
        appendRange(sql, args, "stat_date", request.getParameter("startDate"), request.getParameter("endDate"));
        sql.append(accessService.scopeSql("org_id", args));
        sql.append(" ORDER BY stat_date DESC, device_id ASC, point_code ASC LIMIT 500");
        return dataEnvelope(jdbcTemplate.queryForList(sql.toString(), args.toArray()));
    }

    private Map<String, Object> localAlarms(HttpServletRequest request) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT id, rule_id, device_id, org_id, alarm_type, alarm_level, point_code,
                       alarm_value, threshold_value, alarm_time, deal_status, deal_time, deal_user, deal_remark
                FROM log_alarm
                WHERE 1 = 1
                """);
        appendOrgFilter(sql, args, "org_id", request);
        appendText(sql, args, "deal_status", request.getParameter("dealStatus"));
        appendRange(sql, args, "alarm_time", request.getParameter("startTime"), request.getParameter("endTime"));
        sql.append(accessService.scopeSql("org_id", args));
        sql.append(" ORDER BY alarm_time DESC LIMIT 200");
        return dataEnvelope(jdbcTemplate.queryForList(sql.toString(), args.toArray()));
    }

    private Map<String, Object> dataEnvelope(List<Map<String, Object>> rows) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", "success");
        response.put("data", rows);
        return response;
    }

    private void appendText(StringBuilder sql, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(" AND ").append(column).append(" = ?");
            args.add(value.trim());
        }
    }

    private void appendRange(StringBuilder sql, List<Object> args, String column, String start, String end) {
        if (start != null && !start.isBlank()) {
            sql.append(" AND ").append(column).append(" >= ?");
            args.add(start.trim());
        }
        if (end != null && !end.isBlank()) {
            sql.append(" AND ").append(column).append(" <= ?");
            args.add(end.trim());
        }
    }

    private void appendOrgFilter(StringBuilder sql, List<Object> args, String column, HttpServletRequest request) {
        Long orgId = optionalLongParam(request, "orgId");
        boolean includeChildren = Boolean.parseBoolean(String.valueOf(request.getParameter("includeChildren")));
        sql.append(accessService.orgFilterSql(column, orgId, includeChildren, args));
    }

    private Map<String, Object> authorizedDeviceBySn(String deviceSn) {
        if (deviceSn == null || deviceSn.isBlank()) {
            throw new BusinessException("deviceSn 不能为空");
        }
        List<Map<String, Object>> devices = jdbcTemplate.queryForList("""
                SELECT d.id, d.device_sn, d.device_name, d.gateway_id, d.org_id, d.device_type_id, d.status,
                       g.gateway_sn, g.gateway_name, o.org_name
                FROM dev_device d
                LEFT JOIN dev_gateway g ON g.id = d.gateway_id
                LEFT JOIN dev_org o ON o.id = d.org_id
                WHERE d.device_sn = ?
                LIMIT 1
                """, deviceSn.trim());
        if (devices.isEmpty()) {
            throw new BusinessException("未找到设备 SN: " + deviceSn.trim());
        }
        Map<String, Object> device = devices.get(0);
        Object id = device.get("id");
        if (!(id instanceof Number number)) {
            throw new BusinessException("设备档案 ID 无效");
        }
        accessService.assertDeviceAccess(number.longValue());
        return device;
    }

    private Long requiredLongParam(HttpServletRequest request, String name) {
        Long value = optionalLongParam(request, name);
        if (value == null) {
            throw new BusinessException(name + " 不能为空");
        }
        return value;
    }

    private Long optionalLongParam(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        return Long.valueOf(value);
    }

    private Map<String, String> queryParams(HttpServletRequest request) {
        return request.getParameterMap().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue()[0]));
    }

    private String queryString(HttpServletRequest request) {
        StringJoiner joiner = new StringJoiner("&");
        request.getParameterMap().forEach((key, values) -> {
            if (values.length > 0) {
                joiner.add(encode(key) + "=" + encode(values[0]));
            }
        });
        String value = joiner.toString();
        return value.isBlank() ? "" : "?" + value;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
