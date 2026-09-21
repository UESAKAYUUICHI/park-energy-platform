package com.parkenergyplatform.controller;

import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.service.EdgeGatewaySyncService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/platform/edge/config")
public class EdgeGatewaySyncController {
    private final EdgeGatewaySyncService service;

    public EdgeGatewaySyncController(EdgeGatewaySyncService service) { this.service = service; }

    @GetMapping
    public ApiResponse<Map<String, Object>> pull(
            @RequestHeader("X-Gateway-Sn") String gatewaySn,
            @RequestHeader("X-Gateway-Secret") String gatewaySecret) {
        return ApiResponse.success(service.pull(gatewaySn, gatewaySecret));
    }

    @PostMapping("/ack")
    public ApiResponse<Map<String, Object>> acknowledge(
            @RequestHeader("X-Gateway-Sn") String gatewaySn,
            @RequestHeader("X-Gateway-Secret") String gatewaySecret,
            @RequestBody Map<String, Object> body) {
        return ApiResponse.success(service.acknowledge(gatewaySn, gatewaySecret, body));
    }

    @GetMapping("/alarms")
    public ApiResponse<Map<String, Object>> alarms(
            @RequestHeader("X-Gateway-Sn") String gatewaySn,
            @RequestHeader("X-Gateway-Secret") String gatewaySecret) {
        return ApiResponse.success(service.activeAlarms(gatewaySn, gatewaySecret));
    }

    @GetMapping("/alarm-rules")
    public ApiResponse<Map<String, Object>> alarmRules(
            @RequestHeader("X-Gateway-Sn") String gatewaySn,
            @RequestHeader("X-Gateway-Secret") String gatewaySecret) {
        return ApiResponse.success(service.alarmRules(gatewaySn, gatewaySecret));
    }

    @GetMapping("/alarm-protocols")
    public ApiResponse<Map<String, Object>> alarmProtocols(
            @RequestHeader("X-Gateway-Sn") String gatewaySn,
            @RequestHeader("X-Gateway-Secret") String gatewaySecret) {
        return ApiResponse.success(service.alarmProtocols(gatewaySn, gatewaySecret));
    }
}
