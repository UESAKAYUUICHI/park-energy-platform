package com.parkenergyplatform.service;

import java.sql.Date;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.common.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns the accounting boundary. Closing requires issued bills, completed collection and cleared differences. */
@Service
public class BillingPeriodService {
    private final JdbcTemplate jdbc;
    private final BusinessDataAccessService access;

    public BillingPeriodService(JdbcTemplate jdbc, BusinessDataAccessService access) { this.jdbc = jdbc; this.access = access; }

    public PageResult<Map<String, Object>> page(Map<String, String> params) {
        int page = positive(params.get("pageNum"), 1), size = Math.min(positive(params.get("pageSize"), 20), 200);
        List<Object> args = new ArrayList<>(); StringBuilder where = new StringBuilder(" WHERE 1=1");
        equals(where, args, "p.org_id", params.get("orgId")); equals(where, args, "p.status", params.get("status"));
        String keyword = text(params.get("keyword")); if (keyword != null) { where.append(" AND p.period_code LIKE ?"); args.add("%" + keyword + "%"); }
        where.append(access.scopeSql("p.org_id", args));
        String from = " FROM billing_period p LEFT JOIN dev_org o ON o.id=p.org_id";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(size); pageArgs.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.*, o.org_name,
                  (SELECT COUNT(*) FROM billing_bill b JOIN billing_account a ON a.id=b.account_id
                    WHERE a.org_id=p.org_id AND BINARY b.bill_cycle=BINARY p.period_code) AS bill_count,
                  (SELECT COALESCE(SUM(b.outstanding_amount),0) FROM billing_bill b JOIN billing_account a ON a.id=b.account_id
                    WHERE a.org_id=p.org_id AND BINARY b.bill_cycle=BINARY p.period_code AND b.bill_status='ISSUED') AS outstanding_amount
                """ + from + where + " ORDER BY p.start_date DESC,p.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        return PageResult.of(rows, total == null ? 0 : total, page, size);
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body, String operator) {
        long orgId = number(body.get("orgId"), "orgId"); assertOrg(orgId);
        String code = required(body.get("periodCode"), "periodCode"); LocalDate start = LocalDate.parse(required(body.get("startDate"), "startDate")); LocalDate end = LocalDate.parse(required(body.get("endDate"), "endDate"));
        if (end.isBefore(start)) throw new BusinessException("账期结束日期不能早于开始日期");
        Long duplicate = jdbc.queryForObject("SELECT COUNT(*) FROM billing_period WHERE org_id=? AND period_code=?", Long.class, orgId, code);
        if (duplicate != null && duplicate > 0) throw new BusinessException(409, "该园区账期已存在");
        jdbc.update("INSERT INTO billing_period (period_code,org_id,start_date,end_date,remark,create_by,update_by) VALUES (?,?,?,?,?,?,?)", code, orgId, Date.valueOf(start), Date.valueOf(end), text(body.get("remark")), operator, operator);
        Long id = jdbc.queryForObject("SELECT id FROM billing_period WHERE org_id=? AND period_code=?", Long.class, orgId, code);
        return detail(id == null ? 0 : id);
    }

    @Transactional
    public Map<String, Object> close(long id, String operator) {
        Map<String, Object> period = required(id, true); assertOrg(number(period.get("org_id"), "orgId"));
        if (!List.of("OPEN", "CLOSED").contains(String.valueOf(period.get("status")).toUpperCase())) throw new BusinessException("当前账期状态不允许关账");
        Map<String, Object> readiness = readiness(period);
        if (!Boolean.TRUE.equals(readiness.get("closeable"))) {
            @SuppressWarnings("unchecked") List<Map<String, Object>> checks = (List<Map<String, Object>>) readiness.get("checks");
            String blockers = checks.stream().filter(row -> !Boolean.TRUE.equals(row.get("passed")))
                    .map(row -> Objects.toString(row.get("title"), "") + "：" + Objects.toString(row.get("detail"), ""))
                    .limit(4).reduce((a, b) -> a + "；" + b).orElse("关账条件尚未满足");
            throw new BusinessException("暂不可关账：" + blockers);
        }
        String summary = "账单 " + readiness.get("billCount") + " 张；应收 ¥" + readiness.get("receivableAmount")
                + "，实收 ¥" + readiness.get("paidAmount") + "；收款勾兑、凭证和调整检查均已通过。";
        jdbc.update("UPDATE billing_period SET status='CLOSED',close_summary=?,closed_by=?,closed_time=NOW(),update_by=? WHERE id=?", summary + " 月度最终归档前仍可继续出账和收款。", operator, operator, id);
        return detail(id);
    }

    public Map<String, Object> check(long id) {
        Map<String, Object> period = required(id, false);
        assertOrg(number(period.get("org_id"), "orgId"));
        return readiness(period);
    }

    private Map<String, Object> readiness(Map<String, Object> period) {
        Object orgId = period.get("org_id"), code = period.get("period_code");
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT COUNT(*) bill_count,
                       COALESCE(SUM(CASE WHEN b.bill_status<>'VOID' THEN b.total_amount ELSE 0 END),0) receivable_amount,
                       COALESCE(SUM(CASE WHEN b.bill_status<>'VOID' THEN b.paid_amount ELSE 0 END),0) paid_amount,
                       COALESCE(SUM(CASE WHEN b.bill_status='ISSUED' THEN b.outstanding_amount ELSE 0 END),0) outstanding_amount,
                       SUM(CASE WHEN b.bill_status NOT IN ('ISSUED','VOID') THEN 1 ELSE 0 END) unfinished_bill_count
                FROM billing_bill b JOIN billing_account a ON a.id=b.account_id
                WHERE a.org_id=? AND b.bill_cycle=?
                """, orgId, code);
        long billCount = longValue(totals.get("bill_count"));
        long unfinished = longValue(totals.get("unfinished_bill_count"));
        BigDecimal receivable = decimal(totals.get("receivable_amount"));
        BigDecimal paid = decimal(totals.get("paid_amount"));
        BigDecimal outstanding = decimal(totals.get("outstanding_amount"));
        long paymentCount = count("""
                SELECT COUNT(*) FROM billing_payment p JOIN billing_bill b ON b.id=p.bill_id JOIN billing_account a ON a.id=b.account_id
                WHERE a.org_id=? AND b.bill_cycle=? AND p.payment_status='SUCCESS'
                """, orgId, code);
        long unmatchedPayments = count("""
                SELECT COUNT(*) FROM billing_payment p JOIN billing_bill b ON b.id=p.bill_id JOIN billing_account a ON a.id=b.account_id
                WHERE a.org_id=? AND b.bill_cycle=? AND p.payment_status='SUCCESS'
                  AND NOT EXISTS(SELECT 1 FROM billing_bank_match m WHERE m.payment_id=p.id AND m.match_status='MATCHED')
                """, orgId, code);
        long differences = count("""
                SELECT COUNT(DISTINCT s.id)
                FROM billing_bank_statement s
                WHERE s.target_period_id=? AND s.statement_status='DIFFERENCE'
                """, period.get("id"));
        long pendingAdjustments = count("""
                SELECT COUNT(*) FROM billing_adjustment j JOIN billing_bill b ON b.id=j.bill_id JOIN billing_account a ON a.id=b.account_id
                WHERE a.org_id=? AND b.bill_cycle=? AND j.status='PENDING'
                """, orgId, code);
        long missingReceivableVouchers = count("""
                SELECT COUNT(*) FROM billing_bill b JOIN billing_account a ON a.id=b.account_id
                WHERE a.org_id=? AND b.bill_cycle=? AND b.bill_status='ISSUED'
                  AND NOT EXISTS(SELECT 1 FROM billing_voucher v WHERE v.bill_id=b.id AND v.voucher_type='AR_RECEIVABLE' AND v.voucher_status IN ('POSTED','EXPORTED'))
                """, orgId, code);
        long missingReceiptVouchers = count("""
                SELECT COUNT(*) FROM billing_payment p JOIN billing_bill b ON b.id=p.bill_id JOIN billing_account a ON a.id=b.account_id
                WHERE a.org_id=? AND b.bill_cycle=? AND p.payment_status='SUCCESS'
                  AND NOT EXISTS(SELECT 1 FROM billing_voucher v WHERE v.payment_id=p.id AND v.voucher_type='RECEIPT' AND v.voucher_status IN ('POSTED','EXPORTED'))
                """, orgId, code);
        long pendingInvoices = count("""
                SELECT COUNT(DISTINCT i.id)
                FROM billing_invoice i JOIN billing_invoice_bill ib ON ib.invoice_id=i.id
                JOIN billing_bill b ON b.id=ib.bill_id JOIN billing_account a ON a.id=b.account_id
                WHERE a.org_id=? AND b.bill_cycle=? AND i.invoice_status IN ('REQUESTED','RED_APPLIED')
                """, orgId, code);
        boolean billsReady = billCount > 0 && unfinished == 0;
        boolean paidReady = billsReady && outstanding.compareTo(BigDecimal.ZERO) <= 0 && paid.compareTo(receivable) >= 0;
        boolean reconciliationReady = paymentCount > 0 && unmatchedPayments == 0 && differences == 0;
        boolean voucherReady = missingReceivableVouchers == 0 && missingReceiptVouchers == 0;
        boolean adjustmentReady = pendingAdjustments == 0;
        boolean invoiceReady = pendingInvoices == 0;
        List<Map<String, Object>> checks = List.of(
                checkRow("账单发布", billsReady, billCount == 0 ? "本账期尚未生成账单" : unfinished == 0 ? billCount + " 张账单均已正式发布" : unfinished + " 张账单尚未发布", "账单支付"),
                checkRow("收款完成", paidReady, "应收 ¥" + receivable + "，实收 ¥" + paid + "，未收 ¥" + outstanding, "账单支付"),
                checkRow("收款对账", reconciliationReady, paymentCount == 0 ? "尚无有效收款记录" : unmatchedPayments == 0 ? paymentCount + " 笔收款均已勾兑" : unmatchedPayments + " 笔收款尚未关联银行流水", "银行流水"),
                checkRow("对账差异", differences == 0, differences == 0 ? "当前账期无未处理差异" : "存在 " + differences + " 条未处理差异", "银行流水"),
                checkRow("财务凭证", voucherReady, voucherReady ? "应收及收款凭证均已确认入账" : "缺少 " + missingReceivableVouchers + " 张应收入账凭证、" + missingReceiptVouchers + " 张收款入账凭证", "财务凭证"),
                checkRow("调账事项", adjustmentReady, adjustmentReady ? "不存在待审批调账" : "存在 " + pendingAdjustments + " 条待审批调账", "账单支付"),
                checkRow("发票事项", invoiceReady, invoiceReady ? "不存在待开具或待红冲发票" : "存在 " + pendingInvoices + " 条未完成发票业务", "账单支付")
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("periodId", period.get("id")); result.put("periodCode", code); result.put("status", period.get("status"));
        result.put("billCount", billCount); result.put("receivableAmount", receivable); result.put("paidAmount", paid); result.put("outstandingAmount", outstanding);
        result.put("paymentCount", paymentCount); result.put("unmatchedPaymentCount", unmatchedPayments); result.put("differenceCount", differences);
        result.put("missingReceivableVoucherCount", missingReceivableVouchers); result.put("missingReceiptVoucherCount", missingReceiptVouchers); result.put("pendingAdjustmentCount", pendingAdjustments);
        result.put("pendingInvoiceCount",pendingInvoices);
        result.put("checks", checks); result.put("closeable", billsReady && paidReady && reconciliationReady && voucherReady && adjustmentReady && invoiceReady);
        return result;
    }

