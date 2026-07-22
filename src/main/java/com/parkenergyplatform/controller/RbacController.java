package com.parkenergyplatform.controller;

import java.util.List;
import java.util.Map;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.common.PageResult;
import com.parkenergyplatform.dto.AssignIdsRequest;
import com.parkenergyplatform.dto.AssignUserOrgScopesRequest;
import com.parkenergyplatform.dto.PasswordRequest;
import com.parkenergyplatform.entity.SysPermission;
import com.parkenergyplatform.entity.SysRole;
import com.parkenergyplatform.entity.SysUser;
import com.parkenergyplatform.service.RbacService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/rbac")
public class RbacController {
    private final RbacService rbacService;

    public RbacController(RbacService rbacService) {
        this.rbacService = rbacService;
    }

    @GetMapping("/users")
    @SaCheckPermission("system:user:list")
    public ApiResponse<PageResult<SysUser>> users(@RequestParam(required = false) String keyword,
                                                  @RequestParam(defaultValue = "1") int pageNum,
                                                  @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(rbacService.users(keyword, pageNum, pageSize));
    }

    @PostMapping("/users")
    @SaCheckPermission("system:user:add")
    @OperationLog(module = "权限管理", operation = "新增用户")
    public ApiResponse<SysUser> createUser(@RequestBody SysUser user) {
        return ApiResponse.success(rbacService.createUser(user));
    }

    @PutMapping("/users/{id}")
    @SaCheckPermission("system:user:edit")
    @OperationLog(module = "权限管理", operation = "修改用户")
    public ApiResponse<SysUser> updateUser(@PathVariable Long id, @RequestBody SysUser user) {
        return ApiResponse.success(rbacService.updateUser(id, user));
    }

    @DeleteMapping("/users/{id}")
    @SaCheckPermission("system:user:delete")
    @OperationLog(module = "权限管理", operation = "删除用户")
    public ApiResponse<Void> deleteUser(@PathVariable Long id) {
        rbacService.deleteUser(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/users/{id}/password")
    @SaCheckPermission("system:user:edit")
    @OperationLog(module = "权限管理", operation = "重置密码")
    public ApiResponse<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody PasswordRequest request) {
        rbacService.resetPassword(id, request);
        return ApiResponse.success(null);
    }

    @PostMapping("/users/{id}/roles")
    @SaCheckPermission("system:user:edit")
    @OperationLog(module = "权限管理", operation = "分配用户角色")
    public ApiResponse<Void> assignRoles(@PathVariable Long id, @RequestBody AssignIdsRequest request) {
        rbacService.assignRoles(id, request);
        return ApiResponse.success(null);
    }

    @GetMapping("/users/{id}/org-scopes")
    @SaCheckPermission("system:user:scope:list")
    public ApiResponse<List<Map<String, Object>>> userOrgScopes(@PathVariable Long id) {
        return ApiResponse.success(rbacService.userOrgScopes(id));
    }

    @PutMapping("/users/{id}/org-scopes")
    @SaCheckPermission("system:user:scope:edit")
    @OperationLog(module = "权限管理", operation = "分配用户组织范围")
    public ApiResponse<Void> assignUserOrgScopes(@PathVariable Long id, @jakarta.validation.Valid @RequestBody AssignUserOrgScopesRequest request) {
        rbacService.assignUserOrgScopes(id, request);
        return ApiResponse.success(null);
    }

    @GetMapping("/roles")
    @SaCheckPermission("system:role:list")
    public ApiResponse<PageResult<SysRole>> roles(@RequestParam(required = false) String keyword,
                                                  @RequestParam(defaultValue = "1") int pageNum,
                                                  @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(rbacService.roles(keyword, pageNum, pageSize));
    }

    @PostMapping("/roles")
    @SaCheckPermission("system:role:add")
    @OperationLog(module = "权限管理", operation = "新增角色")
    public ApiResponse<SysRole> createRole(@RequestBody SysRole role) {
        return ApiResponse.success(rbacService.createRole(role));
    }

    @PutMapping("/roles/{id}")
    @SaCheckPermission("system:role:edit")
    @OperationLog(module = "权限管理", operation = "修改角色")
    public ApiResponse<SysRole> updateRole(@PathVariable Long id, @RequestBody SysRole role) {
        return ApiResponse.success(rbacService.updateRole(id, role));
    }

    @DeleteMapping("/roles/{id}")
    @SaCheckPermission("system:role:delete")
    @OperationLog(module = "权限管理", operation = "删除角色")
    public ApiResponse<Void> deleteRole(@PathVariable Long id) {
        rbacService.deleteRole(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/roles/{id}/permissions")
    @SaCheckPermission("system:role:edit")
    @OperationLog(module = "权限管理", operation = "分配角色权限")
    public ApiResponse<Void> assignPermissions(@PathVariable Long id, @RequestBody AssignIdsRequest request) {
        rbacService.assignPermissions(id, request);
        return ApiResponse.success(null);
    }

    @GetMapping("/permissions")
    @SaCheckPermission("system:permission:list")
    public ApiResponse<List<SysPermission>> permissions() {
        return ApiResponse.success(rbacService.permissions());
    }

    @PostMapping("/permissions")
    @SaCheckPermission("system:permission:add")
    @OperationLog(module = "权限管理", operation = "新增权限")
    public ApiResponse<SysPermission> createPermission(@RequestBody SysPermission permission) {
        return ApiResponse.success(rbacService.createPermission(permission));
    }

    @PutMapping("/permissions/{id}")
    @SaCheckPermission("system:permission:edit")
    @OperationLog(module = "权限管理", operation = "修改权限")
    public ApiResponse<SysPermission> updatePermission(@PathVariable Long id, @RequestBody SysPermission permission) {
        return ApiResponse.success(rbacService.updatePermission(id, permission));
    }

    @DeleteMapping("/permissions/{id}")
    @SaCheckPermission("system:permission:delete")
    @OperationLog(module = "权限管理", operation = "删除权限")
    public ApiResponse<Void> deletePermission(@PathVariable Long id) {
        rbacService.deletePermission(id);
        return ApiResponse.success(null);
    }

    @GetMapping("/relations")
    @SaCheckPermission("system:role:list")
    public ApiResponse<Map<String, Object>> relations() {
        return ApiResponse.success(rbacService.relationSnapshot());
    }
}
