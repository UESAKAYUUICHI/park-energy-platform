package com.parkenergyplatform.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.service.BillingAutoScheduleService;
import com.parkenergyplatform.service.BillingSettlementArchiveService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/platform/billing/automation")
public class BillingAutomationController {
    private final BillingAutoScheduleService schedules;
    private final BillingSettlementArchiveService archives;
    public BillingAutomationController(BillingAutoScheduleService schedules, BillingSettlementArchiveService archives) { this.schedules=schedules; this.archives=archives; }
    @GetMapping("/schedules") @SaCheckPermission("billing:list") public ApiResponse<Object> schedules(@RequestParam(required=false) Long orgId){return ApiResponse.success(schedules.list(orgId));}
    @PostMapping("/schedules") @SaCheckPermission("billing:batch:generate") public ApiResponse<Object> create(@RequestBody Map<String,Object> body){return ApiResponse.success(schedules.save(body,null,operator()));}
    @PutMapping("/schedules/{id}") @SaCheckPermission("billing:batch:generate") public ApiResponse<Object> update(@PathVariable long id,@RequestBody Map<String,Object> body){return ApiResponse.success(schedules.save(body,id,operator()));}
    @DeleteMapping("/schedules/{id}") @SaCheckPermission("billing:batch:generate") public ApiResponse<Void> delete(@PathVariable long id){schedules.delete(id);return ApiResponse.success(null);}
    @PostMapping("/schedules/{id}/run") @SaCheckPermission("billing:batch:generate") public ApiResponse<Object> run(@PathVariable long id){return ApiResponse.success(schedules.run(id,operator()));}
    @GetMapping("/archives") @SaCheckPermission("billing:list") public ApiResponse<Object> archives(@RequestParam(required=false) Long orgId,@RequestParam(required=false) String periodCode){return ApiResponse.success(archives.list(orgId,periodCode));}
    @GetMapping("/archives/monthly/{periodId}/preview") @SaCheckPermission("billing:list") public ApiResponse<Object> monthlyPreview(@PathVariable long periodId){return ApiResponse.success(archives.monthlyPreview(periodId));}
    @PostMapping("/archives/monthly/{periodId}/finalize") @SaCheckPermission("billing:batch:review") @OperationLog(module="结算与财务",operation="月度最终归档") public ApiResponse<Object> finalizeMonth(@PathVariable long periodId,@RequestBody Map<String,Object> body){return ApiResponse.success(archives.finalizeMonth(periodId,body,operator()));}
    @PostMapping("/archives/prepare/{periodId}") @SaCheckPermission("billing:batch:review") @OperationLog(module="结算与财务",operation="生成结算档案包") public ApiResponse<Object> prepare(@PathVariable long periodId){return ApiResponse.success(archives.prepare(periodId,operator()));}
    @PostMapping("/archives/{id}/archive") @SaCheckPermission("billing:batch:review") @OperationLog(module="结算与财务",operation="正式归档结算包") public ApiResponse<Object> archive(@PathVariable long id){return ApiResponse.success(archives.archive(id,operator()));}
    @GetMapping("/archives/{id}") @SaCheckPermission("billing:list") public ApiResponse<Object> detail(@PathVariable long id){return ApiResponse.success(archives.detail(id));}
    @PostMapping("/archives/backfill-snapshots") @SaCheckPermission("billing:batch:review") @OperationLog(module="结算与财务",operation="补建历史结算档案快照") public ApiResponse<Object> backfillSnapshots(){return ApiResponse.success(archives.backfillMissingSnapshots(operator()));}
    private String operator(){Object name=StpUtil.getSession().get("username");return name==null?String.valueOf(StpUtil.getLoginId()):String.valueOf(name);}
}
