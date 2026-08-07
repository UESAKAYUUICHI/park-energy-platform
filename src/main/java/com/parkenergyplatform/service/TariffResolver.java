package com.parkenergyplatform.service;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.parkenergyplatform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Resolves an explicit rule plan first, then the closest active organisation plan. */
@Service
public class TariffResolver {
    private final JdbcTemplate jdbcTemplate;

    public TariffResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public TariffPlan resolve(Long explicitPlanId, long deviceOrgId, LocalDate date) {
        if (explicitPlanId != null) {
            TariffPlan plan = byId(explicitPlanId);
            if (!plan.activeOn(date)) throw new BusinessException("绑定的电价方案在结算日期内未启用: " + plan.planCode());
            if (!ancestors(deviceOrgId).contains(plan.orgId())) {
                throw new BusinessException("计费规则绑定的电价方案不属于该设备所在园区或其上级组织");
            }
            if ("SELF".equalsIgnoreCase(plan.applyMode()) && plan.orgId() != deviceOrgId) {
                throw new BusinessException("计费规则绑定的电价方案仅对本园区生效，不能用于下级组织设备");
            }
            return plan;
        }
        List<Long> ancestors = ancestors(deviceOrgId);
        if (ancestors.isEmpty()) throw new BusinessException("设备未绑定有效园区组织，无法解析分时电价");
        String in = String.join(",", java.util.Collections.nCopies(ancestors.size(), "?"));
        List<Object> args = new ArrayList<>(ancestors);
        args.add(Date.valueOf(date));
        args.add(Date.valueOf(date));
        String sql = "SELECT * FROM billing_tariff_plan WHERE status = 'ACTIVE' AND org_id IN (" + in + ")"
                + " AND effective_start_date <= ? AND (effective_end_date IS NULL OR effective_end_date >= ?)";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray());
        Map<Long, Integer> distance = new HashMap<>();
        for (int i = 0; i < ancestors.size(); i++) distance.put(ancestors.get(i), i);
        return rows.stream().map(this::plan)
                .filter(plan -> !"SELF".equalsIgnoreCase(plan.applyMode()) || plan.orgId() == deviceOrgId)
                .sorted((left, right) -> {
            int compared = Integer.compare(distance.get(left.orgId()), distance.get(right.orgId()));
            if (compared != 0) return compared;
            compared = Integer.compare(right.priority(), left.priority());
            return compared != 0 ? compared : Long.compare(left.id(), right.id());
        }).findFirst().orElseThrow(() -> new BusinessException("结算日期没有生效的园区分时电价方案"));
    }

    public TariffPlan byId(long planId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM billing_tariff_plan WHERE id = ?", planId);
        if (rows.isEmpty()) throw new BusinessException(404, "电价方案不存在: " + planId);
        return plan(rows.get(0));
    }

    private List<Long> ancestors(long orgId) {
        Map<Long, Long> parent = new HashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("SELECT id, parent_id FROM dev_org")) {
            parent.put(number(row.get("id")), row.get("parent_id") == null ? null : number(row.get("parent_id")));
        }
        Set<Long> result = new LinkedHashSet<>();
        Long cursor = orgId;
        while (cursor != null && result.add(cursor)) cursor = parent.get(cursor);
        return new ArrayList<>(result);
    }

    private TariffPlan plan(Map<String, Object> row) {
        return new TariffPlan(number(row.get("id")), String.valueOf(row.get("plan_code")),
                number(row.get("org_id")), String.valueOf(row.get("apply_mode")), number(row.get("version")).intValue(), number(row.get("priority")).intValue(),
                String.valueOf(row.get("status")), date(row.get("effective_start_date")), date(row.get("effective_end_date")));
    }

    private Long number(Object value) { return ((Number) value).longValue(); }
    private LocalDate date(Object value) {
        if (value == null) return null;
        return value instanceof Date sqlDate ? sqlDate.toLocalDate() : LocalDate.parse(String.valueOf(value));
    }

    public record TariffPlan(long id, String planCode, long orgId, String applyMode, int version, int priority, String status,
                             LocalDate effectiveStartDate, LocalDate effectiveEndDate) {
        public boolean activeOn(LocalDate date) {
            return "ACTIVE".equalsIgnoreCase(status) && !date.isBefore(effectiveStartDate)
                    && (effectiveEndDate == null || !date.isAfter(effectiveEndDate));
        }
    }
}
