package com.parkenergyplatform.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ERP-style billing query model.
 *
 * <p>This service deliberately computes workflow state from existing domain tables.
 * It does not introduce new status tables, so the older CRUD tools can keep working
 * while the UI presents a process-oriented operating surface.</p>
 */
@Service
public class BillingErpService {
    private final JdbcTemplate jdbc;
    private final BusinessDataAccessService access;
    private final BillingPeriodService billingPeriods;
    private final BillingSettlementArchiveService settlementArchives;

    public BillingErpService(JdbcTemplate jdbc, BusinessDataAccessService access, BillingPeriodService billingPeriods,
                             BillingSettlementArchiveService settlementArchives) {
        this.jdbc = jdbc;
        this.access = access;
        this.billingPeriods = billingPeriods;
        this.settlementArchives = settlementArchives;
    }

    public Map<String, Object> overview(Long orgId, String billCycle) {
        String cycle = cycle(billCycle);
        List<Map<String, Object>> subjects = subjectRows(orgId, null, cycle, 300);
        List<Map<String, Object>> periods = closingRows(orgId, cycle, 120);

        long admissionReady = subjects.stream().filter(row -> "PASSED".equals(row.get("admissionStatus"))).count();
        long schemeReady = subjects.stream().filter(row -> "ACTIVE".equals(row.get("schemeStatus"))).count();
        long billIssued = subjects.stream().filter(row -> "ISSUED".equals(row.get("jobStatus"))).count();
        long blocked = subjects.stream().filter(row -> !"PASSED".equals(row.get("admissionStatus"))
                || !"ACTIVE".equals(row.get("schemeStatus"))).count();
        long difference = periods.stream().mapToLong(row -> number(row.get("differenceCount"))).sum();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("cycle", cycle);
        summary.put("subjectCount", subjects.size());
        summary.put("admissionReady", admissionReady);
        summary.put("schemeReady", schemeReady);
        summary.put("issuedSubjects", billIssued);
        summary.put("blockedSubjects", blocked);
        summary.put("openPeriods", periods.stream().filter(row -> "OPEN".equals(row.get("status"))).count());
        summary.put("differenceCount", difference);
        summary.put("outstandingAmount", subjects.stream().map(row -> money(row.get("outstandingAmount"))).reduce(BigDecimal.ZERO, BigDecimal::add));
        summary.put("paidAmount", subjects.stream().map(row -> money(row.get("paidAmount"))).reduce(BigDecimal.ZERO, BigDecimal::add));
        summary.put("issuedAmount", subjects.stream().map(row -> money(row.get("totalAmount"))).reduce(BigDecimal.ZERO, BigDecimal::add));
        summary.put("draftSubjects", subjects.stream().filter(row -> "DRAFT".equals(row.get("jobStatus"))).count());
        summary.put("reviewSubjects", subjects.stream().filter(row -> "REVIEWING".equals(row.get("jobStatus"))).count());
        summary.put("closeablePeriods", periods.stream().filter(row -> "CLOSEABLE".equals(row.get("closingStatus"))).count());

        List<Map<String, Object>> stages = List.of(
                stage("对象准入", admissionReady, subjects.size(), blocked, "/billing/admission"),
                stage("方案生效", schemeReady, subjects.size(), subjects.size() - schemeReady, "/billing/schemes"),
                stage("出账作业", billIssued, subjects.size(), subjects.size() - billIssued, "/billing/jobs"),
                stage("关账归档", periods.stream().filter(row -> "CLOSED".equals(row.get("status"))).count(),
                        periods.size(), difference, "/billing/closing")
        );

        List<Map<String, Object>> todos = new ArrayList<>();
        subjects.stream().filter(row -> !"PASSED".equals(row.get("admissionStatus"))).limit(4)
                .forEach(row -> todos.add(todo("对象准入", text(row.get("subjectName")), text(row.get("admissionText")), "/billing/admission")));
        subjects.stream().filter(row -> "INCOMPLETE".equals(row.get("schemeStatus"))).limit(4)
                .forEach(row -> todos.add(todo("方案生效", text(row.get("subjectName")), text(row.get("schemeText")), "/billing/schemes")));
        subjects.stream().filter(row -> List.of("READY", "DRAFT", "REVIEWING").contains(text(row.get("jobStatus")))).limit(4)
                .forEach(row -> todos.add(todo("出账作业", text(row.get("subjectName")), text(row.get("jobText")), "/billing/jobs")));
        periods.stream().filter(row -> number(row.get("differenceCount")) > 0).limit(3)
                .forEach(row -> todos.add(todo("关账归档", text(row.get("periodCode")), "存在 " + row.get("differenceCount") + " 条对账差异", "/billing/closing")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("stages", stages);
        result.put("todos", todos);
        result.put("riskSubjects", subjects.stream()
                .filter(row -> !"PASSED".equals(row.get("admissionStatus")) || !"ACTIVE".equals(row.get("schemeStatus"))
                        || "BLOCKED".equals(row.get("jobStatus")) || money(row.get("outstandingAmount")).compareTo(BigDecimal.ZERO) > 0)
                .sorted((left, right) -> money(right.get("outstandingAmount")).compareTo(money(left.get("outstandingAmount"))))
                .limit(8).toList());
        result.put("recentEvents", recentEvents(orgId, cycle, 12));
        result.put("subjects", subjects.stream().limit(12).toList());
        result.put("periods", periods.stream().limit(8).toList());
        return result;
    }

    public Map<String, Object> admission(Long orgId, String keyword) {
        List<Map<String, Object>> rows = subjectRows(orgId, keyword, cycle(null), 500);
        Map<String, Object> result = baseStage("对象准入", "建立结算主体、合同依据、空间占用、计费账户和结算表计之间的准入关系。", rows);
        result.put("queues", queue(rows, "admissionStatus", Map.of("PASSED", "准入通过", "BLOCKED", "准入阻断")));
        return result;
    }

    public Map<String, Object> schemes(Long orgId, String keyword) {
        List<Map<String, Object>> rows = subjectRows(orgId, keyword, cycle(null), 500);
        Map<String, Object> result = baseStage("方案生效", "让已准入对象获得可试算、可出账的计量来源、计价模型和生效范围。", rows);
        result.put("queues", queue(rows, "schemeStatus", Map.of("ACTIVE", "已生效", "INCOMPLETE", "待配置")));
        return result;
    }

    public Map<String, Object> jobs(Long orgId, String billCycle, String keyword) {
        String cycle = cycle(billCycle);
        List<Map<String, Object>> rows = subjectRows(orgId, keyword, cycle, 500);
        List<Map<String, Object>> billRows = billingRows(orgId, keyword, cycle, 500);
        Map<String, Object> result = baseStage("出账作业", "基于账期、合同、表计数据和计费方案执行预检、试算、生成、审核发布与应收形成。", rows);
        result.put("cycle", cycle);
        result.put("billRows", billRows);
        result.put("queues", queue(rows, "jobStatus", Map.of(
                "BLOCKED", "不可出账", "READY", "可生成", "DRAFT", "草稿待审", "REVIEWING", "审核发布", "ISSUED", "已形成应收")));
        result.put("batches", batchRows(orgId, cycle));
        result.put("jobSummary", Map.of(
                "cycle", cycle,
                "ready", rows.stream().filter(row -> "READY".equals(row.get("jobStatus"))).count(),
                "blocked", rows.stream().filter(row -> "BLOCKED".equals(row.get("jobStatus"))).count(),
                "draft", rows.stream().filter(row -> "DRAFT".equals(row.get("jobStatus"))).count(),
                "reviewing", rows.stream().filter(row -> "REVIEWING".equals(row.get("jobStatus"))).count(),
                "issued", billRows.stream().filter(row -> "ISSUED".equals(row.get("billStatus"))).count(),
                "amount", billRows.stream().map(row -> money(row.get("totalAmount"))).reduce(BigDecimal.ZERO, BigDecimal::add),
                "outstanding", billRows.stream().map(row -> money(row.get("outstandingAmount"))).reduce(BigDecimal.ZERO, BigDecimal::add)
        ));
        return result;
    }

    private List<Map<String, Object>> billingRows(Long orgId, String keyword, String billCycle, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE BINARY b.bill_cycle = BINARY ?");
        args.add(billCycle);
        where.append(access.orgFilterSql("a.org_id", orgId, true, args));
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (b.bill_no LIKE ? OR b.tenant_name_snapshot LIKE ? OR a.account_name LIKE ? OR t.tenant_name LIKE ?)");
            for (int i = 0; i < 4; i++) args.add("%" + keyword.trim() + "%");
        }
        where.append(access.scopeSql("a.org_id", args));
        args.add(limit);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT b.id AS billId, b.bill_no AS billNo, b.account_id AS accountId, b.batch_id AS batchId,
                       b.contract_id AS contractId, b.tenant_name_snapshot AS tenantName,
                       b.bill_type AS billType, b.supplement_settlement_id AS supplementSettlementId,
                       COALESCE(NULLIF(b.tenant_name_snapshot, ''), a.account_name) AS accountName,
                       a.org_id AS orgId, o.org_name AS orgName,
                       b.bill_cycle AS billCycle, b.start_date AS startDate, b.end_date AS endDate,
                       b.due_date AS dueDate, b.bill_status AS billStatus, b.pay_status AS payStatus,
                       b.total_amount AS totalAmount, b.paid_amount AS paidAmount,
                       b.outstanding_amount AS outstandingAmount, b.create_time AS createTime,
                       b.update_time AS updateTime, b.remark AS remark,
                       (SELECT i.id FROM billing_invoice_bill ib
                          JOIN billing_invoice i ON i.id=ib.invoice_id
                         WHERE ib.bill_id=b.id
                         ORDER BY CASE WHEN i.invoice_status='RED' THEN 1 ELSE 0 END, i.id DESC LIMIT 1) AS invoiceId,
                       (SELECT i.invoice_no FROM billing_invoice_bill ib
                          JOIN billing_invoice i ON i.id=ib.invoice_id
                         WHERE ib.bill_id=b.id
                         ORDER BY CASE WHEN i.invoice_status='RED' THEN 1 ELSE 0 END, i.id DESC LIMIT 1) AS invoiceNo,
                       (SELECT i.invoice_status FROM billing_invoice_bill ib
                          JOIN billing_invoice i ON i.id=ib.invoice_id
                         WHERE ib.bill_id=b.id
                         ORDER BY CASE WHEN i.invoice_status='RED' THEN 1 ELSE 0 END, i.id DESC LIMIT 1) AS invoiceStatus,
                       CASE
                         WHEN b.bill_status = 'VOID' THEN 'VOID'
                         WHEN b.outstanding_amount > 0 AND b.due_date < CURRENT_DATE THEN 'OVERDUE'
                         WHEN b.bill_status IN ('DRAFT','REVIEWED') THEN 'PENDING_REVIEW'
                         WHEN b.outstanding_amount > 0 AND b.paid_amount > 0 THEN 'PARTIAL'
                         WHEN b.outstanding_amount > 0 THEN 'PENDING_PAYMENT'
                         ELSE 'SETTLED'
                       END AS settlementStatus
                FROM billing_bill b
                JOIN billing_account a ON a.id = b.account_id
                LEFT JOIN crm_tenant t ON t.id = a.tenant_id
                LEFT JOIN dev_org o ON o.id = a.org_id
                """ + where + " ORDER BY CASE WHEN b.outstanding_amount > 0 THEN 0 ELSE 1 END, b.create_time DESC, b.id DESC LIMIT ?", args.toArray());
        for (Map<String, Object> row : rows) {
            row.put("hasArrears", money(row.get("outstandingAmount")).compareTo(BigDecimal.ZERO) > 0);
        }
        return rows;
    }

    public Map<String, Object> closing(Long orgId, String billCycle) {
        String cycle = cycle(billCycle);
        List<Map<String, Object>> periods = closingRows(orgId, cycle, 300);
        List<Map<String, Object>> archiveItems = monthlyArchiveItems(orgId, cycle, 500);
        Map<String, Object> result = baseStage("结算档案", "每张账单独立完结；月内可继续出账和收款，最终月度归档后统一永久锁定。", archiveItems);
        result.put("cycle", cycle);
        result.put("periods", periods);
        result.put("archiveItems", archiveItems);
        result.put("queues", queue(periods, "closingStatus", Map.of(
                "NO_PERIOD", "未建账期", "OPEN", "开放账期", "DIFFERENCE", "差异处理", "CLOSEABLE", "允许关账", "CLOSED", "已关账")));
        return result;
    }

    public Map<String, Object> subjectDetail(long accountId, String billCycle) {
        access.assertBillingAccountAccess(accountId);
        String cycle = cycle(billCycle);
        Map<String, Object> subject = subjectRow(accountId, cycle);
        Long contractId = longOrNull(subject.get("contractId"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("subject", subject);
        result.put("checks", subjectChecks(subject));
        result.put("rules", ruleRows(accountId));
        result.put("meters", meterRows(accountId, contractId));
        result.put("bills", billRows(accountId, cycle));
        result.put("adjustments", adjustmentRows(accountId));
        result.put("collections", collectionRows(accountId));
        result.put("events", billEventRows(accountId));
        result.put("suggestedAction", suggestedSubjectAction(subject));
        return result;
    }

    public Map<String, Object> closingDetail(long periodId) {
        Map<String, Object> period = periodRow(periodId);
        period.putAll(billingPeriods.check(periodId));
        enrichClosing(period);
        Long orgId = longOrNull(period.get("orgId"));
        String periodCode = text(period.get("periodCode"));
        Map<String, Object> archive = periodArchive(orgId, periodCode);
        boolean archived = "ARCHIVED".equalsIgnoreCase(text(archive.get("archive_status")));
        if (archived) {
            Map<String,Object> snapshot = new LinkedHashMap<>(settlementArchives.detail(longOrNull(archive.get("id"))));
            snapshot.put("closingStatus", "ARCHIVED");
            snapshot.put("closingText", "结算资料已封存归档");
            snapshot.put("checks", List.of());
            snapshot.put("archive", archive);
            snapshot.put("supplements", periodSupplements(periodId));
            return snapshot;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", period);
        result.put("checks", archived ? List.of() : closingChecks(period));
        result.put("bills", periodBills(orgId, periodCode));
        result.put("payments", periodPayments(orgId, periodCode));
        result.put("statements", periodStatements(orgId, periodCode));
        result.put("vouchers", periodVouchers(orgId, periodCode));
        result.put("adjustments", periodAdjustments(orgId, periodCode));
        result.put("supplements", periodSupplements(periodId));
        result.put("events", periodEvents(orgId, periodCode));
        result.put("archive", archive);
        result.put("suggestedAction", suggestedClosingAction(period));
        return result;
    }

    private List<Map<String,Object>> periodSupplements(long periodId) {
        return jdbc.queryForList("""
                SELECT s.*,c.contract_no,c.contract_name,b.bill_no,b.total_amount,b.bill_status,b.pay_status
                FROM billing_supplement_settlement s
                LEFT JOIN leasing_contract c ON c.id=s.contract_id
                LEFT JOIN billing_bill b ON b.id=s.generated_bill_id
                WHERE s.source_period_id=? ORDER BY s.id DESC
                """, periodId);
    }

    @Transactional
    public Map<String, Object> prepareDemo325(String billCycle) {
        String cycle = cycle(billCycle);
        YearMonth month = YearMonth.parse(cycle);
        Map<String, Object> demo = demo325Context();
        long deviceId = number(demo.get("deviceId"));
        long deviceTypeId = number(demo.get("deviceTypeId"));
        long orgId = number(demo.get("orgId"));
        long accountId = number(demo.get("accountId"));
        long planId = number(demo.get("planId"));
        if (!access.hasOrgAccess(orgId)) throw new com.parkenergyplatform.common.BusinessException(403, "没有 325 演示园区权限");

        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        jdbc.update("""
                UPDATE dev_device
                SET settlement_enabled = 1, meter_role = 'MAIN', meter_factor = 1.000000,
                    quality_gate_start_date = ?, quality_threshold_pct = 80.00,
                    collect_interval_seconds = COALESCE(collect_interval_seconds, 300),
                    update_by = 'admin', update_time = NOW()
                WHERE id = ?
                """, Date.valueOf(start), deviceId);

        List<Map<String, Object>> periods = jdbc.queryForList("""
                SELECT period_code AS periodCode,
                       CASE
                         WHEN period_code LIKE 'VALLEY%' THEN 3.600000
                         WHEN period_code LIKE 'FLAT%' THEN 4.800000
                         WHEN period_code LIKE 'PEAK%' THEN 2.900000
                         WHEN period_code LIKE 'SHARP%' THEN 1.700000
                         ELSE 2.500000
                       END AS demoUsage
                FROM billing_tariff_period
                WHERE tariff_plan_id = ?
                ORDER BY sort, id
                """, planId);
        if (periods.isEmpty()) throw new com.parkenergyplatform.common.BusinessException("DEMO-325 电价方案缺少分时时段");

        jdbc.update("DELETE FROM stats_tou_daily WHERE device_id = ? AND point_code = 'FORWARD_ACTIVE_ENERGY' AND stat_date BETWEEN ? AND ?",
                deviceId, Date.valueOf(start), Date.valueOf(end));
        BigDecimal startValue = BigDecimal.valueOf(32500);
        int days = 0;
        BigDecimal totalUsage = BigDecimal.ZERO;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            BigDecimal dailyUsage = BigDecimal.ZERO;
            for (Map<String, Object> period : periods) {
                BigDecimal usage = money(period.get("demoUsage"))
                        .multiply(BigDecimal.ONE.add(BigDecimal.valueOf((date.getDayOfMonth() % 5) * 0.03)))
                        .setScale(6, RoundingMode.HALF_UP);
                dailyUsage = dailyUsage.add(usage);
                jdbc.update("""
                        INSERT INTO stats_tou_daily
                          (device_id, device_type_id, org_id, point_code, stat_date, tariff_plan_id, tariff_plan_version,
                           tariff_period_code, usage_value, sample_count, data_complete_rate, quality_status)
                        VALUES (?, ?, ?, 'FORWARD_ACTIVE_ENERGY', ?, ?, 1, ?, ?, 288, 100.00, 'NORMAL')
                        ON DUPLICATE KEY UPDATE usage_value = VALUES(usage_value), sample_count = VALUES(sample_count),
                          data_complete_rate = 100.00, quality_status = 'NORMAL', update_time = CURRENT_TIMESTAMP
                        """, deviceId, deviceTypeId, orgId, Date.valueOf(date), planId, period.get("periodCode"), usage);
            }
            BigDecimal endValue = startValue.add(dailyUsage).setScale(6, RoundingMode.HALF_UP);
            jdbc.update("""
                    INSERT INTO stats_daily_point
                      (device_id, device_type_id, org_id, point_code, stat_date, first_collect_time, last_collect_time,
                       start_value, end_value, usage_value, max_value, min_value, avg_value, data_complete_rate, sample_count)
                    VALUES (?, ?, ?, 'FORWARD_ACTIVE_ENERGY', ?, ?, ?, ?, ?, ?, ?, ?, ?, 100.00, 288)
                    ON DUPLICATE KEY UPDATE first_collect_time = VALUES(first_collect_time), last_collect_time = VALUES(last_collect_time),
                      start_value = VALUES(start_value), end_value = VALUES(end_value), usage_value = VALUES(usage_value),
                      max_value = VALUES(max_value), min_value = VALUES(min_value), avg_value = VALUES(avg_value),
                      data_complete_rate = 100.00, sample_count = VALUES(sample_count), update_time = CURRENT_TIMESTAMP
                    """, deviceId, deviceTypeId, orgId, Date.valueOf(date), Timestamp.valueOf(date.atTime(0, 5)),
                    Timestamp.valueOf(date.atTime(23, 55)), startValue, endValue, dailyUsage, endValue, startValue,
                    startValue.add(endValue).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP));
            jdbc.update("""
                    INSERT INTO stats_collection_daily
                      (device_id, org_id, stat_date, expected_samples, received_samples, data_complete_rate,
                       longest_gap_seconds, first_collect_time, last_collect_time, quality_status)
                    VALUES (?, ?, ?, 288, 288, 100.00, 300, ?, ?, 'NORMAL')
                    ON DUPLICATE KEY UPDATE expected_samples = 288, received_samples = 288, data_complete_rate = 100.00,
                      longest_gap_seconds = 300, first_collect_time = VALUES(first_collect_time),
                      last_collect_time = VALUES(last_collect_time), quality_status = 'NORMAL', update_time = CURRENT_TIMESTAMP
                    """, deviceId, orgId, Date.valueOf(date), Timestamp.valueOf(date.atTime(0, 5)),
                    Timestamp.valueOf(date.atTime(23, 55)));
            days++;
            totalUsage = totalUsage.add(dailyUsage);
            startValue = endValue;
        }
        ensureDemoPeriod(orgId, cycle, start, end);
        jdbc.update("""
                UPDATE billing_bill
                SET paid_amount = COALESCE(paid_amount, 0.00),
                    outstanding_amount = GREATEST(total_amount - COALESCE(paid_amount, 0.00), 0.00),
                    pay_status = CASE WHEN GREATEST(total_amount - COALESCE(paid_amount, 0.00), 0.00) <= 0 THEN 1 ELSE pay_status END
                WHERE account_id = ? AND BINARY bill_cycle = BINARY ? AND pay_status IN (0, 4)
                """, accountId, cycle);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountId", accountId);
        result.put("deviceId", deviceId);
        result.put("orgId", orgId);
        result.put("billCycle", cycle);
        result.put("daysPrepared", days);
        result.put("totalUsage", totalUsage.setScale(2, RoundingMode.HALF_UP));
        result.put("message", "DEMO-325 采集质量、日用量和分时用量已补齐，可重新生成出账批次");
        return result;
    }

    private Map<String, Object> demo325Context() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT d.id AS deviceId, d.device_type_id AS deviceTypeId, d.org_id AS orgId,
                       r.account_id AS accountId, r.tariff_plan_id AS planId
                FROM dev_device d
                JOIN billing_rule_scope s ON s.scope_type = 'DEVICE' AND s.scope_id = d.id
                JOIN billing_rule r ON r.id = s.rule_id AND r.enabled = 1
                WHERE d.device_sn = 'WZBC-LS2-M-325'
                  AND r.rule_name LIKE '%325%'
                  AND r.tariff_plan_id IS NOT NULL
                ORDER BY r.id DESC LIMIT 1
                """);
        if (rows.isEmpty()) throw new com.parkenergyplatform.common.BusinessException("DEMO-325 计费规则未初始化，请先执行演示种子数据");
        return rows.get(0);
    }

