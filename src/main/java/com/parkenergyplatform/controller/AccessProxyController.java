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

    @GetMapping("/raw-messages/{rawLogId}")
    @SaCheckPermission("access:view")
    public ApiResponse<Map<String, Object>> rawMessage(@PathVariable long rawLogId) {
        return ApiResponse.success(authorizedGatewayDetail(remoteServiceClient.getAccess("/api/access/raw-messages/" + rawLogId)));
    }

    @PostMapping("/raw-messages/{rawLogId}/replay")
    @SaCheckPermission(value = {"archive:device:deploy", "archive:edit"}, mode = cn.dev33.satoken.annotation.SaMode.OR)
    @OperationLog(module = "接入管理", operation = "重放接入原始报文")
    public ApiResponse<Map<String, Object>> replayRawMessage(@PathVariable long rawLogId) {
        authorizedGatewayDetail(remoteServiceClient.getAccess("/api/access/raw-messages/" + rawLogId));
        return ApiResponse.success(remoteServiceClient.postAccess("/api/access/raw-messages/" + rawLogId + "/replay", Map.of()));
    }

    @GetMapping("/discovered-devices")
    @SaCheckPermission("access:view")
    public ApiResponse<Map<String, Object>> discoveredDevices() {
        return ApiResponse.success(filterRemoteList(remoteServiceClient.getAccess("/api/access/discovered-devices"), "gateway"));
    }

    @GetMapping("/discovered-devices/{id}")
    @SaCheckPermission("access:view")
    public ApiResponse<Map<String, Object>> discoveredDevice(@PathVariable long id) {
        return ApiResponse.success(authorizedGatewayDetail(remoteServiceClient.getAccess("/api/access/discovered-devices/" + id)));
    }

    @PostMapping("/discovered-devices/{id}/bind")
    @SaCheckPermission(value = {"archive:device:deploy", "archive:edit"}, mode = cn.dev33.satoken.annotation.SaMode.OR)
    @OperationLog(module = "接入管理", operation = "绑定待接入设备")
    public ApiResponse<Map<String, Object>> bindDiscoveredDevice(@PathVariable long id, @RequestBody Map<String, Object> body) {
        authorizedGatewayDetail(remoteServiceClient.getAccess("/api/access/discovered-devices/" + id));
        Object deviceId = body.get("deviceId");
        if (deviceId == null) throw new com.parkenergyplatform.common.BusinessException("deviceId 不能为空");
        accessService.assertDeviceAccess(Long.valueOf(String.valueOf(deviceId)));
        return ApiResponse.success(remoteServiceClient.postAccess("/api/access/discovered-devices/" + id + "/bind", body));
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> authorizedGatewayDetail(Map<String, Object> response) {
        Object data = response.get("data");
        if (!(data instanceof Map<?, ?> row)) {
            return response;
        }
        Map<String, Object> detail = new LinkedHashMap<>((Map<String, Object>) row);
        if (!accessService.hasGatewayAccess(accessService.longOrNull(detail.get("gatewayId")))) {
            throw new com.parkenergyplatform.common.BusinessException(403, "没有该网关的数据访问权限");
        }
        Map<String, Object> result = new LinkedHashMap<>(response);
        result.put("data", detail);
        return result;
    }

    private Map<String, String> queryParams(HttpServletRequest request) {
        return request.getParameterMap().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue()[0]));
    }
}
