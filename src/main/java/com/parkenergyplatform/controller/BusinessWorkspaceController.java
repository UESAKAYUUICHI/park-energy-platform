package com.parkenergyplatform.controller;

import java.util.Map;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.service.BusinessWorkspaceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/workspace")
public class BusinessWorkspaceController {
    private final BusinessWorkspaceService workspaceService;

    public BusinessWorkspaceController(BusinessWorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @GetMapping("/cockpit")
    @SaCheckPermission("dashboard:view")
    public ApiResponse<Map<String, Object>> cockpit(@RequestParam(required = false) Long rootOrgId) {
        return ApiResponse.success(workspaceService.cockpit(rootOrgId));
    }

    @GetMapping("/archive")
    @SaCheckPermission("archive:list")
    public ApiResponse<Map<String, Object>> archive() {
        return ApiResponse.success(workspaceService.archiveWorkspace());
    }

    @GetMapping("/monitor")
    @SaCheckPermission("energy:view")
    public ApiResponse<Map<String, Object>> monitor(@RequestParam(required = false) Long deviceId,
                                                    @RequestParam(required = false) String pointCode) {
        return ApiResponse.success(workspaceService.monitorWorkspace(deviceId, pointCode));
    }

    @GetMapping("/alarms")
    @SaCheckPermission("alarm:rule:list")
    public ApiResponse<Map<String, Object>> alarms() {
        return ApiResponse.success(workspaceService.alarmWorkspace());
    }

    @GetMapping("/billing")
    @SaCheckPermission("billing:list")
    public ApiResponse<Map<String, Object>> billing() {
        return ApiResponse.success(workspaceService.billingWorkspace());
    }

    @GetMapping("/commands")
    @SaCheckPermission("access:view")
    public ApiResponse<Map<String, Object>> commands() {
        return ApiResponse.success(workspaceService.commandWorkspace());
    }

    @PostMapping("/commands")
    @SaCheckPermission("access:command")
    @OperationLog(module = "设备控制", operation = "平台下发设备指令")
    public ApiResponse<Map<String, Object>> sendCommand(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(workspaceService.sendDeviceCommand(request));
    }

    @PostMapping("/parse-test")
    @SaCheckPermission("archive:edit")
    @OperationLog(module = "档案管理", operation = "测点解析测试")
    public ApiResponse<Map<String, Object>> parseTest(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(workspaceService.parseTest(request));
    }
}
