package com.parkenergyplatform.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import cn.dev33.satoken.stp.StpUtil;
import com.parkenergyplatform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Alarm event lifecycle application service. */
@Service
public class AlarmService {
    private final JdbcTemplate jdbcTemplate;
    private final BusinessDataAccessService accessService;

    public AlarmService(JdbcTemplate jdbcTemplate, BusinessDataAccessService accessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessService = accessService;
    }

    public Map<String, Object> detail(long alarmId) {
        accessService.assertAlarmAccess(alarmId);
        Map<String, Object> data = new LinkedHashMap<>(single("""
                SELECT a.*, d.device_sn, d.device_name, d.gateway_id, o.org_name, r.rule_name,
                       r.recovery_hold_seconds, w.work_order_no, w.status AS work_order_status,
                       w.close_type AS work_order_close_type, w.assignee_name
                FROM log_alarm a
                LEFT JOIN dev_device d ON d.id=a.device_id
                LEFT JOIN dev_org o ON o.id=a.org_id
                LEFT JOIN alarm_rule r ON r.id=a.rule_id
                LEFT JOIN ops_work_order w ON w.id=a.work_order_id
                WHERE a.id=?
                """, alarmId));
        data.put("timeline", jdbcTemplate.queryForList("""
                SELECT id, action, from_status, to_status, operator_user_id, operator_name, content, create_time
                FROM alarm_event_log WHERE alarm_id=? ORDER BY id
                """, alarmId));
        data.put("workOrder", data.get("work_order_id") == null ? null : singleOrNull("""
                SELECT id, work_order_no, status, priority, assignee_user_id, assignee_name,
                       sla_due_time, completed_time, verified_time, close_type, verify_remark
                FROM ops_work_order WHERE id=?
                """, data.get("work_order_id")));
        data.put("evidenceLinks", Map.of(
                "device", "/device-archive/devices/" + data.get("device_id"),
                "realtime", "/monitor/realtime?deviceId=" + data.get("device_id") + "&pointCode=" + Objects.toString(data.get("point_code"), ""),
                "history", "/analysis/history?deviceId=" + data.get("device_id") + "&pointCode=" + Objects.toString(data.get("point_code"), ""),
                "commands", "/access/commands?targetId=" + data.get("device_id")));
        return data;
    }

    @Transactional
    public Map<String, Object> action(long alarmId, String action, Map<String, Object> request) {
        accessService.assertAlarmAccess(alarmId);
        Map<String, Object> event = locked(alarmId);
        AlarmEventStatus current = status(event);
        Actor actor = actor();
        String normalized = requiredText(action, "action").toUpperCase(Locale.ROOT).replace('-', '_');
        String remark = text(request.get("remark"));

        switch (normalized) {
            case "ACK", "ACKNOWLEDGE" -> acknowledge(alarmId, current, actor, remark);
            case "START", "START_PROCESS" -> startProcess(alarmId, current, actor, remark);
            case "RECOVER", "MARK_RECOVERED" -> throw new BusinessException(
                    "告警恢复只能由实时数据判定，人工可接警、处理和验收，但不能直接修改设备条件状态");
            case "CLOSE" -> close(alarmId, current, actor, request, remark);
            case "FALSE_POSITIVE" -> falsePositive(alarmId, current, actor, remark);
            case "SUPPRESS" -> suppress(alarmId, current, actor, request, remark);
            case "REOPEN" -> reopen(alarmId, current, actor, remark);
            default -> throw new BusinessException("不支持的告警动作: " + action);
        }
        return detail(alarmId);
    }

    /** Compatibility adapter for the legacy binary deal endpoint. */
    @Transactional
    public Map<String, Object> deal(long alarmId, Map<String, Object> request) {
        Map<String, Object> body = new LinkedHashMap<>(request);
        body.put("remark", Objects.toString(request.getOrDefault("dealRemark", request.get("remark")), "历史处置接口关闭"));
        Map<String, Object> event = detail(alarmId);
        AlarmEventStatus current = status(event);
        if (current == AlarmEventStatus.NEW) {
            action(alarmId, "ACKNOWLEDGE", body);
            current = AlarmEventStatus.ACKNOWLEDGED;
        }
        if (current == AlarmEventStatus.RECOVERED) {
            body.putIfAbsent("closeCode", "MANUAL");
            return action(alarmId, "CLOSE", body);
        }
        return detail(alarmId);
    }

    private void acknowledge(long id, AlarmEventStatus current, Actor actor, String remark) {
        requireTransition(current, AlarmEventStatus.ACKNOWLEDGED);
        jdbcTemplate.update("""
                UPDATE log_alarm SET event_status='ACKNOWLEDGED', ack_user_id=?, ack_user=?, ack_time=NOW(),
                    deal_user=?, deal_remark=?, update_by=?, version=version+1 WHERE id=?
                """, actor.userId(), actor.name(), actor.name(), remark, actor.name(), id);
        log(id, "ACKNOWLEDGE", current, AlarmEventStatus.ACKNOWLEDGED, actor, remark);
    }

