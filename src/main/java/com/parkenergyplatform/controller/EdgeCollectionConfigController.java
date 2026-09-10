package com.parkenergyplatform.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.service.EdgeCollectionConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/platform/edge/config-management")
public class EdgeCollectionConfigController {
    private final EdgeCollectionConfigService service;

    public EdgeCollectionConfigController(EdgeCollectionConfigService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    @SaCheckPermission("archive:list")
    public ApiResponse<Map<String, Object>> overview(HttpServletRequest request) {
        return ApiResponse.success(service.overview(queryParams(request)));
    }

    @GetMapping("/gateways/{gatewayId}")
    @SaCheckPermission("archive:list")
    public ApiResponse<Map<String, Object>> gateway(@PathVariable long gatewayId) {
        return ApiResponse.success(service.gatewayDetail(gatewayId));
    }

    @GetMapping("/gateways/{gatewayId}/precheck")
    @SaCheckPermission("archive:list")
    public ApiResponse<Map<String, Object>> precheck(@PathVariable long gatewayId) {
        return ApiResponse.success(service.precheck(gatewayId));
    }

    @PutMapping("/devices/{deviceId}/binding")
    @SaCheckPermission("archive:edit")
    @OperationLog(module = "边缘采集配置", operation = "修改设备采集绑定")
    public ApiResponse<Map<String, Object>> updateDeviceBinding(@PathVariable long deviceId,
                                                                @RequestBody Map<String, Object> body) {
        return ApiResponse.success(service.updateDeviceBinding(deviceId, body));
    }

    @PostMapping("/devices/{deviceId}/probe")
    @SaCheckPermission("access:command")
    @OperationLog(module = "边缘采集配置", operation = "试采设备")
    public ApiResponse<Map<String, Object>> probeDevice(@PathVariable long deviceId) {
        return ApiResponse.success(service.probeDevice(deviceId));
    }

    @PutMapping("/model-versions/{modelVersionId}/points")
    @SaCheckPermission("archive:edit")
    @OperationLog(module = "边缘采集配置", operation = "保存模型采集点表")
    public ApiResponse<Map<String, Object>> replaceModelPoints(@PathVariable long modelVersionId,
                                                               @RequestBody Map<String, Object> body) {
        return ApiResponse.success(service.replaceModelPoints(modelVersionId, body));
    }

    @PostMapping("/gateways/{gatewayId}/publish")
    @SaCheckPermission("archive:edit")
    @OperationLog(module = "边缘采集配置", operation = "发布网关采集配置")
    public ApiResponse<Map<String, Object>> publish(@PathVariable long gatewayId) {
        return ApiResponse.success(service.markPending(gatewayId));
    }

    private Map<String, String> queryParams(HttpServletRequest request) {
        return request.getParameterMap().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue()[0]));
    }
}
