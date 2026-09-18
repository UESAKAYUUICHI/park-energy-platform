package com.parkenergyplatform.controller;

import java.util.Map;
import java.util.List;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.common.PageResult;
import com.parkenergyplatform.service.BusinessWorkspaceService;
import com.parkenergyplatform.service.PlatformBusinessQueryService;
import com.parkenergyplatform.service.SimpleTableService;
import com.parkenergyplatform.service.MeterTemplateService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/archive")
public class ArchiveController {
    private final SimpleTableService tableService;
    private final PlatformBusinessQueryService queryService;
    private final BusinessWorkspaceService workspaceService;
    private final MeterTemplateService meterTemplateService;

    public ArchiveController(SimpleTableService tableService, PlatformBusinessQueryService queryService,
                             BusinessWorkspaceService workspaceService, MeterTemplateService meterTemplateService) {
        this.tableService = tableService;
        this.queryService = queryService;
        this.workspaceService = workspaceService;
        this.meterTemplateService = meterTemplateService;
    }

    @GetMapping("/org-tree")
    @SaCheckPermission("archive:list")
    public ApiResponse<Object> orgTree() {
        return ApiResponse.success(queryService.orgTree());
    }

    @GetMapping("/device-tree")
    @SaCheckPermission("archive:list")
    public ApiResponse<Object> deviceTree(HttpServletRequest request) {
        return ApiResponse.success(queryService.deviceTree(queryParams(request)));
    }

    @GetMapping("/root-orgs")
    @SaCheckPermission("archive:list")
    public ApiResponse<Object> rootOrgs() {
        return ApiResponse.success(queryService.rootOrgs());
    }

    @GetMapping("/meter-templates")
    @SaCheckPermission("archive:list")
    public ApiResponse<Object> meterTemplates() {
        return ApiResponse.success(meterTemplateService.list());
    }

    @PostMapping("/meter-templates")
    @SaCheckPermission("archive:edit")
    public ApiResponse<Object> createMeterTemplate(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(meterTemplateService.create(body));
    }

    @DeleteMapping("/meter-templates/{id}")
    @SaCheckPermission("archive:edit")
    public ApiResponse<Void> deleteMeterTemplate(@PathVariable long id) {
        meterTemplateService.delete(id);
        return ApiResponse.success(null);
    }

    @GetMapping("/devices/{deviceId}/profile")
    @SaCheckPermission("archive:list")
    public ApiResponse<Map<String, Object>> deviceProfile(@PathVariable long deviceId) {
        return ApiResponse.success(queryService.deviceProfile(deviceId));
    }

    @GetMapping("/devices/cards")
    @SaCheckPermission("archive:list")
    public ApiResponse<PageResult<Map<String, Object>>> deviceCards(HttpServletRequest request) {
        return ApiResponse.success(queryService.deviceCards(queryParams(request)));
    }

    @GetMapping("/devices/{deviceId}/archive-profile")
    @SaCheckPermission("archive:list")
    public ApiResponse<Map<String, Object>> deviceArchiveProfile(@PathVariable long deviceId) {
        return ApiResponse.success(queryService.deviceArchiveProfile(deviceId));
    }

    @GetMapping("/orgs/{orgId}/archive-profile")
    @SaCheckPermission("archive:list")
    public ApiResponse<Map<String, Object>> orgArchiveProfile(@PathVariable long orgId) {
        return ApiResponse.success(queryService.orgArchiveProfile(orgId));
    }

    @GetMapping("/gateways/{gatewayId}/archive-profile")
    @SaCheckPermission("archive:list")
    public ApiResponse<Map<String, Object>> gatewayArchiveProfile(@PathVariable long gatewayId) {
        return ApiResponse.success(queryService.gatewayArchiveProfile(gatewayId));
    }

    @GetMapping("/device-types/{typeId}/points")
    @SaCheckPermission("archive:list")
    public ApiResponse<Map<String, Object>> deviceTypePoints(@PathVariable long typeId) {
        return ApiResponse.success(queryService.deviceTypePoints(typeId));
    }

    @PostMapping("/gateways/{gatewayId}/bind-devices")
    @SaCheckPermission("archive:edit")
    @OperationLog(module = "档案管理", operation = "绑定网关设备")
    public ApiResponse<Object> bindDevices(@PathVariable long gatewayId, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(tableService.bindDevicesToGateway(gatewayId, body));
    }

    @GetMapping("/{resource}")
    @SaCheckPermission("archive:list")
    public ApiResponse<PageResult<Map<String, Object>>> page(@PathVariable String resource, HttpServletRequest request) {
        return ApiResponse.success(tableService.page(resource, queryParams(request)));
    }

    @GetMapping("/{resource}/options")
    @SaCheckPermission("archive:list")
    public ApiResponse<List<Map<String, Object>>> options(@PathVariable String resource, HttpServletRequest request) {
        return ApiResponse.success(tableService.options(resource, queryParams(request)));
    }

    @GetMapping("/{resource}/{id}")
    @SaCheckPermission("archive:list")
    public ApiResponse<Map<String, Object>> get(@PathVariable String resource, @PathVariable long id) {
        return ApiResponse.success(tableService.get(resource, id));
    }

    @PostMapping("/{resource}")
    @SaCheckPermission("archive:add")
    @OperationLog(module = "档案管理", operation = "新增档案")
    public ApiResponse<Map<String, Object>> create(@PathVariable String resource, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(tableService.create(resource, body));
    }

    @PutMapping("/{resource}/{id}")
    @SaCheckPermission("archive:edit")
    @OperationLog(module = "档案管理", operation = "修改档案")
    public ApiResponse<Map<String, Object>> update(@PathVariable String resource, @PathVariable long id,
                                                   @RequestBody Map<String, Object> body) {
        return ApiResponse.success(tableService.update(resource, id, body));
    }

    @DeleteMapping("/{resource}/{id}")
    @SaCheckPermission("archive:delete")
    @OperationLog(module = "档案管理", operation = "删除档案")
    public ApiResponse<Void> delete(@PathVariable String resource, @PathVariable long id) {
        tableService.delete(resource, id);
        return ApiResponse.success(null);
    }

    @PostMapping("/{resource}/{id}/copy")
    @SaCheckPermission("archive:add")
    @OperationLog(module = "档案管理", operation = "复制档案")
    public ApiResponse<Map<String, Object>> copy(@PathVariable String resource, @PathVariable long id) {
        return ApiResponse.success(tableService.copy(resource, id));
    }

    private Map<String, String> queryParams(HttpServletRequest request) {
        return request.getParameterMap().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue()[0]));
    }
}
