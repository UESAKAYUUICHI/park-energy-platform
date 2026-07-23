package com.parkenergyplatform.controller;

import java.util.Map;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.common.PageResult;
import com.parkenergyplatform.service.BusinessWorkspaceService;
import com.parkenergyplatform.service.PlatformBusinessQueryService;
import com.parkenergyplatform.service.SimpleTableService;
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

    public ArchiveController(SimpleTableService tableService, PlatformBusinessQueryService queryService,
                             BusinessWorkspaceService workspaceService) {
        this.tableService = tableService;
        this.queryService = queryService;
        this.workspaceService = workspaceService;
    }

    @GetMapping("/org-tree")
    @SaCheckPermission("archive:list")
    public ApiResponse<Object> orgTree() {
        return ApiResponse.success(queryService.orgTree());
    }

    @GetMapping("/device-tree")
    @SaCheckPermission("archive:list")
    public ApiResponse<Object> deviceTree() {
        return ApiResponse.success(queryService.deviceTree());
    }

    @GetMapping("/devices/{deviceId}/profile")
    @SaCheckPermission("archive:list")
    public ApiResponse<Map<String, Object>> deviceProfile(@PathVariable long deviceId) {
        return ApiResponse.success(queryService.deviceProfile(deviceId));
    }

    @GetMapping("/devices/{deviceId}/archive-profile")
    @SaCheckPermission("archive:list")
    public ApiResponse<Map<String, Object>> deviceArchiveProfile(@PathVariable long deviceId) {
        return ApiResponse.success(queryService.deviceArchiveProfile(deviceId));
    }

    @GetMapping("/device-types/{typeId}/points")
    @SaCheckPermission("archive:list")
    public ApiResponse<Map<String, Object>> deviceTypePoints(@PathVariable long typeId) {
        return ApiResponse.success(queryService.deviceTypePoints(typeId));
    }

    @PostMapping("/device-types/{typeId}/points/batch")
    @SaCheckPermission("archive:edit")
    @OperationLog(module = "档案管理", operation = "批量保存设备类型测点")
    public ApiResponse<Map<String, Object>> saveDeviceTypePoints(@PathVariable long typeId,
                                                                 @RequestBody Map<String, Object> body) {
        return ApiResponse.success(queryService.saveDeviceTypePoints(typeId, body));
    }

    @PostMapping("/point-mappings/parse-test")
    @SaCheckPermission("archive:edit")
    @OperationLog(module = "档案管理", operation = "测点解析测试")
    public ApiResponse<Map<String, Object>> parseTest(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(workspaceService.parseTest(request));
    }

    @GetMapping("/{resource}")
    @SaCheckPermission("archive:list")
    public ApiResponse<PageResult<Map<String, Object>>> page(@PathVariable String resource, HttpServletRequest request) {
        return ApiResponse.success(tableService.page(resource, queryParams(request)));
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

    private Map<String, String> queryParams(HttpServletRequest request) {
        return request.getParameterMap().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue()[0]));
    }
}
