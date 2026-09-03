package com.parkenergyplatform.service;

import com.parkenergyplatform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Central write barrier for accounting periods. */
@Service
public class BillingPeriodGuardService {
    private final JdbcTemplate jdbc;

    public BillingPeriodGuardService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void assertBillWritable(long billId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.status AS period_status, p.period_code,
                       EXISTS(SELECT 1 FROM billing_settlement_archive a WHERE a.period_id=p.id AND a.archive_status='ARCHIVED') AS archived
                FROM billing_bill b
                JOIN billing_account ba ON ba.id=b.account_id
                LEFT JOIN billing_period p ON p.org_id=ba.org_id AND BINARY p.period_code=BINARY b.bill_cycle
                WHERE b.id=?
                """, billId);
        if (rows.isEmpty()) throw new BusinessException(404, "账单不存在: " + billId);
        Map<String, Object> row = rows.get(0);
        String periodCode = Objects.toString(row.get("period_code"), "当前");
        if (number(row.get("archived")) > 0) {
            throw new BusinessException(periodCode + " 账期已归档，禁止修改账单、收款、对账和凭证");
        }
    }

    public void assertPaymentWritable(long paymentId) {
        List<Long> rows = jdbc.queryForList("SELECT bill_id FROM billing_payment WHERE id=?", Long.class, paymentId);
        if (rows.isEmpty()) throw new BusinessException(404, "收款记录不存在: " + paymentId);
        assertBillWritable(rows.get(0));
    }

    public void assertAccountCycleWritable(long accountId, String billCycle) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.status AS period_status,
                       EXISTS(SELECT 1 FROM billing_settlement_archive a WHERE a.period_id=p.id AND a.archive_status='ARCHIVED') AS archived
                FROM billing_account ba
                LEFT JOIN billing_period p ON p.org_id=ba.org_id AND BINARY p.period_code=BINARY ?
                WHERE ba.id=?
                """, billCycle, accountId);
        if (rows.isEmpty()) throw new BusinessException(404, "计费账户不存在: " + accountId);
        if (number(rows.get(0).get("archived")) > 0) throw new BusinessException(billCycle + " 账期已归档，禁止再次出账");
    }

    public void assertOrgCycleWritable(long orgId, String billCycle) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.status AS period_status,
                       EXISTS(SELECT 1 FROM billing_settlement_archive a WHERE a.period_id=p.id AND a.archive_status='ARCHIVED') AS archived
                FROM billing_period p
                WHERE p.org_id=? AND BINARY p.period_code=BINARY ?
                """, orgId, billCycle);
        if (rows.isEmpty()) return;
        if (number(rows.get(0).get("archived")) > 0) throw new BusinessException(billCycle + " 账期已归档，禁止修改出账批次");
    }

    public void assertPeriodWritable(long periodId) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
                SELECT p.period_code,p.status AS period_status,
                       EXISTS(SELECT 1 FROM billing_settlement_archive a WHERE a.period_id=p.id AND a.archive_status='ARCHIVED') AS archived
                FROM billing_period p WHERE p.id=?
                """, periodId);
        if (rows.isEmpty()) throw new BusinessException(404, "账期不存在: " + periodId);
        Map<String,Object> row = rows.get(0);
        String code = Objects.toString(row.get("period_code"), "当前");
        if (number(row.get("archived")) > 0) throw new BusinessException(code + " 账期已归档，禁止录入或修改流水");
    }

    private long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(Objects.toString(value, "0")); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
