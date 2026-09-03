package com.parkenergyplatform.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.common.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 财务事实层：将账单状态变化与应收增减沉淀为不可覆盖的流水，供对象画像、对账和审计复用。
 */
@Service
public class BillingFinanceService {
    private final JdbcTemplate jdbcTemplate;
    private final BusinessDataAccessService accessService;

    public BillingFinanceService(JdbcTemplate jdbcTemplate, BusinessDataAccessService accessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessService = accessService;
    }

    public void recordEvent(long billId, String type, Object beforeBillStatus, Object afterBillStatus,
                            Object beforePayStatus, Object afterPayStatus, BigDecimal amountDelta,
                            String operator, String remark) {
        jdbcTemplate.update("""
                INSERT INTO billing_bill_event
                  (bill_id, event_type, before_bill_status, after_bill_status, before_pay_status, after_pay_status,
                   amount_delta, operator, event_time, remark, create_by, update_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, billId, type, text(beforeBillStatus), text(afterBillStatus), integer(beforePayStatus), integer(afterPayStatus),
                money(amountDelta), operatorOrAdmin(operator), Timestamp.valueOf(LocalDateTime.now()), text(remark),
                operatorOrAdmin(operator), operatorOrAdmin(operator));
    }

    public void issue(long billId, long accountId, BigDecimal amount, String billNo, String operator) {
        post(accountId, billId, "BILL_ISSUED", "DEBIT", amount, "BILL", billId, billNo, "账单发布，确认应收", operator);
    }

    public void payment(long billId, long accountId, long paymentId, BigDecimal amount, String paymentNo,
                        LocalDateTime effectiveTime, String operator) {
        post(accountId, billId, "PAYMENT", "CREDIT", amount, "PAYMENT", paymentId, paymentNo,
                "登记收款，冲减应收", operator, effectiveTime);
    }

    public void paymentReverse(long billId, long accountId, long paymentId, BigDecimal amount, String paymentNo,
                               LocalDateTime effectiveTime, String operator, String reason) {
        post(accountId, billId, "PAYMENT_REVERSE", "DEBIT", amount, "PAYMENT_REVERSE", paymentId, paymentNo,
                "收款冲销，恢复应收：" + Objects.toString(reason, ""), operator, effectiveTime);
    }

    public void adjustment(long billId, long accountId, long adjustmentId, String adjustmentType,
                           BigDecimal amount, String adjustmentNo, String operator) {
        boolean surcharge = "SURCHARGE".equalsIgnoreCase(adjustmentType);
        post(accountId, billId, surcharge ? "ADJUSTMENT_SURCHARGE" : "ADJUSTMENT_DISCOUNT",
                surcharge ? "DEBIT" : "CREDIT", amount, "ADJUSTMENT", adjustmentId, adjustmentNo,
                surcharge ? "补收审批通过，增加应收" : "减免/坏账审批通过，冲减应收", operator);
    }

    public Map<String, Object> profile(long accountId) {
        accessService.assertBillingAccountAccess(accountId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("account", requiredAccount(accountId));
        result.put("receivable", receivableSummary(accountId));
        result.put("aging", aging(accountId));
        result.put("openBills", jdbcTemplate.queryForList("""
                SELECT b.id, b.bill_no, b.bill_cycle, b.due_date, b.total_amount, b.paid_amount, b.outstanding_amount,
                       b.pay_status, DATEDIFF(CURDATE(), b.due_date) AS overdue_days
                FROM billing_bill b
                WHERE b.account_id = ? AND b.bill_status = 'ISSUED' AND b.outstanding_amount > 0
                ORDER BY b.due_date ASC, b.id DESC LIMIT 12
                """, accountId));
        result.put("ledger", ledgerRows(accountId, 12, 0));
        result.put("events", jdbcTemplate.queryForList("""
                SELECT e.*, b.bill_no
                FROM billing_bill_event e JOIN billing_bill b ON b.id = e.bill_id
                WHERE b.account_id = ?
                ORDER BY e.event_time DESC, e.id DESC LIMIT 12
                """, accountId));
        return result;
    }

    public PageResult<Map<String, Object>> ledger(long accountId, Map<String, String> params) {
        accessService.assertBillingAccountAccess(accountId);
        int pageNum = positive(params.get("pageNum"), 1);
        int pageSize = Math.min(positive(params.get("pageSize"), 20), 100);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM billing_receivable_ledger WHERE account_id = ?", Long.class, accountId);
        return PageResult.of(ledgerRows(accountId, pageSize, (pageNum - 1) * pageSize), total == null ? 0 : total, pageNum, pageSize);
    }

    private List<Map<String, Object>> ledgerRows(long accountId, int limit, int offset) {
        return jdbcTemplate.queryForList("""
                SELECT l.*, b.bill_no
                FROM billing_receivable_ledger l
                LEFT JOIN billing_bill b ON b.id = l.bill_id
                WHERE l.account_id = ?
                ORDER BY l.effective_time DESC, l.id DESC LIMIT ? OFFSET ?
                """, accountId, limit, offset);
    }

    private Map<String, Object> receivableSummary(long accountId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT
                  COALESCE(SUM(CASE WHEN entry_type = 'BILL_ISSUED' THEN amount ELSE 0 END), 0) AS issued_amount,
                  COALESCE(SUM(CASE WHEN entry_type = 'ADJUSTMENT_SURCHARGE' THEN amount ELSE 0 END), 0) AS surcharge_amount,
                  COALESCE(SUM(CASE WHEN entry_type = 'ADJUSTMENT_DISCOUNT' THEN amount ELSE 0 END), 0) AS discount_amount,
                  COALESCE(SUM(CASE WHEN entry_type = 'PAYMENT' THEN amount ELSE 0 END), 0) AS received_amount,
                  COALESCE(SUM(CASE WHEN entry_type = 'PAYMENT_REVERSE' THEN amount ELSE 0 END), 0) AS reversed_amount,
                  COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END), 0) AS ledger_balance
                FROM billing_receivable_ledger WHERE account_id = ?
                """, accountId);
        Map<String, Object> result = rows.isEmpty() ? new LinkedHashMap<>() : new LinkedHashMap<>(rows.get(0));
        Map<String, Object> live = jdbcTemplate.queryForMap("""
                SELECT COALESCE(SUM(outstanding_amount), 0) AS open_amount,
                       COALESCE(SUM(CASE WHEN due_date < CURDATE() THEN outstanding_amount ELSE 0 END), 0) AS overdue_amount,
                       COUNT(CASE WHEN outstanding_amount > 0 THEN 1 END) AS open_bill_count
                FROM billing_bill WHERE account_id = ? AND bill_status = 'ISSUED'
                """, accountId);
        result.putAll(live);
        return result;
    }

