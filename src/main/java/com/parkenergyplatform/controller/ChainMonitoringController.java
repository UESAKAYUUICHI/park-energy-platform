package com.parkenergyplatform.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.service.RemoteServiceClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Single platform entry point for operations to inspect the live ingestion chain. */
@RestController
@RequestMapping("/api/platform/monitoring")
public class ChainMonitoringController {
    private final RemoteServiceClient remote;

    public ChainMonitoringController(RemoteServiceClient remote) { this.remote = remote; }

    @GetMapping("/chain")
    @SaCheckPermission("energy:quality:list")
    public ApiResponse<Map<String, Object>> chain() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("access", remote.getAccess("/api/access/monitoring/health"));
        result.put("data", remote.getData("/api/data/monitoring/health"));
        result.put("meterPointContract", remote.getData("/api/data/contracts/meter-points"));
        return ApiResponse.success(result);
    }
}
