package com.parkenergyplatform.service;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import cn.dev33.satoken.exception.SaTokenContextException;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.common.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Operational closure for alarms, manual faults and recurring device inspections. */
@Service
public class OperationsService {
    private final JdbcTemplate jdbcTemplate;
    private final BusinessDataAccessService accessService;
    private final ObjectMapper objectMapper;
    private final EnergySavingVerificationService energySavingVerificationService;

    public OperationsService(JdbcTemplate jdbcTemplate, BusinessDataAccessService accessService, ObjectMapper objectMapper,
                             EnergySavingVerificationService energySavingVerificationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessService = accessService;
        this.objectMapper = objectMapper;
        this.energySavingVerificationService = energySavingVerificationService;
    }

    public PageResult<Map<String, Object>> workOrders(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        equals(where, args, "w.status", params.get("status"));
        equals(where, args, "w.priority", params.get("priority"));
        equals(where, args, "w.assignee_user_id", params.get("assigneeUserId"));
        equals(where, args, "w.device_id", params.get("deviceId"));
        String keyword = text(params.get("keyword"));
        if (keyword != null) {
            where.append(" AND (w.work_order_no LIKE ? OR w.title LIKE ? OR d.device_name LIKE ? OR d.device_sn LIKE ?)");
            for (int i = 0; i < 4; i++) args.add("%" + keyword + "%");
        }
        where.append(accessService.scopeSql("w.org_id", args));
        return page(params, where, args, """
                FROM ops_work_order w
                LEFT JOIN dev_device d ON d.id=w.device_id
                LEFT JOIN dev_gateway g ON g.id=w.gateway_id
                LEFT JOIN log_alarm alarm ON w.source_type='ALARM' AND alarm.id=w.source_id
                LEFT JOIN alarm_rule_version rule_version ON rule_version.id=alarm.rule_version_id
                """, """
                SELECT w.*, d.device_sn, d.device_name, g.gateway_sn, g.gateway_name, alarm.id AS alarm_id,
                       alarm.event_status AS alarm_event_status, alarm.condition_status AS alarm_condition_status,
                       alarm.point_code AS alarm_point_code, alarm.alarm_value, alarm.threshold_value,
                       COALESCE(rule_version.rule_name,'') AS alarm_rule_name,
                       CASE
                         WHEN alarm.id IS NULL THEN '人工工单：依据问题描述完成现场诊断并记录处理结果'
                         WHEN alarm.condition_status='CLEARED' THEN '设备数据已恢复：复核现场状态，确认无残留风险后退出工单'
                         WHEN alarm.alarm_type=1 THEN '过压告警：检查供电电压、接线与负载切换记录'
                         WHEN alarm.alarm_type=2 THEN '欠压告警：检查进线电压、压降和大负载启动情况'
                         WHEN alarm.alarm_type=3 THEN '过流告警：检查负载、回路温升及保护装置状态'
                         WHEN alarm.alarm_type=4 THEN '离线告警：检查设备供电、网关链路和通信参数'
                         ELSE '数据异常：核对测点映射、采集质量和设备现场读数'
                       END AS alarm_suggestion
                """);
    }

    public Map<String, Object> workOrder(long id) {
        Map<String, Object> row = requiredWorkOrder(id, false);
        attachReferenceLabels(row);
        row.put("logs", jdbcTemplate.queryForList("SELECT * FROM ops_work_order_log WHERE work_order_id=? ORDER BY id", id));
        try {
            List<Map<String, Object>> verification = jdbcTemplate.queryForList("""
                    SELECT v.*, d.space_id, s.space_name, b.metric_point_code
                    FROM ops_energy_saving_verification v
                    LEFT JOIN dev_device d ON d.id=v.device_id
                    LEFT JOIN park_space s ON s.id=d.space_id
                    LEFT JOIN energy_efficiency_baseline b ON b.id=v.baseline_id
                    WHERE v.work_order_id=?
                    """, id);
            if (!verification.isEmpty()) row.put("energyVerification", verification.get(0));
        } catch (RuntimeException ignored) {
            // The optional enterprise-energy migration may be pending; old work-order detail must still render.
        }
        return row;
    }

