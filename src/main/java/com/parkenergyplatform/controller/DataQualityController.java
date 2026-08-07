package com.parkenergyplatform.controller;

import java.util.Map;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.common.PageResult;
import com.parkenergyplatform.service.DataQualityService;
import com.parkenergyplatform.service.RemoteServiceClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/data-quality")
public class DataQualityController {
    private final DataQualityService qualityService;
    private final RemoteServiceClient remoteServiceClient;
    public DataQualityController(DataQualityService qualityService, RemoteServiceClient remoteServiceClient) { this.qualityService = qualityService; this.remoteServiceClient = remoteServiceClient; }

    @GetMapping("/summary")
    @SaCheckPermission("energy:quality:list")
    public ApiResponse<Map<String, Object>> summary() { return ApiResponse.success(qualityService.summary()); }

    @GetMapping("/events")
    @SaCheckPermission("energy:quality:list")
    public ApiResponse<PageResult<Map<String, Object>>> events(HttpServletRequest request) { return ApiResponse.success(qualityService.page(params(request))); }

    @GetMapping("/events/{id}")
    @SaCheckPermission("energy:quality:list")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id) { return ApiResponse.success(qualityService.detail(id)); }

    @GetMapping("/collection-daily")
    @SaCheckPermission("energy:quality:list")
    public ApiResponse<PageResult<Map<String, Object>>> collectionDaily(HttpServletRequest request) {
        return ApiResponse.success(qualityService.collectionDaily(params(request)));
    }

    @PostMapping("/events/{id}/replay")
    @SaCheckPermission("energy:quality:replay")
    public ApiResponse<Map<String, Object>> replay(@PathVariable long id) {
        Map<String, Object> event = qualityService.requestReplay(id);
        remoteServiceClient.postAccess("/api/access/raw-messages/" + event.get("raw_log_id") + "/replay", Map.of());
        return ApiResponse.success(qualityService.detail(id));
    }

    private Map<String, String> params(HttpServletRequest request) { return request.getParameterMap().entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue()[0])); }
}
