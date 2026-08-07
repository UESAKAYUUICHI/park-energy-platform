package com.parkenergyplatform.controller;

import java.util.Map;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.common.PageResult;
import com.parkenergyplatform.service.BillingBatchService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/billing/batches")
public class BillingBatchController {
    private final BillingBatchService batchService;
    public BillingBatchController(BillingBatchService batchService) { this.batchService = batchService; }
    @GetMapping @SaCheckPermission("billing:batch:list") public ApiResponse<PageResult<Map<String,Object>>> page(HttpServletRequest request){return ApiResponse.success(batchService.page(params(request)));}
    @GetMapping("/{id}") @SaCheckPermission("billing:batch:list") public ApiResponse<Map<String,Object>> detail(@PathVariable long id){return ApiResponse.success(batchService.detail(id));}
    @PostMapping @SaCheckPermission("billing:batch:generate") @OperationLog(module="计费管理",operation="创建出账批次") public ApiResponse<Map<String,Object>> create(@RequestBody Map<String,Object> body){return ApiResponse.success(batchService.create(body));}
    @PostMapping("/{id}/generate") @SaCheckPermission("billing:batch:generate") @OperationLog(module="计费管理",operation="批量生成账单") public ApiResponse<Map<String,Object>> generate(@PathVariable long id){return ApiResponse.success(batchService.generate(id));}
    @PostMapping("/{id}/review") @SaCheckPermission("billing:batch:review") @OperationLog(module="计费管理",operation="审核出账批次") public ApiResponse<Map<String,Object>> review(@PathVariable long id){return ApiResponse.success(batchService.review(id,operator()));}
    @PostMapping("/{id}/issue") @SaCheckPermission("billing:batch:review") @OperationLog(module="计费管理",operation="发布账单") public ApiResponse<Map<String,Object>> issue(@PathVariable long id){return ApiResponse.success(batchService.issue(id,operator()));}
    private String operator(){Object name=StpUtil.getSession().get("username");return name==null?String.valueOf(StpUtil.getLoginId()):String.valueOf(name);} private Map<String,String> params(HttpServletRequest r){return r.getParameterMap().entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,e->e.getValue()[0]));}
}