    private void ensureDemoPeriod(long orgId, String cycle, LocalDate start, LocalDate end) {
        Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM billing_period WHERE org_id = ? AND period_code = ?",
                Long.class, orgId, cycle);
        if (exists != null && exists > 0) return;
        jdbc.update("""
                INSERT INTO billing_period (period_code, org_id, start_date, end_date, status, remark, create_by, update_by)
                VALUES (?, ?, ?, ?, 'OPEN', '[DEMO-325] 演示账期自动补齐', 'admin', 'admin')
                """, cycle, orgId, Date.valueOf(start), Date.valueOf(end));
    }

    private Map<String, Object> subjectRow(long accountId, String billCycle) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id AS accountId, a.account_name AS accountName, a.status AS accountStatus,
                       a.org_id AS orgId, o.org_name AS orgName, t.tenant_name AS tenantName,
                       t.contact_name AS contactName, t.contact_phone AS contactPhone,
                       c.id AS contractId, c.contract_no AS contractNo, c.contract_name AS contractName,
                       c.status AS contractStatus, c.start_date AS contractStartDate, c.end_date AS contractEndDate,
                       (SELECT COUNT(*) FROM leasing_contract_space cs WHERE cs.contract_id = c.id) AS spaceCount,
                       (SELECT COUNT(*) FROM leasing_contract_meter cm WHERE cm.contract_id = c.id AND cm.status='ACTIVE') AS meterCount,
                       (SELECT COUNT(*) FROM billing_rule r WHERE r.account_id = a.id AND r.enabled = 1) AS activeRuleCount,
                       (SELECT COUNT(*) FROM billing_rule r LEFT JOIN billing_price_item pi ON pi.rule_id = r.id
                         WHERE r.account_id = a.id AND r.enabled = 1 AND (r.price_mode='TIME_PERIOD' OR pi.id IS NOT NULL)) AS pricedRuleCount,
                       (SELECT COUNT(*) FROM billing_bill b WHERE b.account_id = a.id AND BINARY b.bill_cycle = BINARY ?) AS billCount,
                       (SELECT COALESCE(SUM(b.total_amount),0) FROM billing_bill b WHERE b.account_id = a.id AND BINARY b.bill_cycle = BINARY ?) AS totalAmount,
                       (SELECT COALESCE(SUM(b.paid_amount),0) FROM billing_bill b WHERE b.account_id = a.id AND BINARY b.bill_cycle = BINARY ?) AS paidAmount,
                       (SELECT COALESCE(SUM(b.outstanding_amount),0) FROM billing_bill b WHERE b.account_id = a.id AND BINARY b.bill_cycle = BINARY ?) AS outstandingAmount,
                       (SELECT GROUP_CONCAT(DISTINCT b.bill_status ORDER BY b.bill_status SEPARATOR ',') FROM billing_bill b WHERE b.account_id = a.id AND BINARY b.bill_cycle = BINARY ?) AS billStatuses,
                       (SELECT b.id FROM billing_bill b WHERE b.account_id = a.id AND BINARY b.bill_cycle = BINARY ? ORDER BY b.id DESC LIMIT 1) AS billId,
                       (SELECT b.batch_id FROM billing_bill b WHERE b.account_id = a.id AND BINARY b.bill_cycle = BINARY ? ORDER BY b.id DESC LIMIT 1) AS batchId,
                       (SELECT bb.status FROM billing_bill b LEFT JOIN billing_batch bb ON bb.id = b.batch_id WHERE b.account_id = a.id AND BINARY b.bill_cycle = BINARY ? ORDER BY b.id DESC LIMIT 1) AS batchStatus
                FROM billing_account a
                LEFT JOIN dev_org o ON o.id = a.org_id
                LEFT JOIN crm_tenant t ON t.id = a.tenant_id
                LEFT JOIN leasing_contract c ON c.id = a.contract_id
                WHERE a.id = ?
                """, billCycle, billCycle, billCycle, billCycle, billCycle, billCycle, billCycle, billCycle, accountId);
        if (rows.isEmpty()) throw new com.parkenergyplatform.common.BusinessException(404, "计费账户不存在: " + accountId);
        Map<String, Object> row = rows.get(0);
        enrichSubject(row);
        return row;
    }

    private List<Map<String, Object>> subjectChecks(Map<String, Object> subject) {
        boolean accountReady = number(subject.get("accountStatus")) == 1;
        boolean contractReady = subject.get("contractId") == null || "ACTIVE".equals(text(subject.get("contractStatus")));
        boolean spaceReady = subject.get("contractId") == null || number(subject.get("spaceCount")) > 0;
        boolean meterReady = number(subject.get("meterCount")) > 0;
        boolean ruleReady = number(subject.get("activeRuleCount")) > 0 && number(subject.get("pricedRuleCount")) > 0;
        boolean billReady = !"BLOCKED".equals(text(subject.get("jobStatus")));
        return List.of(
                check("主体信息", accountReady, accountReady ? "计费账户已启用" : "计费账户未启用", "records"),
                check("合同依据", contractReady, contractReady ? "合同处于有效结算状态" : "合同未生效或已终止", "contracts"),
                check("空间占用", spaceReady, spaceReady ? number(subject.get("spaceCount")) + " 个空间已绑定" : "合同尚未绑定空间", "contracts"),
                check("计量关系", meterReady, meterReady ? number(subject.get("meterCount")) + " 台结算表计已绑定" : "未绑定结算表计", "meters"),
                check("方案生效", ruleReady, ruleReady ? number(subject.get("activeRuleCount")) + " 条规则可参与试算" : "缺少启用规则或价格参数", "rules"),
                check("出账准备", billReady, billReady ? text(subject.get("jobText")) : "准入或方案未完成，暂不可出账", "settlement")
        );
    }

    private Map<String, Object> check(String label, boolean ok, String detail, String tool) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("title", label);
        row.put("ok", ok);
        row.put("passed", ok);
        row.put("status", ok ? "PASSED" : "BLOCKED");
        row.put("detail", detail);
        row.put("tool", tool);
        row.put("target", tool);
        return row;
    }

    private List<Map<String, Object>> ruleRows(long accountId) {
        return jdbc.queryForList("""
                SELECT r.*, dt.type_name AS deviceTypeName,
                       (SELECT COUNT(*) FROM billing_rule_scope s WHERE s.rule_id = r.id) AS scopeCount,
                       (SELECT COUNT(*) FROM billing_price_item p WHERE p.rule_id = r.id) AS priceItemCount
                FROM billing_rule r LEFT JOIN dev_device_type dt ON dt.id = r.device_type_id
                WHERE r.account_id = ? ORDER BY r.enabled DESC, r.id DESC LIMIT 20
                """, accountId);
    }

    private List<Map<String, Object>> meterRows(long accountId, Long contractId) {
        if (contractId != null) {
            return jdbc.queryForList("""
                    SELECT cm.*, d.device_sn AS deviceSn, d.device_name AS deviceName, d.meter_role AS meterRole,
                           d.settlement_enabled AS settlementEnabled, d.status AS deviceStatus
                    FROM leasing_contract_meter cm JOIN dev_device d ON d.id = cm.device_id
                    WHERE cm.contract_id = ? ORDER BY cm.status, cm.id DESC LIMIT 20
                    """, contractId);
        }
        return jdbc.queryForList("""
                SELECT d.id AS deviceId, d.device_sn AS deviceSn, d.device_name AS deviceName, d.meter_role AS meterRole,
                       d.settlement_enabled AS settlementEnabled, d.status AS deviceStatus
                FROM dev_device d JOIN billing_account a ON a.org_id = d.org_id
                WHERE a.id = ? AND d.settlement_enabled = 1 ORDER BY d.id DESC LIMIT 20
                """, accountId);
    }

    private List<Map<String, Object>> billRows(long accountId, String cycle) {
        return jdbc.queryForList("""
                SELECT id, bill_no AS billNo, bill_cycle AS billCycle, bill_type AS billType, bill_status AS billStatus,
                       pay_status AS payStatus, total_amount AS totalAmount, paid_amount AS paidAmount,
                       outstanding_amount AS outstandingAmount, due_date AS dueDate, create_time AS createTime
                FROM billing_bill WHERE account_id = ? AND BINARY bill_cycle = BINARY ? ORDER BY id DESC LIMIT 20
                """, accountId, cycle);
    }

    private List<Map<String, Object>> adjustmentRows(long accountId) {
        return jdbc.queryForList("""
                SELECT aj.*, b.bill_no AS billNo
                FROM billing_adjustment aj JOIN billing_bill b ON b.id = aj.bill_id
                WHERE b.account_id = ? ORDER BY aj.id DESC LIMIT 12
                """, accountId);
    }

    private List<Map<String, Object>> collectionRows(long accountId) {
        return jdbc.queryForList("""
                SELECT c.*, b.bill_no AS billNo
                FROM billing_collection_record c JOIN billing_bill b ON b.id = c.bill_id
                WHERE b.account_id = ? ORDER BY c.collection_time DESC, c.id DESC LIMIT 12
                """, accountId);
    }

    private List<Map<String, Object>> billEventRows(long accountId) {
        return jdbc.queryForList("""
                SELECT e.*, b.bill_no AS billNo
                FROM billing_bill_event e JOIN billing_bill b ON b.id = e.bill_id
                WHERE b.account_id = ? ORDER BY e.event_time DESC, e.id DESC LIMIT 16
                """, accountId);
    }

    private Map<String, Object> periodRow(long periodId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.id AS periodId, p.period_code AS periodCode, p.org_id AS orgId, o.org_name AS orgName,
                       p.start_date AS startDate, p.end_date AS endDate, p.status, p.closed_time AS closedTime,
                       (SELECT COUNT(*) FROM billing_bill b JOIN billing_account a ON a.id=b.account_id
                         WHERE a.org_id=p.org_id AND BINARY b.bill_cycle=BINARY p.period_code) AS billCount,
                       (SELECT COUNT(*) FROM billing_bill b JOIN billing_account a ON a.id=b.account_id
                         WHERE a.org_id=p.org_id AND BINARY b.bill_cycle=BINARY p.period_code AND b.bill_status='ISSUED') AS issuedBillCount,
                       (SELECT COALESCE(SUM(b.total_amount),0) FROM billing_bill b JOIN billing_account a ON a.id=b.account_id
                         WHERE a.org_id=p.org_id AND BINARY b.bill_cycle=BINARY p.period_code AND b.bill_status='ISSUED') AS issuedAmount,
                       (SELECT COALESCE(SUM(b.outstanding_amount),0) FROM billing_bill b JOIN billing_account a ON a.id=b.account_id
                         WHERE a.org_id=p.org_id AND BINARY b.bill_cycle=BINARY p.period_code AND b.bill_status='ISSUED') AS outstandingAmount,
                       (SELECT COUNT(*) FROM billing_bank_statement s WHERE s.target_period_id=p.id) AS reconciliationCount,
                       (SELECT COUNT(*) FROM billing_bank_statement s WHERE s.target_period_id=p.id AND s.statement_status IN ('MATCHED','PARTIAL')) AS matchedCount,
                       (SELECT COUNT(*) FROM billing_bank_statement s WHERE s.target_period_id=p.id AND s.statement_status='DIFFERENCE') AS differenceCount
                FROM billing_period p LEFT JOIN dev_org o ON o.id = p.org_id WHERE p.id = ?
                """, periodId);
        if (rows.isEmpty()) throw new com.parkenergyplatform.common.BusinessException(404, "账期不存在: " + periodId);
        Map<String, Object> row = rows.get(0);
        Long orgId = longOrNull(row.get("orgId"));
        if (orgId == null || !access.hasOrgAccess(orgId)) throw new com.parkenergyplatform.common.BusinessException(403, "没有该园区账期权限");
        enrichClosing(row);
        return row;
    }

    private List<Map<String, Object>> closingChecks(Map<String, Object> period) {
        long billCount = number(period.get("billCount"));
        long issuedBillCount = number(period.get("issuedBillCount"));
        long differenceCount = number(period.get("differenceCount"));
        long unmatched = number(period.get("unmatchedPaymentCount"));
        long missingAr = number(period.get("missingReceivableVoucherCount"));
        long missingReceipt = number(period.get("missingReceiptVoucherCount"));
        long pendingAdjustments = number(period.get("pendingAdjustmentCount"));
        long pendingInvoices = number(period.get("pendingInvoiceCount"));
        boolean closed = "CLOSED".equals(text(period.get("status")));
        return List.of(
                check("账期建立", true, text(period.get("periodCode")) + " 已建立", "finance"),
                check("账单发布", billCount > 0 && billCount == issuedBillCount, issuedBillCount + " / " + billCount + " 张账单已发布", "batches"),
                check("收款跟进", money(period.get("outstandingAmount")).compareTo(BigDecimal.ZERO) <= 0, "剩余应收 ¥" + money(period.get("outstandingAmount")), "collections"),
                check("收款对账", unmatched == 0 && differenceCount == 0, unmatched == 0 ? (differenceCount == 0 ? "收款已完成勾兑，无对账差异" : "存在 " + differenceCount + " 条对账差异") : "存在 " + unmatched + " 笔收款尚未勾兑", "finance"),
                check("财务凭证", missingAr == 0 && missingReceipt == 0, missingAr == 0 && missingReceipt == 0 ? "应收与收款凭证均已确认入账" : "缺少 " + missingAr + " 张应收入账凭证、" + missingReceipt + " 张收款入账凭证", "finance"),
                check("调账事项", pendingAdjustments == 0, pendingAdjustments == 0 ? "不存在待审批调账" : "存在 " + pendingAdjustments + " 条待审批调账", "collections"),
                check("发票事项", pendingInvoices == 0, pendingInvoices == 0 ? "不存在待开具或待红冲发票" : "存在 " + pendingInvoices + " 条未完成发票", "finance"),
                check("关账归档", closed, closed ? "账期已关闭，等待或已经完成归档" : text(period.get("closingText")), "finance")
        );
    }

    private List<Map<String, Object>> periodBills(Long orgId, String periodCode) {
        return jdbc.queryForList("""
                SELECT b.id, b.bill_no AS billNo, a.account_name AS accountName, b.tenant_name_snapshot AS tenantName,
                       b.contract_id AS contractId,c.contract_no AS contractNo,c.contract_name AS contractName,
                       b.bill_status AS billStatus,b.pay_status AS payStatus,b.total_amount AS totalAmount,
                       b.paid_amount AS paidAmount,b.outstanding_amount AS outstandingAmount
                FROM billing_bill b JOIN billing_account a ON a.id = b.account_id
                LEFT JOIN leasing_contract c ON c.id=b.contract_id
                WHERE a.org_id = ? AND BINARY b.bill_cycle = BINARY ? ORDER BY b.id DESC LIMIT 30
                """, orgId, periodCode);
    }

    private List<Map<String, Object>> periodPayments(Long orgId, String periodCode) {
        return jdbc.queryForList("""
                SELECT p.*,b.bill_no AS billNo,b.tenant_name_snapshot AS tenantName,
                       EXISTS(SELECT 1 FROM billing_bank_match m WHERE m.payment_id=p.id AND m.match_status='MATCHED') AS reconciled
                FROM billing_payment p JOIN billing_bill b ON b.id=p.bill_id JOIN billing_account a ON a.id=b.account_id
                WHERE a.org_id=? AND BINARY b.bill_cycle=BINARY ? ORDER BY p.pay_time,p.id
                """, orgId, periodCode);
    }

    private List<Map<String, Object>> periodStatements(Long orgId, String periodCode) {
        return jdbc.queryForList("""
                SELECT DISTINCT s.id,s.external_transaction_no,s.payer_name,s.statement_time,s.amount,s.matched_amount,s.statement_status,
                       (SELECT MAX(mx.id) FROM billing_bank_match mx WHERE mx.statement_id=s.id AND mx.match_status='MATCHED') AS active_match_id
                FROM billing_bank_statement s JOIN billing_period p ON p.id=s.target_period_id
                WHERE p.org_id=? AND BINARY p.period_code=BINARY ? ORDER BY s.statement_time,s.id
                """, orgId, periodCode);
    }

    private List<Map<String, Object>> periodVouchers(Long orgId, String periodCode) {
        return jdbc.queryForList("""
                SELECT v.*,b.bill_no AS billNo,p.payment_no AS paymentNo
                FROM billing_voucher v
                LEFT JOIN billing_payment p ON p.id=v.payment_id
                LEFT JOIN billing_bill b ON b.id=COALESCE(v.bill_id,p.bill_id)
                WHERE v.org_id=? AND BINARY b.bill_cycle=BINARY ? ORDER BY v.id
                """, orgId, periodCode);
    }

    private List<Map<String, Object>> periodAdjustments(Long orgId, String periodCode) {
        return jdbc.queryForList("""
                SELECT j.*,b.bill_no AS billNo FROM billing_adjustment j JOIN billing_bill b ON b.id=j.bill_id JOIN billing_account a ON a.id=b.account_id
                WHERE a.org_id=? AND BINARY b.bill_cycle=BINARY ? ORDER BY j.id
                """, orgId, periodCode);
    }

    private Map<String, Object> periodArchive(Long orgId, String periodCode) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM billing_settlement_archive WHERE org_id=? AND period_code=?", orgId, periodCode);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private List<Map<String, Object>> periodEvents(Long orgId, String periodCode) {
        return jdbc.queryForList("""
                SELECT e.*, b.bill_no AS billNo, a.account_name AS accountName
                FROM billing_bill_event e
                JOIN billing_bill b ON b.id = e.bill_id
                JOIN billing_account a ON a.id = b.account_id
                WHERE a.org_id = ? AND BINARY b.bill_cycle = BINARY ?
                ORDER BY e.event_time DESC, e.id DESC LIMIT 20
                """, orgId, periodCode);
    }

    private Map<String, Object> suggestedSubjectAction(Map<String, Object> subject) {
        String status = text(subject.get("admissionStatus"));
        String scheme = text(subject.get("schemeStatus"));
        String job = text(subject.get("jobStatus"));
        if (!"PASSED".equals(status)) return todo("对象准入", "查看准入阻断", text(subject.get("admissionText")), "contracts");
        if (!"ACTIVE".equals(scheme)) return todo("方案生效", "配置并启用计费方案", text(subject.get("schemeText")), "rules");
        if ("READY".equals(job)) return todo("出账作业", "运行预检并生成账单", text(subject.get("jobText")), "settlement");
        if ("DRAFT".equals(job) || "REVIEWING".equals(job)) return todo("出账作业", "处理审核发布", text(subject.get("jobText")), "batches");
        return todo("收款闭环", "查看收款与应收", text(subject.get("jobText")), "collections");
    }

    private Map<String, Object> suggestedClosingAction(Map<String, Object> period) {
        String status = text(period.get("closingStatus"));
        if ("DIFFERENCE".equals(status)) return todo("关账归档", "处理对账差异", text(period.get("closingText")), "finance");
        if ("CLOSEABLE".equals(status)) return todo("关账归档", "执行关账检查", text(period.get("closingText")), "finance");
        if ("CLOSED".equals(status)) return todo("关账归档", "查看归档留痕", text(period.get("closingText")), "finance");
        return todo("出账作业", "完成账单发布", text(period.get("closingText")), "batches");
    }

    private List<Map<String, Object>> subjectRows(Long orgId, String keyword, String billCycle, int limit) {
        List<Object> whereArgs = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        where.append(access.orgFilterSql("a.org_id", orgId, true, whereArgs));
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (a.account_name LIKE ? OR t.tenant_name LIKE ? OR c.contract_no LIKE ? OR o.org_name LIKE ?)");
            for (int i = 0; i < 4; i++) whereArgs.add("%" + keyword.trim() + "%");
        }
        where.append(access.scopeSql("a.org_id", whereArgs));
        List<Object> args = new ArrayList<>();
        args.add(billCycle);
        args.add(billCycle);
        args.add(billCycle);
        args.add(billCycle);
        args.add(billCycle);
        args.add(billCycle);
        args.add(billCycle);
        args.add(billCycle);
        args.addAll(whereArgs);
        args.add(limit);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id AS accountId, a.account_name AS accountName, a.status AS accountStatus,
                       a.org_id AS orgId, o.org_name AS orgName, t.tenant_name AS tenantName,
                       t.contact_name AS contactName, t.contact_phone AS contactPhone,
                       c.id AS contractId, c.contract_no AS contractNo, c.contract_name AS contractName,
                       c.status AS contractStatus, c.start_date AS contractStartDate, c.end_date AS contractEndDate,
                       (SELECT COUNT(*) FROM leasing_contract_space cs WHERE cs.contract_id = c.id) AS spaceCount,
                       (SELECT COUNT(*) FROM leasing_contract_meter cm WHERE cm.contract_id = c.id AND cm.status='ACTIVE') AS meterCount,
                       (SELECT COUNT(*) FROM billing_rule r WHERE r.account_id = a.id AND r.enabled = 1) AS activeRuleCount,
                       (SELECT COUNT(*) FROM billing_rule r LEFT JOIN billing_price_item pi ON pi.rule_id = r.id
                         WHERE r.account_id = a.id AND r.enabled = 1 AND (r.price_mode='TIME_PERIOD' OR pi.id IS NOT NULL)) AS pricedRuleCount,
                       (SELECT COUNT(*) FROM billing_bill b WHERE b.account_id = a.id AND BINARY b.bill_cycle = BINARY ?) AS billCount,
                       (SELECT COALESCE(SUM(b.total_amount),0) FROM billing_bill b WHERE b.account_id = a.id AND BINARY b.bill_cycle = BINARY ?) AS totalAmount,
                       (SELECT COALESCE(SUM(b.paid_amount),0) FROM billing_bill b WHERE b.account_id = a.id AND BINARY b.bill_cycle = BINARY ?) AS paidAmount,
                       (SELECT COALESCE(SUM(b.outstanding_amount),0) FROM billing_bill b WHERE b.account_id = a.id AND BINARY b.bill_cycle = BINARY ?) AS outstandingAmount,
                       (SELECT GROUP_CONCAT(DISTINCT b.bill_status ORDER BY b.bill_status SEPARATOR ',') FROM billing_bill b WHERE b.account_id = a.id AND BINARY b.bill_cycle = BINARY ?) AS billStatuses,
                       (SELECT b.id FROM billing_bill b WHERE b.account_id = a.id AND BINARY b.bill_cycle = BINARY ? ORDER BY b.id DESC LIMIT 1) AS billId,
                       (SELECT b.batch_id FROM billing_bill b WHERE b.account_id = a.id AND BINARY b.bill_cycle = BINARY ? ORDER BY b.id DESC LIMIT 1) AS batchId,
                       (SELECT bb.status FROM billing_bill b LEFT JOIN billing_batch bb ON bb.id = b.batch_id WHERE b.account_id = a.id AND BINARY b.bill_cycle = BINARY ? ORDER BY b.id DESC LIMIT 1) AS batchStatus
                FROM billing_account a
                LEFT JOIN dev_org o ON o.id = a.org_id
                LEFT JOIN crm_tenant t ON t.id = a.tenant_id
                LEFT JOIN leasing_contract c ON c.id = a.contract_id
                """ + where + " ORDER BY a.id DESC LIMIT ?", args.toArray());
        for (Map<String, Object> row : rows) enrichSubject(row);
        return rows;
    }

    private void enrichSubject(Map<String, Object> row) {
        row.put("id", row.get("accountId"));
        boolean accountReady = number(row.get("accountStatus")) == 1;
        boolean contractReady = row.get("contractId") == null || "ACTIVE".equals(text(row.get("contractStatus")));
        boolean spaceReady = row.get("contractId") == null || number(row.get("spaceCount")) > 0;
        boolean meterReady = number(row.get("meterCount")) > 0;
        boolean ruleReady = number(row.get("activeRuleCount")) > 0 && number(row.get("pricedRuleCount")) > 0;
        String admissionStatus = accountReady && contractReady && spaceReady && meterReady ? "PASSED" : "BLOCKED";
        String schemeStatus = ruleReady ? "ACTIVE" : "INCOMPLETE";
        String billStatuses = text(row.get("billStatuses"));
        String jobStatus;
        if (!"PASSED".equals(admissionStatus) || !"ACTIVE".equals(schemeStatus)) jobStatus = "BLOCKED";
        else if (billStatuses == null) jobStatus = "READY";
        else if (billStatuses.contains("ISSUED")) jobStatus = "ISSUED";
        else if (billStatuses.contains("REVIEW")) jobStatus = "REVIEWING";
        else jobStatus = "DRAFT";
        row.put("subjectName", firstText(row.get("tenantName"), row.get("accountName")));
        row.put("admissionStatus", admissionStatus);
        row.put("admissionText", admissionStatus.equals("PASSED") ? "主体、合同、空间、账户和表计已满足准入"
                : missingText(List.of(
                accountReady ? "" : "计费账户未启用",
                contractReady ? "" : "合同未生效",
                spaceReady ? "" : "合同未绑定空间",
                meterReady ? "" : "未绑定结算表计")));
        row.put("schemeStatus", schemeStatus);
        row.put("schemeText", ruleReady ? number(row.get("activeRuleCount")) + " 条计费规则已生效" : "缺少启用规则或价格参数");
        row.put("jobStatus", jobStatus);
        row.put("jobText", switch (jobStatus) {
            case "READY" -> "可运行预检并生成本期账单";
            case "ISSUED" -> "账单已发布并形成应收";
            case "REVIEWING" -> "账单处于审核发布阶段";
            case "DRAFT" -> "草稿账单待审核";
            default -> "准入或方案未完成，暂不可出账";
        });
        row.put("readinessScore", (accountReady ? 20 : 0) + (contractReady ? 20 : 0) + (spaceReady ? 20 : 0)
                + (meterReady ? 20 : 0) + (ruleReady ? 20 : 0));
    }

    private List<Map<String, Object>> batchRows(Long orgId, String cycle) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        where.append(access.orgFilterSql("b.org_id", orgId, true, args));
        where.append(" AND BINARY b.bill_cycle = BINARY ?");
        args.add(cycle);
        where.append(access.scopeSql("b.org_id", args));
        return jdbc.queryForList("""
                SELECT b.*, o.org_name AS orgName
                FROM billing_batch b LEFT JOIN dev_org o ON o.id = b.org_id
                """ + where + " ORDER BY b.id DESC LIMIT 20", args.toArray());
    }

    private List<Map<String, Object>> recentEvents(Long orgId, String cycle, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE BINARY b.bill_cycle = BINARY ?");
        args.add(cycle);
        where.append(access.orgFilterSql("a.org_id", orgId, true, args));
        where.append(access.scopeSql("a.org_id", args));
        args.add(limit);
        return jdbc.queryForList("""
                SELECT e.id, e.event_type AS eventType, e.event_time AS eventTime, e.operator,
                       b.bill_no AS billNo, a.account_name AS accountName, e.after_bill_status AS afterBillStatus,
                       e.remark
                FROM billing_bill_event e
                JOIN billing_bill b ON b.id = e.bill_id
                JOIN billing_account a ON a.id = b.account_id
                """ + where + " ORDER BY e.event_time DESC, e.id DESC LIMIT ?", args.toArray());
    }

    private List<Map<String, Object>> monthlyArchiveItems(Long orgId, String billCycle, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE BINARY b.bill_cycle=BINARY ?");
        args.add(billCycle);
        where.append(access.orgFilterSql("a.org_id", orgId, true, args));
        where.append(access.scopeSql("a.org_id", args));
        args.add(limit);
        List<Map<String,Object>> rows = jdbc.queryForList("""
                SELECT b.id AS billId,b.bill_no AS billNo,b.bill_type AS billType,b.bill_status AS billStatus,
                       b.pay_status AS payStatus,b.bill_cycle AS periodCode,b.total_amount AS totalAmount,
                       b.paid_amount AS paidAmount,b.outstanding_amount AS outstandingAmount,b.create_time AS createTime,
                       a.org_id AS orgId,o.org_name AS orgName,a.account_name AS accountName,b.tenant_name_snapshot AS tenantName,
                       c.contract_no AS contractNo,c.contract_name AS contractName,
                       s.supplement_no AS supplementNo,s.status AS supplementStatus,
                       COALESCE(py.paymentCount,0) AS paymentCount,
                       GREATEST(COALESCE(py.paymentCount,0)-COALESCE(bm.matchedPaymentCount,0),0) AS unmatchedPaymentCount,
                       COALESCE(bm.matchedPaymentCount,0) AS matchedStatementCount,
                       COALESCE(v.receivableVoucherCount,0) AS receivableVoucherCount,
                       COALESCE(rv.receiptVoucherCount,0) AS receiptVoucherCount,
                       GREATEST(COALESCE(py.paymentCount,0)-COALESCE(rv.receiptPaymentCount,0),0) AS missingReceiptVoucherCount,
                       COALESCE(inv.pendingInvoiceCount,0) AS pendingInvoiceCount,
                       COALESCE(inv.invoiceCount,0) AS invoiceCount,
                       IF(ma.orgId IS NULL,0,1) AS monthArchived
                FROM billing_bill b JOIN billing_account a ON a.id=b.account_id
                LEFT JOIN dev_org o ON o.id=a.org_id LEFT JOIN leasing_contract c ON c.id=b.contract_id
                LEFT JOIN billing_supplement_settlement s ON s.generated_bill_id=b.id
                LEFT JOIN (
                  SELECT bill_id,COUNT(*) AS paymentCount FROM billing_payment
                  WHERE payment_status='SUCCESS' GROUP BY bill_id
                ) py ON py.bill_id=b.id
                LEFT JOIN (
                  SELECT p.bill_id,COUNT(DISTINCT m.payment_id) AS matchedPaymentCount
                  FROM billing_bank_match m JOIN billing_payment p ON p.id=m.payment_id
                  WHERE m.match_status='MATCHED' AND p.payment_status='SUCCESS' GROUP BY p.bill_id
                ) bm ON bm.bill_id=b.id
                LEFT JOIN (
                  SELECT bill_id,COUNT(*) AS receivableVoucherCount
                  FROM billing_voucher WHERE voucher_type='AR_RECEIVABLE'
                    AND voucher_status IN ('POSTED','EXPORTED') GROUP BY bill_id
                ) v ON v.bill_id=b.id
                LEFT JOIN (
                  SELECT p.bill_id,COUNT(*) AS receiptVoucherCount,COUNT(DISTINCT v.payment_id) AS receiptPaymentCount
                  FROM billing_voucher v JOIN billing_payment p ON p.id=v.payment_id
                  WHERE v.voucher_type='RECEIPT' AND v.voucher_status IN ('POSTED','EXPORTED')
                  GROUP BY p.bill_id
                ) rv ON rv.bill_id=b.id
                LEFT JOIN (
                  SELECT ib.bill_id,COUNT(*) AS invoiceCount,
                    SUM(CASE WHEN i.invoice_status IN ('REQUESTED','RED_APPLIED') THEN 1 ELSE 0 END) AS pendingInvoiceCount
                  FROM billing_invoice_bill ib JOIN billing_invoice i ON i.id=ib.invoice_id
                  GROUP BY ib.bill_id
                ) inv ON inv.bill_id=b.id
                LEFT JOIN (
                  SELECT DISTINCT p.org_id AS orgId,p.period_code AS periodCode
                  FROM billing_settlement_archive ar JOIN billing_period p ON p.id=ar.period_id
                  WHERE ar.archive_status='ARCHIVED'
                ) ma ON ma.orgId=a.org_id AND BINARY ma.periodCode=BINARY b.bill_cycle
                """ + where + " ORDER BY b.create_time DESC,b.id DESC LIMIT ?", args.toArray());
        for (Map<String,Object> row : rows) {
            String itemStatus;
            if (number(row.get("monthArchived")) > 0) itemStatus = "MONTH_ARCHIVED";
            else if ("VOID".equals(text(row.get("billStatus")))) itemStatus = "VOID";
            else if (!"ISSUED".equals(text(row.get("billStatus")))) itemStatus = "PROCESSING";
            else if (number(row.get("payStatus")) != 1 || money(row.get("outstandingAmount")).signum() > 0) itemStatus = "RECEIVING";
            else if (number(row.get("unmatchedPaymentCount")) > 0) itemStatus = "RECONCILING";
            else if (number(row.get("receivableVoucherCount")) == 0 || number(row.get("missingReceiptVoucherCount")) > 0) itemStatus = "VOUCHER_PENDING";
            else if (number(row.get("pendingInvoiceCount")) > 0) itemStatus = "INVOICE_PENDING";
            else itemStatus = "COMPLETED";
            row.put("id", row.get("billId"));
            row.put("archiveNo", "BILL-" + row.get("billNo"));
            row.put("itemStatus", itemStatus);
        }
        return rows;
    }

    private List<Map<String, Object>> closingRows(Long orgId, String billCycle, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        where.append(access.orgFilterSql("p.org_id", orgId, true, args));
        if (billCycle != null && !billCycle.isBlank()) {
            where.append(" AND BINARY p.period_code = BINARY ?");
            args.add(billCycle);
        }
        where.append(access.scopeSql("p.org_id", args));
        args.add(limit);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.id AS periodId, p.period_code AS periodCode, p.org_id AS orgId, o.org_name AS orgName,
                       p.start_date AS startDate, p.end_date AS endDate, p.status, p.closed_by AS closedBy, p.closed_time AS closedTime,
                       ar.id AS archiveId, ar.archive_no AS archiveNo, ar.archive_status AS archiveStatus, ar.archived_by AS archivedBy, ar.archived_time AS archivedTime,
                       (SELECT COUNT(*) FROM billing_bill b JOIN billing_account a ON a.id=b.account_id
                         WHERE a.org_id=p.org_id AND BINARY b.bill_cycle=BINARY p.period_code) AS billCount,
                       (SELECT COUNT(*) FROM billing_bill b JOIN billing_account a ON a.id=b.account_id
                         WHERE a.org_id=p.org_id AND BINARY b.bill_cycle=BINARY p.period_code AND b.bill_status='ISSUED') AS issuedBillCount,
                       (SELECT COALESCE(SUM(b.total_amount),0) FROM billing_bill b JOIN billing_account a ON a.id=b.account_id
                         WHERE a.org_id=p.org_id AND BINARY b.bill_cycle=BINARY p.period_code AND b.bill_status='ISSUED') AS issuedAmount,
                       (SELECT COALESCE(SUM(b.outstanding_amount),0) FROM billing_bill b JOIN billing_account a ON a.id=b.account_id
                         WHERE a.org_id=p.org_id AND BINARY b.bill_cycle=BINARY p.period_code AND b.bill_status='ISSUED') AS outstandingAmount,
                       (SELECT COALESCE(SUM(b.paid_amount),0) FROM billing_bill b JOIN billing_account a ON a.id=b.account_id
                         WHERE a.org_id=p.org_id AND BINARY b.bill_cycle=BINARY p.period_code AND b.bill_status<>'VOID') AS paidAmount,
                       (SELECT COUNT(*) FROM billing_payment py JOIN billing_bill b ON b.id=py.bill_id JOIN billing_account a ON a.id=b.account_id
                         WHERE a.org_id=p.org_id AND BINARY b.bill_cycle=BINARY p.period_code AND py.payment_status='SUCCESS') AS paymentCount,
                       (SELECT COUNT(*) FROM billing_payment py JOIN billing_bill b ON b.id=py.bill_id JOIN billing_account a ON a.id=b.account_id
                         WHERE a.org_id=p.org_id AND BINARY b.bill_cycle=BINARY p.period_code AND py.payment_status='SUCCESS'
                           AND NOT EXISTS(SELECT 1 FROM billing_bank_match m WHERE m.payment_id=py.id AND m.match_status='MATCHED')) AS unmatchedPaymentCount,
                       (SELECT COUNT(*) FROM billing_bill b JOIN billing_account a ON a.id=b.account_id
                         WHERE a.org_id=p.org_id AND BINARY b.bill_cycle=BINARY p.period_code AND b.bill_status='ISSUED'
                           AND NOT EXISTS(SELECT 1 FROM billing_voucher v WHERE v.bill_id=b.id AND v.voucher_type='AR_RECEIVABLE' AND v.voucher_status IN ('POSTED','EXPORTED'))) AS missingReceivableVoucherCount,
                       (SELECT COUNT(*) FROM billing_payment py JOIN billing_bill b ON b.id=py.bill_id JOIN billing_account a ON a.id=b.account_id
                         WHERE a.org_id=p.org_id AND BINARY b.bill_cycle=BINARY p.period_code AND py.payment_status='SUCCESS'
                           AND NOT EXISTS(SELECT 1 FROM billing_voucher v WHERE v.payment_id=py.id AND v.voucher_type='RECEIPT' AND v.voucher_status IN ('POSTED','EXPORTED'))) AS missingReceiptVoucherCount,
                       (SELECT COUNT(*) FROM billing_adjustment j JOIN billing_bill b ON b.id=j.bill_id JOIN billing_account a ON a.id=b.account_id
                         WHERE a.org_id=p.org_id AND BINARY b.bill_cycle=BINARY p.period_code AND j.status='PENDING') AS pendingAdjustmentCount,
                       (SELECT COUNT(DISTINCT i.id) FROM billing_invoice i JOIN billing_invoice_bill ib ON ib.invoice_id=i.id
                         JOIN billing_bill b ON b.id=ib.bill_id JOIN billing_account a ON a.id=b.account_id
                         WHERE a.org_id=p.org_id AND BINARY b.bill_cycle=BINARY p.period_code AND i.invoice_status IN ('REQUESTED','RED_APPLIED')) AS pendingInvoiceCount,
                       (SELECT COUNT(*) FROM billing_bank_statement s WHERE s.target_period_id=p.id) AS reconciliationCount,
                       (SELECT COUNT(*) FROM billing_bank_statement s WHERE s.target_period_id=p.id AND s.statement_status IN ('MATCHED','PARTIAL')) AS matchedCount,
                       (SELECT COUNT(*) FROM billing_bank_statement s WHERE s.target_period_id=p.id AND s.statement_status='DIFFERENCE') AS differenceCount
                FROM billing_period p LEFT JOIN dev_org o ON o.id = p.org_id
                LEFT JOIN billing_settlement_archive ar ON ar.period_id=p.id
                """ + where + " ORDER BY p.start_date DESC, p.id DESC LIMIT ?", args.toArray());
        for (Map<String, Object> row : rows) enrichClosing(row);
        return rows;
    }

    private void enrichClosing(Map<String, Object> row) {
        row.put("id", row.get("periodId"));
        String status = text(row.get("status"));
        long differences = number(row.get("differenceCount"));
        long billCount = number(row.get("billCount"));
        long issued = number(row.get("issuedBillCount"));
        boolean paidOff = money(row.get("outstandingAmount")).compareTo(BigDecimal.ZERO) <= 0;
        boolean reconciled = number(row.get("paymentCount")) > 0 && number(row.get("unmatchedPaymentCount")) == 0 && differences == 0;
        boolean vouchersReady = number(row.get("missingReceivableVoucherCount")) == 0 && number(row.get("missingReceiptVoucherCount")) == 0;
        boolean adjustmentsReady = number(row.get("pendingAdjustmentCount")) == 0;
        boolean invoicesReady = number(row.get("pendingInvoiceCount")) == 0;
        String closingStatus;
        if ("ARCHIVED".equals(text(row.get("archiveStatus")))) closingStatus = "ARCHIVED";
        else if ("CLOSED".equals(status)) closingStatus = "CLOSED";
        else if (differences > 0) closingStatus = "DIFFERENCE";
        else if (billCount > 0 && billCount == issued && paidOff && reconciled && vouchersReady && adjustmentsReady && invoicesReady) closingStatus = "CLOSEABLE";
        else if (billCount > 0) closingStatus = "INCOMPLETE";
        else closingStatus = "OPEN";
        row.put("closingStatus", closingStatus);
        row.put("closingText", switch (closingStatus) {
            case "ARCHIVED" -> "结算资料已封存归档";
            case "CLOSED" -> "账期已锁定，等待正式归档";
            case "DIFFERENCE" -> "存在对账差异，需处理后关账";
            case "CLOSEABLE" -> "账单、收款、勾兑与凭证均已完成，可关账";
            case "INCOMPLETE" -> "账期仍有收款、勾兑、凭证或调账事项待补齐";
            default -> "账期开放，等待出账或收款完成";
        });
    }

    private Map<String, Object> baseStage(String title, String description, List<Map<String, Object>> rows) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", title);
        result.put("description", description);
        result.put("rows", rows);
        result.put("summary", Map.of("total", rows.size()));
        return result;
    }

    private Map<String, Object> stage(String title, long done, long total, long risk, String path) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", title);
        row.put("done", done);
        row.put("total", total);
        row.put("risk", risk);
        row.put("path", path);
        row.put("percent", total == 0 ? 0 : Math.round(done * 100.0 / total));
        return row;
    }

    private Map<String, Object> todo(String stage, String title, String detail, String path) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", stage);
        row.put("title", title);
        row.put("detail", detail);
        row.put("path", path);
        return row;
    }

    private List<Map<String, Object>> queue(List<Map<String, Object>> rows, String field, Map<String, String> labels) {
        List<Map<String, Object>> result = new ArrayList<>();
        labels.forEach((key, label) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", key);
            item.put("label", label);
            item.put("count", rows.stream().filter(row -> key.equals(text(row.get(field)))).count());
            result.add(item);
        });
        return result;
    }

    private String cycle(String value) {
        return value == null || value.isBlank() ? YearMonth.now().toString() : value.trim();
    }

    private long number(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(value)); } catch (RuntimeException ignored) { return 0; }
    }

    private Long longOrNull(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try { return Long.valueOf(String.valueOf(value)); } catch (RuntimeException ignored) { return null; }
    }

    private BigDecimal money(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal decimal) return decimal;
        try { return new BigDecimal(String.valueOf(value)); } catch (RuntimeException ignored) { return BigDecimal.ZERO; }
    }

    private String text(Object value) {
        if (value == null) return "";
        return Objects.toString(value, "").trim();
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            String text = text(value);
            if (!text.isBlank()) return text;
        }
        return "未命名对象";
    }

    private String missingText(List<String> parts) {
        return parts.stream().filter(part -> part != null && !part.isBlank()).findFirst().orElse("存在准入阻断项");
    }
}
