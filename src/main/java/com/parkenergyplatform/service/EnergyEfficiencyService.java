package com.parkenergyplatform.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.parkenergyplatform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Aggregates small, indexed fact-table queries into the energy-efficiency judgement panel. */
@Service
public class EnergyEfficiencyService {
    private final JdbcTemplate jdbcTemplate;
    private final BusinessDataAccessService accessService;

    public EnergyEfficiencyService(JdbcTemplate jdbcTemplate, BusinessDataAccessService accessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessService = accessService;
    }

    public Map<String, Object> overview(Map<String, String> params) {
        Long deviceId = longOrNull(params.get("deviceId"));
        if (deviceId != null) accessService.assertDeviceAccess(deviceId);
        Long orgId = longOrNull(params.get("orgId"));
        if (orgId != null && !accessService.hasOrgAccess(orgId)) throw new BusinessException(403, "没有该组织的数据访问权限");
        LocalDate end = dateOr(params.get("endDate"), LocalDate.now());
        LocalDate start = dateOr(params.get("startDate"), end.minusDays(29));
        if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) > 366) throw new BusinessException("统计范围应在 1 至 367 天内");
        String carrier = textOr(params.get("energyCarrier"), "ELECTRICITY").toUpperCase(Locale.ROOT);

        Filter filter = filter(deviceId, orgId, start, end, carrier);
        Map<String, Object> usage = single("""
                SELECT COALESCE(SUM(s.usage_value), 0) usage_value, AVG(s.data_complete_rate) completeness
                FROM stats_daily_point s JOIN dev_device d ON d.id=s.device_id
                JOIN dev_point_definition p ON p.device_type_id=s.device_type_id AND p.point_code=s.point_code
                WHERE p.energy_dimension='CONSUMPTION' %s
                """.formatted(filter.sql()), filter.args());
        Map<String, Object> tou = tou(filter);
        Map<String, Object> quality = quality(filter);
        Map<String, Object> comparison = comparison(deviceId, orgId, start, end, carrier, decimal(usage.get("usage_value")));
        Map<String, Object> baseline = baseline(deviceId, orgId, end, carrier, decimal(usage.get("usage_value")), start, end);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("range", Map.of("startDate", start, "endDate", end, "energyCarrier", carrier));
        response.put("consumption", decimal(usage.get("usage_value")));
        response.put("dataCompleteRate", scale(decimal(usage.get("completeness")), 2));
        response.put("tou", tou);
        response.put("quality", quality);
        response.put("comparison", comparison);
        response.put("baseline", baseline);
        response.put("judgements", judgements(quality, baseline));
        return response;
    }

    private Map<String, Object> tou(Filter filter) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT tariff_period_code, COALESCE(SUM(usage_value),0) usage_value
                FROM stats_tou_daily s JOIN dev_device d ON d.id=s.device_id
                JOIN dev_point_definition p ON p.device_type_id=s.device_type_id AND p.point_code=s.point_code
                WHERE p.energy_dimension='CONSUMPTION' %s GROUP BY tariff_period_code
                """.formatted(filter.sql()), filter.args().toArray());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sharpPeak", BigDecimal.ZERO); values.put("peak", BigDecimal.ZERO);
        values.put("flat", BigDecimal.ZERO); values.put("valley", BigDecimal.ZERO); values.put("deepValley", BigDecimal.ZERO);
        for (Map<String, Object> row : rows) {
            String key = textOr(String.valueOf(row.get("tariff_period_code")), "").toUpperCase(Locale.ROOT);
            String target = switch (key) { case "SHARP_PEAK" -> "sharpPeak"; case "PEAK" -> "peak"; case "FLAT" -> "flat"; case "VALLEY" -> "valley"; case "DEEP_VALLEY" -> "deepValley"; default -> null; };
            if (target != null) values.put(target, decimal(row.get("usage_value")));
        }
        return values;
    }

    private Map<String, Object> quality(Filter filter) {
        Map<String, Object> basic = single("""
                SELECT MAX(CASE WHEN s.point_code IN ('ACTIVE_POWER_TOTAL','DEMAND_MAX') THEN s.max_value END) max_demand,
                       AVG(CASE WHEN s.point_code='POWER_FACTOR_TOTAL' THEN s.avg_value END) power_factor,
                       100*AVG(CASE WHEN s.point_code IN ('VOLTAGE_A','VOLTAGE_B','VOLTAGE_C')
                         THEN CASE WHEN s.min_value>=198 AND s.max_value<=242 THEN 1 ELSE 0 END END) voltage_qualified_rate
                FROM stats_hourly_point s JOIN dev_device d ON d.id=s.device_id
                WHERE s.point_code IN ('ACTIVE_POWER_TOTAL','DEMAND_MAX','POWER_FACTOR_TOTAL','VOLTAGE_A','VOLTAGE_B','VOLTAGE_C') %s
                """.formatted(filter.sql()), filter.args());
        List<Map<String, Object>> currentRows = jdbcTemplate.queryForList("""
                SELECT s.device_id, s.stat_date, s.stat_hour,
                  MAX(CASE WHEN s.point_code='CURRENT_A' THEN s.avg_value END) current_a,
                  MAX(CASE WHEN s.point_code='CURRENT_B' THEN s.avg_value END) current_b,
                  MAX(CASE WHEN s.point_code='CURRENT_C' THEN s.avg_value END) current_c
                FROM stats_hourly_point s JOIN dev_device d ON d.id=s.device_id
                WHERE s.point_code IN ('CURRENT_A','CURRENT_B','CURRENT_C') %s
                GROUP BY s.device_id, s.stat_date, s.stat_hour
                """.formatted(filter.sql()), filter.args().toArray());
        BigDecimal imbalanceSum = BigDecimal.ZERO; int valid = 0;
        for (Map<String, Object> row : currentRows) {
            List<BigDecimal> currents = List.of(decimal(row.get("current_a")), decimal(row.get("current_b")), decimal(row.get("current_c")));
            if (currents.stream().anyMatch(value -> value.compareTo(BigDecimal.ZERO) <= 0)) continue;
            BigDecimal max = currents.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal min = currents.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal average = currents.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(3), 6, RoundingMode.HALF_UP);
            imbalanceSum = imbalanceSum.add(max.subtract(min).multiply(BigDecimal.valueOf(100)).divide(average, 4, RoundingMode.HALF_UP)); valid++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("maxDemand", decimal(basic.get("max_demand")));
        result.put("powerFactor", scale(decimal(basic.get("power_factor")), 4));
        result.put("voltageQualifiedRate", scale(decimal(basic.get("voltage_qualified_rate")), 2));
        result.put("threePhaseImbalance", valid == 0 ? null : scale(imbalanceSum.divide(BigDecimal.valueOf(valid), 4, RoundingMode.HALF_UP), 2));
        return result;
    }

    private Map<String, Object> comparison(Long deviceId, Long orgId, LocalDate start, LocalDate end, String carrier, BigDecimal current) {
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        LocalDate previousEnd = start.minusDays(1); LocalDate previousStart = previousEnd.minusDays(days - 1);
        Filter previous = filter(deviceId, orgId, previousStart, previousEnd, carrier);
        BigDecimal before = decimal(single("SELECT COALESCE(SUM(s.usage_value),0) usage_value FROM stats_daily_point s JOIN dev_device d ON d.id=s.device_id JOIN dev_point_definition p ON p.device_type_id=s.device_type_id AND p.point_code=s.point_code WHERE p.energy_dimension='CONSUMPTION' %s".formatted(previous.sql()), previous.args()).get("usage_value"));
        Map<String, Object> result = new LinkedHashMap<>(); result.put("previousConsumption", before);
        result.put("periodOverPeriodRate", before.compareTo(BigDecimal.ZERO) == 0 ? null : scale(current.subtract(before).multiply(BigDecimal.valueOf(100)).divide(before, 4, RoundingMode.HALF_UP), 2));
        return result;
    }

    private Map<String, Object> baseline(Long deviceId, Long orgId, LocalDate date, String carrier, BigDecimal actual, LocalDate start, LocalDate end) {
        List<Map<String, Object>> rows;
        if (deviceId != null) rows = jdbcTemplate.queryForList("""
                SELECT * FROM energy_efficiency_baseline WHERE enabled=1 AND energy_carrier=?
                AND (device_id=? OR (device_id IS NULL AND org_id=(SELECT org_id FROM dev_device WHERE id=?)))
                AND effective_start_date<=? AND (effective_end_date IS NULL OR effective_end_date>=?)
                ORDER BY device_id IS NULL, effective_start_date DESC LIMIT 1
                """, carrier, deviceId, deviceId, date, date);
        else if (orgId != null) rows = jdbcTemplate.queryForList("SELECT * FROM energy_efficiency_baseline WHERE enabled=1 AND energy_carrier=? AND org_id=? AND device_id IS NULL AND effective_start_date<=? AND (effective_end_date IS NULL OR effective_end_date>=?) ORDER BY effective_start_date DESC LIMIT 1", carrier, orgId, date, date);
        else return Map.of("available", false);
        if (rows.isEmpty()) return Map.of("available", false);
        Map<String, Object> row = rows.get(0); BigDecimal daily = decimal(row.get("baseline_daily_value"));
        BigDecimal expected = daily.multiply(BigDecimal.valueOf(ChronoUnit.DAYS.between(start, end) + 1));
        Map<String, Object> result = new LinkedHashMap<>(); result.put("available", true); result.put("baselineId", row.get("id"));
        result.put("metricPointCode", row.get("metric_point_code"));
        result.put("expectedConsumption", expected); result.put("savedUsage", expected.subtract(actual));
        result.put("savingRate", expected.compareTo(BigDecimal.ZERO) == 0 ? null : scale(expected.subtract(actual).multiply(BigDecimal.valueOf(100)).divide(expected, 4, RoundingMode.HALF_UP), 2));
        BigDecimal normalization = decimal(row.get("normalization_value"));
        if (normalization.compareTo(BigDecimal.ZERO) > 0) result.put("normalizedConsumption", scale(actual.divide(normalization, 4, RoundingMode.HALF_UP), 4));
        result.put("normalizationType", row.get("normalization_type"));
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("demand", row.get("demand_threshold")); thresholds.put("powerFactor", row.get("power_factor_threshold")); thresholds.put("imbalancePct", row.get("imbalance_threshold_pct"));
        result.put("thresholds", thresholds);
        return result;
    }

    private List<Map<String, Object>> judgements(Map<String, Object> quality, Map<String, Object> baseline) {
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal pf = decimal(quality.get("powerFactor")); if (pf.compareTo(BigDecimal.ZERO) > 0) rows.add(Map.of("code", "POWER_FACTOR", "level", pf.compareTo(BigDecimal.valueOf(.9)) >= 0 ? "NORMAL" : "WARNING", "value", pf));
        BigDecimal voltage = decimal(quality.get("voltageQualifiedRate")); if (voltage.compareTo(BigDecimal.ZERO) > 0) rows.add(Map.of("code", "VOLTAGE_QUALITY", "level", voltage.compareTo(BigDecimal.valueOf(95)) >= 0 ? "NORMAL" : "WARNING", "value", voltage));
        if (Boolean.TRUE.equals(baseline.get("available")) && baseline.get("savingRate") != null) rows.add(Map.of("code", "SAVING", "level", decimal(baseline.get("savingRate")).compareTo(BigDecimal.ZERO) >= 0 ? "NORMAL" : "WARNING", "value", baseline.get("savingRate")));
        return rows;
    }

    private Filter filter(Long deviceId, Long orgId, LocalDate start, LocalDate end, String carrier) {
        StringBuilder sql = new StringBuilder(" AND s.stat_date BETWEEN ? AND ? AND d.energy_carrier=?");
        List<Object> args = new ArrayList<>(List.of(start, end, carrier));
        if (deviceId != null) { sql.append(" AND s.device_id=?"); args.add(deviceId); }
        if (orgId != null) { sql.append(" AND s.org_id=?"); args.add(orgId); }
        sql.append(accessService.scopeSql("s.org_id", args)); return new Filter(sql.toString(), args);
    }
    private Map<String, Object> single(String sql, List<Object> args) { return jdbcTemplate.queryForList(sql, args.toArray()).get(0); }
    private BigDecimal decimal(Object value) { if (value == null) return BigDecimal.ZERO; try { return new BigDecimal(value.toString()); } catch (RuntimeException ignored) { return BigDecimal.ZERO; } }
    private BigDecimal scale(BigDecimal value, int scale) { return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP); }
    private Long longOrNull(String value) { try { return value == null || value.isBlank() ? null : Long.valueOf(value); } catch (NumberFormatException ignored) { throw new BusinessException("参数必须为数字"); } }
    private LocalDate dateOr(String value, LocalDate fallback) { try { return value == null || value.isBlank() ? fallback : LocalDate.parse(value); } catch (RuntimeException ignored) { throw new BusinessException("日期格式必须为 yyyy-MM-dd"); } }
    private String textOr(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private record Filter(String sql, List<Object> args) { }
}
