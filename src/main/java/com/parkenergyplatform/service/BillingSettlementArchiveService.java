package com.parkenergyplatform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.parkenergyplatform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BillingSettlementArchiveService {
    private final JdbcTemplate jdbc;
    private final BusinessDataAccessService access;
    private final BillingPeriodService periods;
    private final ObjectMapper mapper;

    public BillingSettlementArchiveService(JdbcTemplate jdbc, BusinessDataAccessService access, BillingPeriodService periods, ObjectMapper mapper) { this.jdbc = jdbc; this.access = access; this.periods = periods; this.mapper = mapper; }

    public List<Map<String, Object>> list(Long orgId, String periodCode) {
        List<Object> args = new java.util.ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (orgId != null) { if (!access.hasOrgAccess(orgId)) throw new BusinessException(403, "没有该园区归档权限"); where.append(" AND a.org_id=?"); args.add(orgId); }
        if (periodCode != null && !periodCode.isBlank()) { where.append(" AND a.period_code=?"); args.add(periodCode); }
        where.append(access.scopeSql("a.org_id", args));
        return jdbc.queryForList("SELECT a.*,o.org_name FROM billing_settlement_archive a LEFT JOIN dev_org o ON o.id=a.org_id" + where + " ORDER BY a.period_code DESC,a.id DESC", args.toArray());
    }

    public Map<String,Object> monthlyPreview(long periodId) {
        Map<String,Object> period = period(periodId);
        long orgId = number(period.get("org_id"), "orgId");
        assertOrg(orgId);
        String code = String.valueOf(period.get("period_code"));
        Map<String,Object> readiness = periods.check(periodId);
        Map<String,Object> summary = jdbc.queryForMap("""
                SELECT COUNT(*) AS billCount,
                       COALESCE(SUM(CASE WHEN b.bill_status='ISSUED' THEN b.total_amount ELSE 0 END),0) AS receivableAmount,
                       COALESCE(SUM(b.paid_amount),0) AS paidAmount,
                       COALESCE(SUM(CASE WHEN b.bill_status='ISSUED' THEN b.outstanding_amount ELSE 0 END),0) AS outstandingAmount,
                       SUM(CASE WHEN b.bill_type='SUPPLEMENT' THEN 1 ELSE 0 END) AS supplementCount,
                       SUM(CASE WHEN b.pay_status=1 AND b.bill_status='ISSUED' THEN 1 ELSE 0 END) AS completedBillCount
                FROM billing_bill b JOIN billing_account a ON a.id=b.account_id
                WHERE a.org_id=? AND BINARY b.bill_cycle=BINARY ?
                """, orgId, code);
        Map<String,Object> evidence = jdbc.queryForMap("""
                SELECT
                  (SELECT COUNT(*) FROM billing_payment py JOIN billing_bill b ON b.id=py.bill_id JOIN billing_account a ON a.id=b.account_id WHERE a.org_id=? AND BINARY b.bill_cycle=BINARY ? AND py.payment_status='SUCCESS') AS paymentCount,
                  (SELECT COUNT(*) FROM billing_bank_statement s WHERE s.target_period_id=?) AS statementCount,
                  (SELECT COUNT(*) FROM billing_voucher v LEFT JOIN billing_payment py ON py.id=v.payment_id JOIN billing_bill b ON b.id=COALESCE(v.bill_id,py.bill_id) JOIN billing_account a ON a.id=b.account_id WHERE a.org_id=? AND BINARY b.bill_cycle=BINARY ?) AS voucherCount,
                  (SELECT COUNT(DISTINCT i.id) FROM billing_invoice i JOIN billing_invoice_bill ib ON ib.invoice_id=i.id JOIN billing_bill b ON b.id=ib.bill_id JOIN billing_account a ON a.id=b.account_id WHERE a.org_id=? AND BINARY b.bill_cycle=BINARY ?) AS invoiceCount
                """, orgId, code, periodId, orgId, code, orgId, code);
        List<Map<String,Object>> bills = jdbc.queryForList("""
                SELECT b.id,b.bill_no,b.bill_type,b.bill_status,b.pay_status,b.total_amount,b.paid_amount,b.outstanding_amount,
                       a.account_name,b.tenant_name_snapshot,c.contract_no,c.contract_name
                FROM billing_bill b JOIN billing_account a ON a.id=b.account_id
                LEFT JOIN leasing_contract c ON c.id=b.contract_id
                WHERE a.org_id=? AND BINARY b.bill_cycle=BINARY ? ORDER BY b.id
                """, orgId, code);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("period", period);
        result.put("summary", summary);
        result.put("evidence", evidence);
        result.put("checks", readiness.get("checks"));
        result.put("closeable", readiness.get("closeable"));
        result.put("bills", bills);
        result.put("warning", "最终归档后该月账单、收款、流水、凭证、发票和补充结算将永久锁定。");
        return result;
    }

    @Transactional
    public Map<String,Object> finalizeMonth(long periodId, Map<String,Object> body, String operator) {
        if (!Boolean.TRUE.equals(body.get("reviewed"))) throw new BusinessException("请先核对本月全部关账数据");
        if (!Boolean.TRUE.equals(body.get("lockConfirmed"))) throw new BusinessException("请确认已知晓最终归档会永久锁定该月数据");
        Map<String,Object> period = period(periodId);
        assertOrg(number(period.get("org_id"), "orgId"));
        List<Map<String,Object>> existing = jdbc.queryForList("SELECT * FROM billing_settlement_archive WHERE period_id=? AND archive_status='ARCHIVED'", periodId);
        if (!existing.isEmpty()) return detail(number(existing.get(0).get("id"), "archiveId"));
        Map<String,Object> preview = monthlyPreview(periodId);
        if (!Boolean.TRUE.equals(preview.get("closeable"))) throw new BusinessException("本月最终归档检查未全部通过，请处理阻断项后重试");
        periods.close(periodId, operator);
        Map<String,Object> prepared = prepare(periodId, operator);
        return archive(number(prepared.get("id"), "archiveId"), operator);
    }

    @Transactional
    public Map<String, Object> prepare(long periodId, String operator) {
        Map<String, Object> period = period(periodId);
        long orgId = number(period.get("org_id"), "orgId");
        assertOrg(orgId);
        String code = String.valueOf(period.get("period_code"));
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT * FROM billing_settlement_archive WHERE org_id=? AND period_code=?", orgId, code);
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT COUNT(*) bill_count,
                       COALESCE(SUM(CASE WHEN b.bill_status='ISSUED' THEN b.total_amount ELSE 0 END),0) issued_amount,
                       COALESCE(SUM(b.paid_amount),0) paid_amount,
                       COALESCE(SUM(CASE WHEN b.bill_status='ISSUED' THEN b.outstanding_amount ELSE 0 END),0) outstanding_amount
                FROM billing_bill b JOIN billing_account ba ON ba.id=b.account_id
                WHERE ba.org_id=? AND b.bill_cycle=?
                """, orgId, code);
        Map<String, Object> finance = jdbc.queryForMap("""
                SELECT
                  (SELECT COUNT(*) FROM billing_bank_statement s WHERE s.target_period_id=?) statement_count,
                  (SELECT COUNT(*) FROM billing_bank_statement s WHERE s.target_period_id=? AND s.statement_status IN ('MATCHED','PARTIAL')) matched_statement_count,
                  (SELECT COUNT(*) FROM billing_bank_statement s WHERE s.target_period_id=? AND s.statement_status='DIFFERENCE') difference_count,
                  (SELECT COUNT(*) FROM billing_voucher v
                     LEFT JOIN billing_payment p ON p.id=v.payment_id
                     JOIN billing_bill b ON b.id=COALESCE(v.bill_id,p.bill_id)
                    WHERE v.org_id=? AND b.bill_cycle=? AND v.voucher_status IN ('POSTED','EXPORTED')) voucher_count
                """, periodId, periodId, periodId, orgId, code);
        List<Long> batchIds = jdbc.queryForList("SELECT id FROM billing_batch WHERE org_id=? AND bill_cycle=? ORDER BY id DESC LIMIT 1", Long.class, orgId, code);
        Long batchId = batchIds.isEmpty() ? null : batchIds.get(0);
        Long adjustmentCount = jdbc.queryForObject("SELECT COUNT(*) FROM billing_adjustment j JOIN billing_bill b ON b.id=j.bill_id JOIN billing_account ba ON ba.id=b.account_id WHERE ba.org_id=? AND BINARY b.bill_cycle=BINARY ?", Long.class, orgId, code);
        String archiveNo = "SETTLE-" + code.replace("-", "") + "-" + orgId;
        if (!existing.isEmpty()) {
            long existingId = number(existing.get(0).get("id"), "archiveId");
            jdbc.update("""
                    UPDATE billing_settlement_archive SET batch_id=?,bill_count=?,issued_amount=?,paid_amount=?,outstanding_amount=?,
                      statement_count=?,matched_statement_count=?,difference_count=?,voucher_count=?,adjustment_count=?,
                      archive_status='READY',archive_summary=?,updated_by=? WHERE id=? AND archive_status<>'ARCHIVED'
                    """, batchId, totals.get("bill_count"), totals.get("issued_amount"), totals.get("paid_amount"), totals.get("outstanding_amount"),
                    finance.get("statement_count"), finance.get("matched_statement_count"), finance.get("difference_count"), finance.get("voucher_count"), adjustmentCount,
                    "账期结算包已刷新，待正式归档", operator, existingId);
            return detail(existingId);
        }
        jdbc.update("""
                INSERT INTO billing_settlement_archive
                  (archive_no,period_id,org_id,batch_id,period_code,archive_status,
                   bill_count,issued_amount,paid_amount,outstanding_amount,
                   statement_count,matched_statement_count,difference_count,voucher_count,adjustment_count,
                   archive_summary,created_by,updated_by)
                VALUES (?,?,?,?,?,'READY',?,?,?,?,?,?,?,?,?,?,?,?)
                """, archiveNo, periodId, orgId, batchId, code, totals.get("bill_count"), totals.get("issued_amount"), totals.get("paid_amount"), totals.get("outstanding_amount"),
                finance.get("statement_count"), finance.get("matched_statement_count"), finance.get("difference_count"), finance.get("voucher_count"), adjustmentCount,
                "账期结算包已生成，待关账归档", operator, operator);
        Long id = jdbc.queryForObject("SELECT id FROM billing_settlement_archive WHERE archive_no=?", Long.class, archiveNo);
        return detail(id == null ? 0 : id);
    }

    @Transactional
    public Map<String, Object> archive(long id, String operator) {
        Map<String, Object> row = required(id);
        assertOrg(number(row.get("org_id"), "orgId"));
        if (!"READY".equalsIgnoreCase(String.valueOf(row.get("archive_status")))) throw new BusinessException("只有准备完成的结算包可以归档");
        Map<String, Object> period = period(number(row.get("period_id"), "periodId"));
        if (!"CLOSED".equalsIgnoreCase(String.valueOf(period.get("status")))) throw new BusinessException("账期尚未关账，不能归档结算包");
        Map<String, Object> readiness = periods.check(number(row.get("period_id"), "periodId"));
        if (!Boolean.TRUE.equals(readiness.get("closeable"))) throw new BusinessException("归档前复核未通过，结算资料已经发生变化");
        jdbc.update("UPDATE billing_settlement_archive SET archive_status='ARCHIVED',archived_by=?,archived_time=NOW(),updated_by=? WHERE id=?", operator, operator, id);
        writeSnapshot(id, operator);
        return detail(id);
    }

    public Map<String, Object> detail(long id) {
        Map<String, Object> row = required(id);
        assertOrg(number(row.get("org_id"), "orgId"));
        if ("ARCHIVED".equalsIgnoreCase(String.valueOf(row.get("archive_status")))) {
            List<Map<String,Object>> snapshots = jdbc.queryForList("SELECT * FROM billing_settlement_archive_snapshot WHERE archive_id=? ORDER BY snapshot_version DESC LIMIT 1", id);
            if (!snapshots.isEmpty()) return snapshotDetail(row, snapshots.get(0));
        }
        Map<String,Object> result = liveDetail(row);
        result.put("snapshotVerified", false);
        return result;
    }

    @Transactional
    public Map<String,Object> backfillMissingSnapshots(String operator) {
        List<Long> ids = jdbc.queryForList("""
                SELECT a.id FROM billing_settlement_archive a
                WHERE a.archive_status='ARCHIVED'
                  AND NOT EXISTS(SELECT 1 FROM billing_settlement_archive_snapshot s WHERE s.archive_id=a.id)
                ORDER BY a.id
                """, Long.class);
        int created = 0;
        for (Long id : ids) {
            Map<String,Object> row = required(id);
            if (!access.hasOrgAccess(number(row.get("org_id"), "orgId"))) continue;
            writeSnapshot(id, operator);
            created++;
        }
        return Map.of("created", created, "remaining", Math.max(0, ids.size() - created));
    }

    private Map<String,Object> liveDetail(Map<String,Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("period", period(number(row.get("period_id"), "periodId")));
        result.put("bills", jdbc.queryForList("""
                SELECT b.id,b.bill_no,b.bill_status,b.pay_status,b.total_amount,b.paid_amount,b.outstanding_amount,
                       b.contract_id,c.contract_no,c.contract_name,a.account_name,b.tenant_name_snapshot
                FROM billing_bill b JOIN billing_account a ON a.id=b.account_id
                LEFT JOIN leasing_contract c ON c.id=b.contract_id
                WHERE a.org_id=? AND b.bill_cycle=? ORDER BY b.id
                """, row.get("org_id"), row.get("period_code")));
        result.put("payments", jdbc.queryForList("""
                SELECT p.*,b.bill_no,b.tenant_name_snapshot,
                       EXISTS(SELECT 1 FROM billing_bank_match m WHERE m.payment_id=p.id AND m.match_status='MATCHED') AS reconciled
                FROM billing_payment p JOIN billing_bill b ON b.id=p.bill_id JOIN billing_account a ON a.id=b.account_id
                WHERE a.org_id=? AND b.bill_cycle=? ORDER BY p.pay_time,p.id
                """, row.get("org_id"), row.get("period_code")));
        result.put("statements", jdbc.queryForList("""
                SELECT DISTINCT s.id,s.external_transaction_no,s.payer_name,s.statement_time,s.amount,s.matched_amount,s.statement_status,
                       (SELECT MAX(mx.id) FROM billing_bank_match mx WHERE mx.statement_id=s.id AND mx.match_status='MATCHED') AS active_match_id
                FROM billing_bank_statement s
                WHERE s.target_period_id=? ORDER BY s.statement_time,s.id
                """, row.get("period_id")));
        result.put("vouchers", jdbc.queryForList("""
                SELECT v.id,v.voucher_no,v.voucher_type,v.voucher_status,v.voucher_date,v.total_debit,v.total_credit,
                       b.bill_no,p.payment_no
                FROM billing_voucher v
                LEFT JOIN billing_payment p ON p.id=v.payment_id
                LEFT JOIN billing_bill b ON b.id=COALESCE(v.bill_id,p.bill_id)
                WHERE v.org_id=? AND b.bill_cycle=? ORDER BY v.id
                """, row.get("org_id"), row.get("period_code")));
        result.put("adjustments", jdbc.queryForList("""
                SELECT j.*,b.bill_no FROM billing_adjustment j JOIN billing_bill b ON b.id=j.bill_id JOIN billing_account a ON a.id=b.account_id
                WHERE a.org_id=? AND b.bill_cycle=? ORDER BY j.id
                """, row.get("org_id"), row.get("period_code")));
        result.put("invoices", jdbc.queryForList("""
                SELECT DISTINCT i.* FROM billing_invoice i JOIN billing_invoice_bill ib ON ib.invoice_id=i.id
                JOIN billing_bill b ON b.id=ib.bill_id JOIN billing_account a ON a.id=b.account_id
                WHERE a.org_id=? AND BINARY b.bill_cycle=BINARY ? ORDER BY i.id
                """,row.get("org_id"),row.get("period_code")));
        result.put("invoiceBills", jdbc.queryForList("""
                SELECT ib.*,b.bill_no FROM billing_invoice_bill ib JOIN billing_invoice i ON i.id=ib.invoice_id
                JOIN billing_bill b ON b.id=ib.bill_id JOIN billing_account a ON a.id=b.account_id
                WHERE a.org_id=? AND BINARY b.bill_cycle=BINARY ? ORDER BY ib.invoice_id,ib.id
                """,row.get("org_id"),row.get("period_code")));
        result.put("bankImportBatches",jdbc.queryForList("SELECT * FROM billing_bank_import_batch WHERE target_period_id=? ORDER BY id",row.get("period_id")));
        result.put("events", jdbc.queryForList("""
                SELECT e.*,b.bill_no FROM billing_bill_event e JOIN billing_bill b ON b.id=e.bill_id JOIN billing_account a ON a.id=b.account_id
                WHERE a.org_id=? AND b.bill_cycle=? ORDER BY e.event_time DESC,e.id DESC
                """, row.get("org_id"), row.get("period_code")));
        result.put("billDetails", jdbc.queryForList("""
                SELECT d.*,dv.device_name,dv.device_sn,pd.point_name,pd.unit,tp.plan_code,tp.plan_name
                FROM billing_bill_detail d JOIN billing_bill b ON b.id=d.bill_id JOIN billing_account ba ON ba.id=b.account_id
                LEFT JOIN dev_device dv ON dv.id=d.device_id
                LEFT JOIN dev_point_definition pd ON pd.device_type_id=d.device_type_id AND pd.point_code=d.point_code
                LEFT JOIN billing_tariff_plan tp ON tp.id=d.tariff_plan_id
                WHERE ba.org_id=? AND BINARY b.bill_cycle=BINARY ? ORDER BY d.bill_id,d.id
                """, row.get("org_id"), row.get("period_code")));
        result.put("bankMatches", jdbc.queryForList("""
                SELECT m.*,s.external_transaction_no,b.bill_no,p.payment_no
                FROM billing_bank_match m JOIN billing_bank_statement s ON s.id=m.statement_id
                JOIN billing_bill b ON b.id=m.bill_id JOIN billing_account ba ON ba.id=b.account_id
                LEFT JOIN billing_payment p ON p.id=m.payment_id
                WHERE ba.org_id=? AND BINARY b.bill_cycle=BINARY ? ORDER BY m.id
                """, row.get("org_id"), row.get("period_code")));
        result.put("voucherLines", jdbc.queryForList("""
                SELECT l.* FROM billing_voucher_line l JOIN billing_voucher v ON v.id=l.voucher_id
                LEFT JOIN billing_payment p ON p.id=v.payment_id
                JOIN billing_bill b ON b.id=COALESCE(v.bill_id,p.bill_id)
                JOIN billing_account ba ON ba.id=b.account_id
                WHERE ba.org_id=? AND BINARY b.bill_cycle=BINARY ? ORDER BY l.voucher_id,l.line_no
                """, row.get("org_id"), row.get("period_code")));
        result.put("contracts", contractSnapshots(row.get("org_id"), row.get("period_code")));
        List<Map<String,Object>> batches = jdbc.queryForList("SELECT * FROM billing_batch WHERE org_id=? AND BINARY bill_cycle=BINARY ? ORDER BY id", row.get("org_id"), row.get("period_code"));
        result.put("batches", batches);
        return result;
    }

    private List<Map<String,Object>> contractSnapshots(Object orgId, Object periodCode) {
        List<Map<String,Object>> contracts = jdbc.queryForList("""
                SELECT DISTINCT c.*,t.tenant_name,t.contact_name,t.contact_phone
                FROM leasing_contract c JOIN billing_bill b ON b.contract_id=c.id JOIN billing_account ba ON ba.id=b.account_id
                LEFT JOIN crm_tenant t ON t.id=c.tenant_id
                WHERE ba.org_id=? AND BINARY b.bill_cycle=BINARY ? ORDER BY c.id
                """, orgId, periodCode);
        for (Map<String,Object> contract : contracts) {
            Object contractId = contract.get("id");
            contract.put("spaces", jdbc.queryForList("SELECT cs.*,s.space_code,s.space_name FROM leasing_contract_space cs LEFT JOIN park_space s ON s.id=cs.space_id WHERE cs.contract_id=? ORDER BY cs.id", contractId));
            contract.put("meters", jdbc.queryForList("SELECT cm.*,d.device_name,d.device_sn,d.device_type_id FROM leasing_contract_meter cm LEFT JOIN dev_device d ON d.id=cm.device_id WHERE cm.contract_id=? ORDER BY cm.id", contractId));
            contract.put("rules", jdbc.queryForList("""
                    SELECT r.* FROM billing_rule r JOIN billing_account a ON a.id=r.account_id
                    WHERE a.contract_id=? ORDER BY r.id
                    """, contractId));
        }
        return contracts;
    }

    private void writeSnapshot(long archiveId, String operator) {
        Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM billing_settlement_archive_snapshot WHERE archive_id=?", Long.class, archiveId);
        if (exists != null && exists > 0) return;
        Map<String,Object> row = required(archiveId);
        Map<String,Object> payload = liveDetail(row);
        try {
            String canonical = canonicalJson(payload);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
            int count = payload.values().stream().filter(List.class::isInstance).map(List.class::cast).mapToInt(List::size).sum();
            jdbc.update("INSERT INTO billing_settlement_archive_snapshot (archive_id,snapshot_version,schema_version,snapshot_payload,snapshot_hash,record_count,generated_by,generated_time) VALUES (?,1,'2.0',?,?,?,?,?)",
                    archiveId, canonical, hash, count, operator, java.sql.Timestamp.valueOf(LocalDateTime.now()));
            Long snapshotId = jdbc.queryForObject("SELECT id FROM billing_settlement_archive_snapshot WHERE archive_id=? AND snapshot_version=1", Long.class, archiveId);
            if (snapshotId == null) throw new BusinessException("结算档案快照保存失败");
            normalizeStoredHash(snapshotId, archiveId, operator);
        } catch (Exception exception) {
            throw new BusinessException("结算档案快照生成失败：" + exception.getMessage());
        }
    }

    private Map<String,Object> snapshotDetail(Map<String,Object> archive, Map<String,Object> snapshot) {
        try {
            String payloadText = String.valueOf(snapshot.get("snapshot_payload"));
            Map<String,Object> payload = mapper.readValue(payloadText, new TypeReference<>() {});
            String actualHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalJson(payload).getBytes(StandardCharsets.UTF_8)));
            String expectedHash = String.valueOf(snapshot.get("snapshot_hash"));
            if (!actualHash.equalsIgnoreCase(expectedHash)) throw new BusinessException("结算档案完整性校验失败，禁止继续使用该档案");
            Map<String,Object> result = new LinkedHashMap<>(archive);
            result.putAll(payload);
            result.put("snapshot", snapshot);
            result.put("snapshotHash", expectedHash);
            result.put("snapshotVersion", snapshot.get("snapshot_version"));
            result.put("snapshotVerified", true);
            return result;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("结算档案快照读取失败：" + exception.getMessage());
        }
    }

    private String canonicalJson(Object value) throws Exception {
        return mapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true).writeValueAsString(value);
    }

    private void normalizeStoredHash(long snapshotId,long archiveId,String operator) {
        try {
            Map<String,Object> snapshot = jdbc.queryForMap("SELECT snapshot_payload FROM billing_settlement_archive_snapshot WHERE id=?", snapshotId);
            Map<String,Object> payload = mapper.readValue(String.valueOf(snapshot.get("snapshot_payload")), new TypeReference<>() {});
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalJson(payload).getBytes(StandardCharsets.UTF_8)));
            jdbc.update("UPDATE billing_settlement_archive_snapshot SET snapshot_hash=? WHERE id=?", hash, snapshotId);
            jdbc.update("UPDATE billing_settlement_archive SET snapshot_hash=?,updated_by=? WHERE id=?", hash, operator, archiveId);
        } catch (Exception exception) {
            throw new BusinessException("结算档案摘要固化失败：" + exception.getMessage());
        }
    }

    private Map<String, Object> required(long id) { List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM billing_settlement_archive WHERE id=?",id); if(rows.isEmpty()) throw new BusinessException(404,"结算包不存在"); return rows.get(0); }
    private Map<String, Object> period(long id) { List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM billing_period WHERE id=?",id); if(rows.isEmpty()) throw new BusinessException(404,"账期不存在"); return rows.get(0); }
    private void assertOrg(long id) { if(!access.hasOrgAccess(id)) throw new BusinessException(403,"没有该园区归档权限"); }
    private long number(Object value,String name){try{return Long.parseLong(String.valueOf(value));}catch(Exception e){throw new BusinessException(name+"必须为数字");}}
}
