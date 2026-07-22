package com.parkenergyplatform.controller;

import java.util.Map;

import cn.dev33.satoken.stp.StpUtil;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.dto.LoginRequest;
import com.parkenergyplatform.service.RbacService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/auth")
public class AuthController {
    private final RbacService rbacService;

    public AuthController(RbacService rbacService) {
        this.rbacService = rbacService;
    }

    @PostMapping("/login")
    @OperationLog(module = "系统登录", operation = "用户登录")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        Map<String, Object> payload = rbacService.login(request);
        StpUtil.getSession().set("username", request.username());
        return ApiResponse.success(payload);
    }

    @PostMapping("/logout")
    @OperationLog(module = "系统登录", operation = "退出登录")
    public ApiResponse<Void> logout() {
        StpUtil.logout();
        return ApiResponse.success(null);
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        return ApiResponse.success(rbacService.currentUser());
    }
}
