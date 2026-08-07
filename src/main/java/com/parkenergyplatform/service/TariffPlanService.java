package com.parkenergyplatform.service;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import cn.dev33.satoken.stp.StpUtil;
import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.common.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TariffPlanService {
    private final JdbcTemplate jdbcTemplate;
    private final BusinessDataAccessService accessService;

    public TariffPlanService(JdbcTemplate jdbcTemplate, BusinessDataAccessService accessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessService = accessService;
    }

    public PageResult<Map<String, Object>> page(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (text(params.get("orgId")) != null) { where.append(" AND p.org_id = ?"); args.add(Long.parseLong(params.get("orgId"))); }
        if (text(params.get("status")) != null) { where.append(" AND p.status = ?"); args.add(params.get("status")); }
        if (text(params.get("keyword")) != null) {
            where.append(" AND (p.plan_code LIKE ? OR p.plan_name LIKE ?)");
            args.add("%" + params.get("keyword").trim() + "%"); args.add("%" + params.get("keyword").trim() + "%");
        }
        where.append(accessService.scopeSql("p.org_id", args));
        int pageNum = positive(params.get("pageNum"), 1);
        int pageSize = Math.min(positive(params.get("pageSize"), 20), 200);
        String from = " FROM billing_tariff_plan p LEFT JOIN dev_org o ON o.id = p.org_id";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(pageSize); pageArgs.add((pageNum - 1) * pageSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT p.*, o.org_name,
                       (SELECT COUNT(*) FROM billing_tariff_period tp WHERE tp.tariff_plan_id = p.id) AS period_count
                """ + from + where + " ORDER BY p.org_id, p.plan_code, p.version DESC, p.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        return PageResult.of(rows, total == null ? 0 : total, pageNum, pageSize);
    }

    public Map<String, Object> detail(long id) {
        Map<String, Object> plan = required(id);
        assertPlanAccess(plan);
        Map<String, Object> result = new LinkedHashMap<>(plan);
        result.put("periods", jdbcTemplate.queryForList("SELECT * FROM billing_tariff_period WHERE tariff_plan_id = ? ORDER BY sort, id", id));
        return result;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        long orgId = longValue(body.get("orgId"), "orgId");
        assertOrg(orgId);
        validateBody(body);
        String code = requiredText(body.get("planCode"), "planCode");
        int version = numberOr(body.get("version"), 1);
        if (exists("SELECT COUNT(*) FROM billing_tariff_plan WHERE org_id = ? AND plan_code = ? AND version = ?", orgId, code, version)) {
            throw new BusinessException(409, "同一园区下方案编码与版本已存在");
        }
        long id = insertPlan(body, version, "DRAFT");
        replacePeriods(id, periods(body));
        return detail(id);
    }

    @Transactional
    public Map<String, Object> update(long id, Map<String, Object> body) {
        Map<String, Object> previous = required(id); assertPlanAccess(previous);
        if ("ACTIVE".equalsIgnoreCase(String.valueOf(previous.get("status")))) {
            throw new BusinessException("生效中的方案不可直接修改，请复制为新版本后再启用");
        }
        long orgId = longValue(body.getOrDefault("orgId", previous.get("org_id")), "orgId"); assertOrg(orgId);
        validateBody(body);
        jdbcTemplate.update("""
                UPDATE billing_tariff_plan SET plan_name=?, org_id=?, apply_mode=?, effective_start_date=?, effective_end_date=?,
                       priority=?, timezone=?, remark=? WHERE id=?
                """, requiredText(body.get("planName"), "planName"), orgId,
                textOr(body, "applyMode", previous.get("apply_mode"), "SUBTREE"),
                Date.valueOf(LocalDate.parse(requiredText(body.get("effectiveStartDate"), "effectiveStartDate"))),
                nullableDate(body.get("effectiveEndDate")), numberOr(body.get("priority"), numberOr(previous.get("priority"), 100)),
                textOr(body, "timezone", previous.get("timezone"), "Asia/Shanghai"), text(body.get("remark")), id);
        replacePeriods(id, periods(body));
        return detail(id);
    }

    public Map<String, Object> validate(Map<String, Object> body) {
        validateBody(body);
        return Map.of("valid", true, "message", "覆盖完整，无时间重叠");
    }

    @Transactional
    public Map<String, Object> enable(long id) {
        Map<String, Object> plan = detail(id);
        if ("ACTIVE".equalsIgnoreCase(String.valueOf(plan.get("status")))) return plan;
        Map<String, Object> validationBody = new HashMap<>();
        validationBody.put("planName", plan.get("plan_name"));
        validationBody.put("effectiveStartDate", plan.get("effective_start_date"));
        validationBody.put("effectiveEndDate", plan.get("effective_end_date"));
        validationBody.put("periods", plan.get("periods"));
        validateBody(validationBody);
        LocalDate start = date(plan.get("effective_start_date"));
        LocalDate end = date(plan.get("effective_end_date"));
        Long conflict = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM billing_tariff_plan
                WHERE org_id = ? AND status = 'ACTIVE' AND id <> ?
                  AND effective_start_date <= COALESCE(?, '9999-12-31')
                  AND COALESCE(effective_end_date, '9999-12-31') >= ?
                """, Long.class, plan.get("org_id"), id, end == null ? null : Date.valueOf(end), Date.valueOf(start));
        if (conflict != null && conflict > 0) throw new BusinessException("该园区已有生效日期重叠的电价方案，请先停用或调整日期");
        jdbcTemplate.update("UPDATE billing_tariff_plan SET status='ACTIVE', approved_by=?, approved_time=NOW() WHERE id=?",
                StpUtil.isLogin() ? String.valueOf(StpUtil.getSession().get("username")) : "system", id);
        return detail(id);
    }

    @Transactional
    public Map<String, Object> disable(long id) {
        Map<String, Object> plan = required(id); assertPlanAccess(plan);
        jdbcTemplate.update("UPDATE billing_tariff_plan SET status='DISABLED' WHERE id=?", id);
        return detail(id);
    }

    @Transactional
    public Map<String, Object> copyVersion(long id, Map<String, Object> body) {
        Map<String, Object> source = detail(id);
        int nextVersion = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(version), 0) + 1 FROM billing_tariff_plan WHERE org_id=? AND plan_code=?",
                Integer.class, source.get("org_id"), source.get("plan_code"));
        Map<String, Object> copy = new LinkedHashMap<>();
        copy.put("orgId", source.get("org_id")); copy.put("planCode", source.get("plan_code"));
        copy.put("planName", body.getOrDefault("planName", source.get("plan_name") + " V" + nextVersion));
        copy.put("applyMode", source.get("apply_mode")); copy.put("effectiveStartDate", body.getOrDefault("effectiveStartDate", source.get("effective_start_date").toString()));
        copy.put("effectiveEndDate", body.getOrDefault("effectiveEndDate", source.get("effective_end_date") == null ? null : source.get("effective_end_date").toString()));
        copy.put("priority", source.get("priority")); copy.put("timezone", source.get("timezone")); copy.put("remark", body.getOrDefault("remark", source.get("remark")));
        copy.put("version", nextVersion); copy.put("periods", source.get("periods"));
        long newId = insertPlan(copy, nextVersion, "DRAFT"); replacePeriods(newId, periods(copy));
        return detail(newId);
    }

    private long insertPlan(Map<String, Object> body, int version, String status) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO billing_tariff_plan
                      (plan_code, plan_name, org_id, apply_mode, effective_start_date, effective_end_date, version,
                       priority, timezone, status, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requiredText(body.get("planCode"), "planCode")); ps.setString(2, requiredText(body.get("planName"), "planName"));
            ps.setLong(3, longValue(body.get("orgId"), "orgId")); ps.setString(4, textOr(body, "applyMode", null, "SUBTREE"));
            ps.setDate(5, Date.valueOf(LocalDate.parse(requiredText(body.get("effectiveStartDate"), "effectiveStartDate")))); ps.setDate(6, nullableDate(body.get("effectiveEndDate")));
            ps.setInt(7, version); ps.setInt(8, numberOr(body.get("priority"), 100)); ps.setString(9, textOr(body, "timezone", null, "Asia/Shanghai"));
            ps.setString(10, status); ps.setString(11, text(body.get("remark"))); return ps;
        }, holder);
        if (holder.getKey() == null) throw new BusinessException("电价方案创建失败");
        return holder.getKey().longValue();
    }

    private void replacePeriods(long planId, List<Map<String, Object>> periods) {
        jdbcTemplate.update("DELETE FROM billing_tariff_period WHERE tariff_plan_id=?", planId);
        for (int i = 0; i < periods.size(); i++) {
            Map<String, Object> item = periods.get(i);
            jdbcTemplate.update("""
                    INSERT INTO billing_tariff_period
                      (tariff_plan_id, day_type, season_code, season_start_md, season_end_md, period_code, period_name,
                       start_time, end_time, unit_price, sort)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, planId, textOr(item, "dayType", null, "ALL"), textOr(item, "seasonCode", null, "ALL"),
                    text(item.get("seasonStartMd")), text(item.get("seasonEndMd")), requiredText(item.get("periodCode"), "periodCode"),
                    requiredText(item.get("periodName"), "periodName"), java.sql.Time.valueOf(requiredText(item.get("startTime"), "startTime") + ":00"),
                    java.sql.Time.valueOf(requiredText(item.get("endTime"), "endTime") + ":00"), new BigDecimal(requiredText(item.get("unitPrice"), "unitPrice")),
                    numberOr(item.get("sort"), i + 1));
        }
    }

    private void validateBody(Map<String, Object> body) {
        requiredText(body.get("planName"), "planName");
        LocalDate start = LocalDate.parse(requiredText(body.get("effectiveStartDate"), "effectiveStartDate"));
        LocalDate end = body.get("effectiveEndDate") == null || text(body.get("effectiveEndDate")) == null ? null : LocalDate.parse(text(body.get("effectiveEndDate")));
        if (end != null && end.isBefore(start)) throw new BusinessException("生效结束日期不能早于开始日期");
        Map<String, List<Map<String, Object>>> groups = periods(body).stream().collect(Collectors.groupingBy(item ->
                textOr(item, "dayType", null, "ALL") + "|" + textOr(item, "seasonCode", null, "ALL")));
        if (groups.isEmpty()) throw new BusinessException("至少需要一个分时计费时段");
        for (Map.Entry<String, List<Map<String, Object>>> group : groups.entrySet()) validateCoverage(group.getKey(), group.getValue());
    }

    private void validateCoverage(String group, List<Map<String, Object>> periods) {
        List<Range> ranges = new ArrayList<>();
        for (Map<String, Object> item : periods) {
            int start = LocalTime.parse(requiredText(item.get("startTime"), "startTime")).toSecondOfDay();
            int end = LocalTime.parse(requiredText(item.get("endTime"), "endTime")).toSecondOfDay();
            if (start == end) throw new BusinessException("时段不能为零长度: " + group);
            if (end > start) ranges.add(new Range(start, end)); else { ranges.add(new Range(start, 86400)); ranges.add(new Range(0, end)); }
            if (new BigDecimal(requiredText(item.get("unitPrice"), "unitPrice")).compareTo(BigDecimal.ZERO) < 0) throw new BusinessException("电价不能小于零");
        }
        ranges.sort(Comparator.comparingInt(Range::start)); int cursor = 0;
        for (Range range : ranges) {
            if (range.start() != cursor) throw new BusinessException("时段组 " + group + " 存在空档或重叠");
            cursor = range.end();
        }
        if (cursor != 86400) throw new BusinessException("时段组 " + group + " 未覆盖完整 24 小时");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> periods(Map<String, Object> body) {
        Object value = body.get("periods"); if (!(value instanceof List<?> list)) throw new BusinessException("periods 必须为数组");
        return list.stream().map(item -> {
            if (!(item instanceof Map<?, ?> map)) throw new BusinessException("periods 数据格式错误");
            Map<String, Object> normalized = new HashMap<>();
            map.forEach((key, val) -> normalized.put(camel(String.valueOf(key)), val));
            for (String timeKey : List.of("startTime", "endTime")) {
                Object time = normalized.get(timeKey);
                if (time != null && String.valueOf(time).length() >= 5) normalized.put(timeKey, String.valueOf(time).substring(0, 5));
            }
            return normalized;
        }).toList();
    }

    private Map<String, Object> required(long id) { List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM billing_tariff_plan WHERE id=?", id); if (rows.isEmpty()) throw new BusinessException(404, "电价方案不存在"); return rows.get(0); }
    private void assertPlanAccess(Map<String, Object> plan) { assertOrg(longValue(plan.get("org_id"), "orgId")); }
    private void assertOrg(long orgId) { if (!accessService.hasOrgAccess(orgId)) throw new BusinessException(403, "没有该园区的电价配置权限"); }
    private boolean exists(String sql, Object... args) { Long count = jdbcTemplate.queryForObject(sql, Long.class, args); return count != null && count > 0; }
    private int positive(String value, int fallback) { try { int number = Integer.parseInt(value); return number > 0 ? number : fallback; } catch (Exception ignored) { return fallback; } }
    private int numberOr(Object value, int fallback) { try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return fallback; } }
    private long longValue(Object value, String field) { if (value == null) throw new BusinessException(field + " 不能为空"); return Long.parseLong(String.valueOf(value)); }
    private String requiredText(Object value, String field) { String result = text(value); if (result == null) throw new BusinessException(field + " 不能为空"); return result; }
    private String text(Object value) { return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim(); }
    private String textOr(Map<String, Object> body, String key, Object fallback, String defaultValue) { String value = text(body.get(key)); return value == null ? (text(fallback) == null ? defaultValue : text(fallback)) : value; }
    private Date nullableDate(Object value) { String text = text(value); return text == null ? null : Date.valueOf(LocalDate.parse(text)); }
    private LocalDate date(Object value) { if (value == null) return null; return value instanceof Date sqlDate ? sqlDate.toLocalDate() : LocalDate.parse(String.valueOf(value)); }
    private String camel(String value) {
        StringBuilder result = new StringBuilder(); boolean upper = false;
        for (char character : value.toCharArray()) { if (character == '_') upper = true; else { result.append(upper ? Character.toUpperCase(character) : character); upper = false; } }
        return result.toString();
    }
    private record Range(int start, int end) { }
}
