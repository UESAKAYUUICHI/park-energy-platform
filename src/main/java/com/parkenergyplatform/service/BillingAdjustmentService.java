package com.parkenergyplatform.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.common.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 已发布账单的减免、补收和坏账核销审批。 */
@Service
public class BillingAdjustmentService {
    private static final Set<String> TYPES = Set.of("DISCOUNT", "SURCHARGE", "WRITE_OFF");
    private final JdbcTemplate jdbcTemplate;
    private final BusinessDataAccessService accessService;

    public BillingAdjustmentService(JdbcTemplate jdbcTemplate, BusinessDataAccessService accessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessService = accessService;
    }

    public PageResult<Map<String, Object>> page(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        String status = text(params.get("status"));
        if (status != null) { where.append(" AND a.status = ?"); args.add(status); }
        String keyword = text(params.get("keyword"));
        if (keyword != null) { where.append(" AND (a.adjustment_no LIKE ? OR b.bill_no LIKE ? OR b.tenant_name_snapshot LIKE ?)"); for (int i = 0; i < 3; i++) args.add("%" + keyword + "%"); }
        where.append(accessService.scopeSql("ba.org_id", args));
        int pageNum = positive(params.get("pageNum"), 1), pageSize = Math.min(positive(params.get("pageSize"), 20), 200);
        String from = " FROM billing_adjustment a JOIN billing_bill b ON b.id = a.bill_id JOIN billing_account ba ON ba.id = b.account_id";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(pageSize); pageArgs.add((pageNum - 1) * pageSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT a.*, b.bill_no, b.total_amount, b.paid_amount, b.outstanding_amount, b.tenant_name_snapshot, ba.account_name" + from + where + " ORDER BY a.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        return PageResult.of(rows, total == null ? 0 : total, pageNum, pageSize);
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        long billId = number(body.get("billId"), "billId");
        accessService.assertBillAccess(billId);
        Map<String, Object> bill = requiredBill(billId, false);
        if (!"ISSUED".equalsIgnoreCase(Objects.toString(bill.get("bill_status"), ""))) throw new BusinessException("仅已发布账单可创建调整单");
        String type = required(body.get("adjustmentType"), "adjustmentType").toUpperCase();
        if (!TYPES.contains(type)) throw new BusinessException("adjustmentType 仅支持 " + TYPES);
        BigDecimal amount = amount(body.get("adjustmentAmount"));
        String no = "ADJ" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")) + String.format("%06d", Math.floorMod(billId, 1_000_000L));
        jdbcTemplate.update("""
                INSERT INTO billing_adjustment (adjustment_no, bill_id, adjustment_type, adjustment_amount, reason, remark, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, no, billId, type, amount, required(body.get("reason"), "reason"), text(body.get("remark")), required(body.get("operator"), "operator"));
        Long id = jdbcTemplate.queryForObject("SELECT id FROM billing_adjustment WHERE adjustment_no = ?", Long.class, no);
        return detail(id);
    }

    @Transactional
    public Map<String, Object> approve(long adjustmentId, String operator) {
        Map<String, Object> adjustment = requiredAdjustment(adjustmentId, true);
        long billId = number(adjustment.get("bill_id"), "billId");
        accessService.assertBillAccess(billId);
        if (!"PENDING".equalsIgnoreCase(Objects.toString(adjustment.get("status"), ""))) throw new BusinessException("仅待审批调整单可以审批");
        Map<String, Object> bill = requiredBill(billId, true);
        if (!"ISSUED".equalsIgnoreCase(Objects.toString(bill.get("bill_status"), ""))) throw new BusinessException("账单非已发布状态，不能审批调整");
        BigDecimal currentTotal = money(bill.get("total_amount"));
        BigDecimal paid = money(bill.get("paid_amount"));
        BigDecimal amount = money(adjustment.get("adjustment_amount"));
        String type = Objects.toString(adjustment.get("adjustment_type"), "");
        BigDecimal delta = "SURCHARGE".equals(type) ? amount : amount.negate();
        BigDecimal newTotal = currentTotal.add(delta).setScale(2, RoundingMode.HALF_UP);
        if (newTotal.compareTo(paid) < 0) throw new BusinessException("调整后应收不能低于累计已收，请先通过收款冲销或退款处理");
        if (newTotal.compareTo(BigDecimal.ZERO) < 0) throw new BusinessException("调整后应收不能小于零");
        BigDecimal outstanding = newTotal.subtract(paid).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        int payStatus = outstanding.compareTo(BigDecimal.ZERO) == 0 ? 1 : (paid.compareTo(BigDecimal.ZERO) > 0 ? 4 : 0);
        jdbcTemplate.update("UPDATE billing_bill SET total_amount = ?, outstanding_amount = ?, pay_status = ? WHERE id = ?", newTotal, outstanding, payStatus, billId);
        jdbcTemplate.update("UPDATE billing_adjustment SET status = 'APPROVED', approved_by = ?, approved_time = ? WHERE id = ?", operator, Timestamp.valueOf(LocalDateTime.now()), adjustmentId);
        return detail(adjustmentId);
    }

    @Transactional
    public Map<String, Object> cancel(long adjustmentId, String operator) {
        Map<String, Object> adjustment = requiredAdjustment(adjustmentId, true);
        long billId = number(adjustment.get("bill_id"), "billId");
        accessService.assertBillAccess(billId);
        if (!"PENDING".equalsIgnoreCase(Objects.toString(adjustment.get("status"), ""))) throw new BusinessException("仅待审批调整单可以撤销");
        jdbcTemplate.update("UPDATE billing_adjustment SET status = 'CANCELLED', cancelled_by = ?, cancelled_time = ? WHERE id = ?", operator, Timestamp.valueOf(LocalDateTime.now()), adjustmentId);
        return detail(adjustmentId);
    }

    public Map<String, Object> detail(long adjustmentId) {
        Map<String, Object> adjustment = requiredAdjustment(adjustmentId, false);
        accessService.assertBillAccess(number(adjustment.get("bill_id"), "billId"));
        return adjustment;
    }

    private Map<String, Object> requiredAdjustment(long id, boolean lock) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM billing_adjustment WHERE id = ?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw new BusinessException(404, "账单调整单不存在: " + id);
        return new LinkedHashMap<>(rows.get(0));
    }
    private Map<String, Object> requiredBill(long id, boolean lock) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM billing_bill WHERE id = ?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw new BusinessException(404, "账单不存在: " + id);
        return new LinkedHashMap<>(rows.get(0));
    }
    private BigDecimal amount(Object value) { BigDecimal amount = money(value); if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("adjustmentAmount 必须大于零"); return amount; }
    private BigDecimal money(Object value) { try { return new BigDecimal(String.valueOf(value)).setScale(2, RoundingMode.HALF_UP); } catch (RuntimeException exception) { throw new BusinessException("金额格式不正确"); } }
    private long number(Object value, String field) { if (value == null) throw new BusinessException(field + " 不能为空"); try { return Long.parseLong(String.valueOf(value)); } catch (RuntimeException exception) { throw new BusinessException(field + " 格式不正确"); } }
    private String required(Object value, String field) { String text = text(value); if (text == null) throw new BusinessException(field + " 不能为空"); return text; }
    private String text(Object value) { if (value == null) return null; String text = String.valueOf(value).trim(); return text.isEmpty() ? null : text; }
    private int positive(String value, int defaultValue) { try { int result = Integer.parseInt(value); return result > 0 ? result : defaultValue; } catch (RuntimeException exception) { return defaultValue; } }
}
