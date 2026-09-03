package com.parkenergyplatform.service;

import com.parkenergyplatform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 已定稿或已关账账期的独立补充/临时结算，不回写原账单和结算包。 */
@Service
public class BillingSupplementSettlementService {
    private static final Set<String> TYPES = Set.of("LATE_CONTRACT", "TEMPORARY", "DATA_CORRECTION", "PRICE_CORRECTION");
    private final JdbcTemplate jdbc;
    private final BusinessDataAccessService access;
    private final BillingService bills;

    public BillingSupplementSettlementService(JdbcTemplate jdbc, BusinessDataAccessService access, BillingService bills) {
        this.jdbc = jdbc; this.access = access; this.bills = bills;
    }

    public List<Map<String,Object>> list(Long orgId, Long periodId) {
        List<Object> args = new ArrayList<>(); StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (orgId != null) { assertOrg(orgId); where.append(" AND s.org_id=?"); args.add(orgId); }
        if (periodId != null) { where.append(" AND s.source_period_id=?"); args.add(periodId); }
        where.append(access.scopeSql("s.org_id", args));
        return jdbc.queryForList("""
                SELECT s.*,c.contract_no,c.contract_name,a.account_name,b.bill_no,b.total_amount,b.bill_status,b.pay_status
                FROM billing_supplement_settlement s
                LEFT JOIN leasing_contract c ON c.id=s.contract_id
                LEFT JOIN billing_account a ON a.id=s.account_id
                LEFT JOIN billing_bill b ON b.id=s.generated_bill_id
                """ + where + " ORDER BY s.id DESC", args.toArray());
    }

    @Transactional
    public Map<String,Object> create(Map<String,Object> body, String operator) {
        long periodId = number(body.get("periodId"), "periodId");
        Map<String,Object> period = one("SELECT * FROM billing_period WHERE id=?", periodId, "原账期不存在");
        long orgId = number(period.get("org_id"), "orgId"); assertOrg(orgId);
        assertMonthNotArchived(periodId, String.valueOf(period.get("period_code")));
        long contractId = number(body.get("contractId"), "contractId");
        List<Map<String,Object>> accounts = jdbc.queryForList("SELECT * FROM billing_account WHERE contract_id=? AND org_id=? AND status=1 ORDER BY id", contractId, orgId);
        if (accounts.isEmpty()) throw new BusinessException("所选合同没有启用的计费账户，不能发起补充结算");
        long accountId = number(accounts.get(0).get("id"), "accountId");
        access.assertBillingAccountAccess(accountId);
        String type = text(body.get("settlementType"), "LATE_CONTRACT").toUpperCase();
        if (!TYPES.contains(type)) throw new BusinessException("不支持的补充结算类型");
        LocalDate start = date(body.get("startDate"), "startDate"), end = date(body.get("endDate"), "endDate");
        if (end.isBefore(start)) throw new BusinessException("结算结束日期不能早于开始日期");
        LocalDate periodStart = date(period.get("start_date"), "periodStart"), periodEnd = date(period.get("end_date"), "periodEnd");
        if (start.isBefore(periodStart) || end.isAfter(periodEnd)) throw new BusinessException("补充结算范围必须在原账期 " + period.get("period_code") + " 内");
        List<Map<String,Object>> overlaps = jdbc.queryForList("""
                SELECT * FROM billing_supplement_settlement
                WHERE account_id=? AND settlement_start_date<=? AND settlement_end_date>=?
                ORDER BY id DESC
                """, accountId, Date.valueOf(end), Date.valueOf(start));
        // 失败或尚未出账的相同草稿不产生财务事实；返回原单后可直接重试，避免失败后被自身锁死。
        for (Map<String,Object> overlap : overlaps) {
            String overlapStatus = String.valueOf(overlap.get("status")).toUpperCase();
            boolean exactRange = start.equals(date(overlap.get("settlement_start_date"), "settlementStart"))
                    && end.equals(date(overlap.get("settlement_end_date"), "settlementEnd"));
            if (exactRange && List.of("DRAFT", "FAILED").contains(overlapStatus)
                    && overlap.get("generated_bill_id") == null) {
                return detail(number(overlap.get("id"), "supplementId"));
            }
        }
        Long archiveId = jdbc.queryForObject("SELECT id FROM billing_settlement_archive WHERE period_id=? AND archive_status='ARCHIVED'", Long.class, periodId);
        String no = "SUP" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")) + String.format("%05d", Math.floorMod(contractId, 100000));
        int term = integer(body.get("paymentTermDays"), 15); if (term < 0 || term > 90) throw new BusinessException("付款期限必须在 0 到 90 天之间");
        String reason = text(body.get("reason"), ""); if (reason.isBlank()) throw new BusinessException("请填写补充结算原因");
        jdbc.update("""
                INSERT INTO billing_supplement_settlement
                (supplement_no,org_id,source_period_id,source_archive_id,source_cycle,contract_id,account_id,settlement_type,processing_mode,
                 settlement_start_date,settlement_end_date,payment_term_days,status,reason,remark,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,'SUPPLEMENT_BILL',?,?,?,'DRAFT',?,?,?,?)
                """, no,orgId,periodId,archiveId,String.valueOf(period.get("period_code")),contractId,accountId,type,
                Date.valueOf(start),Date.valueOf(end),term,reason,text(body.get("remark"),null),operator,operator);
        Long id = jdbc.queryForObject("SELECT id FROM billing_supplement_settlement WHERE supplement_no=?", Long.class, no);
        return detail(id == null ? 0 : id);
    }

