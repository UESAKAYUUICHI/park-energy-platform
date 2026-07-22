package com.parkenergyplatform.controller;

import java.util.Map;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.common.PageResult;
import com.parkenergyplatform.service.PlatformBusinessQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/audit")
public class AuditController {
    private final PlatformBusinessQueryService queryService;

    public AuditController(PlatformBusinessQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/operation-logs")
    @SaCheckPermission("system:operation:list")
    public ApiResponse<PageResult<Map<String, Object>>> operationLogs(HttpServletRequest request) {
        return ApiResponse.success(queryService.operationLogs(queryParams(request), false));
    }

    @GetMapping("/my-operations")
    public ApiResponse<PageResult<Map<String, Object>>> myOperations(HttpServletRequest request) {
        return ApiResponse.success(queryService.operationLogs(queryParams(request), true));
    }

    private Map<String, String> queryParams(HttpServletRequest request) {
        return request.getParameterMap().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue()[0]));
    }
}
