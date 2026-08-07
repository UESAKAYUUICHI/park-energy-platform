package com.parkenergyplatform.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.common.PageResult;
import com.parkenergyplatform.service.OperationsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/operations")
public class OperationsController {
    private final OperationsService operationsService;

    public OperationsController(OperationsService operationsService) {
        this.operationsService = operationsService;
    }

    @GetMapping("/work-orders")
    @SaCheckPermission("ops:workorder:list")
    public ApiResponse<PageResult<Map<String, Object>>> workOrders(HttpServletRequest request) {
        return ApiResponse.success(operationsService.workOrders(params(request)));
    }

    @GetMapping("/work-orders/{id}")
    @SaCheckPermission("ops:workorder:list")
    public ApiResponse<Map<String, Object>> workOrder(@PathVariable long id) {
        return ApiResponse.success(operationsService.workOrder(id));
    }

    @GetMapping("/assignees")
    @SaCheckPermission("ops:workorder:operate")
    public ApiResponse<List<Map<String, Object>>> assignees(@RequestParam long orgId,
                                                            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(operationsService.assignees(orgId, keyword));
    }

    @PostMapping("/work-orders")
    @SaCheckPermission("ops:workorder:create")
    @OperationLog(module = "设备运维", operation = "创建人工运维工单")
    public ApiResponse<Map<String, Object>> createWorkOrder(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(operationsService.createManual(body));
    }

    @PostMapping("/alarms/{alarmId}/work-order")
    @SaCheckPermission("ops:workorder:create")
    @OperationLog(module = "设备运维", operation = "告警转运维工单")
    public ApiResponse<Map<String, Object>> createFromAlarm(@PathVariable long alarmId) {
        return ApiResponse.success(operationsService.createFromAlarm(alarmId));
    }

    @PostMapping("/work-orders/{id}/{action}")
    @SaCheckPermission("ops:workorder:operate")
    @OperationLog(module = "设备运维", operation = "流转运维工单")
    public ApiResponse<Map<String, Object>> operate(@PathVariable long id, @PathVariable String action,
                                                     @RequestBody Map<String, Object> body) {
        return ApiResponse.success(operationsService.operate(id, action, body));
    }

    @GetMapping("/inspection-plans")
    @SaCheckPermission("ops:inspection:list")
    public ApiResponse<PageResult<Map<String, Object>>> inspectionPlans(HttpServletRequest request) {
        return ApiResponse.success(operationsService.inspectionPlans(params(request)));
    }

    @PostMapping("/inspection-plans")
    @SaCheckPermission("ops:inspection:edit")
    @OperationLog(module = "设备运维", operation = "创建巡检计划")
    public ApiResponse<Map<String, Object>> createInspectionPlan(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(operationsService.saveInspectionPlan(null, body));
    }

    @PutMapping("/inspection-plans/{id}")
    @SaCheckPermission("ops:inspection:edit")
    @OperationLog(module = "设备运维", operation = "修改巡检计划")
    public ApiResponse<Map<String, Object>> updateInspectionPlan(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(operationsService.saveInspectionPlan(id, body));
    }

    @PostMapping("/inspection-tasks/generate")
    @SaCheckPermission("ops:inspection:edit")
    @OperationLog(module = "设备运维", operation = "生成巡检任务")
    public ApiResponse<Map<String, Object>> generateInspectionTasks(@RequestParam(required = false) String taskDate) {
        LocalDate date = taskDate == null || taskDate.isBlank() ? LocalDate.now() : LocalDate.parse(taskDate);
        return ApiResponse.success(Map.of("taskDate", date, "created", operationsService.generateInspectionTasks(date)));
    }

    @GetMapping("/inspection-tasks")
    @SaCheckPermission("ops:inspection:list")
    public ApiResponse<PageResult<Map<String, Object>>> inspectionTasks(HttpServletRequest request) {
        return ApiResponse.success(operationsService.inspectionTasks(params(request)));
    }

    @PostMapping("/inspection-tasks/{id}/execute")
    @SaCheckPermission("ops:inspection:operate")
    @OperationLog(module = "设备运维", operation = "执行巡检任务")
    public ApiResponse<Map<String, Object>> executeInspection(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(operationsService.executeInspection(id, body));
    }

    private Map<String, String> params(HttpServletRequest request) {
        return request.getParameterMap().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue()[0]));
    }
}
