package com.parkenergyplatform.controller;

import java.util.Map;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.service.PlatformBusinessQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/statistics")
public class StatisticsController {
    private final PlatformBusinessQueryService queryService;

    public StatisticsController(PlatformBusinessQueryService queryService) {
        this.queryService = queryService;
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

    private Map<String, String> queryParams(HttpServletRequest request) {
        return request.getParameterMap().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue()[0]));
    }
}
