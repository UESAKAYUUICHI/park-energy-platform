package com.parkenergyplatform.controller;

import java.util.Map;
import java.util.List;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.service.DeviceProvisionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/devices")
public class DeviceProvisionController {
    private final DeviceProvisionService provisionService;

    public DeviceProvisionController(DeviceProvisionService provisionService) {
        this.provisionService = provisionService;
    }

    @PostMapping("/provision")
    @SaCheckPermission(value = {"archive:device:provision", "archive:add"}, mode = SaMode.OR)
    @OperationLog(module = "设备档案", operation = "按已发布型号创建设备")
    public ApiResponse<Map<String, Object>> provision(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(provisionService.provision(body));
    }

    @PutMapping("/{deviceId}/context")
    @SaCheckPermission(value = {"archive:device:commission", "archive:edit"}, mode = SaMode.OR)
    @OperationLog(module = "设备档案", operation = "原子化修改设备上下文与计量投运")
    public ApiResponse<Map<String, Object>> updateContext(@PathVariable long deviceId,
                                                           @RequestBody Map<String, Object> body) {
        return ApiResponse.success(provisionService.updateContext(deviceId, body));
    }

    @PutMapping("/{deviceId}/attributes")
    @SaCheckPermission(value = {"archive:device:commission", "archive:edit"}, mode = SaMode.OR)
    @OperationLog(module = "设备档案", operation = "保存设备实例属性覆盖")
    public ApiResponse<List<Map<String, Object>>> replaceAttributeOverrides(@PathVariable long deviceId,
                                                                            @RequestBody Map<String, Object> body) {
        return ApiResponse.success(provisionService.replaceAttributeOverrides(deviceId, body));
    }

    @PutMapping("/{deviceId}/deployment")
    @SaCheckPermission(value = {"archive:device:deploy", "archive:edit"}, mode = SaMode.OR)
    @OperationLog(module = "设备档案", operation = "部署设备到网关")
    public ApiResponse<Map<String, Object>> deploy(@PathVariable long deviceId, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(provisionService.deploy(deviceId, body));
    }

    @PostMapping("/{deviceId}/unbind")
    @SaCheckPermission(value = {"archive:device:deploy", "archive:edit"}, mode = SaMode.OR)
    @OperationLog(module = "设备档案", operation = "解绑设备网关")
    public ApiResponse<Map<String, Object>> unbind(@PathVariable long deviceId, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(provisionService.unbind(deviceId, body));
    }
}