    private void startProcess(long id, AlarmEventStatus current, Actor actor, String remark) {
        requireTransition(current, AlarmEventStatus.IN_PROGRESS);
        jdbcTemplate.update("""
                UPDATE log_alarm SET event_status='IN_PROGRESS',
                    ack_user_id=COALESCE(ack_user_id,?), ack_user=COALESCE(ack_user,?), ack_time=COALESCE(ack_time,NOW()),
                    process_time=COALESCE(process_time,NOW()), deal_user=?, deal_remark=?, update_by=?, version=version+1 WHERE id=?
                """, actor.userId(), actor.name(), actor.name(), remark, actor.name(), id);
        log(id, "START_PROCESS", current, AlarmEventStatus.IN_PROGRESS, actor, remark);
    }

    private void recover(long id, AlarmEventStatus current, Actor actor, Map<String, Object> body, String remark) {
        requireTransition(current, AlarmEventStatus.RECOVERED);
        jdbcTemplate.update("""
                UPDATE log_alarm SET event_status='RECOVERED', condition_status='CLEARED', recovery_time=NOW(),
                    recovery_value=?, deal_user=?, deal_remark=?, update_by=?, version=version+1 WHERE id=?
                """, text(body.get("recoveryValue")), actor.name(), remark, actor.name(), id);
        log(id, "RECOVER", current, AlarmEventStatus.RECOVERED, actor, remark);
    }

    private void close(long id, AlarmEventStatus current, Actor actor, Map<String, Object> body, String remark) {
        requireTransition(current, AlarmEventStatus.CLOSED);
        assertNoActiveWorkOrder(id, "关闭");
        String requiredRemark = requiredText(remark, "remark");
        String closeCode = textOr(body.get("closeCode"), "RESOLVED").toUpperCase(Locale.ROOT);
        if (!List.of("RESOLVED", "MANUAL").contains(closeCode)) throw new BusinessException("关闭原因仅支持 RESOLVED 或 MANUAL");
        jdbcTemplate.update("""
                UPDATE log_alarm SET event_status='CLOSED', active_fingerprint=NULL, deal_status=1, deal_time=NOW(),
                    deal_user=?, deal_remark=?, close_time=NOW(), close_user_id=?, close_user=?, close_code=?, update_by=?, version=version+1
                WHERE id=?
                """, actor.name(), requiredRemark, actor.userId(), actor.name(), closeCode, actor.name(), id);
        log(id, "CLOSE", current, AlarmEventStatus.CLOSED, actor, requiredRemark);
    }

    private void falsePositive(long id, AlarmEventStatus current, Actor actor, String remark) {
        requireTransition(current, AlarmEventStatus.FALSE_POSITIVE);
        assertNoActiveWorkOrder(id, "标记误报");
        String requiredRemark = requiredText(remark, "remark");
        jdbcTemplate.update("""
                UPDATE log_alarm SET event_status='FALSE_POSITIVE', condition_status='CLEARED', active_fingerprint=NULL, deal_status=1, deal_time=NOW(),
                    deal_user=?, deal_remark=?, close_time=NOW(), close_user_id=?, close_user=?, close_code='FALSE_POSITIVE',
                    update_by=?, version=version+1 WHERE id=?
                """, actor.name(), requiredRemark, actor.userId(), actor.name(), actor.name(), id);
        log(id, "FALSE_POSITIVE", current, AlarmEventStatus.FALSE_POSITIVE, actor, requiredRemark);
    }

    private void suppress(long id, AlarmEventStatus current, Actor actor, Map<String, Object> body, String remark) {
        requireTransition(current, AlarmEventStatus.SUPPRESSED);
        assertNoActiveWorkOrder(id, "抑制");
        LocalDateTime until = dateTime(body.get("suppressUntil"));
        if (!until.isAfter(LocalDateTime.now())) throw new BusinessException("抑制截止时间必须晚于当前时间");
        String reason = requiredText(textOr(body.get("suppressReason"), remark), "suppressReason");
        jdbcTemplate.update("""
                UPDATE log_alarm SET event_status='SUPPRESSED', suppress_until=?, suppress_reason=?, deal_user=?,
                    deal_remark=?, update_by=?, version=version+1 WHERE id=?
                """, Timestamp.valueOf(until), reason, actor.name(), reason, actor.name(), id);
        log(id, "SUPPRESS", current, AlarmEventStatus.SUPPRESSED, actor, reason + "，截止 " + until);
    }

