package com.parkenergyplatform.controller;

import java.util.Map;
import java.util.LinkedHashMap;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.common.PageResult;
import com.parkenergyplatform.service.BillingCollectionService;
import com.parkenergyplatform.service.BillingFinanceService;
import com.parkenergyplatform.service.BillingService;
import com.parkenergyplatform.service.BillingWorkflowService;
import com.parkenergyplatform.service.SimpleTableService;
import com.parkenergyplatform.service.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/billing")
public class BillingController {
    private final BillingService billingService;
    private final BillingWorkflowService workflowService;
    private final BillingCollectionService collectionService;
    private final BillingFinanceService financeService;
    private final SimpleTableService tableService;
    private final TenantService tenantService;

    public BillingController(BillingService billingService, BillingWorkflowService workflowService, BillingCollectionService collectionService,
                             BillingFinanceService financeService, SimpleTableService tableService, TenantService tenantService) {
        this.billingService = billingService;
        this.workflowService = workflowService;
        this.collectionService = collectionService;
        this.financeService = financeService;
        this.tableService = tableService;
        this.tenantService = tenantService;
    }

    @PostMapping("/bills/generate")
    @SaCheckPermission("billing:bill:generate")
    @OperationLog(module = "计费管理", operation = "生成账单")
    public ApiResponse<Map<String, Object>> generate(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(billingService.generate(audited(request)));
    }

    @PostMapping("/bills/preview")
    @SaCheckPermission("billing:bill:generate")
    public ApiResponse<Map<String, Object>> preview(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(billingService.preview(request));
    }

    @GetMapping("/bills")
    @SaCheckPermission("billing:bill:list")
    public ApiResponse<PageResult<Map<String, Object>>> bills(HttpServletRequest request) {
        return ApiResponse.success(billingService.listBills(queryParams(request)));
    }

    @GetMapping("/bills/{billId}")
    @SaCheckPermission("billing:bill:list")
    public ApiResponse<Map<String, Object>> bill(@PathVariable long billId) {
        return ApiResponse.success(billingService.bill(billId));
    }

    @PutMapping("/bills/{billId}/remark")
    @SaCheckPermission("billing:bill:generate")
    @OperationLog(module = "计费管理", operation = "修改账单备注")
    public ApiResponse<Map<String, Object>> updateRemark(@PathVariable long billId, @RequestBody Map<String, Object> request) {
        return ApiResponse.success(billingService.updateRemark(billId, audited(request)));
    }

    @PostMapping("/bills/{billId}/review")
    @SaCheckPermission("billing:batch:review")
    @OperationLog(module = "计费管理", operation = "审核账单")
    public ApiResponse<Map<String, Object>> review(@PathVariable long billId) {
        return ApiResponse.success(billingService.review(billId, operator()));
    }

    @PostMapping("/bills/{billId}/issue")
    @SaCheckPermission("billing:batch:review")
    @OperationLog(module = "计费管理", operation = "发布账单")
    public ApiResponse<Map<String, Object>> issue(@PathVariable long billId) {
        return ApiResponse.success(billingService.issue(billId, operator()));
    }

    @GetMapping("/bills/{billId}/workflow-preview")
    @SaCheckPermission("billing:bill:list")
    public ApiResponse<Map<String, Object>> workflowPreview(@PathVariable long billId) {
        return ApiResponse.success(workflowService.preview(billId));
    }

    @PostMapping("/bills/{billId}/workflow/review")
    @SaCheckPermission("billing:batch:review")
    @OperationLog(module = "结算与财务", operation = "确认账单审核")
    public ApiResponse<Map<String, Object>> confirmWorkflowReview(@PathVariable long billId) {
        return ApiResponse.success(workflowService.confirmReview(billId, operator()));
    }

    @PostMapping("/bills/{billId}/workflow/issue")
    @SaCheckPermission("billing:batch:review")
    @OperationLog(module = "结算与财务", operation = "确认账单出账")
    public ApiResponse<Map<String, Object>> confirmWorkflowIssue(@PathVariable long billId) {
        return ApiResponse.success(workflowService.confirmIssue(billId, operator()));
    }

    @PostMapping("/bills/{billId}/workflow/simulate-settlement")
    @SaCheckPermission("billing:bill:pay")
    @OperationLog(module = "结算与财务", operation = "模拟到账并自动对账")
    public ApiResponse<Map<String, Object>> simulateSettlement(@PathVariable long billId, @RequestBody(required = false) Map<String, Object> request) {
        return ApiResponse.success(workflowService.simulateSettlement(billId, request == null ? Map.of() : request, operator()));
    }

    @GetMapping("/accounts/{accountId}/finance-profile")
    @SaCheckPermission("billing:list")
    public ApiResponse<Map<String, Object>> financeProfile(@PathVariable long accountId) {
        return ApiResponse.success(financeService.profile(accountId));
    }

    @GetMapping("/accounts/{accountId}/ledger")
    @SaCheckPermission("billing:list")
    public ApiResponse<PageResult<Map<String, Object>>> ledger(@PathVariable long accountId, HttpServletRequest request) {
        return ApiResponse.success(financeService.ledger(accountId, queryParams(request)));
    }

