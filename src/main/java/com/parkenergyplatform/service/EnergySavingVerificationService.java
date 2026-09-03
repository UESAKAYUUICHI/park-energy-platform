package com.parkenergyplatform.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Writes a non-blocking, reproducible energy-effect verification whenever a device work order closes. */
@Service
public class EnergySavingVerificationService {
    private final JdbcTemplate jdbcTemplate;

    public EnergySavingVerificationService(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    public void verifyOnClosure(Map<String, Object> workOrder, String operator) {
        Long deviceId = longOrNull(workOrder.get("device_id"));
        Long orgId = longOrNull(workOrder.get("org_id"));
        if (deviceId == null || orgId == null) return;
        try {
            List<Map<String, Object>> baselines = jdbcTemplate.queryForList("""
                    SELECT * FROM energy_efficiency_baseline
                    WHERE enabled=1 AND energy_carrier='ELECTRICITY'
                      AND (device_id=? OR (device_id IS NULL AND org_id=?))
                      AND effective_start_date<=CURDATE() AND (effective_end_date IS NULL OR effective_end_date>=CURDATE())
                    ORDER BY device_id IS NULL, effective_start_date DESC LIMIT 1
                    """, deviceId, orgId);
            if (baselines.isEmpty()) { save(workOrder, null, null, null, null, null, null, "NO_BASELINE", "未配置设备或组织级电力能效基线", operator); return; }
            Map<String, Object> baseline = baselines.get(0);
            String pointCode = metricPointCode(baseline, deviceId);
            int window = Math.max(1, Math.min(31, intValue(baseline.get("verification_window_days"), 7)));
            List<LocalDate> dates = jdbcTemplate.queryForList("SELECT MAX(stat_date) FROM stats_daily_point WHERE device_id=? AND point_code=?", LocalDate.class, deviceId, pointCode);
            LocalDate end = dates.isEmpty() ? null : dates.get(0);
            if (end == null) { save(workOrder, longOrNull(baseline.get("id")), null, null, null, null, null, "PENDING_DATA", "关闭后尚无可核验的日电量统计", operator); return; }
            LocalDate start = end.minusDays(window - 1L);
            Map<String, Object> actual = jdbcTemplate.queryForList("""
                    SELECT COALESCE(SUM(usage_value),0) usage, AVG(data_complete_rate) completeness
                    FROM stats_daily_point WHERE device_id=? AND point_code=? AND stat_date BETWEEN ? AND ?
                    """, deviceId, pointCode, start, end).get(0);
            BigDecimal expected = decimal(baseline.get("baseline_daily_value")).multiply(BigDecimal.valueOf(window));
            BigDecimal usage = decimal(actual.get("usage")); BigDecimal completeness = decimal(actual.get("completeness"));
            BigDecimal saved = expected.subtract(usage); BigDecimal rate = expected.compareTo(BigDecimal.ZERO) == 0 ? null : saved.multiply(BigDecimal.valueOf(100)).divide(expected, 4, RoundingMode.HALF_UP);
            String verdict = completeness.compareTo(BigDecimal.valueOf(80)) < 0 ? "PENDING_DATA" : saved.compareTo(BigDecimal.ZERO) >= 0 ? "SAVED" : "NO_SAVING";
            save(workOrder, longOrNull(baseline.get("id")), start, end, expected, usage, completeness, verdict,
                    "关闭后 " + window + " 日核验；节约量=" + saved + "，节能率=" + rate, operator);
        } catch (RuntimeException ignored) {
            // Migration may not be applied yet. Verification must never prevent the regulated work-order closure.
        }
    }

    private void save(Map<String, Object> order, Long baselineId, LocalDate start, LocalDate end, BigDecimal expected,
                      BigDecimal actual, BigDecimal completeness, String verdict, String remark, String operator) {
        BigDecimal saved = expected == null || actual == null ? null : expected.subtract(actual);
        BigDecimal rate = saved == null || expected.compareTo(BigDecimal.ZERO) == 0 ? null : saved.multiply(BigDecimal.valueOf(100)).divide(expected, 4, RoundingMode.HALF_UP);
        jdbcTemplate.update("""
                INSERT INTO ops_energy_saving_verification (work_order_id,org_id,device_id,baseline_id,verification_start_date,verification_end_date,expected_usage,actual_usage,saved_usage,saving_rate,data_complete_rate,verdict,remark,generated_at,create_by,update_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(),?,?)
                ON DUPLICATE KEY UPDATE baseline_id=VALUES(baseline_id),verification_start_date=VALUES(verification_start_date),verification_end_date=VALUES(verification_end_date),expected_usage=VALUES(expected_usage),actual_usage=VALUES(actual_usage),saved_usage=VALUES(saved_usage),saving_rate=VALUES(saving_rate),data_complete_rate=VALUES(data_complete_rate),verdict=VALUES(verdict),remark=VALUES(remark),generated_at=NOW(),update_by=VALUES(update_by)
                """, longOrNull(order.get("id")), longOrNull(order.get("org_id")), longOrNull(order.get("device_id")), baselineId,
                start, end, expected, actual, saved, rate, completeness, verdict, remark, operator, operator);
    }

    private String metricPointCode(Map<String, Object> baseline, long deviceId) {
        Object configured = baseline.get("metric_point_code");
        if (configured != null && !configured.toString().isBlank()) return configured.toString().trim().toUpperCase();
        List<String> points = jdbcTemplate.queryForList("""
                SELECT p.point_code
                FROM dev_device d JOIN dev_point_definition p ON p.device_type_id=d.device_type_id
                WHERE d.id=? AND p.enabled=1 AND p.billable=1 AND p.stat_enabled=1
                  AND UPPER(COALESCE(p.business_role,''))='TOTAL_ACCUMULATED'
                ORDER BY p.id LIMIT 1
                """, String.class, deviceId);
        return points.isEmpty() ? "FORWARD_ACTIVE_ENERGY" : points.get(0);
    }
    private Long longOrNull(Object value) { try { return value == null ? null : Long.valueOf(value.toString()); } catch (RuntimeException ignored) { return null; } }
    private int intValue(Object value, int fallback) { try { return Integer.parseInt(String.valueOf(value)); } catch (RuntimeException ignored) { return fallback; } }
    private BigDecimal decimal(Object value) { try { return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString()); } catch (RuntimeException ignored) { return BigDecimal.ZERO; } }
}
