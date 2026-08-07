package com.parkenergyplatform.controller;

import java.util.Map;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.common.PageResult;
import com.parkenergyplatform.service.TariffPlanService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/billing/tariff-plans")
public class TariffPlanController {
    private final TariffPlanService tariffPlanService;

    public TariffPlanController(TariffPlanService tariffPlanService) {
        this.tariffPlanService = tariffPlanService;
    }

    @GetMapping
    @SaCheckPermission("billing:tariff:list")
    public ApiResponse<PageResult<Map<String, Object>>> page(HttpServletRequest request) {
        return ApiResponse.success(tariffPlanService.page(queryParams(request)));
    }

    @GetMapping("/{id}")
    @SaCheckPermission("billing:tariff:list")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id) {
        return ApiResponse.success(tariffPlanService.detail(id));
    }

    @PostMapping
    @SaCheckPermission("billing:tariff:add")
    @OperationLog(module = "计费管理", operation = "创建园区分时电价方案")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(tariffPlanService.create(body));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("billing:tariff:edit")
    @OperationLog(module = "计费管理", operation = "修改园区分时电价方案")
    public ApiResponse<Map<String, Object>> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(tariffPlanService.update(id, body));
    }

    @PostMapping("/validate")
    @SaCheckPermission("billing:tariff:edit")
    public ApiResponse<Map<String, Object>> validate(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(tariffPlanService.validate(body));
    }

    @PostMapping("/{id}/enable")
    @SaCheckPermission("billing:tariff:enable")
    @OperationLog(module = "计费管理", operation = "启用园区分时电价方案")
    public ApiResponse<Map<String, Object>> enable(@PathVariable long id) {
        return ApiResponse.success(tariffPlanService.enable(id));
    }

    @PostMapping("/{id}/disable")
    @SaCheckPermission("billing:tariff:enable")
    @OperationLog(module = "计费管理", operation = "停用园区分时电价方案")
    public ApiResponse<Map<String, Object>> disable(@PathVariable long id) {
        return ApiResponse.success(tariffPlanService.disable(id));
    }

    @PostMapping("/{id}/copy-version")
    @SaCheckPermission("billing:tariff:copy")
    @OperationLog(module = "计费管理", operation = "复制园区分时电价版本")
    public ApiResponse<Map<String, Object>> copyVersion(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.success(tariffPlanService.copyVersion(id, body == null ? Map.of() : body));
    }

    private Map<String, String> queryParams(HttpServletRequest request) {
        return request.getParameterMap().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue()[0]));
    }
}
