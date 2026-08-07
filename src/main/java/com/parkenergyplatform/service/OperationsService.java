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

import cn.dev33.satoken.stp.StpUtil;
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

    public OperationsService(JdbcTemplate jdbcTemplate, BusinessDataAccessService accessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessService = accessService;
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
        return page(params, where, args, " FROM ops_work_order w LEFT JOIN dev_device d ON d.id=w.device_id LEFT JOIN dev_gateway g ON g.id=w.gateway_id",
                "SELECT w.*, d.device_sn, d.device_name, g.gateway_sn, g.gateway_name");
    }

    public Map<String, Object> workOrder(long id) {
        Map<String, Object> row = requiredWorkOrder(id, false);
        row.put("logs", jdbcTemplate.queryForList("SELECT * FROM ops_work_order_log WHERE work_order_id=? ORDER BY id", id));
        return row;
    }

    public List<Map<String, Object>> assignees(long orgId, String keyword) {
        assertOrg(orgId);
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        args.add(orgId);
        args.add(orgId);
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
                      WHERE ur.user_id = u.id AND permission.perm_code IN ('ops:workorder:operate', '*')
                  )
                """;
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
    public Map<String, Object> createFromAlarm(long alarmId) {
        accessService.assertAlarmAccess(alarmId);
        Map<String, Object> alarm = single("SELECT * FROM log_alarm WHERE id=?", alarmId);
        Map<String, Object> existing = activeSourceWorkOrder("ALARM", alarmId);
        if (existing != null) return workOrder(number(existing.get("id")));
        String priority = switch (intValue(alarm.get("alarm_level"), 2)) { case 3 -> "P1"; case 2 -> "P2"; default -> "P3"; };
        String title = "告警工单：" + textOr(alarm.get("point_code"), "设备异常") + " / 设备 " + alarm.get("device_id");
        Map<String, Object> order = createWorkOrder(number(alarm.get("org_id")), "ALARM", alarmId, "FAULT", priority,
                nullableLong(alarm.get("device_id")), null, title,
                "告警值=" + textOr(alarm.get("alarm_value"), "-") + "，阈值=" + textOr(alarm.get("threshold_value"), "-"), null);
        jdbcTemplate.update("UPDATE log_alarm SET work_order_id=? WHERE id=?", order.get("id"), alarmId);
        return workOrder(number(order.get("id")));
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
                jdbcTemplate.update("UPDATE ops_work_order SET status='ASSIGNED', assignee_user_id=?, assignee_name=?, assign_time=NOW(), sla_due_time=COALESCE(?, sla_due_time) WHERE id=?",
                        assigneeId, assigneeName, nullableTimestamp(body.get("slaDueTime")), id);
                log(id, "ASSIGN", status, "ASSIGNED", actor, text(body.get("remark")));
            }
            case "ACCEPT" -> {
                requireStatus(status, "ASSIGNED");
                assertAssignee(order, actor, "工单");
                jdbcTemplate.update("UPDATE ops_work_order SET status='ACCEPTED', accepted_time=NOW() WHERE id=?", id);
                log(id, "ACCEPT", status, "ACCEPTED", actor, text(body.get("remark")));
            }
            case "ARRIVE" -> {
                requireStatus(status, "ACCEPTED", "PROCESSING");
                assertAssignee(order, actor, "工单");
                jdbcTemplate.update("UPDATE ops_work_order SET status='PROCESSING', arrived_time=COALESCE(arrived_time, NOW()) WHERE id=?", id);
                log(id, "ARRIVE", status, "PROCESSING", actor, text(body.get("remark")));
            }
            case "COMPLETE" -> {
                requireStatus(status, "ACCEPTED", "PROCESSING");
                assertAssignee(order, actor, "工单");
                jdbcTemplate.update("UPDATE ops_work_order SET status='VERIFYING', completed_time=NOW(), cause_category=?, solution=?, evidence_urls=? WHERE id=?",
                        text(body.get("causeCategory")), requiredText(body.get("solution"), "solution"), text(body.get("evidenceUrls")), id);
                log(id, "COMPLETE", status, "VERIFYING", actor, text(body.get("remark")));
            }
            case "VERIFY" -> {
                requireStatus(status, "VERIFYING");
                String remark = requiredText(body.get("verifyRemark"), "verifyRemark");
                jdbcTemplate.update("UPDATE ops_work_order SET status='CLOSED', verified_time=NOW(), closed_time=NOW(), verify_remark=? WHERE id=?", remark, id);
                log(id, "VERIFY", status, "CLOSED", actor, remark);
                closeSourceAlarm(order, actor, remark);
            }
            case "CANCEL" -> {
                requireStatus(status, "PENDING", "ASSIGNED");
                jdbcTemplate.update("UPDATE ops_work_order SET status='CANCELLED', closed_time=NOW(), verify_remark=? WHERE id=?", requiredText(body.get("remark"), "remark"), id);
                log(id, "CANCEL", status, "CANCELLED", actor, text(body.get("remark")));
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
        long orgId = requiredLong(body.get("orgId"), "orgId");
        assertOrg(orgId);
        String scopeType = textOr(body.get("scopeType"), "ORG").toUpperCase();
        Long scopeId = nullableLong(body.get("scopeId"));
        if (!"ORG".equals(scopeType) && !"DEVICE".equals(scopeType)) throw new BusinessException("scopeType 仅支持 ORG 或 DEVICE");
        if ("DEVICE".equals(scopeType)) {
            if (scopeId == null) throw new BusinessException("设备范围必须选择设备");
            accessService.assertDeviceAccess(scopeId);
        }
        int cycleDays = boundedInt(body.get("cycleDays"), 1, 1, 365);
        int deadlineHour = boundedInt(body.get("deadlineHour"), 18, 0, 23);
        if (id == null) {
            String planNo = textOr(body.get("planNo"), "IP" + System.currentTimeMillis());
            jdbcTemplate.update("INSERT INTO ops_inspection_plan (plan_no, plan_name, org_id, scope_type, scope_id, cycle_days, deadline_hour, assignee_user_id, assignee_name, checklist_json, enabled, remark) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    planNo, requiredText(body.get("planName"), "planName"), orgId, scopeType, scopeId, cycleDays, deadlineHour,
                    nullableLong(body.get("assigneeUserId")), assigneeName(orgId, body.get("assigneeUserId")), text(body.get("checklistJson")), intValue(body.get("enabled"), 1), text(body.get("remark")));
            id = jdbcTemplate.queryForObject("SELECT id FROM ops_inspection_plan WHERE plan_no=?", Long.class, planNo);
        } else {
            Map<String, Object> existing = single("SELECT * FROM ops_inspection_plan WHERE id=?", id);
            assertOrg(number(existing.get("org_id")));
            jdbcTemplate.update("UPDATE ops_inspection_plan SET plan_name=?, org_id=?, scope_type=?, scope_id=?, cycle_days=?, deadline_hour=?, assignee_user_id=?, assignee_name=?, checklist_json=?, enabled=?, remark=? WHERE id=?",
                    requiredText(body.get("planName"), "planName"), orgId, scopeType, scopeId, cycleDays, deadlineHour,
                    nullableLong(body.get("assigneeUserId")), assigneeName(orgId, body.get("assigneeUserId")), text(body.get("checklistJson")), intValue(body.get("enabled"), 1), text(body.get("remark")), id);
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
                Map<String, Object> order = createWorkOrder(number(task.get("org_id")), "INSPECTION", taskId, "INSPECTION", "P2",
                        nullableLong(task.get("device_id")), null, "巡检异常工单：设备 " + task.get("device_id"),
                        requiredText(body.get("resultRemark"), "resultRemark"), null);
                workOrderId = number(order.get("id"));
            } else workOrderId = number(existing.get("id"));
        }
        jdbcTemplate.update("UPDATE ops_inspection_task SET status=?, result=?, result_remark=?, evidence_urls=?, check_time=NOW(), work_order_id=? WHERE id=?",
                "ABNORMAL".equals(result) ? "ABNORMAL" : "COMPLETED", result, text(body.get("resultRemark")), text(body.get("evidenceUrls")), workOrderId, taskId);
        return single("SELECT * FROM ops_inspection_task WHERE id=?", taskId);
    }

    @Transactional
    public int generateInspectionTasks(LocalDate taskDate) {
        List<Map<String, Object>> plans = jdbcTemplate.queryForList("SELECT * FROM ops_inspection_plan WHERE enabled=1");
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
                int inserted = jdbcTemplate.update("INSERT IGNORE INTO ops_inspection_task (task_no, plan_id, org_id, device_id, task_date, assignee_user_id, assignee_name, due_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        taskNo, plan.get("id"), plan.get("org_id"), deviceId, Date.valueOf(taskDate), plan.get("assignee_user_id"), plan.get("assignee_name"), Timestamp.valueOf(due));
                created += inserted;
            }
            jdbcTemplate.update("UPDATE ops_inspection_plan SET last_generate_date=? WHERE id=?", Date.valueOf(taskDate), plan.get("id"));
        }
        return created;
    }

    @Scheduled(cron = "${park.operations.inspection-cron:0 10 0 * * *}")
    public void generateDailyInspectionTasks() {
        generateInspectionTasks(LocalDate.now());
    }

    private Map<String, Object> createWorkOrder(long orgId, String sourceType, Long sourceId, String workType, String priority,
                                                 Long deviceId, Long gatewayId, String title, String description, LocalDateTime slaDueTime) {
        Actor actor = actor();
        String no = "WO" + System.currentTimeMillis() + String.format("%04d", Math.floorMod((int) (Math.random() * 10_000), 10_000));
        jdbcTemplate.update("INSERT INTO ops_work_order (work_order_no, org_id, source_type, source_id, work_type, priority, device_id, gateway_id, title, description, reporter_user_id, reporter_name, sla_due_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                no, orgId, sourceType, sourceId, workType, priority, deviceId, gatewayId, title, description, actor.userId(), actor.name(), nullableTimestamp(slaDueTime));
        Long id = jdbcTemplate.queryForObject("SELECT id FROM ops_work_order WHERE work_order_no=?", Long.class, no);
        log(id, "CREATE", null, "PENDING", actor, description);
        return single("SELECT * FROM ops_work_order WHERE id=?", id);
    }

    private Map<String, Object> requiredWorkOrder(long id, boolean lock) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM ops_work_order WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw new BusinessException(404, "工单不存在");
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        assertOrg(number(row.get("org_id")));
        return row;
    }

    private Map<String, Object> activeSourceWorkOrder(String sourceType, long sourceId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM ops_work_order WHERE source_type=? AND source_id=? AND status NOT IN ('CLOSED','CANCELLED') ORDER BY id DESC LIMIT 1", sourceType, sourceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void closeSourceAlarm(Map<String, Object> order, Actor actor, String remark) {
        if (!"ALARM".equalsIgnoreCase(textOr(order.get("source_type"), "")) || order.get("source_id") == null) return;
        jdbcTemplate.update("UPDATE log_alarm SET deal_status=1, deal_time=NOW(), deal_user=?, deal_remark=CONCAT(COALESCE(deal_remark,''), ' [工单闭环] ', ?) WHERE id=?", actor.name(), remark, order.get("source_id"));
    }

    private void log(long orderId, String action, String fromStatus, String toStatus, Actor actor, String content) {
        jdbcTemplate.update("INSERT INTO ops_work_order_log (work_order_id, action, from_status, to_status, operator_user_id, operator_name, content) VALUES (?, ?, ?, ?, ?, ?, ?)",
                orderId, action, fromStatus, toStatus, actor.userId(), actor.name(), content);
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
        if (!StpUtil.isLogin()) return new Actor(null, "system");
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
                      WHERE ur.user_id=u.id AND permission.perm_code IN ('ops:workorder:operate','*')
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
