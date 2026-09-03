package com.parkenergyplatform.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import cn.dev33.satoken.stp.StpUtil;
import com.parkenergyplatform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class BusinessDataAccessService {
    private static final ThreadLocal<Boolean> SYSTEM_EXECUTION = ThreadLocal.withInitial(() -> false);
    private final JdbcTemplate jdbcTemplate;
    private final DataScopeService dataScopeService;

    public BusinessDataAccessService(JdbcTemplate jdbcTemplate, DataScopeService dataScopeService) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataScopeService = dataScopeService;
    }

    public <T> T runAsSystem(Supplier<T> action) {
        boolean previous = SYSTEM_EXECUTION.get();
        SYSTEM_EXECUTION.set(true);
        try {
            return action.get();
        } finally {
            SYSTEM_EXECUTION.set(previous);
        }
    }

    public boolean isSystemExecution() {
        return Boolean.TRUE.equals(SYSTEM_EXECUTION.get());
    }

    public void assertDeviceAccess(Long deviceId) {
        if (deviceId == null) {
            throw new BusinessException("deviceId 不能为空");
        }
        Long orgId = findOrgId("SELECT org_id FROM dev_device WHERE id = ?", deviceId);
        assertOrgAccess(orgId, "没有该设备的数据访问权限");
    }

    public void assertGatewayAccess(Long gatewayId) {
        if (gatewayId == null) {
            throw new BusinessException("gatewayId 不能为空");
        }
        Long orgId = findOrgId("SELECT org_id FROM dev_gateway WHERE id = ?", gatewayId);
        assertOrgAccess(orgId, "没有该网关的数据访问权限");
    }

    public void assertAlarmAccess(long alarmId) {
        Long orgId = findOrgId("SELECT org_id FROM log_alarm WHERE id = ?", alarmId);
        assertOrgAccess(orgId, "没有该告警的数据操作权限");
    }

    public void assertBillingAccountAccess(long accountId) {
        Long orgId = findOrgId("SELECT org_id FROM billing_account WHERE id = ?", accountId);
        assertOrgAccess(orgId, "没有该计费账号的数据操作权限");
    }

    public void assertBillAccess(long billId) {
        Long orgId = findOrgId("""
                SELECT a.org_id
                FROM billing_bill b
                JOIN billing_account a ON a.id = b.account_id
                WHERE b.id = ?
                """, billId);
        assertOrgAccess(orgId, "没有该账单的数据操作权限");
    }

    public void assertCommandAccess(String commandId) {
        if (commandId == null || commandId.isBlank()) {
            throw new BusinessException("commandId 不能为空");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT c.gateway_id, c.target_type, c.target_id, g.org_id AS gateway_org_id, d.org_id AS device_org_id
                FROM command_record c
                LEFT JOIN dev_gateway g ON g.id = c.gateway_id
                LEFT JOIN dev_device d ON d.id = c.target_id
                WHERE c.command_id = ?
                LIMIT 1
                """, commandId);
        if (rows.isEmpty()) {
            throw new BusinessException(404, "指令不存在: " + commandId);
        }
        Map<String, Object> row = rows.get(0);
        Long orgId = longOrNull(row.get("device_org_id"));
        if (orgId == null) {
            orgId = longOrNull(row.get("gateway_org_id"));
        }
        assertOrgAccess(orgId, "没有该指令的数据访问权限");
    }

    public void assertCommandRequestAccess(Map<String, Object> body) {
        Long gatewayId = longOrNull(body.get("gatewayId"));
        assertGatewayAccess(gatewayId);
        String targetType = Objects.toString(body.get("targetType"), "");
        Long targetId = longOrNull(body.get("targetId"));
        if ("DEVICE".equalsIgnoreCase(targetType) && targetId != null) {
            assertDeviceAccess(targetId);
        }
    }

    public boolean hasDeviceAccess(Long deviceId) {
        if (deviceId == null) {
            return false;
        }
        Long orgId = findOrgIdOrNull("SELECT org_id FROM dev_device WHERE id = ?", deviceId);
        return hasOrgAccess(orgId);
    }

    public boolean hasGatewayAccess(Long gatewayId) {
        if (gatewayId == null) {
            return false;
        }
        Long orgId = findOrgIdOrNull("SELECT org_id FROM dev_gateway WHERE id = ?", gatewayId);
        return hasOrgAccess(orgId);
    }

    public boolean hasOrgAccess(Long orgId) {
        if (isSystemExecution()) return orgId != null;
        return orgId != null && dataScopeService.hasOrgAccess(StpUtil.getLoginIdAsLong(), orgId);
    }

    public String scopeSql(String orgColumn, List<Object> args) {
        if (isSystemExecution()) return "";
        return dataScopeService.inClause(orgColumn, dataScopeService.visibleOrgIds(StpUtil.getLoginIdAsLong()), args);
    }

    public String orgFilterSql(String orgColumn, Long orgId, boolean includeChildren, List<Object> args) {
        if (orgId == null) {
            return "";
        }
        if (!isSystemExecution()) assertOrgAccess(orgId, "没有该组织的数据访问权限");
        if (!includeChildren) {
            args.add(orgId);
            return " AND " + orgColumn + " = ?";
        }
        Set<Long> ids = new LinkedHashSet<>(orgSubtreeIds(orgId));
        Set<Long> visible = isSystemExecution() ? null : dataScopeService.visibleOrgIds(StpUtil.getLoginIdAsLong());
        if (visible != null) {
            ids.retainAll(visible);
        }
        return dataScopeService.inClause(orgColumn, ids, args);
    }

    public String orgFilterSql(String orgColumn, List<Long> orgIds, boolean includeChildren, List<Object> args) {
        if (orgIds == null || orgIds.isEmpty()) {
            return "";
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (Long orgId : orgIds) {
            if (orgId == null) {
                continue;
            }
            if (!isSystemExecution()) {
                assertOrgAccess(orgId, "没有该组织的数据访问权限");
            }
            if (includeChildren) {
                ids.addAll(orgSubtreeIds(orgId));
            } else {
                ids.add(orgId);
            }
        }
        Set<Long> visible = isSystemExecution() ? null : dataScopeService.visibleOrgIds(StpUtil.getLoginIdAsLong());
        if (visible != null) {
            ids.retainAll(visible);
        }
        return dataScopeService.inClause(orgColumn, ids, args);
    }

    public List<Long> orgSubtreeIds(Long rootOrgId) {
        if (rootOrgId == null) {
            return List.of();
        }
        return dataScopeService.orgSubtreeIds(rootOrgId);
    }

    public Long longOrNull(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return Long.valueOf(value.toString());
    }

    public List<Map<String, Object>> filterByGatewayAccess(List<Map<String, Object>> rows) {
        Set<Long> gatewayIds = rows.stream().map(row -> longOrNull(row.get("gatewayId")))
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<Long> allowedIds = accessibleIds("dev_gateway", gatewayIds);
        return rows.stream().filter(row -> allowedIds.contains(longOrNull(row.get("gatewayId")))).toList();
    }

    public List<Map<String, Object>> filterCommands(List<Map<String, Object>> rows) {
        Set<Long> deviceIds = rows.stream()
                .filter(row -> "DEVICE".equalsIgnoreCase(Objects.toString(row.get("targetType"), "")))
                .map(row -> longOrNull(row.get("targetId"))).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<Long> gatewayIds = rows.stream().map(row -> longOrNull(row.get("gatewayId")))
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<Long> allowedDeviceIds = accessibleIds("dev_device", deviceIds);
        Set<Long> allowedGatewayIds = accessibleIds("dev_gateway", gatewayIds);
        return rows.stream().filter(row -> {
            Long targetId = longOrNull(row.get("targetId"));
            if ("DEVICE".equalsIgnoreCase(Objects.toString(row.get("targetType"), "")) && targetId != null) {
                return allowedDeviceIds.contains(targetId);
            }
            return allowedGatewayIds.contains(longOrNull(row.get("gatewayId")));
        }).toList();
    }

    private Set<Long> accessibleIds(String table, Set<Long> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        Set<Long> visible = dataScopeService.visibleOrgIds(StpUtil.getLoginIdAsLong());
        if (visible == null) {
            return ids;
        }
        if (visible.isEmpty()) {
            return Set.of();
        }
        List<Object> args = new ArrayList<>(ids);
        String idPlaceholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String scope = dataScopeService.inClause("org_id", visible, args);
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT id FROM " + table + " WHERE id IN (" + idPlaceholders + ")" + scope,
                Long.class, args.toArray()));
    }

    private Long findOrgId(String sql, Object... args) {
        Long orgId = findOrgIdOrNull(sql, args);
        if (orgId == null) {
            throw new BusinessException(404, "数据不存在");
        }
        return orgId;
    }

    private Long findOrgIdOrNull(String sql, Object... args) {
        List<Long> rows = jdbcTemplate.queryForList(sql, Long.class, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void assertOrgAccess(Long orgId, String message) {
        if (orgId == null) {
            throw new BusinessException(404, "数据不存在");
        }
        if (!isSystemExecution() && !hasOrgAccess(orgId)) {
            throw new BusinessException(403, message);
        }
    }
}
