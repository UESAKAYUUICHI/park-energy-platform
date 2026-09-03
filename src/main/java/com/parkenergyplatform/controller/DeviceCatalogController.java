package com.parkenergyplatform.controller;

import java.util.List;
import java.util.Map;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.service.DeviceCatalogService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/platform/catalog")
public class DeviceCatalogController {
    private final DeviceCatalogService catalogService;

    public DeviceCatalogController(DeviceCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/tree")
    @SaCheckPermission(value = {"catalog:view", "archive:list"}, mode = SaMode.OR)
    public ApiResponse<List<Map<String, Object>>> tree(@RequestParam(required = false) String keyword,
                                                        @RequestParam(required = false) String depth) {
        return ApiResponse.success(catalogService.tree(keyword, depth));
    }

    @GetMapping("/attribute-tree")
    @SaCheckPermission(value = {"catalog:view", "archive:list"}, mode = SaMode.OR)
    public ApiResponse<List<Map<String, Object>>> attributeTree() {
        return ApiResponse.success(catalogService.attributeTree());
    }

    @GetMapping("/point-tree")
    @SaCheckPermission(value = {"catalog:view", "archive:list"}, mode = SaMode.OR)
    public ApiResponse<List<Map<String, Object>>> pointTree() {
        return ApiResponse.success(catalogService.standardPointTree());
    }

    @GetMapping("/lookups")
    @SaCheckPermission(value = {"catalog:view", "archive:list"}, mode = SaMode.OR)
    public ApiResponse<Map<String, Object>> lookups() {
        return ApiResponse.success(catalogService.lookups());
    }

    @GetMapping("/published-options")
    @SaCheckPermission(value = {"catalog:view", "archive:list"}, mode = SaMode.OR)
    public ApiResponse<List<Map<String, Object>>> publishedOptions() {
        return ApiResponse.success(catalogService.publishedOptions());
    }

    @GetMapping("/models/{modelId}")
    @SaCheckPermission(value = {"catalog:view", "archive:list"}, mode = SaMode.OR)
    public ApiResponse<Map<String, Object>> detail(@PathVariable long modelId,
                                                    @RequestParam(required = false) Long versionId) {
        return ApiResponse.success(catalogService.detail(modelId, versionId));
    }

    @PostMapping("/models/{modelId}/image/upload-url")
    @SaCheckPermission(value = {"catalog:edit", "archive:edit"}, mode = SaMode.OR)
    public ApiResponse<Map<String, Object>> modelImageUploadUrl(@PathVariable long modelId,
                                                                @RequestBody Map<String, Object> body) {
        return ApiResponse.success(catalogService.modelImageUploadUrl(modelId, body));
    }

    @PostMapping(value = "/models/{modelId}/image/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SaCheckPermission(value = {"catalog:edit", "archive:edit"}, mode = SaMode.OR)
    @OperationLog(module = "产品目录", operation = "上传型号图片")
    public ApiResponse<Map<String, Object>> uploadModelImage(@PathVariable long modelId,
                                                             @RequestPart("file") MultipartFile file) {
        return ApiResponse.success(catalogService.uploadModelImage(modelId, file));
    }

    @PutMapping("/models/{modelId}/image")
    @SaCheckPermission(value = {"catalog:edit", "archive:edit"}, mode = SaMode.OR)
    @OperationLog(module = "产品目录", operation = "保存型号图片")
    public ApiResponse<Map<String, Object>> saveModelImage(@PathVariable long modelId,
                                                           @RequestBody Map<String, Object> body) {
        return ApiResponse.success(catalogService.saveModelImage(modelId, body));
    }

    @DeleteMapping("/models/{modelId}/image")
    @SaCheckPermission(value = {"catalog:edit", "archive:edit"}, mode = SaMode.OR)
    @OperationLog(module = "产品目录", operation = "清空型号图片")
    public ApiResponse<Map<String, Object>> clearModelImage(@PathVariable long modelId) {
        return ApiResponse.success(catalogService.clearModelImage(modelId));
    }

    @PostMapping("/categories")
    @SaCheckPermission(value = {"catalog:edit", "archive:add"}, mode = SaMode.OR)
    @OperationLog(module = "产品目录", operation = "新增设备分类")
    public ApiResponse<Map<String, Object>> createCategory(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(catalogService.createCategory(body));
    }

    @PostMapping("/brands")
    @SaCheckPermission(value = {"catalog:edit", "archive:add"}, mode = SaMode.OR)
    @OperationLog(module = "产品目录", operation = "新增设备品牌")
    public ApiResponse<Map<String, Object>> createBrand(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(catalogService.createBrand(body));
    }

    @PostMapping("/series")
    @SaCheckPermission(value = {"catalog:edit", "archive:add"}, mode = SaMode.OR)
    @OperationLog(module = "产品目录", operation = "新增产品系列")
    public ApiResponse<Map<String, Object>> createSeries(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(catalogService.createSeries(body));
    }

    @PostMapping("/attribute-groups")
    @SaCheckPermission(value = {"catalog:attribute:manage", "archive:add"}, mode = SaMode.OR)
    @OperationLog(module = "产品目录", operation = "新增属性分类")
    public ApiResponse<Map<String, Object>> createAttributeGroup(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(catalogService.createAttributeGroup(body));
    }

    @PostMapping("/attributes")
    @SaCheckPermission(value = {"catalog:attribute:manage", "archive:add"}, mode = SaMode.OR)
    @OperationLog(module = "产品目录", operation = "新增属性定义")
    public ApiResponse<Map<String, Object>> createAttribute(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(catalogService.createAttribute(body));
    }

    @PostMapping("/attribute-values")
    @SaCheckPermission(value = {"catalog:attribute:manage", "archive:add"}, mode = SaMode.OR)
    @OperationLog(module = "产品目录", operation = "新增属性固定值")
    public ApiResponse<Map<String, Object>> createAttributeValue(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(catalogService.createAttributeValue(body));
    }

    @PostMapping("/standard-points")
    @SaCheckPermission(value = {"catalog:attribute:manage", "archive:add"}, mode = SaMode.OR)
    @OperationLog(module = "产品目录", operation = "新增标准测点")
    public ApiResponse<Map<String, Object>> createStandardPoint(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(catalogService.createStandardPoint(body));
    }

    @PostMapping("/point-groups")
    @SaCheckPermission(value = {"catalog:attribute:manage", "archive:add"}, mode = SaMode.OR)
    public ApiResponse<Map<String, Object>> createPointGroup(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(catalogService.createPointGroup(body));
    }

    @PutMapping("/nodes/{nodeType}/{id}")
    @SaCheckPermission(value = {"catalog:attribute:manage", "archive:edit"}, mode = SaMode.OR)
    public ApiResponse<Map<String, Object>> updateNode(@PathVariable String nodeType, @PathVariable long id,
                                                        @RequestBody Map<String, Object> body) {
        return ApiResponse.success(catalogService.updateNode(nodeType, id, body));
    }

    @DeleteMapping("/nodes/{nodeType}/{id}")
    @SaCheckPermission(value = {"catalog:attribute:manage", "archive:delete"}, mode = SaMode.OR)
    public ApiResponse<Map<String, Object>> deleteNode(@PathVariable String nodeType, @PathVariable long id) {
        return ApiResponse.success(catalogService.deleteNode(nodeType, id));
    }

    @PostMapping("/models")
    @SaCheckPermission(value = {"catalog:edit", "archive:add"}, mode = SaMode.OR)
    @OperationLog(module = "产品目录", operation = "新增设备型号")
    public ApiResponse<Map<String, Object>> createModel(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(catalogService.createModel(body));
    }

    @PostMapping("/models/{modelId}/versions")
    @SaCheckPermission(value = {"catalog:edit", "archive:edit"}, mode = SaMode.OR)
    @OperationLog(module = "产品目录", operation = "创建设备型号新版本")
    public ApiResponse<Map<String, Object>> createVersion(@PathVariable long modelId,
                                                           @RequestParam(required = false) Long sourceVersionId) {
        return ApiResponse.success(catalogService.createVersion(modelId, sourceVersionId));
    }

    @PutMapping("/versions/{versionId}")
    @SaCheckPermission(value = {"catalog:edit", "archive:edit"}, mode = SaMode.OR)
    @OperationLog(module = "产品目录", operation = "编辑型号草稿")
    public ApiResponse<Map<String, Object>> updateVersion(@PathVariable long versionId,
                                                           @RequestBody Map<String, Object> body) {
        return ApiResponse.success(catalogService.updateVersion(versionId, body));
    }

    @PutMapping("/versions/{versionId}/attributes")
    @SaCheckPermission(value = {"catalog:edit", "archive:edit"}, mode = SaMode.OR)
    @OperationLog(module = "产品目录", operation = "配置型号属性")
    public ApiResponse<Map<String, Object>> replaceAttributes(@PathVariable long versionId,
                                                               @RequestBody List<Map<String, Object>> body) {
        return ApiResponse.success(catalogService.replaceAttributes(versionId, body));
    }

    @PutMapping("/versions/{versionId}/points")
    @SaCheckPermission(value = {"catalog:edit", "archive:edit"}, mode = SaMode.OR)
    @OperationLog(module = "产品目录", operation = "配置型号测点和协议")
    public ApiResponse<Map<String, Object>> replacePoints(@PathVariable long versionId,
                                                           @RequestBody List<Map<String, Object>> body) {
        return ApiResponse.success(catalogService.replacePoints(versionId, body));
    }

    @PostMapping("/versions/{versionId}/validate")
    @SaCheckPermission(value = {"catalog:edit", "archive:edit"}, mode = SaMode.OR)
    public ApiResponse<Map<String, Object>> validate(@PathVariable long versionId) {
        return ApiResponse.success(catalogService.validate(versionId));
    }

    @PostMapping("/versions/{versionId}/publish")
    @SaCheckPermission(value = {"catalog:publish", "archive:edit"}, mode = SaMode.OR)
    @OperationLog(module = "产品目录", operation = "发布型号版本")
    public ApiResponse<Map<String, Object>> publish(@PathVariable long versionId) {
        return ApiResponse.success(catalogService.publish(versionId));
    }

    @PostMapping("/versions/{versionId}/disable")
    @SaCheckPermission(value = {"catalog:disable", "archive:edit"}, mode = SaMode.OR)
    @OperationLog(module = "产品目录", operation = "停用型号版本")
    public ApiResponse<Map<String, Object>> disable(@PathVariable long versionId) {
        return ApiResponse.success(catalogService.disable(versionId));
    }

    @DeleteMapping("/versions/{versionId}")
    @SaCheckPermission(value = {"catalog:disable", "archive:edit"}, mode = SaMode.OR)
    @OperationLog(module = "产品目录", operation = "删除停用型号版本")
    public ApiResponse<Map<String, Object>> deleteVersion(@PathVariable long versionId) {
        return ApiResponse.success(catalogService.deleteVersion(versionId));
    }
}