    private Map<String, Object> checkRow(String title, boolean passed, String detail, String target) {
        Map<String, Object> row = new LinkedHashMap<>(); row.put("title", title); row.put("passed", passed); row.put("detail", detail); row.put("target", target); return row;
    }

    private long count(String sql, Object... args) { Long value = jdbc.queryForObject(sql, Long.class, args); return value == null ? 0 : value; }
    private long longValue(Object value) { if (value instanceof Number n) return n.longValue(); try { return Long.parseLong(String.valueOf(value)); } catch (Exception ignored) { return 0; } }
    private BigDecimal decimal(Object value) { if (value instanceof BigDecimal n) return n; try { return new BigDecimal(String.valueOf(value)); } catch (Exception ignored) { return BigDecimal.ZERO; } }

    @Transactional
    public Map<String, Object> reopen(long id, String operator, String reason) {
        Map<String, Object> period = required(id, true); assertOrg(number(period.get("org_id"), "orgId"));
        if (!"CLOSED".equalsIgnoreCase(String.valueOf(period.get("status")))) throw new BusinessException("只有已关账账期可以反关账");
        Long archived = jdbc.queryForObject("SELECT COUNT(*) FROM billing_settlement_archive WHERE period_id=? AND archive_status='ARCHIVED'", Long.class, id);
        if (archived != null && archived > 0) throw new BusinessException("账期已经正式归档，不能直接反关账；请发起受控的撤销归档流程");
        if (text(reason) == null) throw new BusinessException("反关账必须说明原因");
        jdbc.update("UPDATE billing_period SET status='OPEN',reopened_by=?,reopened_time=NOW(),remark=CONCAT(COALESCE(remark,''),' [反关账：',? ,']'),update_by=? WHERE id=?", operator, reason, operator, id);
        jdbc.update("UPDATE billing_settlement_archive SET archive_status='REOPENED',reopened_by=?,reopened_time=NOW(),updated_by=? WHERE period_id=? AND archive_status='READY'", operator, operator, id);
        return detail(id);
    }

