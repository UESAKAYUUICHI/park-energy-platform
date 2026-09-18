package com.parkenergyplatform.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.service.ProtocolCatalogService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/platform/protocols")
public class ProtocolCatalogController {
    private final ProtocolCatalogService service;

    public ProtocolCatalogController(ProtocolCatalogService service) { this.service = service; }

    @GetMapping
    @SaCheckPermission(value={"catalog:view","archive:list"}, mode=SaMode.OR)
    public ApiResponse<List<Map<String,Object>>> list(@RequestParam(required=false) String keyword) { return ApiResponse.success(service.list(keyword)); }

    @GetMapping("/published-versions")
    @SaCheckPermission(value={"catalog:view","archive:list"}, mode=SaMode.OR)
    public ApiResponse<List<Map<String,Object>>> published() { return ApiResponse.success(service.publishedVersions()); }

    @GetMapping("/versions/{versionId}")
    @SaCheckPermission(value={"catalog:view","archive:list"}, mode=SaMode.OR)
    public ApiResponse<Map<String,Object>> detail(@PathVariable long versionId) { return ApiResponse.success(service.detail(versionId)); }

    @PostMapping
    @SaCheckPermission(value={"catalog:edit","archive:edit"}, mode=SaMode.OR)
    @OperationLog(module="协议中心", operation="创建协议档案")
    public ApiResponse<Map<String,Object>> create(@RequestBody Map<String,Object> body) { return ApiResponse.success(service.create(body)); }

    @PostMapping("/{profileId}/versions")
    @SaCheckPermission(value={"catalog:edit","archive:edit"}, mode=SaMode.OR)
    @OperationLog(module="协议中心", operation="创建协议新版本")
    public ApiResponse<Map<String,Object>> createVersion(@PathVariable long profileId,@RequestBody(required=false) Map<String,Object> body) {
        return ApiResponse.success(service.createVersion(profileId, body == null ? Map.of() : body));
    }

    @PutMapping("/versions/{versionId}")
    @SaCheckPermission(value={"catalog:edit","archive:edit"}, mode=SaMode.OR)
    @OperationLog(module="协议中心", operation="保存协议草稿")
    public ApiResponse<Map<String,Object>> replace(@PathVariable long versionId,@RequestBody Map<String,Object> body) { return ApiResponse.success(service.replaceDraft(versionId,body)); }

    @PostMapping("/versions/{versionId}/validate")
    @SaCheckPermission(value={"catalog:edit","archive:edit"}, mode=SaMode.OR)
    public ApiResponse<Map<String,Object>> validate(@PathVariable long versionId) { return ApiResponse.success(service.validate(versionId)); }

    @PostMapping("/versions/{versionId}/publish")
    @SaCheckPermission(value={"catalog:publish","archive:edit"}, mode=SaMode.OR)
    @OperationLog(module="协议中心", operation="发布协议版本")
    public ApiResponse<Map<String,Object>> publish(@PathVariable long versionId) { return ApiResponse.success(service.publish(versionId)); }

    @DeleteMapping("/{profileId}")
    @SaCheckPermission(value={"catalog:disable","archive:delete"}, mode=SaMode.OR)
    @OperationLog(module="协议中心", operation="删除未使用协议")
    public ApiResponse<Void> delete(@PathVariable long profileId) { service.delete(profileId); return ApiResponse.success(null); }
}
