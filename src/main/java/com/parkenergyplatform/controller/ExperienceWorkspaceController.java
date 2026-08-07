package com.parkenergyplatform.controller;

import java.util.Map;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.service.ExperienceWorkspaceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/experience")
public class ExperienceWorkspaceController {
    private final ExperienceWorkspaceService workspaceService;

    public ExperienceWorkspaceController(ExperienceWorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @GetMapping("/assets")
    @SaCheckPermission("archive:list")
    public ApiResponse<Map<String, Object>> assets(@RequestParam(required = false) Long deviceId) {
        return ApiResponse.success(workspaceService.assetCenter(deviceId));
    }

    @GetMapping("/operations")
    @SaCheckPermission("ops:workorder:list")
    public ApiResponse<Map<String, Object>> operations(@RequestParam(required = false) Long deviceId) {
        return ApiResponse.success(workspaceService.operationsCenter(deviceId));
    }

    @GetMapping("/revenue")
    @SaCheckPermission("billing:list")
    public ApiResponse<Map<String, Object>> revenue(@RequestParam(required = false) Long accountId,
                                                    @RequestParam(required = false) String billCycle) {
        return ApiResponse.success(workspaceService.revenueCenter(accountId, billCycle));
    }

    @GetMapping("/revenue/precheck")
    @SaCheckPermission("billing:bill:generate")
    public ApiResponse<Map<String, Object>> revenuePrecheck(@RequestParam long accountId,
                                                            @RequestParam String billCycle) {
        return ApiResponse.success(workspaceService.revenuePrecheck(accountId, billCycle));
    }
}