    public Map<String, Object> detail(long id) { Map<String, Object> period = required(id, false); assertOrg(number(period.get("org_id"), "orgId")); Map<String,Object> result = new LinkedHashMap<>(period); result.put("bills", jdbc.queryForList("SELECT b.id,b.bill_no,b.bill_status,b.pay_status,b.total_amount,b.outstanding_amount FROM billing_bill b JOIN billing_account a ON a.id=b.account_id WHERE a.org_id=? AND b.bill_cycle=? ORDER BY b.id", period.get("org_id"), period.get("period_code"))); return result; }
    private Map<String,Object> required(long id, boolean locked) { List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM billing_period WHERE id=?"+(locked?" FOR UPDATE":""),id); if(rows.isEmpty()) throw new BusinessException(404,"账期不存在"); return rows.get(0); }
    private void assertOrg(long id) { if(!access.hasOrgAccess(id)) throw new BusinessException(403,"没有该园区账期权限"); }
    private void equals(StringBuilder where,List<Object> args,String field,String value){if(text(value)!=null){where.append(" AND ").append(field).append("=?");args.add(value);}}
    private int positive(String value,int defaultValue){try{int n=Integer.parseInt(value);return n>0?n:defaultValue;}catch(Exception ignored){return defaultValue;}}
    private long number(Object value,String name){if(value==null)throw new BusinessException(name+"不能为空");try{return Long.parseLong(String.valueOf(value));}catch(Exception e){throw new BusinessException(name+"必须为数字");}}
    private String required(Object value,String name){String valueText=text(value);if(valueText==null)throw new BusinessException(name+"不能为空");return valueText;}
    private String text(Object value){if(value==null)return null;String valueText=String.valueOf(value).trim();return valueText.isEmpty()?null:valueText;}
}
