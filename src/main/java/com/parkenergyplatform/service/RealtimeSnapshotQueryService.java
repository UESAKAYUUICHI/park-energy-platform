package com.parkenergyplatform.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import cn.dev33.satoken.stp.StpUtil;
import com.parkenergyplatform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RealtimeSnapshotQueryService {
    private final JdbcTemplate jdbcTemplate;
    private final BusinessDataAccessService accessService;
    private final DataScopeService dataScopeService;
    private final RemoteServiceClient remoteServiceClient;

    public RealtimeSnapshotQueryService(JdbcTemplate jdbcTemplate, BusinessDataAccessService accessService,
                                        DataScopeService dataScopeService, RemoteServiceClient remoteServiceClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessService = accessService;
        this.dataScopeService = dataScopeService;
        this.remoteServiceClient = remoteServiceClient;
    }

    public Map<String, Object> batch(List<Long> deviceIds) {
        for (Long deviceId : deviceIds) {
            accessService.assertDeviceAccess(deviceId);
        }
        return payload(deviceIds);
    }

    public List<Map<String, Object>> byOrg(long orgId) {
        if (!accessService.hasOrgAccess(orgId)) {
            throw new BusinessException(403, "没有该组织的数据访问权限");
        }
        return snapshots(Map.of("orgId", String.valueOf(orgId)));
    }

    public List<Map<String, Object>> snapshots(Map<String, String> params) {
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
        Long orgId = longOrNull(params.get("orgId"));
        boolean includeChildren = Boolean.parseBoolean(Objects.toString(params.getOrDefault("includeChildren", "false")));
        sql.append(accessService.orgFilterSql("d.org_id", orgId, includeChildren, args));
        appendEquals(sql, args, "d.gateway_id", params.get("gatewayId"));
        appendEquals(sql, args, "d.device_type_id", params.get("deviceTypeId"));
        appendKeyword(sql, args, params.get("keyword"), "d.device_sn", "d.device_name", "g.gateway_sn", "o.org_name");
        sql.append(dataScopeService.inClause("d.org_id", dataScopeService.visibleOrgIds(StpUtil.getLoginIdAsLong()), args));
        sql.append(" ORDER BY d.id DESC LIMIT 200");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        if (rows.isEmpty()) {
            return rows;
        }
        Map<String, Object> values;
        try {
            values = payload(rows.stream().map(row -> longOrNull(row.get("id"))).toList());
        } catch (BusinessException unavailable) {
            values = Map.of();
        }
        for (Map<String, Object> row : rows) {
            row.put("realtime", values.getOrDefault(String.valueOf(longOrNull(row.get("id"))), Map.of()));
        }
        return rows;
    }

    public Map<String, Object> realtimeOrEmpty(long deviceId) {
        try {
            return castMap(remoteServiceClient.getDataPayload("/api/data/realtime/devices/" + deviceId));
        } catch (BusinessException unavailable) {
            return Map.of();
        }
    }

    private Map<String, Object> payload(List<Long> deviceIds) {
        if (deviceIds.isEmpty()) return Map.of();
        String ids = deviceIds.stream().filter(Objects::nonNull).map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        return castMap(remoteServiceClient.getDataPayload("/api/data/realtime/devices", Map.of("deviceIds", ids)));
    }

    private void appendEquals(StringBuilder sql, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(" AND ").append(column).append(" = ?");
            args.add(value.trim());
        }
    }

    private void appendKeyword(StringBuilder sql, List<Object> args, String keyword, String... columns) {
        if (keyword == null || keyword.isBlank()) return;
        StringBuilder clause = new StringBuilder(" AND (");
        for (int index = 0; index < columns.length; index++) {
            if (index > 0) clause.append(" OR ");
            clause.append(columns[index]).append(" LIKE ?");
            args.add("%" + keyword.trim() + "%");
        }
        sql.append(clause).append(")");
    }

    private Long longOrNull(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        return Long.valueOf(value.toString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
