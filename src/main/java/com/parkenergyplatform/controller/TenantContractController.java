package com.parkenergyplatform.controller;

import java.util.Map;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.common.PageResult;
import com.parkenergyplatform.service.TenantContractService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/billing/contracts")
public class TenantContractController {
    private final TenantContractService contractService;
    public TenantContractController(TenantContractService contractService) { this.contractService = contractService; }

    @GetMapping @SaCheckPermission("billing:contract:list")
    public ApiResponse<PageResult<Map<String,Object>>> page(HttpServletRequest request) { return ApiResponse.success(contractService.page(params(request))); }
    @GetMapping("/{id}") @SaCheckPermission("billing:contract:list")
    public ApiResponse<Map<String,Object>> detail(@PathVariable long id) { return ApiResponse.success(contractService.detail(id)); }
    @PostMapping @SaCheckPermission("billing:contract:edit") @OperationLog(module="园区经营",operation="新建租户合同")
    public ApiResponse<Map<String,Object>> create(@RequestBody Map<String,Object> body) { return ApiResponse.success(contractService.create(body)); }
    @PutMapping("/{id}") @SaCheckPermission("billing:contract:edit") @OperationLog(module="园区经营",operation="编辑租户合同")
    public ApiResponse<Map<String,Object>> update(@PathVariable long id,@RequestBody Map<String,Object> body) { return ApiResponse.success(contractService.update(id,body)); }
    @PostMapping("/{id}/activate") @SaCheckPermission("billing:contract:edit") @OperationLog(module="园区经营",operation="生效租户合同")
    public ApiResponse<Map<String,Object>> activate(@PathVariable long id) { return ApiResponse.success(contractService.activate(id)); }
    @PostMapping("/{id}/terminate") @SaCheckPermission("billing:contract:edit") @OperationLog(module="园区经营",operation="终止租户合同")
    public ApiResponse<Map<String,Object>> terminate(@PathVariable long id,@RequestBody Map<String,Object> body) { return ApiResponse.success(contractService.terminate(id,body)); }
    @PostMapping("/{id}/delete") @SaCheckPermission("billing:contract:edit") @OperationLog(module="园区经营",operation="删除已终止租户合同")
    public ApiResponse<Void> delete(@PathVariable long id) { contractService.delete(id); return ApiResponse.success(null); }
    private Map<String,String> params(HttpServletRequest r) { return r.getParameterMap().entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,e->e.getValue()[0])); }
}
