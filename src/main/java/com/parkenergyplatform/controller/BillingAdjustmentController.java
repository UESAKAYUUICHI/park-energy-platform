package com.parkenergyplatform.controller;

import java.util.Map;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.common.PageResult;
import com.parkenergyplatform.service.BillingAdjustmentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/billing/adjustments")
public class BillingAdjustmentController {
    private final BillingAdjustmentService adjustmentService;
    public BillingAdjustmentController(BillingAdjustmentService adjustmentService) { this.adjustmentService = adjustmentService; }

    @GetMapping
    @SaCheckPermission("billing:adjustment:list")
    public ApiResponse<PageResult<Map<String, Object>>> page(HttpServletRequest request) { return ApiResponse.success(adjustmentService.page(params(request))); }

    @GetMapping("/{id}")
    @SaCheckPermission("billing:adjustment:list")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id) { return ApiResponse.success(adjustmentService.detail(id)); }

    @PostMapping
    @SaCheckPermission("billing:adjustment:create")
    @OperationLog(module = "计费管理", operation = "创建账单调整单")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) { return ApiResponse.success(adjustmentService.create(body)); }

    @PostMapping("/{id}/approve")
    @SaCheckPermission("billing:adjustment:approve")
    @OperationLog(module = "计费管理", operation = "审批账单调整单")
    public ApiResponse<Map<String, Object>> approve(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.success(adjustmentService.approve(id, String.valueOf(body.getOrDefault("operator", "admin")))); }

    @PostMapping("/{id}/cancel")
    @SaCheckPermission("billing:adjustment:create")
    @OperationLog(module = "计费管理", operation = "撤销账单调整单")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.success(adjustmentService.cancel(id, String.valueOf(body.getOrDefault("operator", "admin")))); }

    private Map<String, String> params(HttpServletRequest request) { return request.getParameterMap().entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, item -> item.getValue()[0])); }
}
