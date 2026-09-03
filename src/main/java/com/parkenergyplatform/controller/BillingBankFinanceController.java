package com.parkenergyplatform.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.common.PageResult;
import com.parkenergyplatform.service.BillingBankFinanceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/platform/billing/finance")
public class BillingBankFinanceController {
    private final BillingBankFinanceService service;
    public BillingBankFinanceController(BillingBankFinanceService service) { this.service = service; }
    @PostMapping("/statements/import") @SaCheckPermission("billing:collection:edit") @OperationLog(module="结算与财务", operation="导入银行流水") public ApiResponse<Map<String,Object>> importStatements(@RequestBody Map<String,Object> body){return ApiResponse.success(service.importStatements(body,operator()));}
    @PostMapping(value="/statements/import-file",consumes="multipart/form-data") @SaCheckPermission("billing:collection:edit") @OperationLog(module="结算与财务",operation="导入银行流水文件") public ApiResponse<Map<String,Object>> importFile(@RequestParam long orgId,@RequestParam(required=false) Long targetPeriodId,@RequestParam(defaultValue="BANK") String sourceChannel,@RequestPart("file") MultipartFile file){return ApiResponse.success(service.importStatementFile(orgId,targetPeriodId,sourceChannel,file,operator()));}
    @GetMapping("/statement-import-batches") @SaCheckPermission("billing:collection:list") public ApiResponse<PageResult<Map<String,Object>>> importBatches(HttpServletRequest r){return ApiResponse.success(service.importBatches(params(r)));}
    @GetMapping("/statement-import-batches/{id}") @SaCheckPermission("billing:collection:list") public ApiResponse<Map<String,Object>> importBatch(@PathVariable long id){return ApiResponse.success(service.importBatch(id));}
    @GetMapping("/statements") @SaCheckPermission("billing:collection:list") public ApiResponse<PageResult<Map<String,Object>>> statements(HttpServletRequest r){return ApiResponse.success(service.statements(params(r)));}
    @GetMapping("/statements/{id}") @SaCheckPermission("billing:collection:list") public ApiResponse<Map<String,Object>> statement(@PathVariable long id){return ApiResponse.success(service.statement(id));}
    @GetMapping("/statements/{id}/candidates") @SaCheckPermission("billing:collection:list") public ApiResponse<List<Map<String,Object>>> candidates(@PathVariable long id){return ApiResponse.success(service.candidates(id));}
    @GetMapping("/statements/{id}/payment-candidates") @SaCheckPermission("billing:collection:list") public ApiResponse<List<Map<String,Object>>> paymentCandidates(@PathVariable long id,@RequestParam(required=false) String billCycle){return ApiResponse.success(service.paymentCandidates(id,billCycle));}
    @PostMapping("/statements/{id}/match") @SaCheckPermission("billing:collection:edit") @OperationLog(module="结算与财务", operation="银行流水自动勾兑") public ApiResponse<Map<String,Object>> match(@PathVariable long id,@RequestBody Map<String,Object> body){String amount=body.get("matchAmount")==null?null:String.valueOf(body.get("matchAmount"));return ApiResponse.success(service.match(id,Long.parseLong(String.valueOf(body.get("billId"))),amount==null?null:new java.math.BigDecimal(amount),operator()));}
    @PostMapping("/statements/{id}/auto-match") @SaCheckPermission("billing:collection:edit") @OperationLog(module="结算与财务", operation="自动匹配银行流水") public ApiResponse<Map<String,Object>> autoMatch(@PathVariable long id){return ApiResponse.success(service.autoMatch(id,operator()));}
    @PostMapping("/statements/{id}/match-payment") @SaCheckPermission("billing:collection:edit") @OperationLog(module="结算与财务", operation="银行流水关联已有收款") public ApiResponse<Map<String,Object>> matchPayment(@PathVariable long id,@RequestBody Map<String,Object> body){return ApiResponse.success(service.matchPayment(id,Long.parseLong(String.valueOf(body.get("paymentId"))),operator()));}
    @PostMapping("/statements/{id}/difference") @SaCheckPermission("billing:collection:edit") @OperationLog(module="结算与财务", operation="标记银行流水差异") public ApiResponse<Map<String,Object>> difference(@PathVariable long id,@RequestBody Map<String,Object> body){return ApiResponse.success(service.difference(id,String.valueOf(body.getOrDefault("reason","")),operator()));}
    @PostMapping("/matches/{id}/reverse") @SaCheckPermission("billing:collection:edit") @OperationLog(module="结算与财务", operation="解除银行流水勾兑") public ApiResponse<Map<String,Object>> reverseMatch(@PathVariable long id,@RequestBody Map<String,Object> body){return ApiResponse.success(service.reverseMatch(id,String.valueOf(body.getOrDefault("reason","")),operator()));}
    @PostMapping("/vouchers") @SaCheckPermission("billing:collection:edit") @OperationLog(module="结算与财务", operation="生成财务凭证") public ApiResponse<Map<String,Object>> voucher(@RequestBody Map<String,Object> body){return ApiResponse.success(service.createVoucher(body,operator()));}
    @GetMapping("/vouchers/{id}") @SaCheckPermission("billing:collection:list") public ApiResponse<Map<String,Object>> voucher(@PathVariable long id){return ApiResponse.success(service.voucher(id));}
    @PostMapping("/vouchers/{id}/post") @SaCheckPermission("billing:collection:edit") @OperationLog(module="结算与财务", operation="财务凭证确认入账") public ApiResponse<Map<String,Object>> post(@PathVariable long id){return ApiResponse.success(service.postVoucher(id,operator()));}
    @PostMapping("/vouchers/{id}/export") @SaCheckPermission("billing:collection:edit") @OperationLog(module="结算与财务", operation="导出财务凭证") public ApiResponse<Map<String,Object>> export(@PathVariable long id,@RequestBody Map<String,Object> body){return ApiResponse.success(service.exportVoucher(id,body,operator()));}
    private Map<String,String> params(HttpServletRequest r){return r.getParameterMap().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,e->e.getValue()[0]));}
    private String operator(){Object name=StpUtil.getSession().get("username");return name==null?String.valueOf(StpUtil.getLoginId()):String.valueOf(name);}
}
