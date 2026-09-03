package com.parkenergyplatform.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.parkenergyplatform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 面向操作台的账单处理编排。将审核、出账与演示到账的业务凭据集中生成，
 * 避免前端用多个请求拼接出半完成的财务事实。
 */
@Service
public class BillingWorkflowService {
    private final JdbcTemplate jdbc;
    private final BillingService billingService;
    private final BillingBankFinanceService bankFinanceService;
    private final BillingFinanceService financeService;

    public BillingWorkflowService(JdbcTemplate jdbc, BillingService billingService,
                                  BillingBankFinanceService bankFinanceService,
                                  BillingFinanceService financeService) {
        this.jdbc = jdbc;
        this.billingService = billingService;
        this.bankFinanceService = bankFinanceService;
        this.financeService = financeService;
    }

    public Map<String, Object> preview(long billId) {
        Map<String, Object> bill = billingService.bill(billId);
        String stage = stage(bill);
        BigDecimal total = money(bill.get("total_amount"));
        BigDecimal outstanding = money(bill.get("outstanding_amount"));
        int itemCount = listSize(bill.get("details"));
        int adjustmentCount = pendingAdjustmentCount(bill.get("adjustments"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bill", bill);
        result.put("stage", stage);
        result.put("checks", List.of(
                check("ACCOUNT_READY", "结算主体已绑定", text(bill.get("account_name")) != null,
                        text(bill.get("account_name")) == null ? "账单缺少计费账户" : text(bill.get("account_name"))),
                check("DETAILS_READY", "计费明细完整", itemCount > 0,
                        itemCount == 0 ? "未找到计费明细" : "共 " + itemCount + " 条计费明细"),
                check("AMOUNT_READY", "应收金额有效", total.compareTo(BigDecimal.ZERO) > 0,
                        "本期应收 ¥" + total),
                check("ADJUSTMENT_CHECK", "无待处理调整单", adjustmentCount == 0,
                        adjustmentCount == 0 ? "未发现待处理调整" : "存在 " + adjustmentCount + " 条待处理调整，请先核对"),
                check("OUTSTANDING", "当前待收金额", "collect".equals(stage),
                        "当前待收 ¥" + outstanding)));
        result.put("documents", documents(bill, stage));
        result.put("timeline", jdbc.queryForList("""
                SELECT event_type,event_time,operator,remark,before_bill_status,after_bill_status,before_pay_status,after_pay_status
                FROM billing_bill_event WHERE bill_id=? ORDER BY event_time DESC,id DESC LIMIT 12
                """, billId));
        return result;
    }

    @Transactional
    public Map<String, Object> confirmReview(long billId, String operator) {
        Map<String, Object> before = billingService.bill(billId);
        requireStage(before, "review", "当前账单不在待审核阶段");
        Map<String, Object> result = billingService.review(billId, operator);
        financeService.recordEvent(billId, "REVIEW_CONFIRMATION", "DRAFT", "REVIEWED", before.get("pay_status"),
                before.get("pay_status"), BigDecimal.ZERO, operator, "已核对计费依据、金额与结算主体，审核确认单已生成");
        return result;
    }

    @Transactional
    public Map<String, Object> confirmIssue(long billId, String operator) {
        Map<String, Object> before = billingService.bill(billId);
        requireStage(before, "issue", "当前账单不在待出账阶段");
        Map<String, Object> result = billingService.issue(billId, operator);
        financeService.recordEvent(billId, "ISSUE_CONFIRMATION", "REVIEWED", "ISSUED", before.get("pay_status"),
                before.get("pay_status"), money(before.get("total_amount")), operator, "已确认收款通知内容，出账确认单已生成");
        return result;
    }

    @Transactional
    public Map<String, Object> simulateSettlement(long billId, Map<String, Object> request, String operator) {
        Map<String, Object> bill = billingService.bill(billId);
        requireStage(bill, "collect", "当前账单不需要登记收款");
        BigDecimal outstanding = money(bill.get("outstanding_amount"));
        BigDecimal amount = request.get("amount") == null ? outstanding : money(request.get("amount"));
        if (amount.compareTo(BigDecimal.ZERO) <= 0 || amount.compareTo(outstanding) > 0) {
            throw new BusinessException("模拟到账金额必须大于零且不超过待收金额 ¥" + outstanding);
        }
        long orgId = number(bill.get("org_id"), "orgId");
        String billNo = text(bill.get("bill_no"));
        String externalNo = "DEMO-" + billNo + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("externalTransactionNo", externalNo);
        record.put("statementTime", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        record.put("payerName", firstText(bill, "tenant_name_snapshot", "account_name", "tenant_name"));
        record.put("amount", amount);
        record.put("narrative", "模拟到账：" + billNo);
        bankFinanceService.importStatements(Map.of(
                "orgId", orgId,
                "sourceChannel", "DEMO",
                "importBatchNo", "DEMO-" + billNo,
                "records", List.of(record)), operator);
        Long statementId = jdbc.queryForObject("SELECT id FROM billing_bank_statement WHERE source_channel='DEMO' AND external_transaction_no=?", Long.class, externalNo);
        if (statementId == null) throw new BusinessException("模拟到账流水创建失败");
        Map<String, Object> statement = bankFinanceService.match(statementId, billId, amount, operator);
        List<Map<String, Object>> matches = castRows(statement.get("matches"));
        Long paymentId = matches.isEmpty() ? null : number(matches.get(0).get("payment_id"), "paymentId");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bill", billingService.bill(billId));
        result.put("statement", statement);
        result.put("paymentId", paymentId);
        result.put("message", "已生成银行流水、收款记录、自动勾兑记录和收款凭证草稿");
        return result;
    }

    private Map<String, Object> documents(Map<String, Object> bill, String stage) {
        String billNo = Objects.toString(bill.get("bill_no"), "-");
        Map<String, Object> documents = new LinkedHashMap<>();
        documents.put("reviewNo", "REV-" + billNo);
        documents.put("issueNo", "ISS-" + billNo);
        documents.put("noticeNo", "NOTICE-" + billNo);
        documents.put("receiptHint", "collect".equals(stage) ? "确认后自动生成银行流水、收款与对账凭据" : "出账后可生成到账凭据");
        return documents;
    }

    private Map<String, Object> check(String code, String label, boolean passed, String detail) {
        return Map.of("code", code, "label", label, "passed", passed, "detail", detail);
    }

    private void requireStage(Map<String, Object> bill, String expected, String message) {
        if (!expected.equals(stage(bill))) throw new BusinessException(message);
    }

    private String stage(Map<String, Object> bill) {
        String status = Objects.toString(bill.get("bill_status"), "").toUpperCase();
        if ("DRAFT".equals(status)) return "review";
        if ("REVIEWED".equals(status) || "REVIEWING".equals(status)) return "issue";
        return money(bill.get("outstanding_amount")).compareTo(BigDecimal.ZERO) > 0 ? "collect" : "done";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castRows(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private int listSize(Object value) { return value instanceof List<?> list ? list.size() : 0; }
    private int pendingAdjustmentCount(Object value) {
        if (!(value instanceof List<?> values)) return 0;
        return (int) values.stream().filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(row -> "PENDING".equalsIgnoreCase(Objects.toString(row.get("status"), ""))).count();
    }
    private String text(Object value) { String result = value == null ? null : String.valueOf(value).trim(); return result == null || result.isEmpty() ? null : result; }
    private String firstText(Map<String, Object> row, String... keys) { for (String key : keys) { String value = text(row.get(key)); if (value != null) return value; } return "结算付款方"; }
    private long number(Object value, String field) { try { return Long.parseLong(String.valueOf(value)); } catch (RuntimeException exception) { throw new BusinessException(field + "不能为空"); } }
    private BigDecimal money(Object value) { try { return new BigDecimal(String.valueOf(value == null ? 0 : value)).setScale(2, RoundingMode.HALF_UP); } catch (RuntimeException exception) { throw new BusinessException("金额不合法"); } }
}
