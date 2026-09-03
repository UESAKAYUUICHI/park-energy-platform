package com.parkenergyplatform.service;

import com.parkenergyplatform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the billing-only scope of a park space. Energy, alarm and operations
 * modules keep using their organization-tree semantics; this service is only
 * used by billing preparation and contract meter selection.
 */
@Service
public class BillingSpaceScopeService {
    private final JdbcTemplate jdbcTemplate;
    private final BusinessDataAccessService accessService;

    public BillingSpaceScopeService(JdbcTemplate jdbcTemplate, BusinessDataAccessService accessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessService = accessService;
    }

    public List<Map<String, Object>> options() {
        List<Object> orgArgs = new ArrayList<>();
        List<Map<String, Object>> result = new ArrayList<>();
        jdbcTemplate.queryForList("""
                SELECT id AS node_id, org_name AS node_name, org_name AS display_name,
                       'ORG' AS node_type, id AS org_id, parent_id
                FROM dev_org
                WHERE 1=1
                """ + accessService.scopeSql("id", orgArgs) + " ORDER BY parent_id, sort, id", orgArgs.toArray())
                .forEach(row -> result.add(new LinkedHashMap<>(row)));

        List<Object> gatewayArgs = new ArrayList<>();
        jdbcTemplate.queryForList("""
                SELECT g.id AS node_id, g.gateway_name AS node_name,
                       CONCAT(g.gateway_name, ' · ', g.gateway_sn) AS display_name,
                       'GATEWAY' AS node_type, g.org_id
                FROM dev_gateway g
                WHERE g.status = 1
                """ + accessService.scopeSql("g.org_id", gatewayArgs) + " ORDER BY g.org_id, g.id", gatewayArgs.toArray())
                .forEach(row -> result.add(new LinkedHashMap<>(row)));
        return result;
    }

