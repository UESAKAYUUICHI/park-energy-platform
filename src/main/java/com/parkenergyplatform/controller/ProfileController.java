package com.parkenergyplatform.controller;

import java.util.Map;

import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.service.RbacService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/profile")
public class ProfileController {
    private final RbacService rbacService;
    public ProfileController(RbacService rbacService) { this.rbacService = rbacService; }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() { return ApiResponse.success(rbacService.personalSummary()); }

    @PutMapping("/self")
    @OperationLog(module = "个人中心", operation = "修改个人资料")
    public ApiResponse<Map<String, Object>> update(@RequestBody Map<String, Object> request) { return ApiResponse.success(rbacService.updateCurrentProfile(request)); }

    @PostMapping("/self/password")
    @OperationLog(module = "个人中心", operation = "修改个人密码")
    public ApiResponse<Void> changePassword(@RequestBody Map<String, Object> request) { rbacService.changeCurrentPassword(request); return ApiResponse.success(null); }
}
