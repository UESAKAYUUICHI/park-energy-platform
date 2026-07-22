package com.parkenergyplatform.controller;

import java.util.Map;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.common.PageResult;
import com.parkenergyplatform.service.BillingService;
import com.parkenergyplatform.service.SimpleTableService;
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
    private final SimpleTableService tableService;

    public BillingController(BillingService billingService, SimpleTableService tableService) {
        this.billingService = billingService;
        this.tableService = tableService;
    }

    @PostMapping("/bills/generate")
    @SaCheckPermission("billing:bill:generate")
    @OperationLog(module = "计费管理", operation = "生成账单")
    public ApiResponse<Map<String, Object>> generate(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(billingService.generate(request));
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

    @PostMapping("/bills/{billId}/pay")
    @SaCheckPermission("billing:bill:pay")
    @OperationLog(module = "计费管理", operation = "账单缴费")
    public ApiResponse<Map<String, Object>> pay(@PathVariable long billId, @RequestBody Map<String, Object> request) {
        return ApiResponse.success(billingService.pay(billId, request));
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
        return ApiResponse.success(billingService.voidBill(billId, request));
    }

    @GetMapping("/rules/{ruleId}/profile")
    @SaCheckPermission("billing:list")
    public ApiResponse<Map<String, Object>> ruleProfile(@PathVariable long ruleId) {
        return ApiResponse.success(billingService.ruleProfile(ruleId));
    }

    @GetMapping("/{resource}")
    @SaCheckPermission("billing:list")
    public ApiResponse<PageResult<Map<String, Object>>> page(@PathVariable String resource, HttpServletRequest request) {
        return ApiResponse.success(tableService.page("billing-" + resource, queryParams(request)));
    }

    @PostMapping("/{resource}")
    @SaCheckPermission("billing:add")
    @OperationLog(module = "计费管理", operation = "新增计费配置")
    public ApiResponse<Map<String, Object>> create(@PathVariable String resource, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(tableService.create("billing-" + resource, body));
    }

    @PutMapping("/{resource}/{id}")
    @SaCheckPermission("billing:edit")
    @OperationLog(module = "计费管理", operation = "修改计费配置")
    public ApiResponse<Map<String, Object>> update(@PathVariable String resource, @PathVariable long id,
                                                   @RequestBody Map<String, Object> body) {
        return ApiResponse.success(tableService.update("billing-" + resource, id, body));
    }

    @DeleteMapping("/{resource}/{id}")
    @SaCheckPermission("billing:delete")
    @OperationLog(module = "计费管理", operation = "删除计费配置")
    public ApiResponse<Void> delete(@PathVariable String resource, @PathVariable long id) {
        tableService.delete("billing-" + resource, id);
        return ApiResponse.success(null);
    }

    private Map<String, String> queryParams(HttpServletRequest request) {
        return request.getParameterMap().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue()[0]));
    }
}