    public Map<String,Object> generate(long id, String operator) {
        Map<String,Object> row = required(id); assertOrg(number(row.get("org_id"), "orgId"));
        assertMonthNotArchived(number(row.get("source_period_id"), "periodId"), String.valueOf(row.get("source_cycle")));
        String status = String.valueOf(row.get("status"));
        if (!"DRAFT".equalsIgnoreCase(status) && !"FAILED".equalsIgnoreCase(status)) throw new BusinessException("仅草稿或生成失败的补充结算单可以出账");
        jdbc.update("UPDATE billing_supplement_settlement SET status='CALCULATING',updated_by=? WHERE id=?",operator,id);
        try {
            Map<String,Object> generated = bills.generate(Map.of(
                    "accountId", row.get("account_id"), "billCycle", row.get("source_cycle"),
                    "startDate", String.valueOf(row.get("settlement_start_date")), "endDate", String.valueOf(row.get("settlement_end_date")),
                    "billType", "SUPPLEMENT", "supplementSettlementId", id, "billingKey", row.get("supplement_no"),
                    "operator", operator, "remark", "SUPPLEMENT:" + row.get("supplement_no")));
            long billId = number(generated.get("id"), "billId");
            jdbc.update("UPDATE billing_supplement_settlement SET status='REVIEWING',generated_bill_id=?,result_summary=?,updated_by=? WHERE id=?", billId,"已生成补充账单，等待审核发布",operator,id);
        } catch (RuntimeException exception) {
            jdbc.update("UPDATE billing_supplement_settlement SET status='FAILED',result_summary=?,updated_by=? WHERE id=?", shortMessage(exception),operator,id);
            throw exception;
        }
        return detail(id);
    }

    @Transactional
    public Map<String,Object> cancel(long id, String operator) {
        Map<String,Object> row=required(id); assertOrg(number(row.get("org_id"),"orgId"));
        if (!List.of("DRAFT","FAILED").contains(String.valueOf(row.get("status")).toUpperCase())) throw new BusinessException("只有未出账的补充结算单可以取消");
        jdbc.update("UPDATE billing_supplement_settlement SET status='CANCELLED',cancelled_by=?,cancelled_time=NOW(),updated_by=? WHERE id=?",operator,operator,id);
        return detail(id);
    }

    @Transactional
    public Map<String,Object> archive(long id, String operator) {
        Map<String,Object> row=required(id); assertOrg(number(row.get("org_id"),"orgId"));
        if (!"PAID".equalsIgnoreCase(String.valueOf(row.get("status")))) throw new BusinessException("补充账单结清后才可归档");
        Map<String,Object> readiness = archiveReadiness(row);
        if (!Boolean.TRUE.equals(readiness.get("archiveReady"))) {
            @SuppressWarnings("unchecked") List<Map<String,Object>> checks = (List<Map<String,Object>>) readiness.get("archiveChecks");
            Map<String,Object> blocker = checks.stream().filter(item -> !Boolean.TRUE.equals(item.get("passed"))).findFirst().orElse(Map.of());
            throw new BusinessException("补充结算归档检查未通过：" + Objects.toString(blocker.get("name"), "资料") + "，" + Objects.toString(blocker.get("detail"), "请先处理"));
        }
        jdbc.update("UPDATE billing_supplement_settlement SET status='ARCHIVED',archived_by=?,archived_time=NOW(),updated_by=? WHERE id=?",operator,operator,id);
        return detail(id);
    }

