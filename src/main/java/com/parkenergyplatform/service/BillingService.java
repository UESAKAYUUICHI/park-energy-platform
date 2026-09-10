package com.parkenergyplatform.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HexFormat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.common.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
public class BillingService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final BusinessDataAccessService accessService;
    private final TariffResolver tariffResolver;
    private final BillingFinanceService financeService;
    private final BillingPeriodGuardService periodGuard;

    public BillingService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, BusinessDataAccessService accessService,
                          TariffResolver tariffResolver, BillingFinanceService financeService,
                          BillingPeriodGuardService periodGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.accessService = accessService;
        this.tariffResolver = tariffResolver;
        this.financeService = financeService;
        this.periodGuard = periodGuard;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> generate(Map<String, Object> request) {
        long accountId = longValue(request.get("accountId"), "accountId");
        accessService.assertBillingAccountAccess(accountId);
        String billCycle = requiredText(request.get("billCycle"), "billCycle");
        String billType = Objects.toString(request.getOrDefault("billType", "REGULAR"), "REGULAR").trim().toUpperCase();
        boolean supplement = "SUPPLEMENT".equals(billType);
        if (!supplement && !"REGULAR".equals(billType)) throw new BusinessException("不支持的账单类型：" + billType);
        String billingKey = supplement
                ? requiredText(request.get("billingKey"), "billingKey")
                : "REGULAR";
        Long supplementSettlementId = supplement ? longOrNull(request.get("supplementSettlementId")) : null;
        Long sourceBillId = supplement ? longOrNull(request.get("sourceBillId")) : null;
        if (supplement && supplementSettlementId == null) throw new BusinessException("补充账单缺少补充结算单关联");
        // 已关账账期只能由经过审批入口创建的补充结算单生成独立账单；常规账单仍受账期锁定。
        if (!supplement) periodGuard.assertAccountCycleWritable(accountId, billCycle);
        LocalDate startDate = LocalDate.parse(requiredText(request.get("startDate"), "startDate"));
        LocalDate endDate = LocalDate.parse(requiredText(request.get("endDate"), "endDate"));
        if (endDate.isBefore(startDate)) {
            throw new BusinessException("endDate 不能早于 startDate");
        }
        if (exists("SELECT COUNT(*) FROM billing_bill WHERE account_id = ? AND bill_cycle = ? AND billing_key = ?", accountId, billCycle, billingKey)) {
            throw new BusinessException(409, supplement ? "该补充结算单已生成账单" : "该账号账期已生成常规账单");
        }
        List<Map<String, Object>> qualityBlockers = settlementQualityIssues(accountId, startDate, endDate);
        if (!qualityBlockers.isEmpty()) {
            throw new BusinessException("结算数据质量未达标，共 " + qualityBlockers.size()
                    + " 项阻断。请先补数、重放或重建统计后再生成账单。首项："
                    + qualityBlockerText(qualityBlockers.get(0)));
        }
        BillCalculation calculation = calculate(accountId, startDate, endDate);
        if (!calculation.touReady()) {
            throw new BusinessException("分时统计尚未就绪，不能生成账单");
        }
        if (calculation.details().isEmpty()) {
            throw new BusinessException("当前账期没有可计费明细，不能生成账单");
        }
        Long batchId = longOrNull(request.get("batchId"));

        String billNo = "BILL" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
                + String.format("%06d", Math.floorMod(accountId, 1_000_000L));
        long billId = insertBill(billNo, accountId, batchId, billType, supplementSettlementId, sourceBillId, billingKey, billCycle, startDate, endDate, calculation.total(),
                Objects.toString(request.getOrDefault("remark", ""), null));
        for (BillDetail detail : calculation.details()) {
            insertDetail(billId, detail);
        }
        financeService.recordEvent(billId, "CREATED", null, "DRAFT", null, 0, calculation.total(),
                Objects.toString(request.getOrDefault("operator", "admin"), "admin"), "账单试算完成，等待审核发布");
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
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("account", account(accountId));
        result.put("startDate", startDate);
        result.put("endDate", endDate);
        List<Map<String, Object>> qualityBlockers = settlementQualityIssues(accountId, startDate, endDate);
        result.put("qualityReady", qualityBlockers.isEmpty());
        result.put("blockers", qualityBlockers);
        if (!qualityBlockers.isEmpty()) {
            result.put("ready", false);
            result.put("totalAmount", BigDecimal.ZERO.setScale(2));
            result.put("details", List.of());
            result.put("touReady", false);
            return result;
        }
        try {
            BillCalculation calculation = calculate(accountId, startDate, endDate);
            result.put("totalAmount", calculation.total().setScale(2, RoundingMode.HALF_UP));
            result.put("details", calculation.details());
            result.put("touReady", calculation.touReady());
            result.put("ready", calculation.touReady() && !calculation.details().isEmpty());
        } catch (BusinessException exception) {
            result.put("ready", false);
            result.put("totalAmount", BigDecimal.ZERO.setScale(2));
            result.put("details", List.of());
            result.put("touReady", false);
            result.put("blockers", List.of(Map.of(
                    "code", "CALCULATION_NOT_READY",
                    "message", Objects.toString(exception.getMessage(), "计费计算尚未就绪"))));
        }
        return result;
    }

    @Transactional
    public Map<String, Object> pay(long billId, Map<String, Object> request) {
        assertBillWritableForWorkflow(billId);
        Map<String, Object> bill = billForUpdate(billId);
        int payStatus = number(bill.get("pay_status")).intValue();
        if (payStatus != 0 && payStatus != 4) {
            throw new BusinessException(3001, "只有未缴费账单允许缴费");
        }
        BigDecimal totalAmount = decimal(bill.get("total_amount")).setScale(2, RoundingMode.HALF_UP);
        if (!"ISSUED".equalsIgnoreCase(Objects.toString(bill.get("bill_status"), ""))) {
            throw new BusinessException("账单尚未审核发布，不能收款");
        }
        BigDecimal payAmount = decimal(request.get("payAmount")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal alreadyPaid = decimal(bill.get("paid_amount")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal outstanding = totalAmount.subtract(alreadyPaid).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        if (payAmount.compareTo(BigDecimal.ZERO) <= 0 || payAmount.compareTo(outstanding) > 0) {
            throw new BusinessException(3002, "本次收款金额必须大于零且不能超过剩余应收金额 " + outstanding);
        }
        String payWay = requiredText(request.get("payWay"), "payWay");
        String operator = Objects.toString(request.getOrDefault("operator", "admin"), "admin");
        String remark = Objects.toString(request.getOrDefault("remark", ""), null);
        LocalDateTime payTime = parsePayTime(request.get("payTime"));
        String paymentNo = "PAY" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
                + String.format("%06d", Math.floorMod(billId, 1_000_000L));
        jdbcTemplate.update("""
                INSERT INTO billing_payment (payment_no, bill_id, pay_amount, pay_way, transaction_no, payment_status, pay_time, operator, remark)
                VALUES (?, ?, ?, ?, ?, 'SUCCESS', ?, ?, ?)
                """, paymentNo, billId, payAmount, payWay, Objects.toString(request.get("transactionNo"), null),
                Timestamp.valueOf(payTime), operator, remark);
        Long paymentId = jdbcTemplate.queryForObject("SELECT id FROM billing_payment WHERE payment_no = ?", Long.class, paymentNo);
        BigDecimal newPaid = alreadyPaid.add(payAmount).setScale(2, RoundingMode.HALF_UP);
        BigDecimal newOutstanding = totalAmount.subtract(newPaid).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        int nextPayStatus = newOutstanding.compareTo(BigDecimal.ZERO) == 0 ? 1 : 4;
        jdbcTemplate.update("""
                UPDATE billing_bill
                SET pay_status = ?, paid_amount = ?, outstanding_amount = ?, pay_time = ?, pay_way = ?
                WHERE id = ?
                """, nextPayStatus, newPaid, newOutstanding, Timestamp.valueOf(payTime), payWay, billId);
        if (nextPayStatus == 1) jdbcTemplate.update("UPDATE billing_supplement_settlement SET status='PAID',updated_by=? WHERE generated_bill_id=? AND status='ISSUED'", operator, billId);
        financeService.payment(billId, number(bill.get("account_id")).longValue(), paymentId == null ? 0 : paymentId,
                payAmount, paymentNo, payTime, operator);
        financeService.recordEvent(billId, "PAYMENT_POSTED", bill.get("bill_status"), bill.get("bill_status"), payStatus,
                nextPayStatus, payAmount.negate(), operator, "登记收款：" + paymentNo);
        return bill(billId);
    }

    @Transactional
    public Map<String, Object> reversePayment(long paymentId, Map<String, Object> request) {
        periodGuard.assertPaymentWritable(paymentId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT p.*, b.total_amount, b.bill_status, b.pay_status, b.account_id
                FROM billing_payment p
                JOIN billing_bill b ON b.id = p.bill_id
                WHERE p.id = ?
                FOR UPDATE
                """, paymentId);
        if (rows.isEmpty()) {
            throw new BusinessException(404, "收款记录不存在: " + paymentId);
        }
        Map<String, Object> payment = rows.get(0);
        long billId = number(payment.get("bill_id")).longValue();
        accessService.assertBillAccess(billId);
        if (!"SUCCESS".equalsIgnoreCase(Objects.toString(payment.get("payment_status"), "SUCCESS"))) {
            throw new BusinessException("该收款记录已冲销，不能重复操作");
        }
        if (!"ISSUED".equalsIgnoreCase(Objects.toString(payment.get("bill_status"), ""))) {
            throw new BusinessException("仅已发布账单的收款允许冲销");
        }
        String operator = requiredText(request.get("operator"), "operator");
        String reason = requiredText(request.get("reason"), "reason");
        LocalDateTime reversedTime = parsePayTime(request.get("reversedTime"));
        jdbcTemplate.update("""
                UPDATE billing_payment
                SET payment_status = 'REVERSED', reversed_by = ?, reversed_time = ?, reverse_reason = ?
                WHERE id = ? AND payment_status = 'SUCCESS'
                """, operator, Timestamp.valueOf(reversedTime), reason, paymentId);
        reverseReceiptVoucher(paymentId, billId, operator, reason, reversedTime);
        BigDecimal totalAmount = decimal(payment.get("total_amount")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal paidAmount = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(pay_amount), 0) FROM billing_payment
                WHERE bill_id = ? AND payment_status = 'SUCCESS'
                """, BigDecimal.class, billId);
        BigDecimal paid = (paidAmount == null ? BigDecimal.ZERO : paidAmount).setScale(2, RoundingMode.HALF_UP);
        BigDecimal outstanding = totalAmount.subtract(paid).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        int payStatus = outstanding.compareTo(BigDecimal.ZERO) == 0 ? 1 : (paid.compareTo(BigDecimal.ZERO) > 0 ? 4 : 0);
        List<Map<String, Object>> latestPayments = jdbcTemplate.queryForList("""
                SELECT pay_time, pay_way FROM billing_payment
                WHERE bill_id = ? AND payment_status = 'SUCCESS'
                ORDER BY pay_time DESC, id DESC LIMIT 1
                """, billId);
        Timestamp payTime = latestPayments.isEmpty() ? null : (Timestamp) latestPayments.get(0).get("pay_time");
        String payWay = latestPayments.isEmpty() ? null : Objects.toString(latestPayments.get(0).get("pay_way"), null);
        jdbcTemplate.update("""
                UPDATE billing_bill
                SET pay_status = ?, paid_amount = ?, outstanding_amount = ?, pay_time = ?, pay_way = ?
                WHERE id = ?
                """, payStatus, paid, outstanding, payTime, payWay, billId);
        financeService.paymentReverse(billId, number(payment.get("account_id")).longValue(), paymentId,
                decimal(payment.get("pay_amount")), Objects.toString(payment.get("payment_no"), null), reversedTime,
                operator, reason);
        financeService.recordEvent(billId, "PAYMENT_REVERSED", payment.get("bill_status"), payment.get("bill_status"),
                payment.get("pay_status"), payStatus, decimal(payment.get("pay_amount")), operator, "收款冲销：" + reason);
        return bill(billId);
    }

    public Map<String, Object> bill(long billId) {
        accessService.assertBillAccess(billId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT b.*, a.account_name, a.org_id,
                       c.contract_no, c.contract_name,
                       o.org_name
                FROM billing_bill b
                JOIN billing_account a ON a.id = b.account_id
                LEFT JOIN leasing_contract c ON c.id = b.contract_id
                LEFT JOIN dev_org o ON o.id = a.org_id
                WHERE b.id = ?
                """, billId);
        if (rows.isEmpty()) {
            throw new BusinessException(404, "账单不存在: " + billId);
        }
        Map<String, Object> bill = new LinkedHashMap<>(rows.get(0));
        bill.put("details", jdbcTemplate.queryForList("""
                SELECT d.*, dv.device_name, dv.device_sn,
                       pd.point_name, pd.unit,
                       tp.plan_name,
                       (SELECT p.period_name
                          FROM billing_tariff_period p
                         WHERE p.tariff_plan_id = d.tariff_plan_id
                           AND p.period_code = d.tariff_period_code
                         ORDER BY p.sort, p.id LIMIT 1) AS period_name
                FROM billing_bill_detail d
                LEFT JOIN dev_device dv ON dv.id = d.device_id
                LEFT JOIN dev_point_definition pd
                       ON pd.device_type_id = d.device_type_id AND pd.point_code = d.point_code
                LEFT JOIN billing_tariff_plan tp ON tp.id = d.tariff_plan_id
                WHERE d.bill_id = ?
                ORDER BY d.id
                """, billId));
        bill.put("payments", jdbcTemplate.queryForList("SELECT * FROM billing_payment WHERE bill_id = ? ORDER BY id", billId));
        bill.put("adjustments", jdbcTemplate.queryForList("SELECT * FROM billing_adjustment WHERE bill_id=? ORDER BY id DESC", billId));
        bill.put("invoices", jdbcTemplate.queryForList("""
                SELECT i.* FROM billing_invoice i
                JOIN billing_invoice_bill ib ON ib.invoice_id=i.id
                WHERE ib.bill_id=? ORDER BY i.id DESC
                """, billId));
        Object snapshot = bill.get("contract_snapshot");
        if (snapshot != null && !String.valueOf(snapshot).isBlank()) {
            try {
                bill.put("contractSnapshot", objectMapper.readValue(String.valueOf(snapshot), Map.class));
            } catch (JsonProcessingException ignored) {
                bill.put("contractSnapshot", Map.of());
            }
        }
        return bill;
    }

    @Transactional
    public Map<String, Object> updateRemark(long billId, Map<String, Object> request) {
        assertBillWritableForWorkflow(billId);
        accessService.assertBillAccess(billId);
        String remark = request.get("remark") == null ? null : String.valueOf(request.get("remark")).trim();
        if (remark != null && remark.length() > 500) {
            throw new BusinessException("账单备注不能超过 500 个字符");
        }
        String operator = Objects.toString(request.getOrDefault("operator", "admin"), "admin");
        jdbcTemplate.update("UPDATE billing_bill SET remark = ?, update_by = ?, update_time = NOW() WHERE id = ?", remark, operator, billId);
        financeService.recordEvent(billId, "REMARK_UPDATED", null, null, null, null, BigDecimal.ZERO, operator,
                remark == null || remark.isBlank() ? "清空账单备注" : "更新账单备注");
        return bill(billId);
    }

    private Map<String, Object> billForUpdate(long billId) {
        accessService.assertBillAccess(billId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM billing_bill WHERE id = ? FOR UPDATE", billId);
        if (rows.isEmpty()) {
            throw new BusinessException(404, "账单不存在: " + billId);
        }
        return new LinkedHashMap<>(rows.get(0));
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
    public Map<String, Object> review(long billId, String operator) {
        assertBillWritableForWorkflow(billId);
        Map<String, Object> bill = billForUpdate(billId);
        if (!"DRAFT".equalsIgnoreCase(Objects.toString(bill.get("bill_status"), ""))) {
            throw new BusinessException("仅草稿账单可以提交审核");
        }
        jdbcTemplate.update("UPDATE billing_bill SET bill_status = 'REVIEWED', reviewed_by = ?, reviewed_time = NOW() WHERE id = ?", operator, billId);
        jdbcTemplate.update("UPDATE billing_supplement_settlement SET status='REVIEWED',updated_by=? WHERE generated_bill_id=? AND status='REVIEWING'", operator, billId);
        financeService.recordEvent(billId, "REVIEWED", "DRAFT", "REVIEWED", bill.get("pay_status"), bill.get("pay_status"),
                BigDecimal.ZERO, operator, "账单审核通过");
        return bill(billId);
    }

    @Transactional
    public Map<String, Object> issue(long billId, String operator) {
        assertBillWritableForWorkflow(billId);
        Map<String, Object> bill = billForUpdate(billId);
        if (!"REVIEWED".equalsIgnoreCase(Objects.toString(bill.get("bill_status"), ""))) {
            throw new BusinessException("仅已审核账单可以发布");
        }
        jdbcTemplate.update("UPDATE billing_bill SET bill_status = 'ISSUED', issued_by = ?, issued_time = NOW() WHERE id = ?", operator, billId);
        jdbcTemplate.update("""
                UPDATE billing_bill b JOIN billing_supplement_settlement s ON s.id=b.supplement_settlement_id
                SET b.due_date=DATE_ADD(CURDATE(), INTERVAL s.payment_term_days DAY),s.status='ISSUED',s.updated_by=?
                WHERE b.id=? AND b.bill_type='SUPPLEMENT'
                """, operator, billId);
        financeService.issue(billId, number(bill.get("account_id")).longValue(), decimal(bill.get("total_amount")),
                Objects.toString(bill.get("bill_no"), null), operator);
        financeService.recordEvent(billId, "ISSUED", "REVIEWED", "ISSUED", bill.get("pay_status"), bill.get("pay_status"),
                decimal(bill.get("total_amount")), operator, "账单已发布，确认应收");
        return bill(billId);
    }

    @Transactional
    public Map<String, Object> recalculate(long billId) {
        assertBillWritableForWorkflow(billId);
        Map<String, Object> bill = bill(billId);
        if (number(bill.get("pay_status")).intValue() != 0) {
            throw new BusinessException("只有未缴费账单允许重算");
        }
        long accountId = number(bill.get("account_id")).longValue();
        if (!"DRAFT".equalsIgnoreCase(Objects.toString(bill.get("bill_status"), "DRAFT"))) {
            throw new BusinessException("已审核或已发布账单不能直接重算，请使用调整单");
        }
        LocalDate startDate = ((java.sql.Date) bill.get("start_date")).toLocalDate();
        LocalDate endDate = ((java.sql.Date) bill.get("end_date")).toLocalDate();
        BillCalculation calculation = calculate(accountId, startDate, endDate);
        BigDecimal previousTotal = decimal(bill.get("total_amount")).setScale(2, RoundingMode.HALF_UP);
        jdbcTemplate.update("DELETE FROM billing_bill_detail WHERE bill_id = ?", billId);
        jdbcTemplate.update("""
                UPDATE billing_bill
                SET total_amount = ?, outstanding_amount = ?, remark = CONCAT(COALESCE(remark, ''), ' [RECALCULATED]')
                WHERE id = ?
                """, calculation.total().setScale(2, RoundingMode.HALF_UP), calculation.total().setScale(2, RoundingMode.HALF_UP), billId);
        for (BillDetail detail : calculation.details()) {
            insertDetail(billId, detail);
        }
        financeService.recordEvent(billId, "RECALCULATED", "DRAFT", "DRAFT", 0, 0,
                calculation.total().subtract(previousTotal), "admin", "草稿账单重新计算");
        return bill(billId);
    }

    @Transactional
    public Map<String, Object> voidBill(long billId, Map<String, Object> request) {
        assertBillWritableForWorkflow(billId);
        Map<String, Object> bill = bill(billId);
        if (number(bill.get("pay_status")).intValue() == 1 || number(bill.get("pay_status")).intValue() == 4) {
            throw new BusinessException("存在收款记录的账单不允许直接作废，请先完成退款或冲销");
        }
        String remark = Objects.toString(request.getOrDefault("remark", "作废"), "作废");
        jdbcTemplate.update("""
                UPDATE billing_bill
                SET pay_status = 3, bill_status = 'VOID', remark = CONCAT('[VOID] ', ?, ' ', COALESCE(remark, ''))
                WHERE id = ?
                """, remark, billId);
        financeService.recordEvent(billId, "VOIDED", bill.get("bill_status"), "VOID", bill.get("pay_status"), 3,
                BigDecimal.ZERO, Objects.toString(request.getOrDefault("operator", "admin"), "admin"), remark);
        return bill(billId);
    }

    private BillCalculation calculate(long accountId, LocalDate startDate, LocalDate endDate) {
        List<Map<String, Object>> rules = jdbcTemplate.queryForList(
                "SELECT * FROM billing_rule WHERE account_id = ? AND enabled = 1", accountId);
        if (rules.isEmpty()) {
            throw new BusinessException("该计费账号没有启用的计费规则");
        }
        List<BillDetail> details = new ArrayList<>();
        boolean touReady = true;
        BigDecimal total = BigDecimal.ZERO;
        int matchedDeviceCount = 0;
        List<String> emptyUsageHints = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            long ruleId = number(rule.get("id")).longValue();
            List<Map<String, Object>> configuredPoints = jdbcTemplate.queryForList("""
                    SELECT device_type_id, point_code FROM billing_rule_point_config
                    WHERE rule_id=? AND enabled=1 ORDER BY sort_no, id
                    """, ruleId);
            if (configuredPoints.isEmpty()) {
                configuredPoints = List.of(Map.of("device_type_id", rule.get("device_type_id"), "point_code", rule.get("metric_point_code")));
            }
            for (Map<String, Object> configuredPoint : configuredPoints) {
                long deviceTypeId = number(configuredPoint.get("device_type_id")).longValue();
                String pointCode = Objects.toString(configuredPoint.get("point_code"), "");
                for (Map<String, Object> device : devicesForRule(ruleId, deviceTypeId, startDate, endDate)) {
                long deviceId = number(device.get("id")).longValue();
                // A meter can be handed over in the middle of a billing period.  Its contract binding,
                // not the device archive alone, defines the chargeable date window.
                LocalDate meterStartDate = billingStartDate(device, startDate);
                LocalDate meterEndDate = billingEndDate(device, endDate);
                if (meterStartDate.isAfter(meterEndDate)) continue;
                matchedDeviceCount++;
                String mode = Objects.toString(rule.get("price_mode"), "UNIT_PRICE").trim().toUpperCase();
                assertSettlementQualityReady(device, meterStartDate, meterEndDate);
                if ("TIME_PERIOD".equals(mode)) {
                    List<BillDetail> touDetails = timePeriodDetails(rule, device, pointCode, meterStartDate, meterEndDate);
                    if (touDetails.isEmpty()) {
                        touReady = false;
                        throw new BusinessException("设备 " + deviceId + " 在账期内缺少可结算的分时用电统计，请先执行分时统计重建");
                    }
                    for (BillDetail detail : touDetails) total = total.add(detail.amount());
                    details.addAll(touDetails);
                    continue;
                }
                BigDecimal rawUsage = usage(deviceId, pointCode, meterStartDate, meterEndDate);
                BigDecimal usage = applyMeterFactor(rawUsage, device);
                // 固定金额是合同约定费用，不以累计量是否产生为前置条件；仍保留设备/测点明细以便审计。
                if ("FIXED".equals(mode)) {
                    PriceResult price = price(rule, usage);
                    total = total.add(price.amount());
                    details.add(new BillDetail(ruleId, deviceId, deviceTypeId, pointCode, usage, price.unitPrice(),
                            price.amount(), null, null, null, snapshot(rule, device, rawUsage, usage, price)));
                    continue;
                }
                if (usage.compareTo(BigDecimal.ZERO) <= 0) {
                    if (emptyUsageHints.size() < 3) emptyUsageHints.add(Objects.toString(device.get("device_name"), "设备" + deviceId) + " / " + pointCode);
                    continue;
                }
                PriceResult price = price(rule, usage);
                total = total.add(price.amount());
                details.add(new BillDetail(ruleId, deviceId, deviceTypeId, pointCode, usage, price.unitPrice(),
                        price.amount(), null, null, null, snapshot(rule, device, rawUsage, usage, price)));
                }
            }
        }
        if (details.isEmpty()) {
            if (matchedDeviceCount == 0) {
                throw new BusinessException("合同未绑定本账期内可计费设备：请检查合同设备绑定、设备类型、启用状态和允许计费设置");
            }
            String hint = emptyUsageHints.isEmpty() ? "请检查分时统计和日报统计" : "未取得日报用量：" + String.join("；", emptyUsageHints);
            throw new BusinessException("账期内没有可计费用量，" + hint);
        }
        return new BillCalculation(details, total, touReady);
    }

    private void assertSettlementQualityReady(Map<String, Object> device,
                                              LocalDate startDate, LocalDate endDate) {
        List<Map<String, Object>> issues = settlementQualityIssues(device, startDate, endDate);
        if (!issues.isEmpty()) {
            throw new BusinessException("设备结算数据质量未达标：" + qualityBlockerText(issues.get(0)));
        }
    }

    private List<Map<String, Object>> settlementQualityIssues(long accountId,
                                                               LocalDate startDate, LocalDate endDate) {
        List<Map<String, Object>> rules = jdbcTemplate.queryForList(
                "SELECT id, device_type_id FROM billing_rule WHERE account_id = ? AND enabled = 1", accountId);
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Map<String, Object> rule : rules) {
            long ruleId = number(rule.get("id")).longValue();
            long deviceTypeId = number(rule.get("device_type_id")).longValue();
            for (Map<String, Object> device : devicesForRule(ruleId, deviceTypeId, startDate, endDate)) {
                LocalDate deviceStart = billingStartDate(device, startDate);
                LocalDate deviceEnd = billingEndDate(device, endDate);
                if (deviceStart.isAfter(deviceEnd)) continue;
                for (Map<String, Object> issue : settlementQualityIssues(device, deviceStart, deviceEnd)) {
                    String key = issue.get("deviceId") + ":" + issue.get("statDate");
                    unique.putIfAbsent(key, issue);
                }
            }
        }
        return new ArrayList<>(unique.values());
    }

    private List<Map<String, Object>> settlementQualityIssues(Map<String, Object> device,
                                                               LocalDate startDate, LocalDate endDate) {
        LocalDate gateStart = localDate(device.get("quality_gate_start_date"));
        if (gateStart == null) return List.of();
        LocalDate checkedStart = startDate.isBefore(gateStart) ? gateStart : startDate;
        if (checkedStart.isAfter(endDate)) return List.of();
        int interval = boundedInt(device.get("collect_interval_seconds"), 300, 10, 86_400);
        BigDecimal threshold = decimalOr(device.get("quality_threshold_pct"), BigDecimal.valueOf(80));
        long deviceId = number(device.get("id")).longValue();
        List<Map<String, Object>> dailyRows = jdbcTemplate.queryForList("""
                SELECT stat_date, received_samples, longest_gap_seconds, quality_status
                FROM stats_collection_daily
                WHERE device_id = ? AND stat_date BETWEEN ? AND ?
                """, deviceId, Date.valueOf(checkedStart), Date.valueOf(endDate));
        Map<LocalDate, Map<String, Object>> byDate = new LinkedHashMap<>();
        for (Map<String, Object> row : dailyRows) {
            LocalDate statDate = localDate(row.get("stat_date"));
            if (statDate != null) byDate.put(statDate, row);
        }
        List<Map<String, Object>> issues = new ArrayList<>();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        for (LocalDate date = checkedStart; !date.isAfter(endDate); date = date.plusDays(1)) {
            Map<String, Object> row = byDate.get(date);
            int expected = expectedSamples(date, interval, today);
            int received = row == null ? 0 : boundedInt(row.get("received_samples"), 0, 0, Integer.MAX_VALUE);
            int longestGap = row == null ? Integer.MAX_VALUE
                    : boundedInt(row.get("longest_gap_seconds"), 0, 0, Integer.MAX_VALUE);
            BigDecimal rate = BigDecimal.valueOf(received).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(Math.max(expected, 1)), 2, RoundingMode.HALF_UP);
            boolean abnormal = row != null
                    && "ABNORMAL".equalsIgnoreCase(Objects.toString(row.get("quality_status"), ""));
            if (!settlementQualityAccepted(rate, threshold, longestGap, interval, abnormal)) {
                Map<String, Object> issue = new LinkedHashMap<>();
                issue.put("code", row == null ? "COLLECTION_MISSING" : "COLLECTION_QUALITY_LOW");
                issue.put("deviceId", deviceId);
                issue.put("deviceSn", Objects.toString(device.get("device_sn"), ""));
                issue.put("deviceName", Objects.toString(device.get("device_name"), ""));
                issue.put("statDate", date);
                issue.put("receivedSamples", received);
                issue.put("expectedSamples", expected);
                issue.put("completeRate", rate);
                issue.put("threshold", threshold);
                issue.put("longestGapSeconds", longestGap == Integer.MAX_VALUE ? null : longestGap);
                issue.put("qualityStatus", row == null ? "MISSING" : Objects.toString(row.get("quality_status"), ""));
                issue.put("message", row == null ? "该日没有有效采集质量记录"
                        : "完整率、最大断档或质量状态未满足结算要求");
                issues.add(issue);
            }
        }
        return issues;
    }

    private String qualityBlockerText(Map<String, Object> issue) {
        return "设备 " + issue.get("deviceId") + "，日期 " + issue.get("statDate")
                + "，完整率 " + issue.get("completeRate") + "%（要求 " + issue.get("threshold") + "%）";
    }

    static boolean settlementQualityAccepted(BigDecimal completeRate, BigDecimal threshold,
                                               int longestGapSeconds, int intervalSeconds,
                                               boolean abnormal) {
        return !abnormal
                && completeRate.compareTo(threshold) >= 0
                && longestGapSeconds <= intervalSeconds * 3;
    }

    /**
     * A bill must not turn a known collection outage into a financial fact.  The gate starts on the
     * device's configured rollout date so that records predating the quality module remain auditable
     * but are not retroactively rejected.
     */
    private void assertSettlementQuality(Map<String, Object> device, LocalDate startDate, LocalDate endDate) {
        LocalDate gateStart = localDate(device.get("quality_gate_start_date"));
        if (gateStart == null) return;
        LocalDate checkedStart = startDate.isBefore(gateStart) ? gateStart : startDate;
        if (checkedStart.isAfter(endDate)) return;
        int interval = boundedInt(device.get("collect_interval_seconds"), 300, 10, 86_400);
        BigDecimal threshold = decimalOr(device.get("quality_threshold_pct"), BigDecimal.valueOf(80));
        long deviceId = number(device.get("id")).longValue();
        List<Map<String, Object>> dailyRows = jdbcTemplate.queryForList("""
                SELECT stat_date, received_samples, longest_gap_seconds, quality_status
                FROM stats_collection_daily
                WHERE device_id = ? AND stat_date BETWEEN ? AND ?
                """, deviceId, Date.valueOf(checkedStart), Date.valueOf(endDate));
        Map<LocalDate, Map<String, Object>> byDate = new LinkedHashMap<>();
        for (Map<String, Object> row : dailyRows) {
            LocalDate statDate = localDate(row.get("stat_date"));
            if (statDate != null) byDate.put(statDate, row);
        }
        List<String> issues = new ArrayList<>();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        for (LocalDate date = checkedStart; !date.isAfter(endDate); date = date.plusDays(1)) {
            Map<String, Object> row = byDate.get(date);
            int expected = expectedSamples(date, interval, today);
            int received = row == null ? 0 : boundedInt(row.get("received_samples"), 0, 0, Integer.MAX_VALUE);
            int longestGap = row == null ? Integer.MAX_VALUE : boundedInt(row.get("longest_gap_seconds"), 0, 0, Integer.MAX_VALUE);
            BigDecimal rate = BigDecimal.valueOf(received).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(Math.max(expected, 1)), 2, RoundingMode.HALF_UP);
            boolean abnormal = row != null && "ABNORMAL".equalsIgnoreCase(Objects.toString(row.get("quality_status"), ""));
            if (rate.compareTo(threshold) < 0 || longestGap > interval * 3 || abnormal) {
                if (issues.size() < 5) {
                    issues.add(date + " 完整率 " + rate + "%（阈值 " + threshold + "%），最大断采 "
                            + (longestGap == Integer.MAX_VALUE ? "无有效采集" : longestGap + " 秒"));
                }
            }
        }
        if (!issues.isEmpty()) {
            throw new BusinessException("设备 " + deviceId + " 的结算数据质量未达标，请在采集质量中心补数或重放后再出账："
                    + String.join("；", issues));
        }
    }

    private int expectedSamples(LocalDate statDate, int interval, LocalDate today) {
        if (statDate.isBefore(today)) return (int) Math.ceil(86_400D / interval);
        if (statDate.isAfter(today)) return 1;
        long elapsed = Math.max(1L, java.time.Duration.between(statDate.atStartOfDay(ZoneId.of("Asia/Shanghai")),
                java.time.ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))).getSeconds());
        return Math.max(1, (int) Math.ceil(elapsed / (double) interval));
    }

    private int boundedInt(Object value, int fallback, int minimum, int maximum) {
        try {
            int number = new BigDecimal(String.valueOf(value)).intValueExact();
            return Math.min(Math.max(number, minimum), maximum);
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private BigDecimal decimalOr(Object value, BigDecimal fallback) {
        if (value == null) return fallback;
        try { return decimal(value); } catch (RuntimeException exception) { return fallback; }
    }

    private LocalDate localDate(Object value) {
        if (value instanceof Date date) return date.toLocalDate();
        if (value instanceof java.util.Date date) return new Date(date.getTime()).toLocalDate();
        if (value == null) return null;
        try { return LocalDate.parse(String.valueOf(value)); } catch (RuntimeException exception) { return null; }
    }

    /** 补充账单独立流转，不能因其“来源账期”已经关账而被错误拦截。 */
    private void assertBillWritableForWorkflow(long billId) {
        List<Map<String,Object>> rows = jdbcTemplate.queryForList("SELECT bill_type FROM billing_bill WHERE id=?", billId);
        if (rows.isEmpty()) throw new BusinessException(404, "账单不存在: " + billId);
        if (!"SUPPLEMENT".equalsIgnoreCase(Objects.toString(rows.get(0).get("bill_type"), "REGULAR"))) {
            periodGuard.assertBillWritable(billId);
        }
    }

    private long insertBill(String billNo, long accountId, Long batchId, String billType, Long supplementSettlementId, Long sourceBillId, String billingKey, String billCycle, LocalDate startDate, LocalDate endDate,
                            BigDecimal total, String remark) {
        Map<String, Object> accountSnapshot = single("""
                SELECT a.contract_id, t.tenant_name
                FROM billing_account a
                LEFT JOIN crm_tenant t ON t.id = a.tenant_id
                WHERE a.id = ?
                """, accountId);
        ContractSnapshot contractSnapshot = contractSnapshot(accountId);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO billing_bill (batch_id, bill_type, supplement_settlement_id, source_bill_id, bill_no, account_id, contract_id, tenant_name_snapshot,
                                              contract_snapshot,contract_snapshot_hash,contract_snapshot_version,contract_snapshot_time,
                                              bill_status, bill_cycle,billing_key,
                                              start_date, end_date, due_date, total_amount, paid_amount, outstanding_amount, pay_status, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, 0.00, ?, 0, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            if (batchId == null) ps.setNull(1, java.sql.Types.BIGINT); else ps.setLong(1, batchId);
            ps.setString(2, billType);
            if (supplementSettlementId == null) ps.setNull(3, java.sql.Types.BIGINT); else ps.setLong(3, supplementSettlementId);
            if (sourceBillId == null) ps.setNull(4, java.sql.Types.BIGINT); else ps.setLong(4, sourceBillId);
            ps.setString(5, billNo);
            ps.setLong(6, accountId);
            if (accountSnapshot.get("contract_id") == null) ps.setNull(7, java.sql.Types.BIGINT); else ps.setLong(7, number(accountSnapshot.get("contract_id")).longValue());
            ps.setString(8, Objects.toString(accountSnapshot.get("tenant_name"), null));
            ps.setString(9, contractSnapshot.json());
            ps.setString(10, contractSnapshot.hash());
            ps.setTimestamp(11, Timestamp.valueOf(LocalDateTime.now()));
            ps.setString(12, billCycle);
            ps.setString(13, billingKey);
            ps.setDate(14, Date.valueOf(startDate));
            ps.setDate(15, Date.valueOf(endDate));
            ps.setDate(16, Date.valueOf(endDate.plusDays(15)));
            BigDecimal amount = total.setScale(2, RoundingMode.HALF_UP);
            ps.setBigDecimal(17, amount);
            ps.setBigDecimal(18, amount);
            ps.setString(19, remark);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new BusinessException("账单创建失败");
        }
        return key.longValue();
    }

    private ContractSnapshot contractSnapshot(long accountId) {
        Map<String,Object> payload = new LinkedHashMap<>();
        Map<String,Object> account = single("""
                SELECT a.*,c.contract_no,c.contract_name,c.start_date AS contract_start_date,c.end_date AS contract_end_date,
                       c.status AS contract_status,c.signed_by,c.signed_time,t.tenant_name,t.contact_name AS tenant_contact_name,
                       t.contact_phone AS tenant_contact_phone,o.org_name
                FROM billing_account a
                LEFT JOIN leasing_contract c ON c.id=a.contract_id
                LEFT JOIN crm_tenant t ON t.id=a.tenant_id
                LEFT JOIN dev_org o ON o.id=a.org_id
                WHERE a.id=?
                """, accountId);
        payload.put("account", account);
        Object contractId = account.get("contract_id");
        payload.put("spaces", contractId == null ? List.of() : jdbcTemplate.queryForList("""
                SELECT cs.space_id,cs.rent_start_date,cs.rent_end_date,s.space_code,s.space_name,s.space_type
                FROM leasing_contract_space cs JOIN park_space s ON s.id=cs.space_id
                WHERE cs.contract_id=? ORDER BY cs.id
                """, contractId));
        payload.put("meters", contractId == null ? List.of() : jdbcTemplate.queryForList("""
                SELECT cm.device_id,cm.start_date,cm.end_date,cm.meter_factor,cm.status,
                       d.device_name,d.device_sn,d.device_type_id,d.energy_carrier,d.meter_factor AS device_meter_factor
                FROM leasing_contract_meter cm JOIN dev_device d ON d.id=cm.device_id
                WHERE cm.contract_id=? ORDER BY cm.id
                """, contractId));
        List<Map<String,Object>> rules = jdbcTemplate.queryForList("SELECT * FROM billing_rule WHERE account_id=? ORDER BY id", accountId);
        for (Map<String,Object> rule : rules) {
            Object ruleId = rule.get("id");
            rule.put("points", jdbcTemplate.queryForList("SELECT device_type_id,point_code,point_name,sort_no,enabled FROM billing_rule_point_config WHERE rule_id=? ORDER BY sort_no,id", ruleId));
            rule.put("scopes", jdbcTemplate.queryForList("SELECT scope_type,scope_id FROM billing_rule_scope WHERE rule_id=? ORDER BY id", ruleId));
            rule.put("priceItems", jdbcTemplate.queryForList("SELECT * FROM billing_price_item WHERE rule_id=? ORDER BY sort,id", ruleId));
            Object tariffPlanId = rule.get("tariff_plan_id");
            if (tariffPlanId != null) {
                Map<String,Object> tariff = single("SELECT * FROM billing_tariff_plan WHERE id=?", tariffPlanId);
                tariff.put("periods", jdbcTemplate.queryForList("SELECT * FROM billing_tariff_period WHERE tariff_plan_id=? ORDER BY start_time,id", tariffPlanId));
                rule.put("tariffPlan", tariff);
            }
        }
        payload.put("rules", rules);
        try {
            String json = objectMapper.writeValueAsString(payload);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
            return new ContractSnapshot(json, hash);
        } catch (Exception exception) {
            throw new BusinessException("出账合同快照生成失败：" + exception.getMessage());
        }
    }

    private void reverseReceiptVoucher(long paymentId,long billId,String operator,String reason,LocalDateTime reversedTime) {
        List<Map<String,Object>> rows = jdbcTemplate.queryForList("""
                SELECT * FROM billing_voucher
                WHERE payment_id=? AND voucher_type='RECEIPT' AND voucher_status<>'VOID'
                ORDER BY id DESC LIMIT 1 FOR UPDATE
                """, paymentId);
        if (rows.isEmpty()) return;
        Map<String,Object> original = rows.get(0);
        String status = Objects.toString(original.get("voucher_status"), "DRAFT");
        long originalId = number(original.get("id")).longValue();
        if ("REVERSED".equalsIgnoreCase(status)) return;
        if ("DRAFT".equalsIgnoreCase(status)) {
            jdbcTemplate.update("UPDATE billing_voucher SET voucher_status='VOID',reversal_reason=?,reversed_by=?,reversed_time=?,updated_by=? WHERE id=?", reason, operator, Timestamp.valueOf(reversedTime), operator, originalId);
            return;
        }
        if (!List.of("POSTED","EXPORTED").contains(status.toUpperCase())) throw new BusinessException("收款凭证状态不允许冲销：" + status);
        List<Map<String,Object>> existing = jdbcTemplate.queryForList("SELECT id FROM billing_voucher WHERE reversal_of_voucher_id=?", originalId);
        if (!existing.isEmpty()) return;
        String voucherNo = "VCHX" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")) + String.format("%06d", Math.floorMod(paymentId,1_000_000L));
        jdbcTemplate.update("""
                INSERT INTO billing_voucher
                  (voucher_no,org_id,bill_id,payment_id,reversal_of_voucher_id,voucher_type,voucher_status,voucher_date,
                   summary,total_debit,total_credit,posted_time,posted_by,reversal_reason,reversed_by,reversed_time,created_by,updated_by)
                VALUES (?,?,?,?,?,'RECEIPT_REVERSAL','POSTED',?,?,?,?,?,?,?,?,?,?,?)
                """, voucherNo, original.get("org_id"), billId, paymentId, originalId, Date.valueOf(reversedTime.toLocalDate()),
                "冲销收款凭证 " + original.get("voucher_no"), original.get("total_debit"), original.get("total_credit"),
                Timestamp.valueOf(reversedTime), operator, reason, operator, Timestamp.valueOf(reversedTime), operator, operator);
        Long reversalId = jdbcTemplate.queryForObject("SELECT id FROM billing_voucher WHERE voucher_no=?", Long.class, voucherNo);
        if (reversalId == null) throw new BusinessException("红字冲销凭证生成失败");
        List<Map<String,Object>> lines = jdbcTemplate.queryForList("SELECT * FROM billing_voucher_line WHERE voucher_id=? ORDER BY line_no", originalId);
        for (Map<String,Object> line : lines) {
            jdbcTemplate.update("""
                    INSERT INTO billing_voucher_line
                      (voucher_id,line_no,account_code,account_name,summary,debit_amount,credit_amount,bill_detail_id,remark)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, reversalId,line.get("line_no"),line.get("account_code"),line.get("account_name"),
                    "红字冲销：" + Objects.toString(line.get("summary"),""),line.get("credit_amount"),line.get("debit_amount"),line.get("bill_detail_id"),reason);
        }
        jdbcTemplate.update("UPDATE billing_voucher SET voucher_status='REVERSED',reversal_reason=?,reversed_by=?,reversed_time=?,updated_by=? WHERE id=?", reason,operator,Timestamp.valueOf(reversedTime),operator,originalId);
        jdbcTemplate.update("INSERT INTO billing_archive_record (org_id,bill_id,voucher_id,archive_type,archive_no,archived_by) VALUES (?,?,?,'VOUCHER',?,?)", original.get("org_id"),billId,reversalId,"ARCH-"+voucherNo,operator);
    }

    private record ContractSnapshot(String json,String hash) {}

    private void insertDetail(long billId, BillDetail detail) {
        jdbcTemplate.update("""
                INSERT INTO billing_bill_detail
                  (bill_id, rule_id, device_id, device_type_id, point_code, usage_value, unit_price, amount,
                   tariff_plan_id, tariff_plan_version, tariff_period_code, calculation_snapshot)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, billId, detail.ruleId, detail.deviceId, detail.deviceTypeId, detail.pointCode,
                detail.usage, detail.unitPrice, detail.amount, detail.tariffPlanId, detail.tariffPlanVersion,
                detail.tariffPeriodCode, detail.snapshot);
    }

    private List<Map<String, Object>> devicesForRule(long ruleId, long deviceTypeId, LocalDate startDate, LocalDate endDate) {
        Long contractId = jdbcTemplate.queryForObject("""
                SELECT a.contract_id FROM billing_rule r
                JOIN billing_account a ON a.id = r.account_id
                WHERE r.id = ?
                """, Long.class, ruleId);
        if (contractId != null) {
            return jdbcTemplate.queryForList("""
                    SELECT d.*, cm.start_date AS billing_start_date, cm.end_date AS billing_end_date,
                           cm.meter_factor AS contract_meter_factor,
                           COALESCE(NULLIF(cm.meter_factor, 0), d.meter_factor, 1.000000) AS effective_meter_factor
                    FROM leasing_contract_meter cm
                    JOIN dev_device d ON d.id = cm.device_id
                    WHERE cm.contract_id = ? AND cm.status = 'ACTIVE'
                      AND cm.start_date <= ? AND (cm.end_date IS NULL OR cm.end_date >= ?)
                      AND d.device_type_id = ? AND d.status = 1 AND d.settlement_enabled = 1
                    """, contractId, Date.valueOf(endDate), Date.valueOf(startDate), deviceTypeId).stream()
                    .filter(device -> accessService.hasDeviceAccess(number(device.get("id")).longValue())).toList();
        }
        List<Map<String, Object>> scopes = jdbcTemplate.queryForList("SELECT * FROM billing_rule_scope WHERE rule_id = ?", ruleId);
        List<Map<String, Object>> devices = new ArrayList<>();
        for (Map<String, Object> scope : scopes) {
            String scopeType = Objects.toString(scope.get("scope_type"), "");
            long scopeId = number(scope.get("scope_id")).longValue();
            if ("ORG".equalsIgnoreCase(scopeType)) {
                List<Long> orgIds = orgSubtreeIds(scopeId);
                if (!orgIds.isEmpty()) {
                    devices.addAll(jdbcTemplate.queryForList(
                            "SELECT d.*, COALESCE(NULLIF(d.meter_factor, 0), 1.000000) AS effective_meter_factor FROM dev_device d WHERE d.org_id IN (" + placeholders(orgIds.size()) + ") AND d.device_type_id = ? AND d.status = 1 AND d.settlement_enabled = 1",
                            deviceQueryArgs(orgIds, deviceTypeId)));
                }
            } else if ("DEVICE".equalsIgnoreCase(scopeType)) {
                devices.addAll(jdbcTemplate.queryForList(
                        "SELECT d.*, COALESCE(NULLIF(d.meter_factor, 0), 1.000000) AS effective_meter_factor FROM dev_device d WHERE d.id = ? AND d.device_type_id = ? AND d.status = 1 AND d.settlement_enabled = 1", scopeId, deviceTypeId));
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
            case "TIME_PERIOD" -> throw new BusinessException("分时计费必须使用 stats_tou_daily 的真实分段用量");
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

    private BigDecimal usage(long deviceId, String pointCode, LocalDate startDate, LocalDate endDate) {
        BigDecimal value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(usage_value), 0)
                FROM stats_daily_point
                WHERE device_id = ? AND point_code = ? AND stat_date BETWEEN ? AND ?
                """, BigDecimal.class, deviceId, pointCode, Date.valueOf(startDate), Date.valueOf(endDate));
        return value == null ? BigDecimal.ZERO : value;
    }

    private LocalDate billingStartDate(Map<String, Object> device, LocalDate billStartDate) {
        Object value = device.get("billing_start_date");
        if (value == null) return billStartDate;
        LocalDate bindingStart = ((Date) value).toLocalDate();
        return bindingStart.isAfter(billStartDate) ? bindingStart : billStartDate;
    }

    private LocalDate billingEndDate(Map<String, Object> device, LocalDate billEndDate) {
        Object value = device.get("billing_end_date");
        if (value == null) return billEndDate;
        LocalDate bindingEnd = ((Date) value).toLocalDate();
        return bindingEnd.isBefore(billEndDate) ? bindingEnd : billEndDate;
    }

    /**
     * A TOU bill is calculated from already tagged meter increments.  It intentionally never derives a
     * daily average price: each bucket retains the effective plan/version/period used when the reading crossed it.
     */
    private List<BillDetail> timePeriodDetails(Map<String, Object> rule, Map<String, Object> device, String pointCode,
                                                LocalDate startDate, LocalDate endDate) {
        long deviceId = number(device.get("id")).longValue();
        long deviceTypeId = number(device.get("device_type_id")).longValue();
        long deviceOrgId = number(device.get("org_id")).longValue();
        long ruleId = number(rule.get("id")).longValue();
        BigDecimal qualityThreshold = decimalOr(device.get("quality_threshold_pct"), BigDecimal.valueOf(80));
        Long explicitPlanId = longOrNull(rule.get("tariff_plan_id"));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT s.*, p.plan_code, p.plan_name
                FROM stats_tou_daily s
                JOIN billing_tariff_plan p ON p.id = s.tariff_plan_id
                WHERE s.device_id = ? AND s.point_code = ? AND s.stat_date BETWEEN ? AND ?
                ORDER BY s.stat_date, s.tariff_plan_id, s.tariff_period_code
                """, deviceId, pointCode, Date.valueOf(startDate), Date.valueOf(endDate));
        Map<TouBucket, BigDecimal> usages = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            LocalDate statDate = ((Date) row.get("stat_date")).toLocalDate();
            TariffResolver.TariffPlan expected = tariffResolver.resolve(explicitPlanId, deviceOrgId, statDate);
            long actualPlanId = number(row.get("tariff_plan_id")).longValue();
            if (expected.id() != actualPlanId) continue;
            BigDecimal completeRate = decimal(row.get("data_complete_rate"));
            String quality = Objects.toString(row.get("quality_status"), "UNKNOWN");
            if (completeRate.compareTo(qualityThreshold) < 0 || !"NORMAL".equalsIgnoreCase(quality)) {
                throw new BusinessException("设备 " + deviceId + " 的分时统计存在数据缺失或质量异常（" + statDate + "）");
            }
            String periodCode = Objects.toString(row.get("tariff_period_code"), "");
            BigDecimal unitPrice = tariffUnitPrice(actualPlanId, periodCode);
            TouBucket bucket = new TouBucket(actualPlanId, number(row.get("tariff_plan_version")).intValue(), periodCode,
                    unitPrice, Objects.toString(row.get("plan_code"), ""), Objects.toString(row.get("plan_name"), ""));
            usages.merge(bucket, decimal(row.get("usage_value")), BigDecimal::add);
        }
        List<BillDetail> details = new ArrayList<>();
        for (Map.Entry<TouBucket, BigDecimal> entry : usages.entrySet()) {
            BigDecimal rawUsage = entry.getValue();
            BigDecimal usage = applyMeterFactor(rawUsage, device);
            if (usage.compareTo(BigDecimal.ZERO) <= 0) continue;
            TouBucket bucket = entry.getKey();
            BigDecimal amount = usage.multiply(bucket.unitPrice()).setScale(2, RoundingMode.HALF_UP);
            details.add(new BillDetail(ruleId, deviceId, deviceTypeId, pointCode, usage, bucket.unitPrice(), amount,
                    bucket.planId(), bucket.planVersion(), bucket.periodCode(),
                    touSnapshot(rule, device, rawUsage, usage, bucket, amount)));
        }
        return details;
    }

    private BigDecimal tariffUnitPrice(long planId, String periodCode) {
        List<BigDecimal> prices = jdbcTemplate.queryForList("""
                SELECT DISTINCT unit_price FROM billing_tariff_period
                WHERE tariff_plan_id = ? AND period_code = ?
                """, BigDecimal.class, planId, periodCode);
        if (prices.size() != 1) {
            throw new BusinessException("电价方案 " + planId + " 的时段 " + periodCode + " 未定义唯一电价");
        }
        return prices.get(0);
    }

    private BigDecimal applyMeterFactor(BigDecimal rawUsage, Map<String, Object> device) {
        if (rawUsage == null) return BigDecimal.ZERO;
        BigDecimal factor = decimalOr(device.get("effective_meter_factor"), decimalOr(device.get("meter_factor"), BigDecimal.ONE));
        if (factor.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("设备 " + device.get("id") + " 的结算倍率必须大于 0");
        }
        return rawUsage.multiply(factor).setScale(6, RoundingMode.HALF_UP);
    }

    private String snapshot(Map<String, Object> rule, Map<String, Object> device, BigDecimal rawUsage, BigDecimal usage,
                            PriceResult price) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "rule", rule,
                    "device", device,
                    "rawUsageValue", rawUsage,
                    "meterFactor", decimalOr(device.get("effective_meter_factor"), decimalOr(device.get("meter_factor"), BigDecimal.ONE)),
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

    private String touSnapshot(Map<String, Object> rule, Map<String, Object> device, BigDecimal rawUsage, BigDecimal usage,
                               TouBucket bucket, BigDecimal amount) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("rule", rule);
            snapshot.put("device", device);
            snapshot.put("rawUsageValue", rawUsage);
            snapshot.put("meterFactor", decimalOr(device.get("effective_meter_factor"), decimalOr(device.get("meter_factor"), BigDecimal.ONE)));
            snapshot.put("usageValue", usage);
            snapshot.put("unitPrice", bucket.unitPrice());
            snapshot.put("amount", amount);
            snapshot.put("priceMode", "TIME_PERIOD");
            snapshot.put("pricingMethod", "TOU_METER_INCREMENT");
            snapshot.put("tariffPlanId", bucket.planId());
            snapshot.put("tariffPlanCode", bucket.planCode());
            snapshot.put("tariffPlanName", bucket.planName());
            snapshot.put("tariffPlanVersion", bucket.planVersion());
            snapshot.put("tariffPeriodCode", bucket.periodCode());
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("分时账单快照生成失败");
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
            try {
                return LocalDateTime.parse(text);
            } catch (Exception ignoredLocalDateTime) {
                // 兼容银行导入、人工录入中常见的“yyyy-MM-dd HH:mm:ss”格式。
                return LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
        }
    }

    private record BillDetail(long ruleId, long deviceId, long deviceTypeId, String pointCode, BigDecimal usage,
                              BigDecimal unitPrice, BigDecimal amount, Long tariffPlanId, Integer tariffPlanVersion,
                              String tariffPeriodCode, String snapshot) {
    }

    private record BillCalculation(List<BillDetail> details, BigDecimal total, boolean touReady) {
    }

    private record PriceResult(BigDecimal unitPrice, BigDecimal amount, String method, List<Map<String, Object>> items) {
    }

    private record TouBucket(long planId, int planVersion, String periodCode, BigDecimal unitPrice,
                             String planCode, String planName) {
    }
}
