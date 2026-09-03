package com.parkenergyplatform.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.service.BillingSpaceScopeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/platform/billing/space-scopes")
public class BillingSpaceScopeController {
    private final BillingSpaceScopeService service;

    public BillingSpaceScopeController(BillingSpaceScopeService service) {
        this.service = service;
    }

    @GetMapping("/options")
    @SaCheckPermission("billing:contract:list")
    public ApiResponse<List<Map<String, Object>>> options() {
        return ApiResponse.success(service.options());
    }

    @GetMapping
    @SaCheckPermission("billing:contract:list")
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) Long spaceId) {
        return ApiResponse.success(service.list(spaceId));
    }

    @PutMapping("/{spaceId}")
    @SaCheckPermission("billing:contract:edit")
    @OperationLog(module = "园区经营", operation = "配置计费空间映射范围")
    public ApiResponse<List<Map<String, Object>>> replace(@PathVariable long spaceId,
                                                           @RequestBody Map<String, Object> body) {
        Object raw = body.get("scopes");
        if (!(raw instanceof List<?> rows)) {
            throw new com.parkenergyplatform.common.BusinessException("scopes 必须为数组");
        }
        List<Map<String, Object>> scopes = rows.stream()
                .filter(Map.class::isInstance)
                .map(row -> (Map<String, Object>) row)
                .toList();
        return ApiResponse.success(service.replace(spaceId, scopes));
    }

    @GetMapping("/devices")
    @SaCheckPermission("billing:contract:list")
    public ApiResponse<List<Map<String, Object>>> devices(HttpServletRequest request) {
        String value = request.getParameter("spaceIds");
        List<Long> ids = value == null ? List.of() : java.util.Arrays.stream(value.split(","))
                .map(String::trim).filter(item -> !item.isBlank()).map(Long::valueOf).toList();
        return ApiResponse.success(service.devices(ids));
    }
}
