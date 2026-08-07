package com.parkenergyplatform.controller;

import java.util.Map;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.service.BusinessDataAccessService;
import com.parkenergyplatform.service.DataScopeService;
import com.parkenergyplatform.service.PlatformBusinessQueryService;
import com.parkenergyplatform.service.RemoteServiceClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/statistics")
public class StatisticsController {
    private final PlatformBusinessQueryService queryService;
    private final RemoteServiceClient remoteServiceClient;
    private final BusinessDataAccessService accessService;
    private final DataScopeService dataScopeService;

    public StatisticsController(PlatformBusinessQueryService queryService, RemoteServiceClient remoteServiceClient,
                                BusinessDataAccessService accessService, DataScopeService dataScopeService) {
        this.queryService = queryService;
        this.remoteServiceClient = remoteServiceClient;
        this.accessService = accessService;
        this.dataScopeService = dataScopeService;
    }

    @GetMapping("/daily/summary")
    @SaCheckPermission("energy:view")
    public ApiResponse<Object> dailySummary(HttpServletRequest request) {
        return ApiResponse.success(queryService.dailySummary(queryParams(request)));
    }

    @GetMapping("/monthly")
    @SaCheckPermission("energy:view")
    public ApiResponse<Object> monthly(HttpServletRequest request) {
        return ApiResponse.success(queryService.monthlyStats(queryParams(request)));
    }

    @GetMapping("/quality")
    @SaCheckPermission("energy:view")
    public ApiResponse<Object> quality(HttpServletRequest request) {
        return ApiResponse.success(queryService.qualityStats(queryParams(request)));
    }

    @PostMapping("/daily/rebuild")
    @SaCheckPermission("energy:statistics:rebuild")
    public ApiResponse<Object> rebuildDaily(HttpServletRequest request) {
        return ApiResponse.success(queryService.rebuildDailyStats(queryParams(request)));
    }

    @GetMapping("/tou")
    @SaCheckPermission("energy:view")
    public ApiResponse<Object> tou(HttpServletRequest request) {
        Map<String, String> params = queryParams(request);
        assertTouScope(params);
        return ApiResponse.success(remoteData(remoteServiceClient.getData("/api/data/statistics/tou", params)));
    }

    @PostMapping("/tou/rebuild")
    @SaCheckPermission("billing:statistics:rebuild")
    public ApiResponse<Object> rebuildTou(HttpServletRequest request) {
        Map<String, String> params = queryParams(request);
        assertTouScope(params);
        return ApiResponse.success(remoteData(remoteServiceClient.postData("/api/data/statistics/tou/rebuild", params)));
    }

    private Map<String, String> queryParams(HttpServletRequest request) {
        return request.getParameterMap().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue()[0]));
    }

    private void assertTouScope(Map<String, String> params) {
        String deviceId = params.get("deviceId");
        if (deviceId != null && !deviceId.isBlank()) {
            try { accessService.assertDeviceAccess(Long.parseLong(deviceId)); }
            catch (NumberFormatException ex) { throw new BusinessException("deviceId 必须为数字"); }
            return;
        }
        if (!dataScopeService.isSuperAdmin(StpUtil.getLoginIdAsLong())) {
            throw new BusinessException(403, "重建或查看全部设备的分时统计仅限超级管理员；请指定 deviceId");
        }
    }

    private Object remoteData(Map<String, Object> response) {
        if (Boolean.FALSE.equals(response.get("success"))) {
            throw new BusinessException(503, String.valueOf(response.getOrDefault("message", "数据服务调用失败")));
        }
        return response.get("data");
    }
}
