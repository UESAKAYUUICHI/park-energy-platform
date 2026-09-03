package com.parkenergyplatform.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.common.PageResult;
import com.parkenergyplatform.service.BillingInvoiceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/platform/billing/invoices")
public class BillingInvoiceController {
    private final BillingInvoiceService service;
    public BillingInvoiceController(BillingInvoiceService service){this.service=service;}
    @GetMapping @SaCheckPermission("billing:collection:list") public ApiResponse<PageResult<Map<String,Object>>> page(HttpServletRequest request){return ApiResponse.success(service.page(params(request)));}
    @GetMapping("/{id}") @SaCheckPermission("billing:collection:list") public ApiResponse<Map<String,Object>> detail(@PathVariable long id){return ApiResponse.success(service.detail(id));}
    @PostMapping @SaCheckPermission("billing:collection:edit") @OperationLog(module="结算与财务",operation="申请开具发票") public ApiResponse<Map<String,Object>> create(@RequestBody Map<String,Object> body){return ApiResponse.success(service.request(body,operator()));}
    @PostMapping("/{id}/issue") @SaCheckPermission("billing:collection:edit") @OperationLog(module="结算与财务",operation="确认开具发票") public ApiResponse<Map<String,Object>> issue(@PathVariable long id,@RequestBody Map<String,Object> body){return ApiResponse.success(service.issue(id,body,operator()));}
    @PostMapping(value="/{id}/file",consumes="multipart/form-data") @SaCheckPermission("billing:collection:edit") @OperationLog(module="结算与财务",operation="上传电子发票") public ApiResponse<Map<String,Object>> upload(@PathVariable long id,@RequestPart("file") MultipartFile file){return ApiResponse.success(service.upload(id,file,operator()));}
    @PostMapping("/{id}/deliver") @SaCheckPermission("billing:collection:edit") @OperationLog(module="结算与财务",operation="交付电子发票") public ApiResponse<Map<String,Object>> deliver(@PathVariable long id){return ApiResponse.success(service.deliver(id,operator()));}
    @PostMapping("/{id}/red-apply") @SaCheckPermission("billing:collection:edit") @OperationLog(module="结算与财务",operation="申请发票红冲") public ApiResponse<Map<String,Object>> redApply(@PathVariable long id,@RequestBody Map<String,Object> body){return ApiResponse.success(service.applyRed(id,String.valueOf(body.getOrDefault("reason","")),operator()));}
    @PostMapping("/{id}/red-confirm") @SaCheckPermission("billing:collection:edit") @OperationLog(module="结算与财务",operation="确认发票红冲") public ApiResponse<Map<String,Object>> redConfirm(@PathVariable long id,@RequestBody Map<String,Object> body){return ApiResponse.success(service.confirmRed(id,body,operator()));}
    private Map<String,String> params(HttpServletRequest request){return request.getParameterMap().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,e->e.getValue()[0]));}
    private String operator(){Object name=StpUtil.getSession().get("username");return name==null?String.valueOf(StpUtil.getLoginId()):String.valueOf(name);}
}