    public Map<String,Object> detail(long id) {
        Map<String,Object> result = new LinkedHashMap<>(required(id));
        result.put("bill", result.get("generated_bill_id") == null ? null : jdbc.queryForList("SELECT * FROM billing_bill WHERE id=?", result.get("generated_bill_id")).stream().findFirst().orElse(null));
        result.put("period", result.get("source_period_id") == null ? null : jdbc.queryForList("SELECT * FROM billing_period WHERE id=?", result.get("source_period_id")).stream().findFirst().orElse(null));
        Map<String,Object> archiveReadiness = archiveReadiness(result);
        result.putAll(archiveReadiness);
        return result;
    }
    private Map<String,Object> required(long id){Map<String,Object> row=one("SELECT * FROM billing_supplement_settlement WHERE id=?",id,"补充结算单不存在");assertOrg(number(row.get("org_id"),"orgId"));return row;}
    private Map<String,Object> archiveReadiness(Map<String,Object> supplement) {
        List<Map<String,Object>> checks = new ArrayList<>();
        Long billId = supplement.get("generated_bill_id") == null ? null : number(supplement.get("generated_bill_id"), "billId");
        if (billId == null) {
            checks.add(check("补充账单", false, "尚未生成补充账单", "查看账单"));
            return Map.of("archiveReady", false, "archiveChecks", checks);
        }
        Map<String,Object> bill = one("SELECT * FROM billing_bill WHERE id=?", billId, "补充账单不存在");
        boolean paid = number(bill.get("pay_status"), "payStatus") == 1 && decimal(bill.get("outstanding_amount")).signum() == 0;
        checks.add(check("账单结清", paid, paid ? "账单已全额结清" : "账单尚未结清，不能归档", "查看账单"));

        Long paymentCount = jdbc.queryForObject("SELECT COUNT(*) FROM billing_payment WHERE bill_id=? AND payment_status='SUCCESS'", Long.class, billId);
        Long unmatchedCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM billing_payment p
                WHERE p.bill_id=? AND p.payment_status='SUCCESS'
                  AND NOT EXISTS(SELECT 1 FROM billing_bank_match m WHERE m.payment_id=p.id AND m.match_status='MATCHED')
                """, Long.class, billId);
        boolean reconciled = paymentCount != null && paymentCount > 0 && (unmatchedCount == null || unmatchedCount == 0);
        String reconciliationDetail = paymentCount == null || paymentCount == 0
                ? "尚未登记有效收款流水" : reconciled ? "全部收款流水已完成银行勾兑" : "存在 " + unmatchedCount + " 笔收款未完成银行勾兑";
        checks.add(check("银行流水勾兑", reconciled, reconciliationDetail, "收款与对账"));

        Long missingAr = jdbc.queryForObject("""
                SELECT COUNT(*) WHERE NOT EXISTS(
                    SELECT 1 FROM billing_voucher
                    WHERE bill_id=? AND voucher_type='AR_RECEIVABLE' AND voucher_status IN ('POSTED','EXPORTED')
                )
                """, Long.class, billId);
        Long missingReceipt = jdbc.queryForObject("""
                SELECT COUNT(*) FROM billing_payment p
                WHERE p.bill_id=? AND p.payment_status='SUCCESS'
                  AND NOT EXISTS(SELECT 1 FROM billing_voucher v WHERE v.payment_id=p.id AND v.voucher_type='RECEIPT' AND v.voucher_status IN ('POSTED','EXPORTED'))
                """, Long.class, billId);
        boolean vouchersReady = (missingAr == null || missingAr == 0) && (missingReceipt == null || missingReceipt == 0);
        String voucherDetail = vouchersReady ? "应收与收款凭证均已确认入账"
                : "缺少 " + (missingAr == null ? 0 : missingAr) + " 张应收凭证、" + (missingReceipt == null ? 0 : missingReceipt) + " 张收款凭证";
        checks.add(check("正式财务凭证", vouchersReady, voucherDetail, "财务凭证"));
        boolean ready = checks.stream().allMatch(item -> Boolean.TRUE.equals(item.get("passed")));
        return Map.of("archiveReady", ready, "archiveChecks", checks);
    }
    private Map<String,Object> check(String name, boolean passed, String detail, String target) { Map<String,Object> item = new LinkedHashMap<>(); item.put("name", name); item.put("passed", passed); item.put("detail", detail); item.put("target", target); return item; }
    private java.math.BigDecimal decimal(Object value){return value == null ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(String.valueOf(value));}
    private Map<String,Object> one(String sql,Object arg,String message){List<Map<String,Object>> rows=jdbc.queryForList(sql,arg);if(rows.isEmpty())throw new BusinessException(404,message);return rows.get(0);}
    private void assertOrg(long orgId){if(!access.hasOrgAccess(orgId))throw new BusinessException(403,"没有该园区结算权限");}
    private void assertMonthNotArchived(long periodId,String cycle){Long count=jdbc.queryForObject("SELECT COUNT(*) FROM billing_settlement_archive WHERE period_id=? AND archive_status='ARCHIVED'",Long.class,periodId);if(count!=null&&count>0)throw new BusinessException(cycle+" 已完成月度最终归档，禁止新增或生成补充账单");}
    private long number(Object value,String name){if(value==null)throw new BusinessException(name+" 不能为空");return Long.parseLong(String.valueOf(value));}
    private String text(Object value,String fallback){String s=value==null?null:String.valueOf(value).trim();return s==null||s.isBlank()?fallback:s;}
    private LocalDate date(Object value,String name){try{return LocalDate.parse(String.valueOf(value));}catch(Exception e){throw new BusinessException(name+" 格式错误");}}
    private int integer(Object value,int fallback){try{return Integer.parseInt(Objects.toString(value,String.valueOf(fallback)));}catch(Exception e){return fallback;}}
    private String shortMessage(RuntimeException e){String s=e.getMessage();return s==null?e.getClass().getSimpleName():s.substring(0,Math.min(s.length(),1400));}
}
