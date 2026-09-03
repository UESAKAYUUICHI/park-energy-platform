package com.parkenergyplatform.controller;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.common.PageResult;
import com.parkenergyplatform.service.BillingReconciliationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/platform/billing/reconciliations")
public class BillingReconciliationController {
    private final BillingReconciliationService service;
    public BillingReconciliationController(BillingReconciliationService service) { this.service=service; }
    @GetMapping @SaCheckPermission("billing:collection:list") public ApiResponse<PageResult<Map<String,Object>>> page(HttpServletRequest r){return ApiResponse.success(service.page(params(r)));}
    @GetMapping("/{id}") @SaCheckPermission("billing:collection:list") public ApiResponse<Map<String,Object>> detail(@PathVariable long id){return ApiResponse.success(service.detail(id));}
    @PostMapping @SaCheckPermission("billing:collection:edit") @OperationLog(module="结算与财务",operation="创建收款对账单") public ApiResponse<Map<String,Object>> create(@RequestBody Map<String,Object> body){return ApiResponse.success(service.create(body,operator()));}
    @PostMapping("/{id}/items") @SaCheckPermission("billing:collection:edit") @OperationLog(module="结算与财务",operation="录入对账流水") public ApiResponse<Map<String,Object>> add(@PathVariable long id,@RequestBody Map<String,Object> body){return ApiResponse.success(service.addItem(id,body,operator()));}
    @GetMapping("/items/{id}/candidates") @SaCheckPermission("billing:collection:list") public ApiResponse<List<Map<String,Object>>> candidates(@PathVariable long id){return ApiResponse.success(service.candidates(id));}
    @PostMapping("/items/{id}/match") @SaCheckPermission("billing:collection:edit") @OperationLog(module="结算与财务",operation="匹配收款流水") public ApiResponse<Map<String,Object>> match(@PathVariable long id,@RequestBody Map<String,Object> body){return ApiResponse.success(service.match(id,Long.parseLong(String.valueOf(body.get("paymentId"))),operator()));}
    @PostMapping("/items/{id}/difference") @SaCheckPermission("billing:collection:edit") @OperationLog(module="结算与财务",operation="确认收款差异") public ApiResponse<Map<String,Object>> difference(@PathVariable long id,@RequestBody Map<String,Object> body){return ApiResponse.success(service.difference(id,String.valueOf(body.getOrDefault("reason", "")),operator()));}
    @PostMapping("/{id}/finish") @SaCheckPermission("billing:collection:edit") @OperationLog(module="结算与财务",operation="完成收款对账") public ApiResponse<Map<String,Object>> finish(@PathVariable long id){return ApiResponse.success(service.finish(id,operator()));}
    private Map<String,String> params(HttpServletRequest r){return r.getParameterMap().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,e->e.getValue()[0]));}
    private String operator(){Object name=StpUtil.getSession().get("username");return name==null?String.valueOf(StpUtil.getLoginId()):String.valueOf(name);}
}
