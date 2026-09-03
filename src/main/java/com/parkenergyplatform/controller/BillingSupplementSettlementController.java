package com.parkenergyplatform.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.service.BillingSupplementSettlementService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/platform/billing/supplements")
public class BillingSupplementSettlementController {
    private final BillingSupplementSettlementService service;
    public BillingSupplementSettlementController(BillingSupplementSettlementService service) { this.service=service; }
    @GetMapping @SaCheckPermission("billing:list") public ApiResponse<Object> list(@RequestParam(required=false) Long orgId,@RequestParam(required=false) Long periodId){return ApiResponse.success(service.list(orgId,periodId));}
    @GetMapping("/{id}") @SaCheckPermission("billing:list") public ApiResponse<Object> detail(@PathVariable long id){return ApiResponse.success(service.detail(id));}
    @PostMapping @SaCheckPermission("billing:batch:generate") @OperationLog(module="结算与财务",operation="发起补充结算") public ApiResponse<Object> create(@RequestBody Map<String,Object> body){return ApiResponse.success(service.create(body,operator()));}
    @PostMapping("/{id}/generate") @SaCheckPermission("billing:batch:generate") @OperationLog(module="结算与财务",operation="生成补充账单") public ApiResponse<Object> generate(@PathVariable long id){return ApiResponse.success(service.generate(id,operator()));}
    @PostMapping("/{id}/cancel") @SaCheckPermission("billing:batch:generate") @OperationLog(module="结算与财务",operation="取消补充结算") public ApiResponse<Object> cancel(@PathVariable long id){return ApiResponse.success(service.cancel(id,operator()));}
    @PostMapping("/{id}/archive") @SaCheckPermission("billing:batch:review") @OperationLog(module="结算与财务",operation="归档补充结算") public ApiResponse<Object> archive(@PathVariable long id){return ApiResponse.success(service.archive(id,operator()));}
    private String operator(){Object name=StpUtil.getSession().get("username");return name==null?String.valueOf(StpUtil.getLoginId()):String.valueOf(name);}
}