    private void reopen(long id, AlarmEventStatus current, Actor actor, String remark) {
        requireTransition(current, AlarmEventStatus.NEW);
        String requiredRemark = requiredText(remark, "remark");
        String fingerprint = Objects.toString(single("SELECT event_fingerprint FROM log_alarm WHERE id=?", id).get("event_fingerprint"), null);
        if (fingerprint == null) throw new BusinessException("告警缺少事件指纹，无法重新打开");
        Long conflict = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM log_alarm WHERE active_fingerprint=? AND id<>?", Long.class, fingerprint, id);
        if (conflict != null && conflict > 0) throw new BusinessException("同一设备与规则已有活动告警，不能重复打开");
        jdbcTemplate.update("""
                UPDATE log_alarm SET event_status='NEW', condition_status='ACTIVE', active_fingerprint=event_fingerprint, deal_status=0,
                    deal_time=NULL, close_time=NULL, close_user_id=NULL, close_user=NULL, close_code=NULL,
                    suppress_until=NULL, suppress_reason=NULL, deal_user=?, deal_remark=?, update_by=?, version=version+1 WHERE id=?
                """, actor.name(), requiredRemark, actor.name(), id);
        log(id, "REOPEN", current, AlarmEventStatus.NEW, actor, requiredRemark);
    }

    private Map<String, Object> locked(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM log_alarm WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new BusinessException(404, "告警不存在: " + id);
        return rows.get(0);
    }

    private AlarmEventStatus status(Map<String, Object> row) {
        String raw = Objects.toString(row.get("event_status"), Number.class.isInstance(row.get("deal_status"))
                && ((Number) row.get("deal_status")).intValue() == 1 ? "CLOSED" : "NEW");
        try { return AlarmEventStatus.valueOf(raw.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new BusinessException("未知告警状态: " + raw); }
    }

    static boolean transitionAllowed(AlarmEventStatus from, AlarmEventStatus to) {
        return switch (to) {
            case ACKNOWLEDGED -> from == AlarmEventStatus.NEW;
            case IN_PROGRESS -> from == AlarmEventStatus.NEW || from == AlarmEventStatus.ACKNOWLEDGED;
            case RECOVERED -> List.of(AlarmEventStatus.NEW, AlarmEventStatus.ACKNOWLEDGED,
                    AlarmEventStatus.IN_PROGRESS, AlarmEventStatus.SUPPRESSED).contains(from);
            case CLOSED -> from == AlarmEventStatus.RECOVERED;
            case FALSE_POSITIVE -> List.of(AlarmEventStatus.NEW, AlarmEventStatus.ACKNOWLEDGED,
                    AlarmEventStatus.IN_PROGRESS).contains(from);
            case SUPPRESSED -> from == AlarmEventStatus.NEW || from == AlarmEventStatus.ACKNOWLEDGED;
            case NEW -> List.of(AlarmEventStatus.RECOVERED, AlarmEventStatus.CLOSED,
                    AlarmEventStatus.FALSE_POSITIVE, AlarmEventStatus.SUPPRESSED).contains(from);
        };
    }

    private void requireTransition(AlarmEventStatus current, AlarmEventStatus target) {
        if (transitionAllowed(current, target)) return;
        throw new BusinessException("告警不能从 " + current + " 流转到 " + target);
    }

    private void assertNoActiveWorkOrder(long alarmId, String action) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ops_work_order
                WHERE source_type='ALARM' AND source_id=? AND status NOT IN ('CLOSED','CANCELLED')
                """, Long.class, alarmId);
        if (count != null && count > 0) throw new BusinessException("告警存在未结束工单，不能直接" + action);
    }

    private void log(long alarmId, String action, AlarmEventStatus from, AlarmEventStatus to, Actor actor, String content) {
        jdbcTemplate.update("""
                INSERT INTO alarm_event_log
                  (alarm_id,action,from_status,to_status,operator_user_id,operator_name,content,create_by,update_by)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, alarmId, action, from == null ? null : from.name(), to.name(), actor.userId(), actor.name(), content,
                actor.name(), actor.name());
    }

    private Actor actor() {
        if (!StpUtil.isLogin()) return new Actor(null, "system");
        Long userId = StpUtil.getLoginIdAsLong();
        Map<String, Object> user = singleOrNull("SELECT username,nickname FROM sys_user WHERE id=?", userId);
        return new Actor(userId, user == null ? "user-" + userId : textOr(user.get("nickname"), textOr(user.get("username"), "user-" + userId)));
    }

    private Map<String, Object> single(String sql, Object... args) {
        Map<String, Object> row = singleOrNull(sql, args);
        if (row == null) throw new BusinessException(404, "数据不存在");
        return row;
    }

    private Map<String, Object> singleOrNull(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private LocalDateTime dateTime(Object value) {
        String raw = requiredText(value, "suppressUntil");
        try { return java.time.OffsetDateTime.parse(raw).toLocalDateTime(); }
        catch (RuntimeException ignored) {
            try { return LocalDateTime.parse(raw); }
            catch (RuntimeException exception) { throw new BusinessException("抑制截止时间格式不正确"); }
        }
    }

    private String text(Object value) { return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim(); }
    private String textOr(Object value, String fallback) { String result = text(value); return result == null ? fallback : result; }
    private String requiredText(Object value, String field) { String result = text(value); if (result == null) throw new BusinessException(field + " 不能为空"); return result; }
    private record Actor(Long userId, String name) { }
}
