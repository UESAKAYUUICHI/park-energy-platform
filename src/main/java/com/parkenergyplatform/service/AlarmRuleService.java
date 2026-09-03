package com.parkenergyplatform.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import cn.dev33.satoken.stp.StpUtil;
import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.common.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Versioned alarm-rule configuration with publish, rollback, preview and dry-run semantics. */
@Service
public class AlarmRuleService {
    private static final List<String> MODES = List.of(
            "THRESHOLD", "N_OF_M", "WINDOW_AVG", "RATE_OF_CHANGE", "MISSING_DATA", "OFFLINE");
    private final JdbcTemplate jdbcTemplate;
    private final BusinessDataAccessService accessService;

    public AlarmRuleService(JdbcTemplate jdbcTemplate, BusinessDataAccessService accessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessService = accessService;
    }

    public PageResult<Map<String, Object>> page(Map<String, String> params) {
        int pageNum = positive(params.get("pageNum"), 1);
        int pageSize = Math.min(positive(params.get("pageSize"), 20), 200);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        String keyword = text(params.get("keyword"));
        if (keyword != null) {
            where.append(" AND (r.rule_name LIKE ? OR r.point_code LIKE ? OR r.rule_key LIKE ?)");
            args.add("%" + keyword + "%"); args.add("%" + keyword + "%"); args.add("%" + keyword + "%");
        }
        if (text(params.get("enabled")) != null) { where.append(" AND r.enabled=?"); args.add(params.get("enabled")); }
        if (text(params.get("evaluationMode")) != null) { where.append(" AND r.evaluation_mode=?"); args.add(params.get("evaluationMode")); }
        if (text(params.get("lifecycleStatus")) != null) { where.append(" AND r.lifecycle_status=?"); args.add(params.get("lifecycleStatus")); }
        where.append(" AND (r.rule_scope=1 OR (r.rule_scope=2").append(accessService.scopeSql("r.org_id", args))
                .append(") OR (r.rule_scope=4 AND EXISTS (SELECT 1 FROM park_space scoped_space WHERE scoped_space.id=r.space_id")
                .append(accessService.scopeSql("scoped_space.org_id", args)).append(")) OR (r.rule_scope=3 AND EXISTS (SELECT 1 FROM dev_device scoped_device WHERE scoped_device.id=r.device_id")
                .append(accessService.scopeSql("scoped_device.org_id", args)).append(")))");
        String from = " FROM alarm_rule r LEFT JOIN dev_org o ON o.id=r.org_id LEFT JOIN park_space s ON s.id=r.space_id LEFT JOIN dev_device d ON d.id=r.device_id";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(pageSize); pageArgs.add((pageNum - 1) * pageSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT r.*,o.org_name,s.space_name,d.device_name,d.device_sn" + from + where + " ORDER BY r.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        return PageResult.of(rows, total == null ? 0 : total, pageNum, pageSize);
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        RuleInput input = validate(body);
        assertUniqueName(input.name(), null);
        jdbcTemplate.update("""
                INSERT INTO alarm_rule
                  (rule_name,alarm_type,rule_scope,org_id,space_id,org_include_children,device_id,point_code,evaluation_mode,
                   compare_operator,threshold_value,threshold_min,threshold_max,recovery_threshold_value,recovery_samples,
                   freshness_seconds,max_sample_gap_seconds,evaluation_window_samples,required_hits,window_seconds,
                   duration_seconds,alarm_level,auto_work_order,recovery_hold_seconds,version_no,lifecycle_status,enabled,remark)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,'DRAFT',?,?)
                """, input.name(), input.type(), input.scope(), input.orgId(), input.spaceId(), input.orgIncludeChildren(), input.deviceId(),
                input.pointCode(), input.evaluationMode(), input.operator(), input.threshold(), input.min(), input.max(),
                input.recoveryThreshold(), input.recoverySamples(), input.freshnessSeconds(), input.maxSampleGapSeconds(),
                input.evaluationWindowSamples(), input.requiredHits(), input.windowSeconds(), input.durationSeconds(),
                input.level(), input.autoWorkOrder(), input.recoveryHoldSeconds(), input.enabled(), input.remark());
        Long id = jdbcTemplate.queryForObject("SELECT id FROM alarm_rule WHERE rule_name=?", Long.class, input.name());
        if (id == null) throw new BusinessException("告警规则创建失败");
        jdbcTemplate.update("UPDATE alarm_rule SET rule_key=CONCAT('AR-',LPAD(id,8,'0')) WHERE id=?", id);
        return required(id);
    }

    @Transactional
    public void delete(long id) {
        if (jdbcTemplate.queryForList("SELECT id FROM alarm_rule WHERE id=?", id).isEmpty()) {
            throw new BusinessException(404, "告警策略不存在");
        }
        jdbcTemplate.update("DELETE FROM alarm_rule_version WHERE rule_id=?", id);
        jdbcTemplate.update("DELETE FROM alarm_rule WHERE id=?", id);
    }

    @Transactional
    public Map<String, Object> update(long id, Map<String, Object> body) {
        Map<String, Object> merged = new LinkedHashMap<>(required(id));
        merged.putAll(body);
        RuleInput input = validate(merged);
        assertUniqueName(input.name(), id);
        jdbcTemplate.update("""
                UPDATE alarm_rule SET rule_name=?,alarm_type=?,rule_scope=?,org_id=?,space_id=?,org_include_children=?,device_id=?,
                    point_code=?,evaluation_mode=?,compare_operator=?,threshold_value=?,threshold_min=?,threshold_max=?,
                    recovery_threshold_value=?,recovery_samples=?,freshness_seconds=?,max_sample_gap_seconds=?,
                    evaluation_window_samples=?,required_hits=?,window_seconds=?,duration_seconds=?,alarm_level=?,
                    auto_work_order=?,recovery_hold_seconds=?,lifecycle_status='DRAFT',enabled=?,remark=? WHERE id=?
                """, input.name(), input.type(), input.scope(), input.orgId(), input.spaceId(), input.orgIncludeChildren(), input.deviceId(),
                input.pointCode(), input.evaluationMode(), input.operator(), input.threshold(), input.min(), input.max(),
                input.recoveryThreshold(), input.recoverySamples(), input.freshnessSeconds(), input.maxSampleGapSeconds(),
                input.evaluationWindowSamples(), input.requiredHits(), input.windowSeconds(), input.durationSeconds(),
                input.level(), input.autoWorkOrder(), input.recoveryHoldSeconds(), input.enabled(), input.remark(), id);
        return required(id);
    }

    @Transactional
    public Map<String, Object> publish(long id) {
        Map<String, Object> rule = required(id);
        validate(rule);
        Integer next = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version_no),0)+1 FROM alarm_rule_version WHERE rule_id=?", Integer.class, id);
        Actor actor = actor();
        jdbcTemplate.update("""
                INSERT INTO alarm_rule_version
                  (rule_id,version_no,rule_name,alarm_type,rule_scope,org_id,space_id,org_include_children,device_id,point_code,
                   evaluation_mode,compare_operator,threshold_value,threshold_min,threshold_max,recovery_threshold_value,
                   recovery_samples,freshness_seconds,max_sample_gap_seconds,evaluation_window_samples,required_hits,
                   window_seconds,duration_seconds,alarm_level,auto_work_order,recovery_hold_seconds,remark,
                   publisher_user_id,publisher_name)
                SELECT id,?,rule_name,alarm_type,rule_scope,org_id,space_id,org_include_children,device_id,point_code,evaluation_mode,
                       compare_operator,threshold_value,threshold_min,threshold_max,recovery_threshold_value,recovery_samples,
                       freshness_seconds,max_sample_gap_seconds,evaluation_window_samples,required_hits,window_seconds,
                       duration_seconds,alarm_level,auto_work_order,recovery_hold_seconds,remark,?,?
                FROM alarm_rule WHERE id=?
                """, next, actor.userId(), actor.name(), id);
        Long versionId = jdbcTemplate.queryForObject(
                "SELECT id FROM alarm_rule_version WHERE rule_id=? AND version_no=?", Long.class, id, next);
        jdbcTemplate.update("""
                UPDATE alarm_rule SET version_no=?,published_version_id=?,lifecycle_status='PUBLISHED',published_time=NOW()
                WHERE id=?
                """, next, versionId, id);
        return required(id);
    }

    @Transactional
    public Map<String, Object> rollback(long id, long versionId) {
        required(id);
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM alarm_rule_version WHERE id=? AND rule_id=?", Long.class, versionId, id);
        if (count == null || count == 0) throw new BusinessException(404, "规则历史版本不存在");
        jdbcTemplate.update("""
                UPDATE alarm_rule r JOIN alarm_rule_version v ON v.id=? AND v.rule_id=r.id
                SET r.rule_name=v.rule_name,r.alarm_type=v.alarm_type,r.rule_scope=v.rule_scope,r.org_id=v.org_id,r.space_id=v.space_id,
                    r.org_include_children=v.org_include_children,r.device_id=v.device_id,r.point_code=v.point_code,
                    r.evaluation_mode=v.evaluation_mode,r.compare_operator=v.compare_operator,
                    r.threshold_value=v.threshold_value,r.threshold_min=v.threshold_min,r.threshold_max=v.threshold_max,
                    r.recovery_threshold_value=v.recovery_threshold_value,r.recovery_samples=v.recovery_samples,
                    r.freshness_seconds=v.freshness_seconds,r.max_sample_gap_seconds=v.max_sample_gap_seconds,
                    r.evaluation_window_samples=v.evaluation_window_samples,r.required_hits=v.required_hits,
                    r.window_seconds=v.window_seconds,r.duration_seconds=v.duration_seconds,r.alarm_level=v.alarm_level,
                    r.auto_work_order=v.auto_work_order,r.recovery_hold_seconds=v.recovery_hold_seconds,
                    r.remark=CONCAT(COALESCE(v.remark,''),' [由版本 ',v.version_no,' 回滚]'),r.lifecycle_status='DRAFT'
                WHERE r.id=?
                """, versionId, id);
        return publish(id);
    }

    public List<Map<String, Object>> versions(long id) {
        required(id);
        return jdbcTemplate.queryForList(
                "SELECT * FROM alarm_rule_version WHERE rule_id=? ORDER BY version_no DESC", id);
    }

    public Map<String, Object> preview(long id) {
        Map<String, Object> rule = required(id);
        int scope = intValue(rule.get("rule_scope"), 1);
        Long orgId = longValue(rule.get("org_id"));
        Long spaceId = longValue(rule.get("space_id"));
        Long deviceId = longValue(rule.get("device_id"));
        boolean children = intValue(rule.get("org_include_children"), 1) == 1;
        String pointCode = text(rule.get("point_code"));
        String mode = text(rule.get("evaluation_mode"));

        List<Object> targetArgs = new ArrayList<>();
        String targetCondition;
        if (scope == 3) {
            targetCondition = "d.id=?"; targetArgs.add(deviceId);
        } else if (scope == 4) {
            targetCondition = "d.space_id=?"; targetArgs.add(spaceId);
        } else if (scope == 2 && children) {
            targetCondition = "d.org_id IN (WITH RECURSIVE descendants AS (SELECT id FROM dev_org WHERE id=? UNION ALL SELECT o.id FROM dev_org o JOIN descendants p ON o.parent_id=p.id) SELECT id FROM descendants)";
            targetArgs.add(orgId);
        } else if (scope == 2) {
            targetCondition = "d.org_id=?"; targetArgs.add(orgId);
        } else targetCondition = "1=1";
        String support = "OFFLINE".equalsIgnoreCase(mode) ? "1=1" :
                "EXISTS (SELECT 1 FROM dev_point_definition p WHERE p.device_type_id=d.device_type_id AND p.point_code=? AND p.enabled=1)";
        List<Object> scopedArgs = new ArrayList<>();
        if (!"OFFLINE".equalsIgnoreCase(mode)) scopedArgs.add(pointCode);
        scopedArgs.addAll(targetArgs);
        String scopeSql = accessService.scopeSql("d.org_id", scopedArgs);
        String sql = "SELECT d.id,d.device_sn,d.device_name,d.org_id,(" + support + ") AS supported FROM dev_device d WHERE d.status=1 AND " + targetCondition + scopeSql + " ORDER BY d.id LIMIT 200";
        List<Map<String, Object>> devices = jdbcTemplate.queryForList(sql, scopedArgs.toArray());
        long supported = devices.stream().filter(row -> intValue(row.get("supported"), 0) == 1).count();
        return Map.of("targetCount", devices.size(), "supportedCount", supported,
                "unsupportedCount", devices.size() - supported, "devices", devices);
    }

    public Map<String, Object> trial(long id, Map<String, Object> body) {
        Map<String, Object> merged = new LinkedHashMap<>(required(id));
        merged.putAll(body);
        RuleInput input = validate(merged);
        BigDecimal value = decimal(value(body, "sampleValue", "sample_value"));
        if (value == null && !List.of("OFFLINE", "MISSING_DATA").contains(input.evaluationMode())) {
            throw new BusinessException("试运行必须填写样本值");
        }
        boolean triggered = value == null || compare(input.operator(), value, input.threshold(), input.min(), input.max());
        boolean recovered = value != null && recoveryCompare(input, value);
        return Map.of("evaluationMode", input.evaluationMode(), "sampleValue", value == null ? "-" : value,
                "triggered", triggered, "recovered", recovered, "writesAlarm", false);
    }

    private RuleInput validate(Map<String, Object> body) {
        String name = requiredText(value(body, "rule_name", "ruleName"), "规则名称");
        int type = boundedInt(value(body, "alarm_type", "alarmType"), 1, 1, 5, "告警类型");
        int scope = boundedInt(value(body, "rule_scope", "ruleScope"), 1, 1, 4, "规则范围");
        Long orgId = longValue(value(body, "org_id", "orgId"));
        Long spaceId = longValue(value(body, "space_id", "spaceId"));
        Long deviceId = longValue(value(body, "device_id", "deviceId"));
        if (scope == 1) { orgId = null; spaceId = null; deviceId = null; }
        if (scope == 2) {
            if (orgId == null) throw new BusinessException("组织规则必须选择组织");
            if (!accessService.hasOrgAccess(orgId)) throw new BusinessException(403, "没有该组织的规则配置权限");
            deviceId = null;
        }
        if (scope == 3) {
            if (deviceId == null) throw new BusinessException("设备规则必须选择设备");
            accessService.assertDeviceAccess(deviceId); orgId = null;
        }
        if (scope == 4) {
            if (spaceId == null) throw new BusinessException("空间规则必须选择空间");
            List<Map<String, Object>> spaces = jdbcTemplate.queryForList("SELECT id,org_id,status FROM park_space WHERE id=?", spaceId);
            if (spaces.isEmpty() || intValue(spaces.get(0).get("status"), 1) != 1) throw new BusinessException("空间不存在或已停用");
            orgId = longValue(spaces.get(0).get("org_id"));
            if (orgId == null || !accessService.hasOrgAccess(orgId)) throw new BusinessException(403, "没有该空间所属组织的规则配置权限");
            deviceId = null;
        }
        int includeChildren = boundedInt(value(body, "org_include_children", "orgIncludeChildren"), 1, 0, 1, "组织子树");
        String mode = normalizeMode(value(body, "evaluation_mode", "evaluationMode"), type);
        String pointCode = "OFFLINE".equals(mode) ? "__DEVICE__" :
                requiredText(value(body, "point_code", "pointCode"), "测点编码").toUpperCase(Locale.ROOT);
        if (!"OFFLINE".equals(mode)) assertPointAvailable(pointCode, scope == 3 ? deviceId : null);
        String operator = normalizeOperator(value(body, "compare_operator", "compareOperator"));
        BigDecimal threshold = decimal(value(body, "threshold_value", "thresholdValue"));
        BigDecimal min = decimal(value(body, "threshold_min", "thresholdMin"));
        BigDecimal max = decimal(value(body, "threshold_max", "thresholdMax"));
        if ("between".equals(operator)) {
            if (min == null || max == null || min.compareTo(max) > 0) throw new BusinessException("区间规则必须设置有效上下限");
            threshold = null;
        } else if (threshold == null && !List.of("OFFLINE", "MISSING_DATA").contains(mode)) {
            throw new BusinessException("阈值不能为空");
        }
        BigDecimal recovery = decimal(value(body, "recovery_threshold_value", "recoveryThresholdValue"));
        validateHysteresis(operator, threshold, recovery);
        int recoverySamples = boundedInt(value(body, "recovery_samples", "recoverySamples"), 3, 1, 100, "连续恢复次数");
        int freshness = boundedInt(value(body, "freshness_seconds", "freshnessSeconds"), 900, 10, 86400, "数据新鲜度");
        int maxGap = boundedInt(value(body, "max_sample_gap_seconds", "maxSampleGapSeconds"), 900, 10, 86400, "最大采样间隔");
        int windowSamples = boundedInt(value(body, "evaluation_window_samples", "evaluationWindowSamples"), 5, 2, 1000, "判断窗口样本数");
        int requiredHits = boundedInt(value(body, "required_hits", "requiredHits"), 3, 1, windowSamples, "窗口命中次数");
        int windowSeconds = boundedInt(value(body, "window_seconds", "windowSeconds"), 300, 10, 86400, "统计窗口");
        int duration = boundedInt(value(body, "duration_seconds", "durationSeconds"), 0, 0, 86400, "持续时间");
        int level = boundedInt(value(body, "alarm_level", "alarmLevel"), 2, 1, 3, "告警等级");
        int autoOrder = boundedInt(value(body, "auto_work_order", "autoWorkOrder"), 1, 0, 1, "自动建单");
        int hold = boundedInt(value(body, "recovery_hold_seconds", "recoveryHoldSeconds"), 8, 1, 3600, "恢复观察时间");
        int enabled = boundedInt(value(body, "enabled"), 1, 0, 1, "启用状态");
        return new RuleInput(name, type, scope, orgId, spaceId, deviceId, includeChildren, pointCode, mode, operator,
                threshold, min, max, recovery, recoverySamples, freshness, maxGap, windowSamples, requiredHits,
                windowSeconds, duration, level, autoOrder, hold, enabled, text(value(body, "remark")));
    }

    private void assertPointAvailable(String pointCode, Long deviceId) {
        Long count = deviceId == null
                ? jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dev_point_definition WHERE point_code=? AND enabled=1", Long.class, pointCode)
                : jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dev_device d JOIN dev_point_definition p ON p.device_type_id=d.device_type_id WHERE d.id=? AND p.point_code=? AND p.enabled=1", Long.class, deviceId, pointCode);
        if (count == null || count == 0) throw new BusinessException("所选范围不存在启用测点: " + pointCode);
    }

    private String normalizeMode(Object value, int type) {
        String mode = text(value);
        if (mode == null) mode = type == 4 ? "OFFLINE" : "THRESHOLD";
        mode = mode.toUpperCase(Locale.ROOT);
        if (!MODES.contains(mode)) throw new BusinessException("不支持的工业判断模式: " + mode);
        if (type == 4 && !"OFFLINE".equals(mode)) throw new BusinessException("设备离线类型必须使用离线模式");
        return mode;
    }

    private String normalizeOperator(Object value) {
        String raw = text(value);
        if (raw == null) return ">";
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "GT", ">" -> ">"; case "GTE", ">=" -> ">="; case "LT", "<" -> "<";
            case "LTE", "<=" -> "<="; case "EQ", "=" -> "="; case "BETWEEN" -> "between";
            default -> throw new BusinessException("不支持的比较符: " + raw);
        };
    }

    private void validateHysteresis(String operator, BigDecimal trigger, BigDecimal recovery) {
        if (trigger == null || recovery == null) return;
        if (List.of(">", ">=").contains(operator) && recovery.compareTo(trigger) > 0) throw new BusinessException("高限恢复阈值不能高于触发阈值");
        if (List.of("<", "<=").contains(operator) && recovery.compareTo(trigger) < 0) throw new BusinessException("低限恢复阈值不能低于触发阈值");
    }

    private boolean compare(String op, BigDecimal value, BigDecimal threshold, BigDecimal min, BigDecimal max) {
        return switch (op) {
            case ">" -> value.compareTo(threshold) > 0; case ">=" -> value.compareTo(threshold) >= 0;
            case "<" -> value.compareTo(threshold) < 0; case "<=" -> value.compareTo(threshold) <= 0;
            case "=" -> value.compareTo(threshold) == 0;
            case "between" -> value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
            default -> false;
        };
    }

    private boolean recoveryCompare(RuleInput input, BigDecimal value) {
        if (input.recoveryThreshold() == null) return !compare(input.operator(), value, input.threshold(), input.min(), input.max());
        return List.of(">", ">=").contains(input.operator())
                ? value.compareTo(input.recoveryThreshold()) <= 0
                : List.of("<", "<=").contains(input.operator())
                ? value.compareTo(input.recoveryThreshold()) >= 0
                : !compare(input.operator(), value, input.threshold(), input.min(), input.max());
    }

    private void assertUniqueName(String name, Long excludedId) {
        Long duplicate = excludedId == null
                ? jdbcTemplate.queryForObject("SELECT COUNT(*) FROM alarm_rule WHERE rule_name=?", Long.class, name)
                : jdbcTemplate.queryForObject("SELECT COUNT(*) FROM alarm_rule WHERE rule_name=? AND id<>?", Long.class, name, excludedId);
        if (duplicate != null && duplicate > 0) throw new BusinessException(409, "告警规则名称已存在");
    }

    private Map<String, Object> required(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM alarm_rule WHERE id=?", id);
        if (rows.isEmpty()) throw new BusinessException(404, "告警规则不存在");
        Map<String, Object> rule = new LinkedHashMap<>(rows.get(0));
        Long orgId = longValue(rule.get("org_id")); Long deviceId = longValue(rule.get("device_id"));
        if (deviceId != null) accessService.assertDeviceAccess(deviceId);
        else if (orgId != null && !accessService.hasOrgAccess(orgId)) throw new BusinessException(403, "没有该规则的访问权限");
        return rule;
    }

    private Actor actor() {
        Long userId = StpUtil.getLoginIdAsLong();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT username,nickname FROM sys_user WHERE id=?", userId);
        String name = rows.isEmpty() ? "user-" + userId : text(rows.get(0).get("nickname"));
        if (name == null && !rows.isEmpty()) name = text(rows.get(0).get("username"));
        return new Actor(userId, name == null ? "user-" + userId : name);
    }

    private Object value(Map<String, Object> body, String... names) { for (String name : names) if (body.containsKey(name)) return body.get(name); return null; }
    private String text(Object value) { return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim(); }
    private String requiredText(Object value, String field) { String result = text(value); if (result == null) throw new BusinessException(field + "不能为空"); return result; }
    private Long longValue(Object value) { String text = text(value); return text == null ? null : Long.parseLong(text); }
    private BigDecimal decimal(Object value) { String text = text(value); try { return text == null ? null : new BigDecimal(text); } catch (NumberFormatException exception) { throw new BusinessException("数值格式不正确"); } }
    private int intValue(Object value, int fallback) { try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); } catch (NumberFormatException exception) { return fallback; } }
    private int boundedInt(Object value, int fallback, int min, int max, String field) { int number = intValue(value, fallback); if (number < min || number > max) throw new BusinessException(field + "超出允许范围"); return number; }
    private int positive(String value, int fallback) { try { int number = Integer.parseInt(Objects.toString(value, "")); return number > 0 ? number : fallback; } catch (NumberFormatException exception) { return fallback; } }

    private record RuleInput(String name, int type, int scope, Long orgId, Long spaceId, Long deviceId, int orgIncludeChildren,
                             String pointCode, String evaluationMode, String operator, BigDecimal threshold,
                             BigDecimal min, BigDecimal max, BigDecimal recoveryThreshold, int recoverySamples,
                             int freshnessSeconds, int maxSampleGapSeconds, int evaluationWindowSamples,
                             int requiredHits, int windowSeconds, int durationSeconds, int level,
                             int autoWorkOrder, int recoveryHoldSeconds, int enabled, String remark) { }
    private record Actor(Long userId, String name) { }
}
