package com.parkenergyplatform.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.common.PageResult;
import com.parkenergyplatform.service.BusinessDataAccessService;
import com.parkenergyplatform.service.PlatformBusinessQueryService;
import com.parkenergyplatform.service.RemoteServiceClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/access")
public class AccessProxyController {
    private final RemoteServiceClient remoteServiceClient;
    private final BusinessDataAccessService accessService;
    private final PlatformBusinessQueryService queryService;

    public AccessProxyController(RemoteServiceClient remoteServiceClient, BusinessDataAccessService accessService,
                                 PlatformBusinessQueryService queryService) {
        this.remoteServiceClient = remoteServiceClient;
        this.accessService = accessService;
        this.queryService = queryService;
    }

    @GetMapping("/gateways/status")
    @SaCheckPermission("access:view")
    public ApiResponse<Map<String, Object>> gateways() {
        return ApiResponse.success(filterRemoteList(remoteServiceClient.getAccess("/api/access/gateways/status"), "gateway"));
    }

    @GetMapping("/raw-messages")
    @SaCheckPermission("access:view")
    public ApiResponse<Map<String, Object>> rawMessages() {
        return ApiResponse.success(filterRemoteList(remoteServiceClient.getAccess("/api/access/raw-messages"), "gateway"));
    }

    @PostMapping("/commands")
    @SaCheckPermission("access:command")
    @OperationLog(module = "接入管理", operation = "发送网关指令")
    public ApiResponse<Map<String, Object>> sendCommand(@RequestBody Map<String, Object> body) {
        accessService.assertCommandRequestAccess(body);
        Map<String, Object> command = new LinkedHashMap<>(body);
        command.putIfAbsent("requestUserId", StpUtil.getLoginIdAsLong());
        command.putIfAbsent("requestUsername", String.valueOf(StpUtil.getLoginId()));
        return ApiResponse.success(remoteServiceClient.postAccess("/api/access/commands", command));
    }

    @GetMapping("/commands")
    @SaCheckPermission("access:view")
    public ApiResponse<Map<String, Object>> commands() {
        return ApiResponse.success(filterRemoteList(remoteServiceClient.getAccess("/api/access/commands"), "command"));
    }

    @GetMapping("/commands/page")
    @SaCheckPermission("access:view")
    public ApiResponse<PageResult<Map<String, Object>>> commandPage(HttpServletRequest request) {
        return ApiResponse.success(queryService.commandPage(queryParams(request)));
    }

    @GetMapping("/commands/{commandId}")
    @SaCheckPermission("access:view")
    public ApiResponse<Map<String, Object>> command(@PathVariable String commandId) {
        accessService.assertCommandAccess(commandId);
        return ApiResponse.success(remoteServiceClient.getAccess("/api/access/commands/" + commandId));
    }

    @GetMapping("/command-targets")
    @SaCheckPermission("access:view")
    public ApiResponse<Map<String, Object>> commandTargets() {
        return ApiResponse.success(queryService.commandTargets());
    }

    @GetMapping("/command-types")
    @SaCheckPermission("access:view")
    public ApiResponse<Object> commandTypes() {
        return ApiResponse.success(queryService.commandTypes());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> filterRemoteList(Map<String, Object> response, String mode) {
        Object data = response.get("data");
        if (!(data instanceof List<?> list)) {
            return response;
        }
        List<Map<String, Object>> rows = list.stream()
                .filter(Map.class::isInstance)
                .map(row -> (Map<String, Object>) row)
                .toList();
        Map<String, Object> filtered = new LinkedHashMap<>(response);
        if ("command".equals(mode)) {
            filtered.put("data", accessService.filterCommands(rows));
        } else {
            filtered.put("data", accessService.filterByGatewayAccess(rows));
        }
        return filtered;
    }

    private Map<String, String> queryParams(HttpServletRequest request) {
        return request.getParameterMap().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue()[0]));
    }
}
