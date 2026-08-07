package com.parkenergyplatform.controller;

import java.util.List;
import java.util.Map;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.common.PageResult;
import com.parkenergyplatform.service.AlarmService;
import com.parkenergyplatform.service.OperationsService;
import com.parkenergyplatform.service.PlatformBusinessQueryService;
import com.parkenergyplatform.service.SimpleTableService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/alarms")
public class AlarmManageController {
    private final AlarmService alarmService;
    private final SimpleTableService tableService;
    private final PlatformBusinessQueryService queryService;
    private final OperationsService operationsService;

    public AlarmManageController(AlarmService alarmService, SimpleTableService tableService,
                                 PlatformBusinessQueryService queryService, OperationsService operationsService) {
        this.alarmService = alarmService;
        this.tableService = tableService;
        this.queryService = queryService;
        this.operationsService = operationsService;
    }

    @GetMapping("/rules")
    @SaCheckPermission("alarm:rule:list")
    public ApiResponse<PageResult<Map<String, Object>>> rules(HttpServletRequest request) {
        return ApiResponse.success(tableService.page("alarm-rules", queryParams(request)));
    }

    @GetMapping("/rules/{ruleId}/profile")
    @SaCheckPermission("alarm:rule:list")
    public ApiResponse<Map<String, Object>> ruleProfile(@PathVariable long ruleId) {
        return ApiResponse.success(queryService.alarmRuleProfile(ruleId));
    }

    @PostMapping("/rules")
    @SaCheckPermission("alarm:rule:add")
    @OperationLog(module = "告警管理", operation = "新增告警规则")
    public ApiResponse<Map<String, Object>> createRule(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(tableService.create("alarm-rules", body));
    }

    @PutMapping("/rules/{id}")
    @SaCheckPermission("alarm:rule:edit")
    @OperationLog(module = "告警管理", operation = "修改告警规则")
    public ApiResponse<Map<String, Object>> updateRule(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(tableService.update("alarm-rules", id, body));
    }

    @PostMapping("/events/{alarmId}/deal")
    @SaCheckPermission("alarm:event:deal")
    @OperationLog(module = "告警管理", operation = "处理告警事件")
    public ApiResponse<Map<String, Object>> deal(@PathVariable long alarmId, @RequestBody Map<String, Object> request) {
        return ApiResponse.success(alarmService.deal(alarmId, request));
    }

    @PostMapping("/events/{alarmId}/work-order")
    @SaCheckPermission("ops:workorder:create")
    @OperationLog(module = "告警管理", operation = "告警转运维工单")
    public ApiResponse<Map<String, Object>> createWorkOrder(@PathVariable long alarmId) {
        return ApiResponse.success(operationsService.createFromAlarm(alarmId));
    }

    @GetMapping("/events")
    @SaCheckPermission("alarm:rule:list")
    public ApiResponse<PageResult<Map<String, Object>>> events(HttpServletRequest request) {
        return ApiResponse.success(queryService.alarmEvents(queryParams(request)));
    }

    @GetMapping("/summary")
    @SaCheckPermission("alarm:rule:list")
    public ApiResponse<Map<String, Object>> summary(HttpServletRequest request) {
        return ApiResponse.success(queryService.alarmSummary(queryParams(request)));
    }

    @PostMapping("/events/batch-deal")
    @SaCheckPermission("alarm:event:deal")
    @OperationLog(module = "告警管理", operation = "批量处理告警事件")
    @SuppressWarnings("unchecked")
    public ApiResponse<Object> batchDeal(@RequestBody Map<String, Object> request) {
        Object ids = request.get("alarmIds");
        if (!(ids instanceof List<?> list)) {
            return ApiResponse.success(List.of());
        }
        List<Map<String, Object>> rows = list.stream()
                .map(id -> alarmService.deal(Long.parseLong(id.toString()), request))
                .toList();
        return ApiResponse.success(rows);
    }

    private Map<String, String> queryParams(HttpServletRequest request) {
        return request.getParameterMap().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue()[0]));
    }
}
