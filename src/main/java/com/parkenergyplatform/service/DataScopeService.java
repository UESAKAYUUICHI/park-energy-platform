package com.parkenergyplatform.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.dto.UserOrgScopeItem;
import com.parkenergyplatform.entity.SysUser;
import com.parkenergyplatform.entity.SysUserOrgScope;
import com.parkenergyplatform.mapper.SysUserMapper;
import com.parkenergyplatform.mapper.SysUserOrgScopeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataScopeService {
    private final JdbcTemplate jdbcTemplate;
    private final SysUserMapper sysUserMapper;
    private final SysUserOrgScopeMapper scopeMapper;

    public DataScopeService(JdbcTemplate jdbcTemplate, SysUserMapper sysUserMapper, SysUserOrgScopeMapper scopeMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.sysUserMapper = sysUserMapper;
        this.scopeMapper = scopeMapper;
    }

    public boolean isSuperAdmin(long userId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_user_role ur
                JOIN sys_role r ON r.id = ur.role_id
                WHERE ur.user_id = ? AND r.role_code = 'super_admin' AND r.status = 1
                """, Long.class, userId);
        return count != null && count > 0;
    }

    public Long userOrgId(long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        return user == null ? null : user.getOrgId();
    }

    public List<Map<String, Object>> userOrgScopes(long userId) {
        return jdbcTemplate.queryForList("""
                SELECT s.id, s.user_id, s.org_id, s.scope_mode, s.create_time,
                       o.org_name, o.parent_id, o.org_type
                FROM sys_user_org_scope s
                LEFT JOIN dev_org o ON o.id = s.org_id
                WHERE s.user_id = ?
                ORDER BY s.id
                """, userId);
    }

    public Set<Long> visibleOrgIds(long userId) {
        if (isSuperAdmin(userId)) {
            return null;
        }
        List<ScopeNode> roots = scopeRoots(userId);
        if (roots.isEmpty()) {
            Long orgId = userOrgId(userId);
            if (orgId == null) {
                return Set.of();
            }
            roots = List.of(new ScopeNode(orgId, "SELF"));
        }
        Map<Long, List<Long>> children = loadOrgChildren();
        Set<Long> visible = new LinkedHashSet<>();
        for (ScopeNode root : roots) {
            if ("SUBTREE".equalsIgnoreCase(root.scopeMode())) {
                expand(root.orgId(), children, visible);
            } else {
                visible.add(root.orgId());
            }
        }
        return visible;
    }

    public boolean hasOrgAccess(long userId, long orgId) {
        Set<Long> visible = visibleOrgIds(userId);
        return visible == null || visible.contains(orgId);
    }

    public List<Long> orgSubtreeIds(long rootOrgId) {
        Map<Long, List<Long>> children = loadOrgChildren();
        Set<Long> visible = new LinkedHashSet<>();
        expand(rootOrgId, children, visible);
        return new ArrayList<>(visible);
    }

    public String inClause(String column, Set<Long> ids, List<Object> args) {
        if (ids == null) {
            return "";
        }
        if (ids.isEmpty()) {
            return " AND 1 = 0";
        }
        StringBuilder sql = new StringBuilder(" AND ").append(column).append(" IN (");
        boolean first = true;
        for (Long id : ids) {
            if (!first) {
                sql.append(", ");
            }
            sql.append("?");
            args.add(id);
            first = false;
        }
        sql.append(")");
        return sql.toString();
    }

    @Transactional
    public void replaceUserOrgScopes(long userId, List<UserOrgScopeItem> scopes) {
        if (sysUserMapper.selectById(userId) == null) {
            throw new BusinessException(404, "用户不存在");
        }
        scopeMapper.delete(new LambdaQueryWrapper<SysUserOrgScope>().eq(SysUserOrgScope::getUserId, userId));
        if (scopes == null || scopes.isEmpty()) {
            return;
        }
        Set<String> uniqueScopes = new LinkedHashSet<>();
        for (UserOrgScopeItem item : scopes) {
            if (item == null || item.orgId() == null) {
                continue;
            }
            if (!orgExists(item.orgId())) {
                throw new BusinessException(404, "组织不存在: " + item.orgId());
            }
            String scopeMode = normalizeScopeMode(item.scopeMode());
            if (!uniqueScopes.add(item.orgId() + ":" + scopeMode)) {
                continue;
            }
            SysUserOrgScope scope = new SysUserOrgScope();
            scope.setUserId(userId);
            scope.setOrgId(item.orgId());
            scope.setScopeMode(scopeMode);
            scopeMapper.insert(scope);
        }
    }

    private List<ScopeNode> scopeRoots(long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT s.org_id, s.scope_mode
                FROM sys_user_org_scope s
                WHERE s.user_id = ?
                ORDER BY s.id
                """, userId);
        List<ScopeNode> roots = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long orgId = longValue(row.get("org_id"));
            if (orgId != null) {
                roots.add(new ScopeNode(orgId, Objects.toString(row.get("scope_mode"), "SELF")));
            }
        }
        return roots;
    }

    private Map<Long, List<Long>> loadOrgChildren() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id, parent_id FROM dev_org ORDER BY parent_id, sort, id");
        Map<Long, List<Long>> children = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Long id = longValue(row.get("id"));
            Long parentId = longValue(row.get("parent_id"));
            if (id == null) {
                continue;
            }
            children.computeIfAbsent(parentId == null ? 0L : parentId, key -> new ArrayList<>()).add(id);
        }
        return children;
    }

    private void expand(Long orgId, Map<Long, List<Long>> children, Set<Long> visible) {
        ArrayDeque<Long> stack = new ArrayDeque<>();
        stack.push(orgId);
        while (!stack.isEmpty()) {
            Long current = stack.pop();
            if (!visible.add(current)) {
                continue;
            }
            for (Long child : children.getOrDefault(current, List.of())) {
                stack.push(child);
            }
        }
    }

    private boolean orgExists(Long orgId) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dev_org WHERE id = ?", Long.class, orgId);
        return count != null && count > 0;
    }

    private String normalizeScopeMode(String scopeMode) {
        String value = scopeMode == null ? "SELF" : scopeMode.trim().toUpperCase();
        if (!"SELF".equals(value) && !"SUBTREE".equals(value)) {
            throw new BusinessException("scopeMode 仅支持 SELF 或 SUBTREE");
        }
        return value;
    }

    private Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        return Long.valueOf(value.toString());
    }

    private record ScopeNode(Long orgId, String scopeMode) {
    }
}
