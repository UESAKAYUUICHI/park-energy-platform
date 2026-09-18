package com.parkenergyplatform.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.service.EdgeCollectionConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    @GetMapping("/gateways/{gatewayId}/device-candidates")
    @SaCheckPermission("archive:list")
    public ApiResponse<Map<String, Object>> deviceCandidates(@PathVariable long gatewayId, HttpServletRequest request) {
        return ApiResponse.success(service.deviceCandidates(gatewayId, queryParams(request)));
    }

    @PostMapping("/gateways/{gatewayId}/channels")
    @SaCheckPermission("archive:edit")
    @OperationLog(module = "边缘采集配置", operation = "新增网关通道")
    public ApiResponse<Map<String, Object>> createChannel(@PathVariable long gatewayId,
                                                          @RequestBody Map<String, Object> body) {
        return ApiResponse.success(service.createChannel(gatewayId, body));
    }

    @PutMapping("/gateways/{gatewayId}/channels/{channelId}")
    @SaCheckPermission("archive:edit")
    @OperationLog(module = "边缘采集配置", operation = "修改网关通道")
    public ApiResponse<Map<String, Object>> updateChannel(@PathVariable long gatewayId,
                                                          @PathVariable String channelId,
                                                          @RequestBody Map<String, Object> body) {
        return ApiResponse.success(service.updateChannel(gatewayId, channelId, body));
    }

    @DeleteMapping("/gateways/{gatewayId}/channels/{channelId}")
    @SaCheckPermission("archive:edit")
    @OperationLog(module = "边缘采集配置", operation = "删除网关通道")
    public ApiResponse<Map<String, Object>> deleteChannel(@PathVariable long gatewayId,
                                                          @PathVariable String channelId) {
        return ApiResponse.success(service.deleteChannel(gatewayId, channelId));
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