    @PostMapping("/bills/{billId}/pay")
    @SaCheckPermission("billing:bill:pay")
    @OperationLog(module = "计费管理", operation = "账单缴费")
    public ApiResponse<Map<String, Object>> pay(@PathVariable long billId, @RequestBody Map<String, Object> request) {
        return ApiResponse.success(billingService.pay(billId, audited(request)));
    }

    @PostMapping("/payments/{paymentId}/reverse")
    @SaCheckPermission("billing:payment:reverse")
    @OperationLog(module = "计费管理", operation = "收款冲销退款")
    public ApiResponse<Map<String, Object>> reversePayment(@PathVariable long paymentId, @RequestBody Map<String, Object> request) {
        return ApiResponse.success(billingService.reversePayment(paymentId, audited(request)));
    }

    @GetMapping("/collections/overdue")
    @SaCheckPermission("billing:collection:list")
    public ApiResponse<PageResult<Map<String, Object>>> overdue(HttpServletRequest request) {
        return ApiResponse.success(collectionService.overdue(queryParams(request)));
    }

    @GetMapping("/bills/{billId}/collections")
    @SaCheckPermission("billing:bill:list")
    public ApiResponse<java.util.List<Map<String, Object>>> collections(@PathVariable long billId) {
        return ApiResponse.success(collectionService.records(billId));
    }

    @PostMapping("/bills/{billId}/collections")
    @SaCheckPermission("billing:collection:edit")
    @OperationLog(module = "计费管理", operation = "账单催缴登记")
    public ApiResponse<Map<String, Object>> createCollection(@PathVariable long billId, @RequestBody Map<String, Object> request) {
        return ApiResponse.success(collectionService.create(billId, request));
    }

    @PostMapping("/bills/{billId}/recalculate")
    @SaCheckPermission("billing:bill:generate")
    @OperationLog(module = "计费管理", operation = "账单重算")
    public ApiResponse<Map<String, Object>> recalculate(@PathVariable long billId) {
        return ApiResponse.success(billingService.recalculate(billId));
    }

    @PostMapping("/bills/{billId}/void")
    @SaCheckPermission("billing:bill:generate")
    @OperationLog(module = "计费管理", operation = "账单作废")
    public ApiResponse<Map<String, Object>> voidBill(@PathVariable long billId, @RequestBody Map<String, Object> request) {
        return ApiResponse.success(billingService.voidBill(billId, audited(request)));
    }

    @GetMapping("/rules/{ruleId}/profile")
    @SaCheckPermission("billing:list")
    public ApiResponse<Map<String, Object>> ruleProfile(@PathVariable long ruleId) {
        return ApiResponse.success(billingService.ruleProfile(ruleId));
    }

    @GetMapping("/{resource}")
    @SaCheckPermission("billing:list")
    public ApiResponse<PageResult<Map<String, Object>>> page(@PathVariable String resource, HttpServletRequest request) {
        if ("tenants".equals(resource)) return ApiResponse.success(tenantService.page(queryParams(request)));
        return ApiResponse.success(tableService.page("billing-" + resource, queryParams(request)));
    }

    @PostMapping("/{resource}")
    @SaCheckPermission("billing:add")
    @OperationLog(module = "计费管理", operation = "新增计费配置")
    public ApiResponse<Map<String, Object>> create(@PathVariable String resource, @RequestBody Map<String, Object> body) {
        if ("tenants".equals(resource)) return ApiResponse.success(tenantService.create(body));
        return ApiResponse.success(tableService.create("billing-" + resource, body));
    }

    @PutMapping("/{resource}/{id}")
    @SaCheckPermission("billing:edit")
    @OperationLog(module = "计费管理", operation = "修改计费配置")
    public ApiResponse<Map<String, Object>> update(@PathVariable String resource, @PathVariable long id,
                                                   @RequestBody Map<String, Object> body) {
        if ("tenants".equals(resource)) return ApiResponse.success(tenantService.update(id, body));
        return ApiResponse.success(tableService.update("billing-" + resource, id, body));
    }

    @DeleteMapping("/{resource}/{id}")
    @SaCheckPermission("billing:delete")
    @OperationLog(module = "计费管理", operation = "删除计费配置")
    public ApiResponse<Void> delete(@PathVariable String resource, @PathVariable long id) {
        if ("tenants".equals(resource)) { tenantService.delete(id); return ApiResponse.success(null); }
        tableService.delete("billing-" + resource, id);
        return ApiResponse.success(null);
    }

    private Map<String, String> queryParams(HttpServletRequest request) {
        return request.getParameterMap().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue()[0]));
    }

    private String operator() {
        Object username = cn.dev33.satoken.stp.StpUtil.getSession().get("username");
        return username == null ? String.valueOf(cn.dev33.satoken.stp.StpUtil.getLoginId()) : String.valueOf(username);
    }

    private Map<String, Object> audited(Map<String, Object> request) {
        Map<String, Object> body = new LinkedHashMap<>(request);
        body.put("operator", operator());
        return body;
    }
}
