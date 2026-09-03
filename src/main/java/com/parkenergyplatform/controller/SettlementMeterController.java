package com.parkenergyplatform.controller;

import java.util.List;
import java.util.Map;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.service.SettlementMeterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/billing/settlement-meters")
public class SettlementMeterController {
    private final SettlementMeterService settlementMeterService;

    public SettlementMeterController(SettlementMeterService settlementMeterService) {
        this.settlementMeterService = settlementMeterService;
    }

    @GetMapping
    @SaCheckPermission("billing:metering:list")
    public ApiResponse<List<Map<String, Object>>> candidates() { return ApiResponse.success(settlementMeterService.candidates()); }

    @GetMapping("/{deviceId}")
    @SaCheckPermission("billing:metering:list")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long deviceId) { return ApiResponse.success(settlementMeterService.detail(deviceId)); }

    @PostMapping("/{deviceId}/enable")
    @SaCheckPermission("billing:metering:execute")
    @OperationLog(module = "计费管理", operation = "启用结算表计")
    public ApiResponse<Map<String, Object>> enable(@PathVariable long deviceId, @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.success(settlementMeterService.enable(deviceId, body == null ? Map.of() : body));
    }

    @PostMapping("/{deviceId}/disable")
    @SaCheckPermission("billing:metering:execute")
    @OperationLog(module = "计费管理", operation = "停用结算表计")
    public ApiResponse<Map<String, Object>> disable(@PathVariable long deviceId) { return ApiResponse.success(settlementMeterService.disable(deviceId)); }
}