    public List<Map<String, Object>> list(Long spaceId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT x.id, x.space_id, x.scope_type, x.scope_node_id, x.scope_org_id,
                       CASE WHEN x.scope_type='ORG' THEN o.org_name ELSE g.gateway_name END AS node_name,
                       CASE WHEN x.scope_type='ORG' THEN o.org_name
                            ELSE CONCAT(g.gateway_name, ' · ', g.gateway_sn) END AS display_name
                FROM billing_space_scope x
                LEFT JOIN dev_org o ON x.scope_type='ORG' AND o.id=x.scope_node_id
                LEFT JOIN dev_gateway g ON x.scope_type='GATEWAY' AND g.id=x.scope_node_id
                WHERE 1=1
                """);
        if (spaceId != null) {
            accessSpace(spaceId);
            sql.append(" AND x.space_id=?");
            args.add(spaceId);
        }
        sql.append(" ORDER BY x.space_id, x.scope_type, x.scope_node_id");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    @Transactional
    public List<Map<String, Object>> replace(long spaceId, List<Map<String, Object>> input) {
        accessSpace(spaceId);
        Set<String> unique = new LinkedHashSet<>();
        List<ScopeRef> refs = new ArrayList<>();
        for (Map<String, Object> row : input) {
            String type = text(first(row, "scopeType", "scope_type"));
            Long nodeId = longValue(first(row, "scopeNodeId", "scope_node_id"));
            if (!"ORG".equals(type) && !"GATEWAY".equals(type)) {
                throw new BusinessException("计费范围节点类型只能是组织或网关");
            }
            if (nodeId == null) throw new BusinessException("计费范围节点不能为空");
            String key = type + ":" + nodeId;
            if (!unique.add(key)) continue;
            long orgId = validateNode(type, nodeId);
            refs.add(new ScopeRef(type, nodeId, orgId));
        }
        if (refs.isEmpty()) throw new BusinessException("计费空间至少需要映射一个组织或网关节点");
        jdbcTemplate.update("DELETE FROM billing_space_scope WHERE space_id=?", spaceId);
        for (ScopeRef ref : refs) {
            jdbcTemplate.update("""
                    INSERT INTO billing_space_scope
                      (space_id, scope_type, scope_node_id, scope_org_id, create_by, update_by)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, spaceId, ref.type(), ref.nodeId(), ref.orgId(), operator(), operator());
        }
        return list(spaceId);
    }

    public List<Map<String, Object>> devices(List<Long> spaceIds) {
        Set<Long> orgIds = new LinkedHashSet<>();
        Set<Long> gatewayIds = new LinkedHashSet<>();
        for (Long spaceId : spaceIds) {
            if (spaceId == null) continue;
            accessSpace(spaceId);
            List<Map<String, Object>> scopes = jdbcTemplate.queryForList(
                    "SELECT scope_type, scope_node_id FROM billing_space_scope WHERE space_id=?", spaceId);
            for (Map<String, Object> scope : scopes) {
                String type = text(scope.get("scope_type"));
                Long nodeId = longValue(scope.get("scope_node_id"));
                if ("ORG".equals(type)) orgIds.addAll(accessService.orgSubtreeIds(nodeId));
                else if ("GATEWAY".equals(type)) gatewayIds.add(nodeId);
            }
        }
        if (orgIds.isEmpty() && gatewayIds.isEmpty()) return List.of();
        List<String> predicates = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (!orgIds.isEmpty()) {
            predicates.add("d.org_id IN (" + placeholders(orgIds.size()) + ")");
            args.addAll(orgIds);
        }
        if (!gatewayIds.isEmpty()) {
            predicates.add("d.gateway_id IN (" + placeholders(gatewayIds.size()) + ")");
            args.addAll(gatewayIds);
        }
        String visibility = accessService.scopeSql("d.org_id", args);
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT d.id, d.device_sn, d.device_name, d.org_id, d.gateway_id,
                       d.space_id, d.device_type_id, d.model_version_id, d.settlement_enabled,
                       d.meter_role, d.meter_factor, d.status,
                       o.org_name, g.gateway_name, g.gateway_sn,
                       t.type_name, t.type_code
                FROM dev_device d
                LEFT JOIN dev_org o ON o.id=d.org_id
                LEFT JOIN dev_gateway g ON g.id=d.gateway_id
                LEFT JOIN dev_device_type t ON t.id=d.device_type_id
                WHERE d.status=1 AND (""" + String.join(" OR ", predicates) + ")" + visibility
                + " ORDER BY o.org_name, d.device_name, d.id", args.toArray());
    }

    public void assertDevicesWithinSpaces(List<Long> spaceIds, List<Long> deviceIds) {
        if (spaceIds == null || spaceIds.isEmpty() || deviceIds == null || deviceIds.isEmpty())
            throw new BusinessException("合同必须选择空间和结算设备");
        Set<Long> allowed = new LinkedHashSet<>();
        for (Map<String, Object> row : devices(spaceIds)) {
            Long id = longValue(row.get("id"));
            if (id != null) allowed.add(id);
        }
        for (Long deviceId : deviceIds) {
            if (deviceId == null || !allowed.contains(deviceId))
                throw new BusinessException("合同设备不在所选空间的组织/网关映射范围内");
        }
    }

    private long validateNode(String type, long nodeId) {
        if ("ORG".equals(type)) {
            Map<String, Object> row = single("SELECT id FROM dev_org WHERE id=?", nodeId);
            accessService.orgFilterSql("id", ((Number) row.get("id")).longValue(), false, new ArrayList<>());
            if (!accessService.hasOrgAccess(nodeId)) throw new BusinessException(403, "没有该组织节点的数据权限");
            return nodeId;
        }
        Map<String, Object> gateway = single("SELECT id, org_id FROM dev_gateway WHERE id=? AND status=1", nodeId);
        long orgId = ((Number) gateway.get("org_id")).longValue();
        if (!accessService.hasGatewayAccess(nodeId)) throw new BusinessException(403, "没有该网关节点的数据权限");
        return orgId;
    }

    private void accessSpace(long spaceId) {
        Map<String, Object> row = single("SELECT id, org_id, status FROM park_space WHERE id=?", spaceId);
        if ("DISABLED".equalsIgnoreCase(text(row.get("status"))) || "INACTIVE".equalsIgnoreCase(text(row.get("status"))))
            throw new BusinessException("已停用空间不能配置计费映射");
        Long orgId = longValue(row.get("org_id"));
        if (!accessService.hasOrgAccess(orgId)) throw new BusinessException(403, "没有该空间的数据权限");
    }

    private Map<String, Object> single(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        if (rows.isEmpty()) throw new BusinessException(404, "映射节点不存在或已停用");
        return rows.get(0);
    }

    private String placeholders(int count) { return String.join(",", java.util.Collections.nCopies(count, "?")); }
    private Object first(Map<String, Object> row, String primary, String fallback) { return row.containsKey(primary) ? row.get(primary) : row.get(fallback); }
    private String text(Object value) { return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim().toUpperCase(); }
    private Long longValue(Object value) { return value == null || String.valueOf(value).isBlank() ? null : Long.valueOf(String.valueOf(value)); }
    private String operator() { return cn.dev33.satoken.stp.StpUtil.isLogin() ? String.valueOf(cn.dev33.satoken.stp.StpUtil.getLoginId()) : "system"; }
    private record ScopeRef(String type, long nodeId, long orgId) {}
}