    public Map<String, Long> workOrderStatusCounts(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        equals(where, args, "w.priority", params.get("priority"));
        equals(where, args, "w.assignee_user_id", params.get("assigneeUserId"));
        equals(where, args, "w.device_id", params.get("deviceId"));
        String keyword = text(params.get("keyword"));
        if (keyword != null) {
            where.append(" AND (w.work_order_no LIKE ? OR w.title LIKE ? OR d.device_name LIKE ? OR d.device_sn LIKE ?)");
            for (int i = 0; i < 4; i++) args.add("%" + keyword + "%");
        }
        where.append(accessService.scopeSql("w.org_id", args));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT w.status,COUNT(*) AS total
                FROM ops_work_order w LEFT JOIN dev_device d ON d.id=w.device_id
                """ + where + " GROUP BY w.status", args.toArray());
        Map<String, Long> result = new LinkedHashMap<>();
        long all = 0;
        for (Map<String, Object> row : rows) {
            long count = number(row.get("total"));
            result.put(textOr(row.get("status"), "UNKNOWN"), count);
            all += count;
        }
        result.put("ALL", all);
        return result;
    }

    public List<Map<String, Object>> assignees(long orgId, String keyword) {
        return eligibleAssignees(orgId, keyword,
                List.of("ops:workorder:execute", "ops:workorder:accept", "ops:workorder:operate", "*"));
    }

    public List<Map<String, Object>> inspectionAssignees(long orgId, String keyword) {
        return eligibleAssignees(orgId, keyword, List.of("ops:inspection:operate", "*"));
    }

    private List<Map<String, Object>> eligibleAssignees(long orgId, String keyword, List<String> permissions) {
        assertOrg(orgId);
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        args.add(orgId);
        args.add(orgId);
        args.addAll(permissions);
        String permissionPlaceholders = String.join(",", java.util.Collections.nCopies(permissions.size(), "?"));
        String sql = """
                WITH RECURSIVE org_ancestors AS (
                    SELECT id, parent_id FROM dev_org WHERE id = ?
                    UNION ALL
                    SELECT parent.id, parent.parent_id
                    FROM dev_org parent JOIN org_ancestors child ON child.parent_id = parent.id
                )
                SELECT DISTINCT u.id, u.username, u.nickname, u.phone, u.org_id AS orgId,
                       home.org_name AS orgName
                FROM sys_user u
                LEFT JOIN dev_org home ON home.id = u.org_id
                LEFT JOIN sys_user_org_scope scope ON scope.user_id = u.id
                WHERE u.status = 1
                  AND (u.org_id = ? OR (scope.scope_mode = 'SELF' AND scope.org_id = ?)
                       OR (scope.scope_mode = 'SUBTREE' AND scope.org_id IN (SELECT id FROM org_ancestors)))
                  AND EXISTS (
                      SELECT 1 FROM sys_user_role ur
                      JOIN sys_role role ON role.id = ur.role_id AND role.status = 1
                      JOIN sys_role_permission rp ON rp.role_id = role.id
                      JOIN sys_permission permission ON permission.id = rp.permission_id AND permission.status = 1
                      WHERE ur.user_id = u.id AND permission.perm_code IN (%s)
                  )
                """.formatted(permissionPlaceholders);
        if (text(keyword) != null) {
            sql += " AND (u.username LIKE ? OR u.nickname LIKE ? OR u.phone LIKE ?)";
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql += " ORDER BY CASE WHEN u.org_id = ? THEN 0 ELSE 1 END, u.nickname, u.username LIMIT 100";
        args.add(orgId);
        return jdbcTemplate.queryForList(sql, args.toArray());
    }

    @Transactional
    public Map<String, Object> createManual(Map<String, Object> body) {
        long orgId = requiredLong(body.get("orgId"), "orgId");
        assertOrg(orgId);
        Long deviceId = nullableLong(body.get("deviceId"));
        if (deviceId != null) {
            accessService.assertDeviceAccess(deviceId);
            Long deviceOrgId = jdbcTemplate.queryForObject("SELECT org_id FROM dev_device WHERE id=?", Long.class, deviceId);
            if (!Objects.equals(orgId, deviceOrgId)) throw new BusinessException("工单组织必须与关联设备所属组织一致");
        }
        Long gatewayId = nullableLong(body.get("gatewayId"));
        if (gatewayId != null) {
            accessService.assertGatewayAccess(gatewayId);
            Long gatewayOrgId = jdbcTemplate.queryForObject("SELECT org_id FROM dev_gateway WHERE id=?", Long.class, gatewayId);
            if (!Objects.equals(orgId, gatewayOrgId)) throw new BusinessException("工单组织必须与关联网关所属组织一致");
        }
        return createWorkOrder(orgId, "MANUAL", null, textOr(body.get("workType"), "FAULT"),
                textOr(body.get("priority"), "P2"), deviceId, gatewayId,
                requiredText(body.get("title"), "title"), text(body.get("description")), nullableDateTime(body.get("slaDueTime")));
    }

    @Transactional
    public Map<String, Object> createFromDataQuality(long eventId) {
        Map<String, Object> event = single("""
                SELECT e.id, e.status, e.error_code, e.error_reason, e.message_id,
                       g.id AS gateway_id, g.gateway_name, g.gateway_sn, g.org_id
                FROM data_ingest_event e JOIN dev_gateway g ON g.id=e.gateway_id
                WHERE e.id=?
                """, eventId);
        accessService.assertGatewayAccess(number(event.get("gateway_id")));
        String status = textOr(event.get("status"), "").toUpperCase();
        if (!List.of("INVALID", "DEAD_LETTER", "REPLAY_REQUESTED").contains(status)) {
            throw new BusinessException("只有异常、死信或待重放事件可以创建数据质量工单");
        }
        Map<String, Object> existing = activeSourceWorkOrder("DATA_QUALITY", eventId);
        if (existing != null) return workOrder(number(existing.get("id")));
        String priority = "DEAD_LETTER".equals(status) ? "P1" : "P2";
        String gateway = textOr(event.get("gateway_name"), textOr(event.get("gateway_sn"), "网关"));
        String title = "数据质量工单：" + gateway + " / " + textOr(event.get("error_code"), "采集异常");
        String description = "消息 " + textOr(event.get("message_id"), "-") + "：" + textOr(event.get("error_reason"), "请检查采集链路、设备映射和原始报文");
        return createWorkOrder(number(event.get("org_id")), "DATA_QUALITY", eventId, "DATA_QUALITY", priority,
                null, number(event.get("gateway_id")), title, description, null);
    }

    @Transactional
    public Map<String, Object> createFromAlarm(long alarmId) {
        accessService.assertAlarmAccess(alarmId);
        return createFromAlarmWorkflow(alarmId, true);
    }

    /** Internal idempotent entry used by the alarm automation scheduler. */
    @Transactional
    public Map<String, Object> createFromAlarmSystem(long alarmId) {
        return createFromAlarmWorkflow(alarmId, false);
    }

    private Map<String, Object> createFromAlarmWorkflow(long alarmId, boolean userRequest) {
        Map<String, Object> alarm = single("SELECT * FROM log_alarm WHERE id=? FOR UPDATE", alarmId);
        String eventStatus = textOr(alarm.get("event_status"), "NEW").toUpperCase();
        String conditionStatus = textOr(alarm.get("condition_status"), "ACTIVE").toUpperCase();
        if (!"ACTIVE".equals(conditionStatus) || List.of("RECOVERED", "CLOSED", "FALSE_POSITIVE").contains(eventStatus)) {
            throw new BusinessException("告警已恢复或已关闭，不再创建处置工单");
        }
        Map<String, Object> existing = activeSourceWorkOrder("ALARM", alarmId);
        if (existing != null) {
            if (List.of("NEW", "ACKNOWLEDGED", "SUPPRESSED").contains(eventStatus)) {
                startSourceAlarm(alarmId, actor(), "继续关联已有运维工单 " + existing.get("work_order_no"));
            }
            return userRequest ? workOrder(number(existing.get("id"))) : existing;
        }
        String priority = switch (intValue(alarm.get("alarm_level"), 2)) { case 3 -> "P1"; case 2 -> "P2"; default -> "P3"; };
        Long alarmDeviceId = nullableLong(alarm.get("device_id"));
        String title = "告警工单：" + textOr(alarm.get("point_code"), "设备异常") + " / " + deviceLabel(alarmDeviceId);
        Map<String, Object> order = createWorkOrder(number(alarm.get("org_id")), "ALARM", alarmId, "FAULT", priority,
                alarmDeviceId, null, title,
                "告警值=" + textOr(alarm.get("alarm_value"), "-") + "，阈值=" + textOr(alarm.get("threshold_value"), "-"), null);
        jdbcTemplate.update("UPDATE log_alarm SET work_order_id=? WHERE id=?", order.get("id"), alarmId);
        startSourceAlarm(alarmId, actor(), "已创建运维工单 " + order.get("work_order_no"));
        return userRequest ? workOrder(number(order.get("id"))) : order;
    }

    /** Closes a recovered incident and any linked work order after the stable recovery window. */
    @Transactional
    public boolean autoCloseRecoveredAlarm(long alarmId, int recoveryHoldSeconds) {
        Map<String, Object> alarm = single("SELECT * FROM log_alarm WHERE id=? FOR UPDATE", alarmId);
        if (!"RECOVERED".equalsIgnoreCase(textOr(alarm.get("event_status"), ""))
                || !"CLEARED".equalsIgnoreCase(textOr(alarm.get("condition_status"), ""))) return false;
        Timestamp recoveredAt = alarm.get("recovery_time") instanceof Timestamp timestamp ? timestamp : null;
        if (!stableRecoveryReached(recoveredAt, recoveryHoldSeconds, LocalDateTime.now())) return false;

        Actor system = new Actor(null, "system");
        List<Map<String, Object>> orders = jdbcTemplate.queryForList("""
                SELECT * FROM ops_work_order
                WHERE source_type='ALARM' AND source_id=? AND status NOT IN ('CLOSED','CANCELLED')
                FOR UPDATE
                """, alarmId);
        for (Map<String, Object> order : orders) {
            String from = textOr(order.get("status"), "PENDING");
            jdbcTemplate.update("""
                    UPDATE ops_work_order SET status='CLOSED', active_source_key=NULL, close_type='AUTO_RECOVERY', update_by='system',
                        completed_time=COALESCE(completed_time,NOW()), verified_time=NOW(), closed_time=NOW(),
                        verify_remark=CONCAT(COALESCE(verify_remark,''), ?)
                    WHERE id=?
                    """, " [系统自动闭环] 设备数据连续恢复正常 " + recoveryHoldSeconds + " 秒", order.get("id"));
            log(number(order.get("id")), "AUTO_RECOVERY_CLOSE", from, "CLOSED", system,
                    "告警恢复观察期结束，系统自动关闭工单");
            notify(number(order.get("org_id")), nullableLong(order.get("assignee_user_id")), "AUTO_CLOSED",
                    number(order.get("id")), "工单已自动关闭", order.get("work_order_no") + " 对应设备数据已恢复");
            energySavingVerificationService.verifyOnClosure(order, "system");
        }
        int updated = jdbcTemplate.update("""
                UPDATE log_alarm SET event_status='CLOSED', active_fingerprint=NULL, deal_status=1,
                    deal_time=NOW(), deal_user='system', deal_remark=?, close_time=NOW(), close_user_id=NULL,
                    close_user='system', close_code='AUTO_RECOVERY', version=version+1
                WHERE id=? AND event_status='RECOVERED' AND condition_status='CLEARED'
                """, "设备数据连续恢复正常 " + recoveryHoldSeconds + " 秒，系统自动闭环", alarmId);
        if (updated == 0) return false;
        alarmLog(alarmId, "AUTO_CLOSE", "RECOVERED", "CLOSED", system,
                "恢复观察期 " + recoveryHoldSeconds + " 秒结束，告警及关联工单已自动关闭");
        return true;
    }

    static boolean stableRecoveryReached(Timestamp recoveredAt, int holdSeconds, LocalDateTime now) {
        return recoveredAt != null && !recoveredAt.toLocalDateTime().plusSeconds(Math.max(holdSeconds, 1)).isAfter(now);
    }

    @Transactional
    public Map<String, Object> operate(long id, String action, Map<String, Object> body) {
        Map<String, Object> order = requiredWorkOrder(id, true);
        String status = textOr(order.get("status"), "PENDING");
        Actor actor = actor();
        String normalized = requiredText(action, "action").toUpperCase();
        switch (normalized) {
            case "ASSIGN" -> {
                requireStatus(status, "PENDING", "ASSIGNED");
                Long assigneeId = requiredLong(body.get("assigneeUserId"), "assigneeUserId");
                Map<String, Object> assignee = assigneeForOrg(number(order.get("org_id")), assigneeId);
                String assigneeName = textOr(assignee.get("nickname"), textOr(assignee.get("username"), String.valueOf(assigneeId)));
                jdbcTemplate.update("UPDATE ops_work_order SET status='ASSIGNED', assignee_user_id=?, assignee_name=?, assign_time=NOW(), sla_due_time=COALESCE(?, sla_due_time), update_by=? WHERE id=?",
                        assigneeId, assigneeName, nullableTimestamp(body.get("slaDueTime")), actor.name(), id);
                log(id, "ASSIGN", status, "ASSIGNED", actor, text(body.get("remark")));
                notify(number(order.get("org_id")), assigneeId, "WORK_ORDER_ASSIGNED", id,
                        "工单已分派", order.get("work_order_no") + " 已分派给你处理");
            }
            case "ACCEPT" -> {
                requireStatus(status, "ASSIGNED");
                assertAssignee(order, actor, "工单");
                jdbcTemplate.update("UPDATE ops_work_order SET status='ACCEPTED', accepted_time=NOW(), update_by=? WHERE id=?", actor.name(), id);
                log(id, "ACCEPT", status, "ACCEPTED", actor, text(body.get("remark")));
            }
            case "ARRIVE" -> {
                requireStatus(status, "ACCEPTED", "PROCESSING");
                assertAssignee(order, actor, "工单");
                jdbcTemplate.update("UPDATE ops_work_order SET status='PROCESSING', arrived_time=COALESCE(arrived_time, NOW()), update_by=? WHERE id=?", actor.name(), id);
                log(id, "ARRIVE", status, "PROCESSING", actor, text(body.get("remark")));
            }
            case "COMPLETE" -> {
                requireStatus(status, "ACCEPTED", "PROCESSING");
                assertAssignee(order, actor, "工单");
                jdbcTemplate.update("UPDATE ops_work_order SET status='VERIFYING', completed_time=NOW(), cause_category=?, solution=?, evidence_urls=?, update_by=? WHERE id=?",
                        text(body.get("causeCategory")), requiredText(body.get("solution"), "solution"), text(body.get("evidenceUrls")), actor.name(), id);
                log(id, "COMPLETE", status, "VERIFYING", actor, text(body.get("remark")));
            }
            case "VERIFY" -> {
                requireStatus(status, "VERIFYING");
                String remark = requiredText(body.get("verifyRemark"), "verifyRemark");
                assertSourceAlarmRecovered(order);
                jdbcTemplate.update("UPDATE ops_work_order SET status='CLOSED', active_source_key=NULL, close_type='MANUAL_VERIFY', verified_time=NOW(), closed_time=NOW(), verify_remark=?, update_by=? WHERE id=?", remark, actor.name(), id);
                log(id, "VERIFY", status, "CLOSED", actor, remark);
                closeSourceAlarm(order, actor, remark);
                energySavingVerificationService.verifyOnClosure(order, actor.name());
            }
            case "CANCEL" -> {
                requireStatus(status, "PENDING", "ASSIGNED");
                jdbcTemplate.update("UPDATE ops_work_order SET status='CANCELLED', active_source_key=NULL, close_type='MANUAL_CANCEL', closed_time=NOW(), verify_remark=?, update_by=? WHERE id=?", requiredText(body.get("remark"), "remark"), actor.name(), id);
                log(id, "CANCEL", status, "CANCELLED", actor, text(body.get("remark")));
                jdbcTemplate.update("UPDATE log_alarm SET work_order_id=NULL, update_by=? WHERE work_order_id=?", actor.name(), id);
                reopenSourceAlarmAfterCancellation(order, actor, text(body.get("remark")));
            }
            default -> throw new BusinessException("不支持的工单动作: " + action);
        }
        return workOrder(id);
    }

    public PageResult<Map<String, Object>> inspectionPlans(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        equals(where, args, "p.enabled", params.get("enabled"));
        where.append(accessService.scopeSql("p.org_id", args));
        return page(params, where, args, " FROM ops_inspection_plan p LEFT JOIN dev_device d ON d.id=p.scope_id",
                "SELECT p.*, d.device_sn, d.device_name");
    }

    public PageResult<Map<String, Object>> inspectionTasks(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        equals(where, args, "t.status", params.get("status"));
        equals(where, args, "t.task_date", params.get("taskDate"));
        equals(where, args, "t.device_id", params.get("deviceId"));
        where.append(accessService.scopeSql("t.org_id", args));
        return page(params, where, args, " FROM ops_inspection_task t JOIN ops_inspection_plan p ON p.id=t.plan_id JOIN dev_device d ON d.id=t.device_id",
                "SELECT t.*, p.plan_no, p.plan_name, d.device_sn, d.device_name");
    }

    @Transactional
    public Map<String, Object> saveInspectionPlan(Long id, Map<String, Object> body) {
        Actor actor = actor();
        long orgId = requiredLong(body.get("orgId"), "orgId");
        assertOrg(orgId);
        String scopeType = textOr(body.get("scopeType"), "ORG").toUpperCase();
        Long scopeId = nullableLong(body.get("scopeId"));
        if (!"ORG".equals(scopeType) && !"DEVICE".equals(scopeType)) throw new BusinessException("scopeType 仅支持 ORG 或 DEVICE");
        if ("DEVICE".equals(scopeType)) {
            if (scopeId == null) throw new BusinessException("设备范围必须选择设备");
            accessService.assertDeviceAccess(scopeId);
            Long deviceOrgId = jdbcTemplate.queryForObject("SELECT org_id FROM dev_device WHERE id=?", Long.class, scopeId);
            if (!Objects.equals(orgId, deviceOrgId)) throw new BusinessException("巡检计划组织必须与指定设备所属组织一致");
        } else scopeId = null;
        int cycleDays = boundedInt(body.get("cycleDays"), 1, 1, 365);
        int deadlineHour = boundedInt(body.get("deadlineHour"), 18, 0, 23);
        Long inspectionAssigneeId = nullableLong(body.get("assigneeUserId"));
        String inspectionAssigneeName = inspectionAssigneeName(orgId, inspectionAssigneeId);
        String checklistJson = normalizeChecklist(body.get("checklistJson"));
        if (id == null) {
            String planNo = textOr(body.get("planNo"), "IP" + System.currentTimeMillis());
            jdbcTemplate.update("INSERT INTO ops_inspection_plan (plan_no, plan_name, org_id, scope_type, scope_id, cycle_days, deadline_hour, assignee_user_id, assignee_name, checklist_json, enabled, remark, create_by, update_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    planNo, requiredText(body.get("planName"), "planName"), orgId, scopeType, scopeId, cycleDays, deadlineHour,
                    inspectionAssigneeId, inspectionAssigneeName, checklistJson, intValue(body.get("enabled"), 1), text(body.get("remark")), actor.name(), actor.name());
            id = jdbcTemplate.queryForObject("SELECT id FROM ops_inspection_plan WHERE plan_no=?", Long.class, planNo);
        } else {
            Map<String, Object> existing = single("SELECT * FROM ops_inspection_plan WHERE id=?", id);
            assertOrg(number(existing.get("org_id")));
            jdbcTemplate.update("UPDATE ops_inspection_plan SET plan_name=?, org_id=?, scope_type=?, scope_id=?, cycle_days=?, deadline_hour=?, assignee_user_id=?, assignee_name=?, checklist_json=?, enabled=?, remark=?, update_by=? WHERE id=?",
                    requiredText(body.get("planName"), "planName"), orgId, scopeType, scopeId, cycleDays, deadlineHour,
                    inspectionAssigneeId, inspectionAssigneeName, checklistJson, intValue(body.get("enabled"), 1), text(body.get("remark")), actor.name(), id);
        }
        return single("SELECT * FROM ops_inspection_plan WHERE id=?", id);
    }

    @Transactional
    public Map<String, Object> executeInspection(long taskId, Map<String, Object> body) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM ops_inspection_task WHERE id=? FOR UPDATE", taskId);
        if (rows.isEmpty()) throw new BusinessException(404, "巡检任务不存在");
        Map<String, Object> task = new LinkedHashMap<>(rows.get(0));
        assertOrg(number(task.get("org_id")));
        String status = textOr(task.get("status"), "PENDING");
        requireStatus(status, "PENDING", "PROCESSING");
        String result = requiredText(body.get("result"), "result").toUpperCase();
        if (!"NORMAL".equals(result) && !"ABNORMAL".equals(result)) throw new BusinessException("巡检结果仅支持 NORMAL 或 ABNORMAL");
        Actor actor = actor();
        assertAssignee(task, actor, "巡检任务");
        Long workOrderId = null;
        if ("ABNORMAL".equals(result)) {
            Map<String, Object> existing = activeSourceWorkOrder("INSPECTION", taskId);
            if (existing == null) {
                Long taskDeviceId = nullableLong(task.get("device_id"));
                Map<String, Object> order = createWorkOrder(number(task.get("org_id")), "INSPECTION", taskId, "INSPECTION", "P2",
                        taskDeviceId, null, "巡检异常工单：" + deviceLabel(taskDeviceId),
                        requiredText(body.get("resultRemark"), "resultRemark"), null);
                workOrderId = number(order.get("id"));
            } else workOrderId = number(existing.get("id"));
        }
        jdbcTemplate.update("UPDATE ops_inspection_task SET status=?, result=?, result_remark=?, evidence_urls=?, check_time=NOW(), work_order_id=?, update_by=? WHERE id=?",
                "ABNORMAL".equals(result) ? "ABNORMAL" : "COMPLETED", result, text(body.get("resultRemark")), text(body.get("evidenceUrls")), workOrderId, actor.name(), taskId);
        return single("SELECT * FROM ops_inspection_task WHERE id=?", taskId);
    }

    @Transactional
    public int generateInspectionTasksForCurrentAccess(LocalDate taskDate) {
        List<Object> args = new ArrayList<>();
        String scope = accessService.scopeSql("p.org_id", args);
        List<Map<String, Object>> plans = jdbcTemplate.queryForList(
                "SELECT p.* FROM ops_inspection_plan p WHERE p.enabled=1" + scope, args.toArray());
        return generateInspectionTasks(taskDate, plans);
    }

    public int generateInspectionTasks(LocalDate taskDate) {
        return generateInspectionTasks(taskDate,
                jdbcTemplate.queryForList("SELECT p.* FROM ops_inspection_plan p WHERE p.enabled=1"));
    }

    private int generateInspectionTasks(LocalDate taskDate, List<Map<String, Object>> plans) {
        int created = 0;
        for (Map<String, Object> plan : plans) {
            LocalDate last = localDate(plan.get("last_generate_date"));
            int cycle = boundedInt(plan.get("cycle_days"), 1, 1, 365);
            if (last != null && last.plusDays(cycle).isAfter(taskDate)) continue;
            List<Long> deviceIds = "DEVICE".equalsIgnoreCase(textOr(plan.get("scope_type"), "ORG"))
                    ? List.of(number(plan.get("scope_id")))
                    : jdbcTemplate.queryForList("SELECT id FROM dev_device WHERE org_id=? AND status=1", Long.class, plan.get("org_id"));
            for (Long deviceId : deviceIds) {
                String taskNo = "IT" + taskDate.toString().replace("-", "") + "-" + plan.get("id") + "-" + deviceId;
                LocalDateTime due = LocalDateTime.of(taskDate, LocalTime.of(boundedInt(plan.get("deadline_hour"), 18, 0, 23), 0));
                int inserted = jdbcTemplate.update("INSERT IGNORE INTO ops_inspection_task (task_no, plan_id, org_id, device_id, task_date, assignee_user_id, assignee_name, due_time, create_by, update_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'system', 'system')",
                        taskNo, plan.get("id"), plan.get("org_id"), deviceId, Date.valueOf(taskDate), plan.get("assignee_user_id"), plan.get("assignee_name"), Timestamp.valueOf(due));
                created += inserted;
            }
            jdbcTemplate.update("UPDATE ops_inspection_plan SET last_generate_date=?, update_by='system' WHERE id=?", Date.valueOf(taskDate), plan.get("id"));
        }
        return created;
    }

    @Transactional
    @Scheduled(cron = "${park.operations.inspection-cron:0 10 0 * * *}")
    public void generateDailyInspectionTasks() {
        Integer locked = jdbcTemplate.queryForObject("SELECT GET_LOCK('park_inspection_daily_generation',0)", Integer.class);
        if (locked == null || locked != 1) return;
        try {
            generateInspectionTasks(LocalDate.now());
        } finally {
            jdbcTemplate.queryForObject("SELECT RELEASE_LOCK('park_inspection_daily_generation')", Integer.class);
        }
    }

    /** Escalates response/resolution SLA breaches while retaining the accountable assignee. */
    @Transactional
    @Scheduled(fixedDelayString = "${park.operations.sla-monitor-interval-ms:30000}",
            initialDelayString = "${park.operations.sla-monitor-initial-delay-ms:15000}")
    public void monitorWorkOrderSla() {
        Integer locked = jdbcTemplate.queryForObject("SELECT GET_LOCK('park_work_order_sla_monitor',0)", Integer.class);
        if (locked == null || locked != 1) return;
        try {
            List<Map<String, Object>> overdue = jdbcTemplate.queryForList("""
                    SELECT w.*,p.escalation_minutes,p.max_escalation_level
                    FROM ops_work_order w JOIN ops_sla_policy p ON BINARY p.priority=BINARY w.priority AND p.enabled=1
                    WHERE w.status NOT IN ('CLOSED','CANCELLED')
                      AND ((w.accepted_time IS NULL AND w.response_due_time<NOW()) OR w.sla_due_time<NOW())
                      AND w.escalation_level<p.max_escalation_level
                      AND (w.last_escalation_time IS NULL OR TIMESTAMPDIFF(MINUTE,w.last_escalation_time,NOW())>=p.escalation_minutes)
                    ORDER BY w.priority,w.sla_due_time LIMIT 100
                    """);
            for (Map<String, Object> order : overdue) escalate(order);
        } finally {
            jdbcTemplate.queryForObject("SELECT RELEASE_LOCK('park_work_order_sla_monitor')", Integer.class);
        }
    }

    private void escalate(Map<String, Object> order) {
        long id = number(order.get("id"));
        int nextLevel = intValue(order.get("escalation_level"), 0) + 1;
        jdbcTemplate.update("UPDATE ops_work_order SET sla_status='BREACHED',escalation_level=?,last_escalation_time=NOW(),update_by='system' WHERE id=?", nextLevel, id);
        long orgId = number(order.get("org_id"));
        Map<String, Object> escalation = currentOnCall(orgId, "ESCALATION");
        Long escalationUser = escalation == null ? null : nullableLong(escalation.get("user_id"));
        String content = order.get("work_order_no") + " 已超过SLA，当前升级级别 L" + nextLevel;
        notify(number(order.get("org_id")), nullableLong(order.get("assignee_user_id")), "SLA_BREACH", id, "工单SLA超时", content);
        if (!Objects.equals(escalationUser, nullableLong(order.get("assignee_user_id")))) {
            notify(number(order.get("org_id")), escalationUser, "ESCALATED", id, "工单责任升级", content);
        }
        String status = textOr(order.get("status"), "PENDING");
        log(id, "SLA_ESCALATE", status, status, new Actor(null, "system"), content);
    }

    private Map<String, Object> slaPolicy(String priority) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM ops_sla_policy WHERE priority=? AND enabled=1", priority);
        if (!rows.isEmpty()) return rows.get(0);
        return Map.of("response_minutes", "P1".equals(priority) ? 10 : "P3".equals(priority) ? 120 : 30,
                "resolution_minutes", "P1".equals(priority) ? 60 : "P3".equals(priority) ? 1440 : 240);
    }

    private Map<String, Object> currentOnCall(long orgId, String dutyRole) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT shift.*,u.nickname,u.username
                FROM ops_on_call_shift shift JOIN sys_user u ON u.id=shift.user_id AND u.status=1
                WHERE shift.org_id=? AND shift.duty_role=? AND shift.enabled=1
                  AND shift.start_time<=NOW() AND shift.end_time>NOW()
                ORDER BY shift.start_time DESC LIMIT 1
                """, orgId, dutyRole);
        if (!rows.isEmpty()) return rows.get(0);
        if (!"ESCALATION".equals(dutyRole)) return null;
        List<Map<String, Object>> managers = escalationManagers(orgId);
        if (managers.isEmpty()) return null;
        Map<String, Object> fallback = new LinkedHashMap<>(managers.get(0));
        fallback.put("user_id", fallback.get("id"));
        return fallback;
    }

    private List<Map<String, Object>> escalationManagers(long orgId) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT u.id, u.username, u.nickname
                FROM sys_user u
                JOIN sys_user_role ur ON ur.user_id=u.id
                JOIN sys_role role ON role.id=ur.role_id AND role.status=1
                JOIN sys_role_permission rp ON rp.role_id=role.id
                JOIN sys_permission permission ON permission.id=rp.permission_id AND permission.status=1
                WHERE u.status=1 AND u.org_id=?
                  AND permission.perm_code IN ('ops:workorder:assign','ops:workorder:verify','*')
                ORDER BY u.id
                """, orgId);
    }

    private void notify(long orgId, Long receiverUserId, String type, long referenceId, String title, String content) {
        if (receiverUserId == null) return;
        jdbcTemplate.update("""
                INSERT INTO ops_notification
                  (org_id,receiver_user_id,notification_type,reference_type,reference_id,title,content)
                VALUES (?,? ,?,'WORK_ORDER',?,?,?)
                """, orgId, receiverUserId, type, referenceId, title, content);
    }

    private Map<String, Object> createWorkOrder(long orgId, String sourceType, Long sourceId, String workType, String priority,
                                                 Long deviceId, Long gatewayId, String title, String description, LocalDateTime slaDueTime) {
        Actor actor = actor();
        String no = "WO" + System.currentTimeMillis() + String.format("%04d", Math.floorMod((int) (Math.random() * 10_000), 10_000));
        String activeSourceKey = activeSourceKey(sourceType, sourceId);
        Map<String, Object> policy = slaPolicy(priority);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime effectiveDue = slaDueTime == null ? now.plusMinutes(number(policy.get("resolution_minutes"))) : slaDueTime;
        LocalDateTime responseDue = now.plusMinutes(number(policy.get("response_minutes")));
        Long assigneeId = null;
        String assigneeName = null;
        String initialStatus = "PENDING";
        jdbcTemplate.update("""
                INSERT INTO ops_work_order
                  (work_order_no,org_id,source_type,source_id,active_source_key,work_type,priority,status,device_id,gateway_id,
                   title,description,reporter_user_id,reporter_name,assignee_user_id,assignee_name,assign_time,
                   sla_due_time,response_due_time,create_by,update_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, no, orgId, sourceType, sourceId, activeSourceKey, workType, priority, initialStatus, deviceId,
                gatewayId, title, description, actor.userId(), actor.name(), assigneeId, assigneeName,
                assigneeId == null ? null : Timestamp.valueOf(now), Timestamp.valueOf(effectiveDue), Timestamp.valueOf(responseDue),
                actor.name(), actor.name());
        Long id = jdbcTemplate.queryForObject("SELECT id FROM ops_work_order WHERE work_order_no=?", Long.class, no);
        log(id, "CREATE", null, "PENDING", actor, description);
        notify(orgId, assigneeId, "WORK_ORDER_CREATED", id, "新运维工单", no + "：" + title);
        if (assigneeId != null) log(id, "AUTO_ASSIGN_ON_CALL", "PENDING", "ASSIGNED", new Actor(null, "system"), "按值班表自动分派给 " + assigneeName);
        return single("SELECT * FROM ops_work_order WHERE id=?", id);
    }

    private Map<String, Object> requiredWorkOrder(long id, boolean lock) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM ops_work_order WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw new BusinessException(404, "工单不存在");
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        assertOrg(number(row.get("org_id")));
        return row;
    }

    private void attachReferenceLabels(Map<String, Object> order) {
        Long deviceId = nullableLong(order.get("device_id"));
        if (deviceId != null) {
            List<Map<String, Object>> devices = jdbcTemplate.queryForList(
                    "SELECT d.device_name,d.device_sn,d.space_id,s.space_name FROM dev_device d LEFT JOIN park_space s ON s.id=d.space_id WHERE d.id=?", deviceId);
            if (!devices.isEmpty()) order.putAll(devices.get(0));
        }
        Long gatewayId = nullableLong(order.get("gateway_id"));
        if (gatewayId != null) {
            List<Map<String, Object>> gateways = jdbcTemplate.queryForList(
                    "SELECT gateway_name,gateway_sn FROM dev_gateway WHERE id=?", gatewayId);
            if (!gateways.isEmpty()) order.putAll(gateways.get(0));
        }
    }

    private String deviceLabel(Long deviceId) {
        if (deviceId == null) return "未关联设备";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT device_name,device_sn FROM dev_device WHERE id=?", deviceId);
        if (rows.isEmpty()) return "设备#" + deviceId;
        Map<String, Object> device = rows.get(0);
        return textOr(device.get("device_name"), textOr(device.get("device_sn"), "设备#" + deviceId));
    }

    private Map<String, Object> activeSourceWorkOrder(String sourceType, long sourceId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM ops_work_order WHERE source_type=? AND source_id=? AND status NOT IN ('CLOSED','CANCELLED') ORDER BY id DESC LIMIT 1", sourceType, sourceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Stable source identity protected by uk_ops_active_source_key while a work order is active. */
    static String activeSourceKey(String sourceType, Long sourceId) {
        if (sourceId == null) return null;
        return sourceType.toUpperCase() + ":" + sourceId;
    }

    static boolean isActiveWorkOrderStatus(String status) {
        return !"CLOSED".equalsIgnoreCase(status) && !"CANCELLED".equalsIgnoreCase(status);
    }

    private void assertSourceAlarmRecovered(Map<String, Object> order) {
        if (!"ALARM".equalsIgnoreCase(textOr(order.get("source_type"), "")) || order.get("source_id") == null) return;
        Map<String, Object> alarm = single("SELECT event_status,condition_status FROM log_alarm WHERE id=? FOR UPDATE", order.get("source_id"));
        if (!"RECOVERED".equalsIgnoreCase(textOr(alarm.get("event_status"), ""))
                || !"CLEARED".equalsIgnoreCase(textOr(alarm.get("condition_status"), ""))) {
            throw new BusinessException("设备告警尚未恢复，不能验收关闭工单");
        }
    }

    private void closeSourceAlarm(Map<String, Object> order, Actor actor, String remark) {
        if (!"ALARM".equalsIgnoreCase(textOr(order.get("source_type"), "")) || order.get("source_id") == null) return;
        long alarmId = number(order.get("source_id"));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT event_status FROM log_alarm WHERE id=? FOR UPDATE", alarmId);
        if (rows.isEmpty()) return;
        String from = textOr(rows.get(0).get("event_status"), "IN_PROGRESS");
        if ("CLOSED".equals(from) || "FALSE_POSITIVE".equals(from)) return;
        if (!"RECOVERED".equals(from)) throw new BusinessException("设备告警尚未恢复，不能关闭关联工单");
        jdbcTemplate.update("""
                UPDATE log_alarm SET event_status='CLOSED', condition_status='CLEARED', active_fingerprint=NULL,
                    recovery_time=COALESCE(recovery_time,NOW()), close_time=NOW(), close_user_id=?, close_user=?,
                    close_code='RESOLVED', deal_status=1, deal_time=NOW(), deal_user=?,
                    deal_remark=CONCAT(COALESCE(deal_remark,''), ' [工单闭环] ', ?), update_by=?, version=version+1
                WHERE id=?
                """, actor.userId(), actor.name(), actor.name(), remark, actor.name(), alarmId);
        alarmLog(alarmId, "WORK_ORDER_VERIFIED", from, "CLOSED", actor,
                "工单 " + order.get("work_order_no") + " 验收通过：" + remark);
    }

    private void startSourceAlarm(long alarmId, Actor actor, String remark) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT event_status,condition_status FROM log_alarm WHERE id=? FOR UPDATE", alarmId);
        if (rows.isEmpty()) return;
        String from = textOr(rows.get(0).get("event_status"), "NEW");
        if (!"ACTIVE".equalsIgnoreCase(textOr(rows.get(0).get("condition_status"), "ACTIVE"))
                || List.of("CLOSED", "FALSE_POSITIVE", "RECOVERED").contains(from)) {
            throw new BusinessException("已恢复或已关闭的告警不能创建处置工单");
        }
        jdbcTemplate.update("""
                UPDATE log_alarm SET event_status='IN_PROGRESS',
                    ack_user_id=COALESCE(ack_user_id,?), ack_user=COALESCE(ack_user,?), ack_time=COALESCE(ack_time,NOW()),
                    process_time=COALESCE(process_time,NOW()), deal_user=?, deal_remark=?, update_by=?, version=version+1 WHERE id=?
                """, actor.userId(), actor.name(), actor.name(), remark, actor.name(), alarmId);
        alarmLog(alarmId, "CREATE_WORK_ORDER", from, "IN_PROGRESS", actor, remark);
    }

    private void reopenSourceAlarmAfterCancellation(Map<String, Object> order, Actor actor, String remark) {
        if (!"ALARM".equalsIgnoreCase(textOr(order.get("source_type"), "")) || order.get("source_id") == null) return;
        long alarmId = number(order.get("source_id"));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT event_status FROM log_alarm WHERE id=? FOR UPDATE", alarmId);
        if (rows.isEmpty()) return;
        String from = textOr(rows.get(0).get("event_status"), "IN_PROGRESS").toUpperCase();
        String to = "IN_PROGRESS".equals(from) ? "ACKNOWLEDGED" : from;
        int updated = jdbcTemplate.update("""
                UPDATE log_alarm SET event_status=?, process_time=CASE WHEN ?='ACKNOWLEDGED' THEN NULL ELSE process_time END,
                    deal_user=?, deal_remark=?, update_by=?, version=version+1
                WHERE id=? AND event_status NOT IN ('CLOSED','FALSE_POSITIVE')
                """, to, to, actor.name(), "关联工单已取消：" + textOr(remark, "未填写原因"), actor.name(), alarmId);
        if (updated > 0) alarmLog(alarmId, "WORK_ORDER_CANCELLED", from, to, actor,
                "工单 " + order.get("work_order_no") + " 已取消：" + textOr(remark, "未填写原因"));
    }

    private void alarmLog(long alarmId, String action, String fromStatus, String toStatus, Actor actor, String content) {
        jdbcTemplate.update("""
                INSERT INTO alarm_event_log
                  (alarm_id,action,from_status,to_status,operator_user_id,operator_name,content,create_by,update_by)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, alarmId, action, fromStatus, toStatus, actor.userId(), actor.name(), content, actor.name(), actor.name());
    }

    private void log(long orderId, String action, String fromStatus, String toStatus, Actor actor, String content) {
        jdbcTemplate.update("INSERT INTO ops_work_order_log (work_order_id, action, from_status, to_status, operator_user_id, operator_name, content, create_by, update_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                orderId, action, fromStatus, toStatus, actor.userId(), actor.name(), content, actor.name(), actor.name());
    }

    private PageResult<Map<String, Object>> page(Map<String, String> params, StringBuilder where, List<Object> args, String from, String select) {
        int pageNum = boundedInt(params.get("pageNum"), 1, 1, Integer.MAX_VALUE);
        int pageSize = boundedInt(params.get("pageSize"), 20, 1, 200);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(pageSize); pageArgs.add((pageNum - 1) * pageSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(select + from + where + " ORDER BY 1 DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        return PageResult.of(rows, total == null ? 0 : total, pageNum, pageSize);
    }

    private Actor actor() {
        try {
            if (!StpUtil.isLogin()) return new Actor(null, "system");
        } catch (SaTokenContextException backgroundTask) {
            // Scheduled automation has no HTTP/Sa-Token context and must act as the platform itself.
            return new Actor(null, "system");
        }
        Long userId = StpUtil.getLoginIdAsLong();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id, username, nickname FROM sys_user WHERE id=?", userId);
        if (rows.isEmpty()) return new Actor(userId, "user-" + userId);
        Map<String, Object> user = rows.get(0);
        return new Actor(userId, textOr(user.get("nickname"), textOr(user.get("username"), "user-" + userId)));
    }

    private String assigneeName(long orgId, Object userId) {
        Long id = nullableLong(userId);
        if (id == null) return null;
        Map<String, Object> user = assigneeForOrg(orgId, id);
        return textOr(user.get("nickname"), textOr(user.get("username"), String.valueOf(id)));
    }

    private String inspectionAssigneeName(long orgId, Long userId) {
        if (userId == null) return null;
        List<Map<String, Object>> users = jdbcTemplate.queryForList("""
                WITH RECURSIVE org_ancestors AS (
                    SELECT id, parent_id FROM dev_org WHERE id = ?
                    UNION ALL
                    SELECT parent.id, parent.parent_id
                    FROM dev_org parent JOIN org_ancestors child ON child.parent_id = parent.id
                )
                SELECT DISTINCT u.id, u.username, u.nickname
                FROM sys_user u
                LEFT JOIN sys_user_org_scope scope ON scope.user_id = u.id
                WHERE u.id = ? AND u.status = 1
                  AND (u.org_id = ? OR (scope.scope_mode = 'SELF' AND scope.org_id = ?)
                       OR (scope.scope_mode = 'SUBTREE' AND scope.org_id IN (SELECT id FROM org_ancestors)))
                  AND EXISTS (
                      SELECT 1 FROM sys_user_role ur
                      JOIN sys_role role ON role.id = ur.role_id AND role.status = 1
                      JOIN sys_role_permission rp ON rp.role_id = role.id
                      JOIN sys_permission permission ON permission.id = rp.permission_id AND permission.status = 1
                      WHERE ur.user_id = u.id AND permission.perm_code IN ('ops:inspection:operate', '*')
                  )
                """, orgId, userId, orgId, orgId);
        if (users.isEmpty()) {
            throw new BusinessException("巡检负责人必须是该组织范围内具备巡检执行权限的启用用户");
        }
        Map<String, Object> user = users.get(0);
        return textOr(user.get("nickname"), textOr(user.get("username"), String.valueOf(userId)));
    }

    private String normalizeChecklist(Object raw) {
        String json = text(raw);
        if (json == null) return "[]";
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) throw new BusinessException("巡检检查项必须是列表");
            List<Map<String, Object>> items = new ArrayList<>();
            int index = 0;
            for (JsonNode node : root) {
                index++;
                String title = node.isTextual() ? text(node.asText()) : text(node.path("title").asText(null));
                if (title == null) throw new BusinessException("第 " + index + " 个巡检检查项缺少名称");
                if (title.length() > 100) throw new BusinessException("巡检检查项名称不能超过100个字符");
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("title", title);
                item.put("required", !node.isObject() || node.path("required").asBoolean(true));
                String instruction = node.isObject() ? text(node.path("instruction").asText(null)) : null;
                if (instruction != null) item.put("instruction", instruction);
                items.add(item);
            }
            return objectMapper.writeValueAsString(items);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("巡检检查项格式不正确");
        }
    }

    private Map<String, Object> assigneeForOrg(long orgId, long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                WITH RECURSIVE org_ancestors AS (
                    SELECT id, parent_id FROM dev_org WHERE id = ?
                    UNION ALL
                    SELECT parent.id, parent.parent_id
                    FROM dev_org parent JOIN org_ancestors child ON child.parent_id = parent.id
                )
                SELECT DISTINCT u.id, u.username, u.nickname
                FROM sys_user u
                LEFT JOIN sys_user_org_scope s ON s.user_id=u.id
                WHERE u.id=? AND u.status=1
                  AND (u.org_id=? OR (s.scope_mode='SELF' AND s.org_id=?)
                       OR (s.scope_mode='SUBTREE' AND s.org_id IN (SELECT id FROM org_ancestors)))
                  AND EXISTS (
                      SELECT 1 FROM sys_user_role ur
                      JOIN sys_role role ON role.id=ur.role_id AND role.status=1
                      JOIN sys_role_permission rp ON rp.role_id=role.id
                      JOIN sys_permission permission ON permission.id=rp.permission_id AND permission.status=1
                      WHERE ur.user_id=u.id AND permission.perm_code IN ('ops:workorder:execute','ops:workorder:accept','ops:workorder:operate','*')
                  )
                """, orgId, userId, orgId, orgId);
        if (rows.isEmpty()) throw new BusinessException("处理人必须是该组织范围内的启用用户");
        return rows.get(0);
    }

    private void assertOrg(long orgId) { if (!accessService.hasOrgAccess(orgId)) throw new BusinessException(403, "没有该组织的运维操作权限"); }
    private void assertAssignee(Map<String, Object> item, Actor actor, String itemName) {
        Long assigneeId = nullableLong(item.get("assignee_user_id"));
        if (assigneeId != null && !Objects.equals(assigneeId, actor.userId()) && !StpUtil.hasRole("super_admin")) {
            throw new BusinessException(403, itemName + "已分配给其他处理人，当前用户不能代为执行");
        }
    }
    private void requireStatus(String current, String... allowed) { for (String value : allowed) if (value.equalsIgnoreCase(current)) return; throw new BusinessException("当前状态 " + current + " 不允许此操作"); }
    private void equals(StringBuilder where, List<Object> args, String column, String value) { if (text(value) != null) { where.append(" AND ").append(column).append(" = ?"); args.add(value.trim()); } }
    private Map<String, Object> single(String sql, Object... args) { List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args); if (rows.isEmpty()) throw new BusinessException(404, "数据不存在"); return new LinkedHashMap<>(rows.get(0)); }
    private long number(Object value) { if (value == null) throw new BusinessException("数据不能为空"); return Long.parseLong(String.valueOf(value)); }
    private Long nullableLong(Object value) { if (value == null || String.valueOf(value).isBlank()) return null; return Long.parseLong(String.valueOf(value)); }
    private long requiredLong(Object value, String field) { Long result = nullableLong(value); if (result == null) throw new BusinessException(field + " 不能为空"); return result; }
    private int intValue(Object value, int fallback) { return boundedInt(value, fallback, Integer.MIN_VALUE, Integer.MAX_VALUE); }
    private int boundedInt(Object value, int fallback, int min, int max) { try { int result = Integer.parseInt(String.valueOf(value)); return Math.min(Math.max(result, min), max); } catch (RuntimeException exception) { return fallback; } }
    private String text(Object value) { return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim(); }
    private String textOr(Object value, String fallback) { String result = text(value); return result == null ? fallback : result; }
    private String requiredText(Object value, String field) { String result = text(value); if (result == null) throw new BusinessException(field + " 不能为空"); return result; }
    private LocalDate localDate(Object value) { if (value instanceof Date date) return date.toLocalDate(); if (value == null) return null; try { return LocalDate.parse(String.valueOf(value)); } catch (RuntimeException exception) { return null; } }
    private LocalDateTime nullableDateTime(Object value) { String text = text(value); if (text == null) return null; try { return java.time.OffsetDateTime.parse(text).toLocalDateTime(); } catch (RuntimeException ignored) { try { return LocalDateTime.parse(text); } catch (RuntimeException exception) { throw new BusinessException("日期时间格式不正确"); } } }
    private Timestamp nullableTimestamp(Object value) { LocalDateTime time = value instanceof LocalDateTime dateTime ? dateTime : nullableDateTime(value); return time == null ? null : Timestamp.valueOf(time); }
    private record Actor(Long userId, String name) { }
}
