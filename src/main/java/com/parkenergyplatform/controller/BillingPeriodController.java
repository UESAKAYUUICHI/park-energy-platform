package com.parkenergyplatform.controller;

import java.util.Map;
import java.util.stream.Collectors;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.common.PageResult;
import com.parkenergyplatform.service.BillingPeriodService;
import com.parkenergyplatform.service.BillingClosingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/platform/billing/periods")
public class BillingPeriodController {
    private final BillingPeriodService service;
    private final BillingClosingService closingService;
    public BillingPeriodController(BillingPeriodService service, BillingClosingService closingService) { this.service = service; this.closingService = closingService; }
    @GetMapping @SaCheckPermission("billing:batch:list") public ApiResponse<PageResult<Map<String,Object>>> page(HttpServletRequest request){return ApiResponse.success(service.page(params(request)));}
    @GetMapping("/{id}") @SaCheckPermission("billing:batch:list") public ApiResponse<Map<String,Object>> detail(@PathVariable long id){return ApiResponse.success(service.detail(id));}
    @GetMapping("/{id}/check") @SaCheckPermission("billing:batch:list") public ApiResponse<Map<String,Object>> check(@PathVariable long id){return ApiResponse.success(service.check(id));}
    @PostMapping @SaCheckPermission("billing:batch:generate") @OperationLog(module="结算与财务",operation="创建结算账期") public ApiResponse<Map<String,Object>> create(@RequestBody Map<String,Object> body){return ApiResponse.success(service.create(body,operator()));}
    @PostMapping("/{id}/close") @SaCheckPermission("billing:batch:review") @OperationLog(module="结算与财务",operation="结算账期关账并生成档案包") public ApiResponse<Map<String,Object>> close(@PathVariable long id){return ApiResponse.success(closingService.closeAndPrepare(id,operator()));}
    @PostMapping("/{id}/reopen") @SaCheckPermission("billing:batch:review") @OperationLog(module="结算与财务",operation="结算账期反关账") public ApiResponse<Map<String,Object>> reopen(@PathVariable long id,@RequestBody Map<String,Object> body){return ApiResponse.success(service.reopen(id,operator(),String.valueOf(body.getOrDefault("reason", ""))));}
    private Map<String,String> params(HttpServletRequest r){return r.getParameterMap().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,e->e.getValue()[0]));}
    private String operator(){Object name=StpUtil.getSession().get("username");return name==null?String.valueOf(StpUtil.getLoginId()):String.valueOf(name);}
}
