package com.parkenergyplatform.controller;

import java.util.List;
import java.util.Map;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.common.PageResult;
import com.parkenergyplatform.service.AlarmService;
import com.parkenergyplatform.service.AlarmRuleService;
import com.parkenergyplatform.service.OperationsService;
import com.parkenergyplatform.service.PlatformBusinessQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final AlarmRuleService ruleService;
    private final PlatformBusinessQueryService queryService;
    private final OperationsService operationsService;

    public AlarmManageController(AlarmService alarmService, AlarmRuleService ruleService,
                                 PlatformBusinessQueryService queryService, OperationsService operationsService) {
        this.alarmService = alarmService;
        this.ruleService = ruleService;
        this.queryService = queryService;
        this.operationsService = operationsService;
    }

    @GetMapping("/rules")
    @SaCheckPermission("alarm:rule:list")
    public ApiResponse<PageResult<Map<String, Object>>> rules(HttpServletRequest request) {
        return ApiResponse.success(ruleService.page(queryParams(request)));
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
        return ApiResponse.success(ruleService.create(body));
    }

    @PutMapping("/rules/{id}")
    @SaCheckPermission("alarm:rule:edit")
    @OperationLog(module = "告警管理", operation = "修改告警规则")
    public ApiResponse<Map<String, Object>> updateRule(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(ruleService.update(id, body));
    }

    @DeleteMapping("/rules/{id}")
    @SaCheckPermission("alarm:rule:edit")
    @OperationLog(module = "告警管理", operation = "删除告警策略")
    public ApiResponse<Void> deleteRule(@PathVariable long id) {
        ruleService.delete(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/rules/{id}/publish")
    @SaCheckPermission("alarm:rule:edit")
    @OperationLog(module = "告警管理", operation = "发布告警规则")
    public ApiResponse<Map<String, Object>> publishRule(@PathVariable long id) {
        return ApiResponse.success(ruleService.publish(id));
    }

    @PostMapping("/rules/{id}/rollback/{versionId}")
    @SaCheckPermission("alarm:rule:edit")
    @OperationLog(module = "告警管理", operation = "回滚告警规则")
    public ApiResponse<Map<String, Object>> rollbackRule(@PathVariable long id, @PathVariable long versionId) {
        return ApiResponse.success(ruleService.rollback(id, versionId));
    }

    @GetMapping("/rules/{id}/versions")
    @SaCheckPermission("alarm:rule:list")
    public ApiResponse<List<Map<String, Object>>> ruleVersions(@PathVariable long id) {
        return ApiResponse.success(ruleService.versions(id));
    }

    @GetMapping("/rules/{id}/preview")
    @SaCheckPermission("alarm:rule:list")
    public ApiResponse<Map<String, Object>> rulePreview(@PathVariable long id) {
        return ApiResponse.success(ruleService.preview(id));
    }

    @PostMapping("/rules/{id}/trial")
    @SaCheckPermission("alarm:rule:list")
    public ApiResponse<Map<String, Object>> trialRule(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(ruleService.trial(id, body));
    }

    @PostMapping("/events/{alarmId}/deal")
    @SaCheckPermission("alarm:event:deal")
    @OperationLog(module = "告警管理", operation = "处理告警事件")
    public ApiResponse<Map<String, Object>> deal(@PathVariable long alarmId, @RequestBody Map<String, Object> request) {
        return ApiResponse.success(alarmService.deal(alarmId, request));
    }

    @GetMapping("/events/{alarmId}")
    @SaCheckPermission("alarm:rule:list")
    public ApiResponse<Map<String, Object>> eventDetail(@PathVariable long alarmId) {
        return ApiResponse.success(alarmService.detail(alarmId));
    }

    @PostMapping("/events/{alarmId}/actions/{action}")
    @SaCheckPermission("alarm:event:deal")
    @OperationLog(module = "告警管理", operation = "流转告警事件")
    public ApiResponse<Map<String, Object>> eventAction(@PathVariable long alarmId, @PathVariable String action,
                                                        @RequestBody(required = false) Map<String, Object> request) {
        return ApiResponse.success(alarmService.action(alarmId, action, request == null ? Map.of() : request));
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

    private Map<String, String> queryParams(HttpServletRequest request) {
        return request.getParameterMap().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue()[0]));
    }
}
