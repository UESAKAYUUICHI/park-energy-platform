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
import com.parkenergyplatform.service.RemoteServiceClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/energy")
public class EnergyProxyController {
    private final RemoteServiceClient remoteServiceClient;
    private final BusinessDataAccessService accessService;
    private final PlatformBusinessQueryService queryService;
    private final JdbcTemplate jdbcTemplate;

    public EnergyProxyController(RemoteServiceClient remoteServiceClient, BusinessDataAccessService accessService,
                                 PlatformBusinessQueryService queryService, JdbcTemplate jdbcTemplate) {
        this.remoteServiceClient = remoteServiceClient;
        this.accessService = accessService;
        this.queryService = queryService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/realtime/devices/{deviceId}")
    @SaCheckPermission("energy:view")
    public ApiResponse<Map<String, Object>> realtime(@PathVariable Long deviceId) {
        accessService.assertDeviceAccess(deviceId);
        return ApiResponse.success(remoteServiceClient.getData("/api/data/realtime/devices/" + deviceId));
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
    public ApiResponse<Map<String, Object>> history(HttpServletRequest request) {
        accessService.assertDeviceAccess(requiredLongParam(request, "deviceId"));
        return ApiResponse.success(remoteServiceClient.getData("/api/data/history" + queryString(request)));
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

    @GetMapping("/statistics/daily")
    @SaCheckPermission("energy:view")
    public ApiResponse<Map<String, Object>> daily(HttpServletRequest request) {
        Long deviceId = optionalLongParam(request, "deviceId");
        if (deviceId != null) {
            accessService.assertDeviceAccess(deviceId);
            return ApiResponse.success(remoteServiceClient.getData("/api/data/statistics/daily" + queryString(request)));
        }
        return ApiResponse.success(localDailyStats(request));
    }

    @GetMapping("/alarms")
    @SaCheckPermission("energy:view")
    public ApiResponse<Map<String, Object>> alarms(HttpServletRequest request) {
        Long deviceId = optionalLongParam(request, "deviceId");
        if (deviceId != null) {
            accessService.assertDeviceAccess(deviceId);
            return ApiResponse.success(remoteServiceClient.getData("/api/data/alarms" + queryString(request)));
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
