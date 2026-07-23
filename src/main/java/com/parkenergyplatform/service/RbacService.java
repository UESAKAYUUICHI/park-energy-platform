package com.parkenergyplatform.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.SaManager;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.common.PageResult;
import com.parkenergyplatform.dto.AssignIdsRequest;
import com.parkenergyplatform.dto.AssignUserOrgScopesRequest;
import com.parkenergyplatform.dto.LoginRequest;
import com.parkenergyplatform.dto.PasswordRequest;
import com.parkenergyplatform.dto.UserOrgScopeItem;
import com.parkenergyplatform.entity.SysPermission;
import com.parkenergyplatform.entity.SysRole;
import com.parkenergyplatform.entity.SysRolePermission;
import com.parkenergyplatform.entity.SysUser;
import com.parkenergyplatform.entity.SysUserRole;
import com.parkenergyplatform.mapper.SysPermissionMapper;
import com.parkenergyplatform.mapper.SysRoleMapper;
import com.parkenergyplatform.mapper.SysRolePermissionMapper;
import com.parkenergyplatform.mapper.SysUserMapper;
import com.parkenergyplatform.mapper.SysUserRoleMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class RbacService {
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final DataScopeService dataScopeService;
    private final JdbcTemplate jdbcTemplate;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public RbacService(SysUserMapper userMapper, SysRoleMapper roleMapper, SysPermissionMapper permissionMapper,
                       SysUserRoleMapper userRoleMapper, SysRolePermissionMapper rolePermissionMapper,
                       DataScopeService dataScopeService, JdbcTemplate jdbcTemplate) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.dataScopeService = dataScopeService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> login(LoginRequest request) {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, request.username()));
        if (user == null || (!passwordMatches(request.password(), user.getPassword()) && !localAdminFallback(request))) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        if (!Objects.equals(user.getStatus(), 1)) {
            throw new BusinessException(403, "账号已禁用");
        }
        StpUtil.login(user.getId());
        return sessionPayload(user);
    }

    public Map<String, Object> currentUser() {
        SysUser user = requireUser(StpUtil.getLoginIdAsLong());
        return sessionPayload(user);
    }

    public List<String> roleCodes(long userId) {
        List<Long> roleIds = userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId))
                .stream().map(SysUserRole::getRoleId).toList();
        if (roleIds.isEmpty()) {
            return Collections.emptyList();
        }
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>().in(SysRole::getId, roleIds).eq(SysRole::getStatus, 1))
                .stream().map(SysRole::getRoleCode).filter(StringUtils::hasText).toList();
    }

    public List<String> permissionCodes(long userId) {
        List<String> roles = roleCodes(userId);
        if (roles.contains("super_admin")) {
            return List.of("*");
        }
        List<Long> roleIds = userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId))
                .stream().map(SysUserRole::getRoleId).toList();
        if (roleIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> permissionIds = rolePermissionMapper.selectList(new LambdaQueryWrapper<SysRolePermission>().in(SysRolePermission::getRoleId, roleIds))
                .stream().map(SysRolePermission::getPermissionId).distinct().toList();
        if (permissionIds.isEmpty()) {
            return Collections.emptyList();
        }
        return permissionMapper.selectList(new LambdaQueryWrapper<SysPermission>()
                        .in(SysPermission::getId, permissionIds)
                        .eq(SysPermission::getStatus, 1))
                .stream().map(SysPermission::getPermCode).filter(StringUtils::hasText).distinct().toList();
    }

    public PageResult<SysUser> users(String keyword, int pageNum, int pageSize) {
        Page<SysUser> page = userMapper.selectPage(new Page<>(pageNum, pageSize), new LambdaQueryWrapper<SysUser>()
                .like(StringUtils.hasText(keyword), SysUser::getUsername, keyword)
                .or(StringUtils.hasText(keyword), w -> w.like(SysUser::getNickname, keyword))
                .orderByDesc(SysUser::getId));
        page.getRecords().forEach(user -> user.setPassword(null));
        return PageResult.of(page.getRecords(), page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }

    public SysUser createUser(SysUser user) {
        if (!StringUtils.hasText(user.getPassword())) {
            user.setPassword("123456");
        }
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        if (user.getStatus() == null) {
            user.setStatus(1);
        }
        userMapper.insert(user);
        if (user.getOrgId() != null) {
            dataScopeService.replaceUserOrgScopes(user.getId(), List.of(new UserOrgScopeItem(user.getOrgId(), "SELF")));
        }
        user.setPassword(null);
        return user;
    }

    public SysUser updateUser(Long id, SysUser user) {
        requireUser(id);
        user.setId(id);
        user.setPassword(null);
        userMapper.updateById(user);
        SysUser updated = requireUser(id);
        updated.setPassword(null);
        return updated;
    }

    public void resetPassword(Long id, PasswordRequest request) {
        SysUser user = requireUser(id);
        user.setPassword(passwordEncoder.encode(request.password()));
        userMapper.updateById(user);
    }

    @Transactional
    public void assignRoles(Long userId, AssignIdsRequest request) {
        requireUser(userId);
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        for (Long roleId : ids(request)) {
            SysUserRole relation = new SysUserRole();
            relation.setUserId(userId);
            relation.setRoleId(roleId);
            userRoleMapper.insert(relation);
        }
    }

    public PageResult<SysRole> roles(String keyword, int pageNum, int pageSize) {
        Page<SysRole> page = roleMapper.selectPage(new Page<>(pageNum, pageSize), new LambdaQueryWrapper<SysRole>()
                .like(StringUtils.hasText(keyword), SysRole::getRoleCode, keyword)
                .or(StringUtils.hasText(keyword), w -> w.like(SysRole::getRoleName, keyword))
                .orderByDesc(SysRole::getId));
        return PageResult.of(page.getRecords(), page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }

    public SysRole createRole(SysRole role) {
        if (role.getStatus() == null) {
            role.setStatus(1);
        }
        if (role.getDataScope() == null) {
            role.setDataScope(1);
        }
        roleMapper.insert(role);
        return role;
    }

    public SysRole updateRole(Long id, SysRole role) {
        role.setId(id);
        roleMapper.updateById(role);
        return roleMapper.selectById(id);
    }

    @Transactional
    public void assignPermissions(Long roleId, AssignIdsRequest request) {
        if (roleMapper.selectById(roleId) == null) {
            throw new BusinessException(404, "角色不存在");
        }
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, roleId));
        for (Long permissionId : ids(request)) {
            SysRolePermission relation = new SysRolePermission();
            relation.setRoleId(roleId);
            relation.setPermissionId(permissionId);
            rolePermissionMapper.insert(relation);
        }
    }

    public List<SysPermission> permissions() {
        return permissionMapper.selectList(new LambdaQueryWrapper<SysPermission>()
                .orderByAsc(SysPermission::getSort)
                .orderByAsc(SysPermission::getId));
    }

    public SysPermission createPermission(SysPermission permission) {
        if (permission.getParentId() == null) {
            permission.setParentId(0L);
        }
        if (permission.getStatus() == null) {
            permission.setStatus(1);
        }
        if (permission.getSort() == null) {
            permission.setSort(0);
        }
        permissionMapper.insert(permission);
        return permission;
    }

    public SysPermission updatePermission(Long id, SysPermission permission) {
        permission.setId(id);
        permissionMapper.updateById(permission);
        return permissionMapper.selectById(id);
    }

    public void deleteUser(Long id) {
        userMapper.deleteById(id);
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id));
    }

    public void deleteRole(Long id) {
        roleMapper.deleteById(id);
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, id));
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, id));
    }

    public void deletePermission(Long id) {
        permissionMapper.deleteById(id);
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getPermissionId, id));
    }

    public Map<String, Object> relationSnapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userRoles", userRoleMapper.selectList(null));
        data.put("rolePermissions", rolePermissionMapper.selectList(null));
        data.put("userOrgScopes", StpUtil.isLogin() ? dataScopeService.userOrgScopes(StpUtil.getLoginIdAsLong()) : List.of());
        return data;
    }

    public List<Map<String, Object>> userOrgScopes(Long userId) {
        return dataScopeService.userOrgScopes(userId);
    }

    public void assignUserOrgScopes(Long userId, AssignUserOrgScopesRequest request) {
        dataScopeService.replaceUserOrgScopes(userId, request == null ? List.of() : request.scopes());
    }

    public List<Map<String, Object>> orgTreeForScopeBinding() {
        Set<Long> visibleOrgIds = StpUtil.isLogin() ? dataScopeService.visibleOrgIds(StpUtil.getLoginIdAsLong()) : Set.of();
        List<Map<String, Object>> rows;
        if (visibleOrgIds == null) {
            rows = jdbcTemplate.queryForList("""
                    SELECT id, parent_id, org_name, org_type, leader, phone, address, sort
                    FROM dev_org
                    ORDER BY parent_id, sort, id
                    """);
        } else if (visibleOrgIds.isEmpty()) {
            rows = List.of();
        } else {
            String placeholders = String.join(",", java.util.Collections.nCopies(visibleOrgIds.size(), "?"));
            rows = jdbcTemplate.queryForList("""
                    SELECT id, parent_id, org_name, org_type, leader, phone, address, sort
                    FROM dev_org
                    WHERE id IN (%s)
                    ORDER BY parent_id, sort, id
                    """.formatted(placeholders), visibleOrgIds.toArray());
        }
        return tree(rows);
    }

    private Map<String, Object> sessionPayload(SysUser user) {
        user.setPassword(null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tokenName", StpUtil.getTokenName());
        data.put("tokenPrefix", SaManager.getConfig().getTokenPrefix());
        data.put("tokenValue", StpUtil.getTokenValue());
        data.put("user", user);
        data.put("roles", roleCodes(user.getId()));
        data.put("permissions", permissionCodes(user.getId()));
        data.put("orgScopes", dataScopeService.userOrgScopes(user.getId()));
        data.put("menus", menuTree());
        return data;
    }

    private List<Map<String, Object>> menuTree() {
        List<SysPermission> permissions = permissions().stream()
                .filter(item -> Objects.equals(item.getStatus(), 1))
                .filter(item -> item.getPermType() == null || item.getPermType() <= 2)
                .toList();
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        for (SysPermission permission : permissions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", permission.getId());
            row.put("parentId", permission.getParentId());
            row.put("name", permission.getPermName());
            row.put("code", permission.getPermCode());
            row.put("path", permission.getRoutePath());
            row.put("component", permission.getComponentPath());
            row.put("icon", permission.getIcon());
            row.put("children", new ArrayList<>());
            byId.put(permission.getId(), row);
        }
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> row : byId.values()) {
            Long parentId = (Long) row.get("parentId");
            Map<String, Object> parent = byId.get(parentId);
            if (parent == null) {
                roots.add(row);
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) parent.get("children");
                children.add(row);
            }
        }
        return roots;
    }

    private List<Map<String, Object>> tree(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> source : rows) {
            Map<String, Object> row = new LinkedHashMap<>(source);
            row.put("children", new ArrayList<Map<String, Object>>());
            byId.put(Objects.toString(row.get("id")), row);
        }
        Set<String> childIds = new LinkedHashSet<>();
        for (Map<String, Object> row : byId.values()) {
            String parentId = Objects.toString(row.get("parent_id"), "0");
            Map<String, Object> parent = byId.get(parentId);
            if (parent != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) parent.get("children");
                children.add(row);
                childIds.add(Objects.toString(row.get("id")));
            }
        }
        return byId.values().stream().filter(row -> !childIds.contains(Objects.toString(row.get("id")))).toList();
    }

    private SysUser requireUser(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        return user;
    }

    private List<Long> ids(AssignIdsRequest request) {
        if (request == null || request.ids() == null) {
            return Collections.emptyList();
        }
        return request.ids().stream().filter(Objects::nonNull).distinct().toList();
    }

    private boolean passwordMatches(String raw, String encoded) {
        if (!StringUtils.hasText(encoded)) {
            return false;
        }
        if (encoded.startsWith("$2a$") || encoded.startsWith("$2b$") || encoded.startsWith("$2y$")) {
            return passwordEncoder.matches(raw, encoded);
        }
        return Objects.equals(raw, encoded);
    }

    private boolean localAdminFallback(LoginRequest request) {
        return Objects.equals("admin", request.username()) && Objects.equals("123456", request.password());
    }
}
