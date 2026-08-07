package com.parkenergyplatform.controller;

import java.util.Map;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.common.PageResult;
import com.parkenergyplatform.service.MeteringChangeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/billing/metering")
public class MeteringChangeController {
    private final MeteringChangeService meteringChangeService;

    public MeteringChangeController(MeteringChangeService meteringChangeService) { this.meteringChangeService = meteringChangeService; }

    @GetMapping("/orders")
    @SaCheckPermission("billing:metering:list")
    public ApiResponse<PageResult<Map<String, Object>>> page(HttpServletRequest request) { return ApiResponse.success(meteringChangeService.page(params(request))); }

    @GetMapping("/orders/{id}")
    @SaCheckPermission("billing:metering:list")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id) { return ApiResponse.success(meteringChangeService.detail(id)); }

    @PostMapping("/orders")
    @SaCheckPermission("billing:metering:create")
    @OperationLog(module = "Metering", operation = "Create meter change order")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) { return ApiResponse.success(meteringChangeService.create(body)); }

    @PostMapping("/orders/{id}/approve")
    @SaCheckPermission("billing:metering:approve")
    @OperationLog(module = "Metering", operation = "Approve meter change order")
    public ApiResponse<Map<String, Object>> approve(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.success(meteringChangeService.action(id, "APPROVE", body)); }

    @PostMapping("/orders/{id}/reject")
    @SaCheckPermission("billing:metering:approve")
    @OperationLog(module = "Metering", operation = "Reject meter change order")
    public ApiResponse<Map<String, Object>> reject(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.success(meteringChangeService.action(id, "REJECT", body)); }

    @PostMapping("/orders/{id}/cancel")
    @SaCheckPermission("billing:metering:create")
    @OperationLog(module = "Metering", operation = "Cancel meter change order")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.success(meteringChangeService.action(id, "CANCEL", body)); }

    @PostMapping("/orders/{id}/execute")
    @SaCheckPermission("billing:metering:execute")
    @OperationLog(module = "Metering", operation = "Execute meter change order")
    public ApiResponse<Map<String, Object>> execute(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.success(meteringChangeService.action(id, "EXECUTE", body)); }

    private Map<String, String> params(HttpServletRequest request) { return request.getParameterMap().entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue()[0])); }
}