    private List<Map<String, Object>> aging(long accountId) {
        return jdbcTemplate.queryForList("""
                SELECT CASE
                         WHEN due_date >= CURDATE() THEN 'NOT_DUE'
                         WHEN DATEDIFF(CURDATE(), due_date) <= 30 THEN 'OVERDUE_1_30'
                         WHEN DATEDIFF(CURDATE(), due_date) <= 60 THEN 'OVERDUE_31_60'
                         ELSE 'OVERDUE_61_PLUS'
                       END AS bucket,
                       COUNT(*) AS bill_count, COALESCE(SUM(outstanding_amount), 0) AS amount
                FROM billing_bill
                WHERE account_id = ? AND bill_status = 'ISSUED' AND outstanding_amount > 0
                GROUP BY CASE
                         WHEN due_date >= CURDATE() THEN 'NOT_DUE'
                         WHEN DATEDIFF(CURDATE(), due_date) <= 30 THEN 'OVERDUE_1_30'
                         WHEN DATEDIFF(CURDATE(), due_date) <= 60 THEN 'OVERDUE_31_60'
                         ELSE 'OVERDUE_61_PLUS'
                         END
                ORDER BY FIELD(bucket, 'NOT_DUE', 'OVERDUE_1_30', 'OVERDUE_31_60', 'OVERDUE_61_PLUS')
                """, accountId);
    }

    private Map<String, Object> requiredAccount(long accountId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT a.*, o.org_name, t.tenant_name, c.contract_no, c.contract_name, c.status AS contract_status
                FROM billing_account a
                LEFT JOIN dev_org o ON o.id = a.org_id
                LEFT JOIN crm_tenant t ON t.id = a.tenant_id
                LEFT JOIN leasing_contract c ON c.id = a.contract_id
                WHERE a.id = ?
                """, accountId);
        if (rows.isEmpty()) throw new BusinessException(404, "计费账户不存在: " + accountId);
        return rows.get(0);
    }

    private void post(long accountId, long billId, String entryType, String direction, BigDecimal amount,
                      String sourceType, long sourceId, String referenceNo, String remark, String operator) {
        post(accountId, billId, entryType, direction, amount, sourceType, sourceId, referenceNo, remark, operator, LocalDateTime.now());
    }

    private void post(long accountId, long billId, String entryType, String direction, BigDecimal amount,
                      String sourceType, long sourceId, String referenceNo, String remark, String operator,
                      LocalDateTime effectiveTime) {
        String actor = operatorOrAdmin(operator);
        jdbcTemplate.update("""
                INSERT IGNORE INTO billing_receivable_ledger
                  (account_id, bill_id, entry_type, direction, amount, effective_time, source_type, source_id,
                   reference_no, remark, create_by, update_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, accountId, billId, entryType, direction, money(amount), Timestamp.valueOf(effectiveTime), sourceType,
                sourceId, text(referenceNo), text(remark), actor, actor);
    }

    private String operatorOrAdmin(String operator) { return operator == null || operator.isBlank() ? "admin" : operator.trim(); }
    private String text(Object value) { if (value == null) return null; String valueText = String.valueOf(value).trim(); return valueText.isEmpty() ? null : valueText; }
    private Integer integer(Object value) { return value instanceof Number number ? number.intValue() : value == null ? null : Integer.valueOf(String.valueOf(value)); }
    private BigDecimal money(BigDecimal value) { return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP); }
    private int positive(String value, int fallback) { try { int parsed = Integer.parseInt(value); return parsed > 0 ? parsed : fallback; } catch (RuntimeException ignored) { return fallback; } }
}
