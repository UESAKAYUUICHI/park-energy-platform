package com.parkenergyplatform.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.common.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BillingService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final BusinessDataAccessService accessService;

    public BillingService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, BusinessDataAccessService accessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.accessService = accessService;
    }

    @Transactional
    public Map<String, Object> generate(Map<String, Object> request) {
        long accountId = longValue(request.get("accountId"), "accountId");
        accessService.assertBillingAccountAccess(accountId);
        String billCycle = requiredText(request.get("billCycle"), "billCycle");
        LocalDate startDate = LocalDate.parse(requiredText(request.get("startDate"), "startDate"));
        LocalDate endDate = LocalDate.parse(requiredText(request.get("endDate"), "endDate"));
        if (endDate.isBefore(startDate)) {
            throw new BusinessException("endDate 不能早于 startDate");
        }
        if (exists("SELECT COUNT(*) FROM billing_bill WHERE account_id = ? AND bill_cycle = ?", accountId, billCycle)) {
            throw new BusinessException(409, "该账号账期已生成账单");
        }
        BillCalculation calculation = calculate(accountId, startDate, endDate);

        String billNo = "BILL" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
                + String.format("%06d", Math.floorMod(accountId, 1_000_000L));
        long billId = insertBill(billNo, accountId, billCycle, startDate, endDate, calculation.total(),
                Objects.toString(request.getOrDefault("remark", ""), null));
        for (BillDetail detail : calculation.details()) {
            insertDetail(billId, detail);
        }
        return bill(billId);
    }

    public Map<String, Object> preview(Map<String, Object> request) {
        long accountId = longValue(request.get("accountId"), "accountId");
        accessService.assertBillingAccountAccess(accountId);
        LocalDate startDate = LocalDate.parse(requiredText(request.get("startDate"), "startDate"));
        LocalDate endDate = LocalDate.parse(requiredText(request.get("endDate"), "endDate"));
        if (endDate.isBefore(startDate)) {
            throw new BusinessException("endDate 不能早于 startDate");
        }
        BillCalculation calculation = calculate(accountId, startDate, endDate);
        return Map.of(
                "account", account(accountId),
                "startDate", startDate,
                "endDate", endDate,
                "totalAmount", calculation.total().setScale(2, RoundingMode.HALF_UP),
                "details", calculation.details()
        );
    }

    @Transactional
    public Map<String, Object> pay(long billId, Map<String, Object> request) {
        Map<String, Object> bill = bill(billId);
        int payStatus = number(bill.get("pay_status")).intValue();
        if (payStatus != 0) {
            throw new BusinessException(3001, "只有未缴费账单允许缴费");
        }
        BigDecimal totalAmount = decimal(bill.get("total_amount")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal payAmount = decimal(request.get("payAmount")).setScale(2, RoundingMode.HALF_UP);
        if (totalAmount.compareTo(payAmount) != 0) {
            throw new BusinessException(3002, "缴费金额必须等于账单应收金额");
        }
        String payWay = requiredText(request.get("payWay"), "payWay");
        String operator = Objects.toString(request.getOrDefault("operator", "admin"), "admin");
        String remark = Objects.toString(request.getOrDefault("remark", ""), null);
        LocalDateTime payTime = parsePayTime(request.get("payTime"));
        jdbcTemplate.update("""
                INSERT INTO billing_payment (bill_id, pay_amount, pay_way, pay_time, operator, remark)
                VALUES (?, ?, ?, ?, ?, ?)
                """, billId, payAmount, payWay, Timestamp.valueOf(payTime), operator, remark);
        jdbcTemplate.update("""
                UPDATE billing_bill
                SET pay_status = 1, pay_time = ?, pay_way = ?
                WHERE id = ?
                """, Timestamp.valueOf(payTime), payWay, billId);
        return bill(billId);
    }

    public Map<String, Object> bill(long billId) {
        accessService.assertBillAccess(billId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM billing_bill WHERE id = ?", billId);
        if (rows.isEmpty()) {
            throw new BusinessException(404, "账单不存在: " + billId);
        }
        Map<String, Object> bill = new LinkedHashMap<>(rows.get(0));
        bill.put("details", jdbcTemplate.queryForList("SELECT * FROM billing_bill_detail WHERE bill_id = ? ORDER BY id", billId));
        bill.put("payments", jdbcTemplate.queryForList("SELECT * FROM billing_payment WHERE bill_id = ? ORDER BY id", billId));
        return bill;
    }

    public PageResult<Map<String, Object>> listBills(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        appendEquals(where, args, "b.account_id", params.get("accountId"));
        appendEquals(where, args, "b.pay_status", params.get("payStatus"));
        appendEquals(where, args, "b.bill_cycle", params.get("billCycle"));
        appendOrgFilter(where, args, "a.org_id", params);
        String keyword = params.get("keyword");
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (b.bill_no LIKE ? OR a.account_name LIKE ? OR b.remark LIKE ?)");
            args.add("%" + keyword.trim() + "%");
            args.add("%" + keyword.trim() + "%");
            args.add("%" + keyword.trim() + "%");
        }
        where.append(accessService.scopeSql("a.org_id", args));
        int pageNum = parsePositive(params.get("pageNum"), 1);
        int pageSize = Math.min(parsePositive(params.get("pageSize"), 20), 200);
        String from = " FROM billing_bill b JOIN billing_account a ON a.id = b.account_id";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((pageNum - 1) * pageSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT b.*, a.account_name, a.org_id,
                       (SELECT COUNT(*) FROM billing_bill_detail d WHERE d.bill_id = b.id) AS detail_count
                """ + from + where + " ORDER BY b.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        return PageResult.of(rows, total == null ? 0 : total, pageNum, pageSize);
    }

    public Map<String, Object> ruleProfile(long ruleId) {
        Map<String, Object> rule = single("SELECT * FROM billing_rule WHERE id = ?", ruleId);
        accessService.assertBillingAccountAccess(number(rule.get("account_id")).longValue());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rule", rule);
        data.put("account", account(number(rule.get("account_id")).longValue()));
        data.put("deviceType", singleOrNull("SELECT * FROM dev_device_type WHERE id = ?", rule.get("device_type_id")));
        data.put("metricPoint", jdbcTemplate.queryForList("""
                SELECT *
                FROM dev_point_definition
                WHERE device_type_id = ? AND point_code = ?
                """, rule.get("device_type_id"), rule.get("metric_point_code")));
        data.put("scopes", jdbcTemplate.queryForList("SELECT * FROM billing_rule_scope WHERE rule_id = ? ORDER BY id", ruleId));
        data.put("priceItems", jdbcTemplate.queryForList("SELECT * FROM billing_price_item WHERE rule_id = ? ORDER BY sort, id", ruleId));
        return data;
    }

    @Transactional
    public Map<String, Object> recalculate(long billId) {
        Map<String, Object> bill = bill(billId);
        if (number(bill.get("pay_status")).intValue() != 0) {
            throw new BusinessException("只有未缴费账单允许重算");
        }
        long accountId = number(bill.get("account_id")).longValue();
        LocalDate startDate = ((java.sql.Date) bill.get("start_date")).toLocalDate();
        LocalDate endDate = ((java.sql.Date) bill.get("end_date")).toLocalDate();
        BillCalculation calculation = calculate(accountId, startDate, endDate);
        jdbcTemplate.update("DELETE FROM billing_bill_detail WHERE bill_id = ?", billId);
        jdbcTemplate.update("""
                UPDATE billing_bill
                SET total_amount = ?, remark = CONCAT(COALESCE(remark, ''), ' [RECALCULATED]')
                WHERE id = ?
                """, calculation.total().setScale(2, RoundingMode.HALF_UP), billId);
        for (BillDetail detail : calculation.details()) {
            insertDetail(billId, detail);
        }
        return bill(billId);
    }

    @Transactional
    public Map<String, Object> voidBill(long billId, Map<String, Object> request) {
        Map<String, Object> bill = bill(billId);
        if (number(bill.get("pay_status")).intValue() == 1) {
            throw new BusinessException("已缴费账单不允许作废");
        }
        String remark = Objects.toString(request.getOrDefault("remark", "作废"), "作废");
        jdbcTemplate.update("""
                UPDATE billing_bill
                SET pay_status = 3, remark = CONCAT('[VOID] ', ?, ' ', COALESCE(remark, ''))
                WHERE id = ?
                """, remark, billId);
        return bill(billId);
    }

    private BillCalculation calculate(long accountId, LocalDate startDate, LocalDate endDate) {
        List<Map<String, Object>> rules = jdbcTemplate.queryForList(
                "SELECT * FROM billing_rule WHERE account_id = ? AND enabled = 1", accountId);
        if (rules.isEmpty()) {
            throw new BusinessException("该计费账号没有启用的计费规则");
        }
        List<BillDetail> details = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> rule : rules) {
            long ruleId = number(rule.get("id")).longValue();
            long deviceTypeId = number(rule.get("device_type_id")).longValue();
            String pointCode = Objects.toString(rule.get("metric_point_code"), "");
            for (Map<String, Object> device : devicesForRule(ruleId, deviceTypeId)) {
                long deviceId = number(device.get("id")).longValue();
                BigDecimal usage = usage(deviceId, pointCode, startDate, endDate);
                if (usage.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                PriceResult price = price(rule, usage);
                total = total.add(price.amount());
                details.add(new BillDetail(ruleId, deviceId, deviceTypeId, pointCode, usage, price.unitPrice(),
                        price.amount(), snapshot(rule, device, usage, price)));
            }
        }
        if (details.isEmpty()) {
            throw new BusinessException("账期内没有可计费用量");
        }
        return new BillCalculation(details, total);
    }

    private long insertBill(String billNo, long accountId, String billCycle, LocalDate startDate, LocalDate endDate,
                            BigDecimal total, String remark) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO billing_bill (bill_no, account_id, bill_cycle, start_date, end_date, total_amount, pay_status, remark)
                    VALUES (?, ?, ?, ?, ?, ?, 0, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, billNo);
            ps.setLong(2, accountId);
            ps.setString(3, billCycle);
            ps.setDate(4, Date.valueOf(startDate));
            ps.setDate(5, Date.valueOf(endDate));
            ps.setBigDecimal(6, total.setScale(2, RoundingMode.HALF_UP));
            ps.setString(7, remark);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new BusinessException("账单创建失败");
        }
        return key.longValue();
    }

    private void insertDetail(long billId, BillDetail detail) {
        jdbcTemplate.update("""
                INSERT INTO billing_bill_detail
                  (bill_id, rule_id, device_id, device_type_id, point_code, usage_value, unit_price, amount, calculation_snapshot)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, billId, detail.ruleId, detail.deviceId, detail.deviceTypeId, detail.pointCode,
                detail.usage, detail.unitPrice, detail.amount, detail.snapshot);
    }

    private List<Map<String, Object>> devicesForRule(long ruleId, long deviceTypeId) {
        List<Map<String, Object>> scopes = jdbcTemplate.queryForList("SELECT * FROM billing_rule_scope WHERE rule_id = ?", ruleId);
        List<Map<String, Object>> devices = new ArrayList<>();
        for (Map<String, Object> scope : scopes) {
            String scopeType = Objects.toString(scope.get("scope_type"), "");
            long scopeId = number(scope.get("scope_id")).longValue();
            if ("ORG".equalsIgnoreCase(scopeType)) {
                List<Long> orgIds = orgSubtreeIds(scopeId);
                if (!orgIds.isEmpty()) {
                    devices.addAll(jdbcTemplate.queryForList(
                            "SELECT * FROM dev_device WHERE org_id IN (" + placeholders(orgIds.size()) + ") AND device_type_id = ? AND status = 1",
                            deviceQueryArgs(orgIds, deviceTypeId)));
                }
            } else if ("DEVICE".equalsIgnoreCase(scopeType)) {
                devices.addAll(jdbcTemplate.queryForList(
                        "SELECT * FROM dev_device WHERE id = ? AND device_type_id = ? AND status = 1", scopeId, deviceTypeId));
            }
        }
        return devices.stream().collect(java.util.stream.Collectors.toMap(row -> row.get("id"), row -> row, (a, b) -> a))
                .values().stream()
                .filter(device -> accessService.hasDeviceAccess(number(device.get("id")).longValue()))
                .toList();
    }

    private List<Long> orgSubtreeIds(long rootOrgId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id, parent_id FROM dev_org ORDER BY parent_id, sort, id");
        Map<Long, List<Long>> children = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Long id = longOrNull(row.get("id"));
            Long parentId = longOrNull(row.get("parent_id"));
            if (id != null) {
                children.computeIfAbsent(parentId == null ? 0L : parentId, key -> new ArrayList<>()).add(id);
            }
        }
        Set<Long> result = new LinkedHashSet<>();
        ArrayDeque<Long> stack = new ArrayDeque<>();
        stack.push(rootOrgId);
        while (!stack.isEmpty()) {
            Long current = stack.pop();
            if (!result.add(current)) {
                continue;
            }
            for (Long child : children.getOrDefault(current, List.of())) {
                stack.push(child);
            }
        }
        return new ArrayList<>(result);
    }

    private String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private Object[] deviceQueryArgs(List<Long> orgIds, long deviceTypeId) {
        List<Object> args = new ArrayList<>(orgIds);
        args.add(deviceTypeId);
        return args.toArray();
    }

    private PriceResult price(Map<String, Object> rule, BigDecimal usage) {
        long ruleId = number(rule.get("id")).longValue();
        String mode = Objects.toString(rule.get("price_mode"), "UNIT_PRICE").trim().toUpperCase();
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT * FROM billing_price_item WHERE rule_id = ? ORDER BY sort, id", ruleId);
        if (items.isEmpty()) {
            throw new BusinessException("计费规则缺少价格明细: " + ruleId);
        }
        return switch (mode) {
            case "FIXED" -> fixedPrice(usage, items);
            case "TIERED" -> tieredPrice(usage, items);
            case "TIME_PERIOD" -> timePeriodPrice(usage, items);
            default -> unitPrice(usage, items);
        };
    }

    private PriceResult unitPrice(BigDecimal usage, List<Map<String, Object>> items) {
        BigDecimal unitPrice = decimal(items.get(0).get("unit_price"));
        BigDecimal amount = usage.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
        return new PriceResult(unitPrice, amount, "UNIT_PRICE", items);
    }

    private PriceResult fixedPrice(BigDecimal usage, List<Map<String, Object>> items) {
        BigDecimal amount = items.stream()
                .map(item -> decimal(item.get("unit_price")))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal unitPrice = usage.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : amount.divide(usage, 6, RoundingMode.HALF_UP);
        return new PriceResult(unitPrice, amount, "FIXED", items);
    }

    private PriceResult tieredPrice(BigDecimal usage, List<Map<String, Object>> items) {
        BigDecimal amount = BigDecimal.ZERO;
        for (Map<String, Object> item : items) {
            BigDecimal min = decimal(item.get("tier_min"));
            BigDecimal max = item.get("tier_max") == null ? null : decimal(item.get("tier_max"));
            BigDecimal upper = max == null || max.compareTo(usage) > 0 ? usage : max;
            BigDecimal tierUsage = upper.subtract(min).max(BigDecimal.ZERO);
            if (tierUsage.compareTo(BigDecimal.ZERO) > 0) {
                amount = amount.add(tierUsage.multiply(decimal(item.get("unit_price"))));
            }
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal unitPrice = usage.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : amount.divide(usage, 6, RoundingMode.HALF_UP);
        return new PriceResult(unitPrice, amount, "TIERED", items);
    }

    private PriceResult timePeriodPrice(BigDecimal usage, List<Map<String, Object>> items) {
        BigDecimal weightedAmount = BigDecimal.ZERO;
        BigDecimal totalHours = BigDecimal.ZERO;
        for (Map<String, Object> item : items) {
            BigDecimal hours = periodHours(item.get("start_time"), item.get("end_time"));
            if (hours.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            weightedAmount = weightedAmount.add(decimal(item.get("unit_price")).multiply(hours));
            totalHours = totalHours.add(hours);
        }
        BigDecimal unitPrice = totalHours.compareTo(BigDecimal.ZERO) == 0
                ? decimal(items.get(0).get("unit_price"))
                : weightedAmount.divide(totalHours, 6, RoundingMode.HALF_UP);
        BigDecimal amount = usage.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
        return new PriceResult(unitPrice, amount, "TIME_PERIOD_WEIGHTED_AVERAGE", items);
    }

    private BigDecimal periodHours(Object start, Object end) {
        if (start == null || end == null) {
            return BigDecimal.ZERO;
        }
        LocalTime startTime = parseTime(start);
        LocalTime endTime = parseTime(end);
        int startSecond = startTime.toSecondOfDay();
        int endSecond = endTime.toSecondOfDay();
        int seconds = endSecond >= startSecond
                ? endSecond - startSecond
                : 24 * 3600 - startSecond + endSecond;
        return BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(3600), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal usage(long deviceId, String pointCode, LocalDate startDate, LocalDate endDate) {
        BigDecimal value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(usage_value), 0)
                FROM stats_daily_point
                WHERE device_id = ? AND point_code = ? AND stat_date BETWEEN ? AND ?
                """, BigDecimal.class, deviceId, pointCode, Date.valueOf(startDate), Date.valueOf(endDate));
        return value == null ? BigDecimal.ZERO : value;
    }

    private String snapshot(Map<String, Object> rule, Map<String, Object> device, BigDecimal usage,
                            PriceResult price) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "rule", rule,
                    "device", device,
                    "usageValue", usage,
                    "unitPrice", price.unitPrice(),
                    "amount", price.amount(),
                    "priceMode", rule.get("price_mode"),
                    "pricingMethod", price.method(),
                    "priceItems", price.items()
            ));
        } catch (JsonProcessingException ex) {
            throw new BusinessException("账单快照生成失败");
        }
    }

    private boolean exists(String sql, Object... args) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class, args);
        return count != null && count > 0;
    }

    private Map<String, Object> account(long accountId) {
        return single("""
                SELECT a.*, o.org_name
                FROM billing_account a
                LEFT JOIN dev_org o ON o.id = a.org_id
                WHERE a.id = ?
                """, accountId);
    }

    private Map<String, Object> single(String sql, Object... args) {
        Map<String, Object> row = singleOrNull(sql, args);
        if (row == null) {
            throw new BusinessException(404, "数据不存在");
        }
        return row;
    }

    private Map<String, Object> singleOrNull(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void appendEquals(StringBuilder sql, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(" AND ").append(column).append(" = ?");
            args.add(value.trim());
        }
    }

    private void appendOrgFilter(StringBuilder sql, List<Object> args, String column, Map<String, String> params) {
        Long orgId = longOrNull(params.get("orgId"));
        boolean includeChildren = Boolean.parseBoolean(Objects.toString(params.getOrDefault("includeChildren", "false")));
        sql.append(accessService.orgFilterSql(column, orgId, includeChildren, args));
    }

    private int parsePositive(String input, int defaultValue) {
        try {
            int value = Integer.parseInt(input);
            return value > 0 ? value : defaultValue;
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private String requiredText(Object value, String field) {
        if (value == null || value.toString().isBlank()) {
            throw new BusinessException(field + " 不能为空");
        }
        return value.toString();
    }

    private long longValue(Object value, String field) {
        if (value == null) {
            throw new BusinessException(field + " 不能为空");
        }
        return number(value).longValue();
    }

    private Long longOrNull(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return number(value).longValue();
    }

    private BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(value.toString());
    }

    private Number number(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        return new BigDecimal(value.toString());
    }

    private LocalDateTime parsePayTime(Object value) {
        if (value == null || value.toString().isBlank()) {
            return LocalDateTime.now();
        }
        String text = value.toString().trim();
        try {
            return OffsetDateTime.parse(text).toLocalDateTime();
        } catch (Exception ignored) {
            return LocalDateTime.parse(text);
        }
    }

    private LocalTime parseTime(Object value) {
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime();
        }
        return LocalTime.parse(value.toString());
    }

    private record BillDetail(long ruleId, long deviceId, long deviceTypeId, String pointCode, BigDecimal usage,
                              BigDecimal unitPrice, BigDecimal amount, String snapshot) {
    }

    private record BillCalculation(List<BillDetail> details, BigDecimal total) {
    }

    private record PriceResult(BigDecimal unitPrice, BigDecimal amount, String method, List<Map<String, Object>> items) {
    }
}
