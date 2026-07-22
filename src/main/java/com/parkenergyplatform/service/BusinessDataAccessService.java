package com.parkenergyplatform.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import cn.dev33.satoken.stp.StpUtil;
import com.parkenergyplatform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class BusinessDataAccessService {
    private final JdbcTemplate jdbcTemplate;
    private final DataScopeService dataScopeService;

    public BusinessDataAccessService(JdbcTemplate jdbcTemplate, DataScopeService dataScopeService) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataScopeService = dataScopeService;
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
        return orgId != null && dataScopeService.hasOrgAccess(StpUtil.getLoginIdAsLong(), orgId);
    }

    public String scopeSql(String orgColumn, List<Object> args) {
        return dataScopeService.inClause(orgColumn, dataScopeService.visibleOrgIds(StpUtil.getLoginIdAsLong()), args);
    }

    public String orgFilterSql(String orgColumn, Long orgId, boolean includeChildren, List<Object> args) {
        if (orgId == null) {
            return "";
        }
        assertOrgAccess(orgId, "没有该组织的数据访问权限");
        if (!includeChildren) {
            args.add(orgId);
            return " AND " + orgColumn + " = ?";
        }
        Set<Long> ids = new LinkedHashSet<>(orgSubtreeIds(orgId));
        Set<Long> visible = dataScopeService.visibleOrgIds(StpUtil.getLoginIdAsLong());
        if (visible != null) {
            ids.retainAll(visible);
        }
        return dataScopeService.inClause(orgColumn, ids, args);
    }

    public List<Long> orgSubtreeIds(Long rootOrgId) {
        if (rootOrgId == null) {
            return List.of();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id, parent_id FROM dev_org ORDER BY parent_id, sort, id");
        Map<Long, List<Long>> children = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Long id = longOrNull(row.get("id"));
            Long parentId = longOrNull(row.get("parent_id"));
            if (id != null) {
                children.computeIfAbsent(parentId == null ? 0L : parentId, key -> new ArrayList<>()).add(id);
            }
        }
        Set<Long> result = new LinkedHashSet<>();
        ArrayDeque<Long> stack = new ArrayDeque<>();
        stack.push(rootOrgId);
        while (!stack.isEmpty()) {
            Long current = stack.pop();
            if (!result.add(current)) {
                continue;
            }
            for (Long child : children.getOrDefault(current, List.of())) {
                stack.push(child);
            }
        }
        return new ArrayList<>(result);
    }

    public Long longOrNull(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return Long.valueOf(value.toString());
    }

    public List<Map<String, Object>> filterByGatewayAccess(List<Map<String, Object>> rows) {
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (hasGatewayAccess(longOrNull(row.get("gatewayId")))) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    public List<Map<String, Object>> filterCommands(List<Map<String, Object>> rows) {
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long targetId = longOrNull(row.get("targetId"));
            String targetType = Objects.toString(row.get("targetType"), "");
            Long gatewayId = longOrNull(row.get("gatewayId"));
            if ("DEVICE".equalsIgnoreCase(targetType) && targetId != null) {
                if (hasDeviceAccess(targetId)) {
                    filtered.add(row);
                }
            } else if (hasGatewayAccess(gatewayId)) {
                filtered.add(row);
            }
        }
        return filtered;
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
        if (!hasOrgAccess(orgId)) {
            throw new BusinessException(403, message);
        }
    }
}
