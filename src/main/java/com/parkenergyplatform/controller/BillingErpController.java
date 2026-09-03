package com.parkenergyplatform.controller;

import java.util.Map;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.service.BillingErpService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/billing-erp")
public class BillingErpController {
    private final BillingErpService service;

    public BillingErpController(BillingErpService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    @SaCheckPermission("billing:list")
    public ApiResponse<Map<String, Object>> overview(@RequestParam(required = false) Long orgId,
                                                     @RequestParam(required = false) String billCycle) {
        return ApiResponse.success(service.overview(orgId, billCycle));
    }

    @GetMapping("/admission")
    @SaCheckPermission("billing:list")
    public ApiResponse<Map<String, Object>> admission(@RequestParam(required = false) Long orgId,
                                                      @RequestParam(required = false) String keyword) {
        return ApiResponse.success(service.admission(orgId, keyword));
    }

    @GetMapping("/schemes")
    @SaCheckPermission("billing:list")
    public ApiResponse<Map<String, Object>> schemes(@RequestParam(required = false) Long orgId,
                                                    @RequestParam(required = false) String keyword) {
        return ApiResponse.success(service.schemes(orgId, keyword));
    }

    @GetMapping("/jobs")
    @SaCheckPermission("billing:list")
    public ApiResponse<Map<String, Object>> jobs(@RequestParam(required = false) Long orgId,
                                                 @RequestParam(required = false) String billCycle,
                                                 @RequestParam(required = false) String keyword) {
        return ApiResponse.success(service.jobs(orgId, billCycle, keyword));
    }

    @GetMapping("/closing")
    @SaCheckPermission("billing:list")
    public ApiResponse<Map<String, Object>> closing(@RequestParam(required = false) Long orgId,
                                                    @RequestParam(required = false) String billCycle) {
        return ApiResponse.success(service.closing(orgId, billCycle));
    }

    @GetMapping("/subjects/{accountId}")
    @SaCheckPermission("billing:list")
    public ApiResponse<Map<String, Object>> subjectDetail(@PathVariable long accountId,
                                                          @RequestParam(required = false) String billCycle) {
        return ApiResponse.success(service.subjectDetail(accountId, billCycle));
    }

    @GetMapping("/periods/{periodId}")
    @SaCheckPermission("billing:list")
    public ApiResponse<Map<String, Object>> closingDetail(@PathVariable long periodId) {
        return ApiResponse.success(service.closingDetail(periodId));
    }

    @PostMapping("/demo325/prepare")
    @SaCheckPermission("billing:list")
    public ApiResponse<Map<String, Object>> prepareDemo325(@RequestParam(required = false) String billCycle) {
        return ApiResponse.success(service.prepareDemo325(billCycle));
    }
}
