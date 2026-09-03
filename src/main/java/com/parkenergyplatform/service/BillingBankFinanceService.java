package com.parkenergyplatform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.common.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.apache.poi.ss.usermodel.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HexFormat;
import java.util.Locale;

/** 外部流水、收款勾兑、财务凭证和归档的最小闭环。 */
@Service
public class BillingBankFinanceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final BusinessDataAccessService access;
    private final BillingService billing;
    private final BillingPeriodGuardService periodGuard;

    public BillingBankFinanceService(JdbcTemplate jdbc, ObjectMapper mapper, BusinessDataAccessService access,
                                     BillingService billing, BillingPeriodGuardService periodGuard) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.access = access;
        this.billing = billing;
        this.periodGuard = periodGuard;
    }

    @Transactional
    public Map<String, Object> importStatements(Map<String, Object> body, String operator) {
        long orgId = number(body.get("orgId"), "orgId");
        assertOrg(orgId);
        String channel = textOr(body.get("sourceChannel"), "BANK");
        String batchNo = textOr(body.get("importBatchNo"), "IMP-" + System.currentTimeMillis());
        Long bodyPeriodId = longOrNull(body.get("targetPeriodId"));
        Long importBatchId = longOrNull(body.get("importBatchId"));
        if (bodyPeriodId != null) periodGuard.assertPeriodWritable(bodyPeriodId);
        Object rawRecords = body.get("records");
        if (!(rawRecords instanceof List<?> records) || records.isEmpty()) throw new BusinessException("流水记录不能为空");
        int inserted = 0, duplicated = 0;
        for (Object item : records) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            raw.forEach((key, value) -> row.put(String.valueOf(key), value));
            String externalNo = required(first(row, "externalTransactionNo", "transactionNo", "bankReference"), "externalTransactionNo");
            Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM billing_bank_statement WHERE source_channel=? AND external_transaction_no=?", Long.class, channel, externalNo);
            if (exists != null && exists > 0) { duplicated++; continue; }
            LocalDateTime statementTime = dateTime(first(row, "statementTime", "payTime"));
            BigDecimal amount = amount(first(row, "amount", "receivedAmount"));
            Long targetPeriodId = longOrNull(first(row, "targetPeriodId"));
            if (targetPeriodId == null) targetPeriodId = bodyPeriodId;
            if (targetPeriodId != null) periodGuard.assertPeriodWritable(targetPeriodId);
            jdbc.update("""
                    INSERT INTO billing_bank_statement
                      (org_id,target_period_id,source_channel,external_transaction_no,statement_time,payer_name,payer_account,payee_name,amount,currency,narrative,raw_payload,statement_status,matched_amount,import_batch_no,import_batch_id,source_row_no,imported_by)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'IMPORTED',0,?,?,?,?)
                    """, orgId, targetPeriodId, channel, externalNo, Timestamp.valueOf(statementTime), text(first(row, "payerName", "accountName")),
                    text(first(row, "payerAccount", "payerNo")), text(first(row, "payeeName")), amount,
                    textOr(first(row, "currency"), "CNY"), text(first(row, "narrative", "remark")), json(row), batchNo, importBatchId,
                    longOrNull(first(row,"sourceRowNo")), operator);
            inserted++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orgId", orgId); result.put("importBatchNo", batchNo); result.put("inserted", inserted); result.put("duplicated", duplicated);
        return result;
    }

    @Transactional
    public Map<String,Object> importStatementFile(long orgId,Long targetPeriodId,String sourceChannel,MultipartFile file,String operator) {
        assertOrg(orgId);
        if (targetPeriodId != null) periodGuard.assertPeriodWritable(targetPeriodId);
        if (file == null || file.isEmpty()) throw new BusinessException("请选择银行流水文件");
        String filename = textOr(file.getOriginalFilename(), "bank-statement");
        String lower = filename.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".csv") && !lower.endsWith(".xlsx")) throw new BusinessException("仅支持 CSV 或 XLSX 银行流水文件");
        try {
            byte[] bytes = file.getBytes();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            List<Map<String,Object>> duplicate = jdbc.queryForList("SELECT * FROM billing_bank_import_batch WHERE org_id=? AND file_sha256=?", orgId, hash);
            if (!duplicate.isEmpty()) throw new BusinessException(409, "该文件已导入，批次号：" + duplicate.get(0).get("batch_no"));
            String batchNo = "BANKIMP" + LocalDateTime.now().toString().replaceAll("[^0-9]", "").substring(0,14) + orgId;
            jdbc.update("""
                    INSERT INTO billing_bank_import_batch
                      (batch_no,org_id,target_period_id,source_channel,original_filename,file_sha256,import_status,imported_by)
                    VALUES (?,?,?,?,?,?,'PARSING',?)
                    """, batchNo,orgId,targetPeriodId,textOr(sourceChannel,"BANK"),filename,hash,operator);
            Long batchId = jdbc.queryForObject("SELECT id FROM billing_bank_import_batch WHERE batch_no=?",Long.class,batchNo);
            if (batchId == null) throw new BusinessException("银行导入批次创建失败");
            List<Map<String,Object>> records = lower.endsWith(".xlsx") ? xlsxRecords(bytes) : csvRecords(bytes);
            int success=0,duplicates=0,errors=0;
            BigDecimal total=BigDecimal.ZERO;
            List<Map<String,Object>> errorList=new ArrayList<>();
            for (int i=0;i<records.size();i++) {
                Map<String,Object> record=records.get(i);
                int rowNo=i+2;
                record.put("sourceRowNo",rowNo);
                try {
                    Map<String,Object> request=new LinkedHashMap<>();request.put("orgId",orgId);request.put("targetPeriodId",targetPeriodId);
                    request.put("sourceChannel",textOr(sourceChannel,"BANK"));request.put("importBatchNo",batchNo);request.put("importBatchId",batchId);request.put("records",List.of(record));
                    Map<String,Object> imported=importStatements(request,operator);
                    int inserted=((Number)imported.get("inserted")).intValue();
                    int duplicated=((Number)imported.get("duplicated")).intValue();
                    success+=inserted;duplicates+=duplicated;
                    if(inserted>0) total=total.add(amount(first(record,"amount","receivedAmount")));
                } catch (RuntimeException exception) {
                    errors++;
                    errorList.add(Map.of("row",rowNo,"message",rootMessage(exception)));
                }
            }
            String status=errors==0?"SUCCESS":success>0?"PARTIAL":"FAILED";
            jdbc.update("""
                    UPDATE billing_bank_import_batch SET total_rows=?,success_rows=?,duplicate_rows=?,error_rows=?,total_amount=?,import_status=?,error_summary=? WHERE id=?
                    """,records.size(),success,duplicates,errors,total,status,json(errorList),batchId);
            return importBatch(batchId);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("银行流水文件解析失败：" + rootMessage(exception));
        }
    }

    public PageResult<Map<String,Object>> importBatches(Map<String,String> params) {
        int page=positive(params.get("pageNum"),1),size=Math.min(positive(params.get("pageSize"),20),100);
        List<Object> args=new ArrayList<>();StringBuilder where=new StringBuilder(" WHERE 1=1");
        String org=text(params.get("orgId"));if(org!=null){where.append(" AND b.org_id=?");args.add(org);}
        where.append(access.scopeSql("b.org_id",args));
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM billing_bank_import_batch b"+where,Long.class,args.toArray());
        List<Object> pageArgs=new ArrayList<>(args);pageArgs.add(size);pageArgs.add((page-1)*size);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT b.*,o.org_name,p.period_code FROM billing_bank_import_batch b LEFT JOIN dev_org o ON o.id=b.org_id LEFT JOIN billing_period p ON p.id=b.target_period_id"+where+" ORDER BY b.id DESC LIMIT ? OFFSET ?",pageArgs.toArray());
        return PageResult.of(rows,total==null?0:total,page,size);
    }

    public Map<String,Object> importBatch(long id) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT b.*,o.org_name,p.period_code FROM billing_bank_import_batch b LEFT JOIN dev_org o ON o.id=b.org_id LEFT JOIN billing_period p ON p.id=b.target_period_id WHERE b.id=?",id);
        if(rows.isEmpty())throw new BusinessException(404,"导入批次不存在");
        Map<String,Object> row=rows.get(0);assertOrg(number(row.get("org_id"),"orgId"));
        row.put("statements",jdbc.queryForList("SELECT * FROM billing_bank_statement WHERE import_batch_id=? ORDER BY source_row_no,id",id));
        return row;
    }

    private List<Map<String,Object>> csvRecords(byte[] bytes) {
        String content=new String(bytes,StandardCharsets.UTF_8).replace("\uFEFF","");
        String[] lines=content.split("\\r?\\n");
        if(lines.length<2)throw new BusinessException("CSV 文件没有数据行");
        List<String> headers=csvLine(lines[0]);List<Map<String,Object>> result=new ArrayList<>();
        for(int i=1;i<lines.length;i++){if(lines[i].isBlank())continue;result.add(normalizeRecord(headers,csvLine(lines[i])));}
        return result;
    }

    private List<String> csvLine(String line) {
        List<String> cells=new ArrayList<>();StringBuilder value=new StringBuilder();boolean quoted=false;
        for(int i=0;i<line.length();i++){char ch=line.charAt(i);if(ch=='\"'){if(quoted&&i+1<line.length()&&line.charAt(i+1)=='\"'){value.append('\"');i++;}else quoted=!quoted;}else if(ch==','&&!quoted){cells.add(value.toString().trim());value.setLength(0);}else value.append(ch);}cells.add(value.toString().trim());return cells;
    }

    private List<Map<String,Object>> xlsxRecords(byte[] bytes) throws Exception {
        List<Map<String,Object>> result=new ArrayList<>();
        try(Workbook workbook=WorkbookFactory.create(new ByteArrayInputStream(bytes))){Sheet sheet=workbook.getSheetAt(0);if(sheet.getPhysicalNumberOfRows()<2)throw new BusinessException("XLSX 文件没有数据行");DataFormatter formatter=new DataFormatter();Row headerRow=sheet.getRow(sheet.getFirstRowNum());List<String> headers=new ArrayList<>();for(int i=0;i<headerRow.getLastCellNum();i++)headers.add(formatter.formatCellValue(headerRow.getCell(i)));for(int n=headerRow.getRowNum()+1;n<=sheet.getLastRowNum();n++){Row row=sheet.getRow(n);if(row==null)continue;List<String> cells=new ArrayList<>();boolean empty=true;for(int i=0;i<headers.size();i++){Cell cell=row.getCell(i);String value=cell!=null&&DateUtil.isCellDateFormatted(cell)?cell.getLocalDateTimeCellValue().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME):formatter.formatCellValue(cell).trim();cells.add(value);if(!value.isEmpty())empty=false;}if(!empty)result.add(normalizeRecord(headers,cells));}}
        return result;
    }

    private Map<String,Object> normalizeRecord(List<String> headers,List<String> cells) {
        Map<String,Object> row=new LinkedHashMap<>();for(int i=0;i<headers.size();i++){String key=headerKey(headers.get(i));String value=i<cells.size()?cells.get(i).trim():"";if(key!=null&&!value.isEmpty())row.put(key,value);}return row;
    }

    private String headerKey(String raw) {
        String key=raw==null?"":raw.trim().toLowerCase(Locale.ROOT).replace("_","").replace(" ","");
        return switch(key){case "流水号","交易流水号","银行流水号","externaltransactionno","transactionno","bankreference"->"externalTransactionNo";case "入账时间","交易时间","收款时间","statementtime","paytime"->"statementTime";case "付款方","付款方名称","payername","accountname"->"payerName";case "付款账号","付款方账号","payeraccount","payerno"->"payerAccount";case "收款方","收款方名称","payeename"->"payeeName";case "金额","收款金额","amount","receivedamount"->"amount";case "币种","currency"->"currency";case "摘要","附言","备注","narrative","remark"->"narrative";default->null;};
    }

    private String rootMessage(Throwable throwable){Throwable current=throwable;while(current.getCause()!=null&&current.getCause()!=current)current=current.getCause();String value=current.getMessage();return value==null||value.isBlank()?current.getClass().getSimpleName():value;}

    public PageResult<Map<String, Object>> statements(Map<String, String> params) {
        int page = positive(params.get("pageNum"), 1), size = Math.min(positive(params.get("pageSize"), 20), 200);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        String org = text(params.get("orgId")); if (org != null) { where.append(" AND s.org_id=?"); args.add(org); }
        String status = text(params.get("status")); if (status != null) { where.append(" AND s.statement_status=?"); args.add(status); }
        String keyword = text(params.get("keyword")); if (keyword != null) { where.append(" AND (s.external_transaction_no LIKE ? OR s.payer_name LIKE ? OR s.narrative LIKE ?)"); args.add("%"+keyword+"%"); args.add("%"+keyword+"%"); args.add("%"+keyword+"%"); }
        where.append(access.scopeSql("s.org_id", args));
        String from = " FROM billing_bank_statement s LEFT JOIN dev_org o ON o.id=s.org_id";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(size); pageArgs.add((page-1)*size);
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT s.*,o.org_name" + from + where + " ORDER BY s.statement_time DESC,s.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        return PageResult.of(rows, total == null ? 0 : total, page, size);
    }

    public Map<String, Object> statement(long id) {
        Map<String,Object> statement = statementRow(id, false); assertOrg(number(statement.get("org_id"), "orgId"));
        Map<String,Object> result = new LinkedHashMap<>(statement);
        result.put("matches", jdbc.queryForList("""
                SELECT m.*,b.bill_no,p.payment_no FROM billing_bank_match m
                JOIN billing_bill b ON b.id=m.bill_id LEFT JOIN billing_payment p ON p.id=m.payment_id
                WHERE m.statement_id=? ORDER BY m.id DESC
                """, id));
        result.put("candidates", candidates(id));
        return result;
    }

    public List<Map<String,Object>> candidates(long statementId) {
        Map<String,Object> statement = statementRow(statementId, false); assertOrg(number(statement.get("org_id"), "orgId"));
        BigDecimal remaining = amount(statement.get("amount")).subtract(amount(statement.get("matched_amount"))).max(BigDecimal.ZERO);
        return jdbc.queryForList("""
                SELECT b.id AS bill_id,b.bill_no,b.tenant_name_snapshot,b.bill_cycle,b.total_amount,b.paid_amount,b.outstanding_amount,b.due_date,
                       a.account_name
                FROM billing_bill b JOIN billing_account a ON a.id=b.account_id
                WHERE a.org_id=? AND b.bill_status='ISSUED' AND b.outstanding_amount>0
                  AND b.outstanding_amount<=?
                ORDER BY ABS(b.outstanding_amount-?),b.due_date,b.id DESC LIMIT 50
                """, statement.get("org_id"), remaining, remaining);
    }

    public List<Map<String,Object>> paymentCandidates(long statementId, String billCycle) {
        Map<String,Object> statement = statementRow(statementId, false);
        assertOrg(number(statement.get("org_id"), "orgId"));
        BigDecimal remaining = amount(statement.get("amount")).subtract(amount(statement.get("matched_amount"))).max(BigDecimal.ZERO);
        return jdbc.queryForList("""
                SELECT p.id AS payment_id,p.payment_no,p.pay_amount,p.pay_time,p.pay_way,p.transaction_no,
                       b.id AS bill_id,b.bill_no,b.bill_cycle,b.tenant_name_snapshot,a.account_name
                FROM billing_payment p
                JOIN billing_bill b ON b.id=p.bill_id
                JOIN billing_account a ON a.id=b.account_id
                WHERE a.org_id=? AND p.payment_status='SUCCESS' AND p.pay_amount<=?
                  AND (? IS NULL OR BINARY b.bill_cycle=BINARY ?)
                  AND NOT EXISTS(SELECT 1 FROM billing_bank_match m WHERE m.payment_id=p.id AND m.match_status='MATCHED')
                  AND NOT EXISTS(SELECT 1 FROM billing_bank_match m WHERE m.statement_id=? AND m.bill_id=b.id AND m.match_status='MATCHED')
                ORDER BY ABS(p.pay_amount-?),ABS(TIMESTAMPDIFF(SECOND,p.pay_time,?)),p.id DESC LIMIT 50
                """, statement.get("org_id"), remaining, text(billCycle), text(billCycle), statementId, remaining, statement.get("statement_time"));
    }

    @Transactional
    public Map<String,Object> matchPayment(long statementId, long paymentId, String operator) {
        periodGuard.assertPaymentWritable(paymentId);
        Map<String,Object> statement = statementRow(statementId, true);
        long orgId = number(statement.get("org_id"), "orgId");
        assertOrg(orgId);
        List<Map<String,Object>> rows = jdbc.queryForList("""
                SELECT p.*,b.id AS bill_id,a.org_id
                FROM billing_payment p JOIN billing_bill b ON b.id=p.bill_id JOIN billing_account a ON a.id=b.account_id
                WHERE p.id=? AND p.payment_status='SUCCESS' FOR UPDATE
                """, paymentId);
        if (rows.isEmpty()) throw new BusinessException("成功收款记录不存在");
        Map<String,Object> payment = rows.get(0);
        if (number(payment.get("org_id"), "orgId") != orgId) throw new BusinessException("银行流水与收款记录不属于同一园区");
        Long matched = jdbc.queryForObject("SELECT COUNT(*) FROM billing_bank_match WHERE payment_id=? AND match_status='MATCHED'", Long.class, paymentId);
        if (matched != null && matched > 0) throw new BusinessException("该收款记录已经完成银行流水勾兑");
        BigDecimal statementAmount = amount(statement.get("amount"));
        BigDecimal oldMatchedAmount = amount(statement.get("matched_amount"));
        BigDecimal remaining = statementAmount.subtract(oldMatchedAmount).max(BigDecimal.ZERO);
        BigDecimal paymentAmount = amount(payment.get("pay_amount"));
        if (paymentAmount.compareTo(BigDecimal.ZERO) <= 0 || paymentAmount.compareTo(remaining) > 0) {
            throw new BusinessException("银行流水剩余金额不足以关联该笔收款");
        }
        long billId = number(payment.get("bill_id"), "billId");
        bindStatementPeriod(statement, billId, operator);
        jdbc.update("INSERT INTO billing_bank_match (statement_id,bill_id,payment_id,matched_amount,match_status,match_type,matched_by,remark) VALUES (?,?,?,?,'MATCHED','MANUAL',?,?)",
                statementId, billId, paymentId, paymentAmount, operator, "关联已登记收款：" + payment.get("payment_no"));
        BigDecimal newMatchedAmount = oldMatchedAmount.add(paymentAmount).setScale(2, RoundingMode.HALF_UP);
        String status = newMatchedAmount.compareTo(statementAmount) >= 0 ? "MATCHED" : "PARTIAL";
        jdbc.update("UPDATE billing_bank_statement SET matched_amount=?,statement_status=?,updated_by=? WHERE id=?", newMatchedAmount, status, operator, statementId);
        createVoucher(Map.of("paymentId", paymentId, "voucherType", "RECEIPT"), operator);
        return statement(statementId);
    }

    @Transactional
    public Map<String,Object> match(long statementId, long billId, BigDecimal requestedAmount, String operator) {
        periodGuard.assertBillWritable(billId);
        Map<String,Object> statement = statementRow(statementId, true); assertOrg(number(statement.get("org_id"), "orgId"));
        Map<String,Object> bill = billing.bill(billId);
        if (orgForBill(billId) != number(statement.get("org_id"), "orgId")) throw new BusinessException("流水与账单不属于同一园区");
        bindStatementPeriod(statement, billId, operator);
        BigDecimal statementAmount = amount(statement.get("amount"));
        BigDecimal matchedAmount = amount(statement.get("matched_amount"));
        BigDecimal statementRemaining = statementAmount.subtract(matchedAmount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal outstanding = amount(bill.get("outstanding_amount"));
        BigDecimal matchAmount = requestedAmount == null ? statementRemaining.min(outstanding) : requestedAmount.setScale(2, RoundingMode.HALF_UP);
        if (matchAmount.compareTo(BigDecimal.ZERO) <= 0 || matchAmount.compareTo(statementRemaining) > 0 || matchAmount.compareTo(outstanding) > 0) {
            throw new BusinessException("本次勾兑金额必须大于零，且不能超过流水剩余金额和账单剩余应收");
        }
        billing.pay(billId, Map.of("payAmount", matchAmount, "payWay", statement.get("source_channel"), "transactionNo", statement.get("external_transaction_no") + "-" + matchedAmount, "operator", operator, "remark", "银行流水勾兑"));
        String transactionNo = statement.get("external_transaction_no") + "-" + matchedAmount;
        Long paymentId = jdbc.queryForObject("SELECT id FROM billing_payment WHERE bill_id=? AND transaction_no=? ORDER BY id DESC LIMIT 1", Long.class, billId, transactionNo);
        BigDecimal newMatched = matchedAmount.add(matchAmount).setScale(2, RoundingMode.HALF_UP);
        String statementStatus = newMatched.compareTo(statementAmount) >= 0 ? "MATCHED" : "PARTIAL";
        jdbc.update("INSERT INTO billing_bank_match (statement_id,bill_id,payment_id,matched_amount,match_status,match_type,matched_by,remark) VALUES (?,?,?,?,'MATCHED','AUTO',?,?)", statementId, billId, paymentId, matchAmount, operator, "本次勾兑金额：" + matchAmount);
        jdbc.update("UPDATE billing_bank_statement SET matched_amount=?,statement_status=?,updated_by=? WHERE id=?", newMatched, statementStatus, operator, statementId);
        if (paymentId != null) createVoucher(Map.of("paymentId", paymentId, "voucherType", "RECEIPT"), operator);
        return statement(statementId);
    }

    @Transactional
    public Map<String,Object> autoMatch(long statementId, String operator) {
        Map<String,Object> statement = statementRow(statementId, true);
        assertOrg(number(statement.get("org_id"), "orgId"));
        BigDecimal remaining = amount(statement.get("amount")).subtract(amount(statement.get("matched_amount"))).max(BigDecimal.ZERO);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("该流水已经完成对账");
        List<Map<String,Object>> exact = candidates(statementId).stream()
                .filter(row -> amount(row.get("outstanding_amount")).compareTo(remaining) == 0).toList();
        if (exact.isEmpty()) throw new BusinessException("没有金额一致的待收账单，需通过高级匹配处理");
        if (exact.size() > 1) throw new BusinessException("存在 " + exact.size() + " 条金额一致的账单，请选择后确认匹配");
        return match(statementId, number(exact.get(0).get("bill_id"), "billId"), remaining, operator);
    }

    @Transactional
    public Map<String,Object> difference(long statementId, String reason, String operator) {
        Map<String,Object> statement = statementRow(statementId, true); assertOrg(number(statement.get("org_id"), "orgId"));
        List<Long> linkedBills = jdbc.queryForList("SELECT DISTINCT bill_id FROM billing_bank_match WHERE statement_id=? AND match_status='MATCHED'", Long.class, statementId);
        for (Long billId : linkedBills) periodGuard.assertBillWritable(billId);
        if (text(reason) == null) throw new BusinessException("标记差异必须填写原因");
        jdbc.update("UPDATE billing_bank_statement SET statement_status='DIFFERENCE',narrative=CONCAT(COALESCE(narrative,''),' [差异：',?,']'),updated_by=? WHERE id=?", reason, operator, statementId);
        return statement(statementId);
    }

    @Transactional
    public Map<String,Object> reverseMatch(long matchId, String reason, String operator) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT * FROM billing_bank_match WHERE id=? FOR UPDATE", matchId);
        if (rows.isEmpty()) throw new BusinessException(404, "勾兑记录不存在");
        Map<String,Object> match = rows.get(0);
        if (!"MATCHED".equalsIgnoreCase(String.valueOf(match.get("match_status")))) throw new BusinessException("该勾兑记录已经解除");
        long billId = number(match.get("bill_id"), "billId");
        periodGuard.assertBillWritable(billId);
        if (text(reason) == null) throw new BusinessException("解除勾兑必须填写原因");
        Long paymentId = longOrNull(match.get("payment_id"));
        if ("AUTO".equalsIgnoreCase(String.valueOf(match.get("match_type"))) && paymentId != null) {
            billing.reversePayment(paymentId, Map.of("operator", operator, "reason", reason));
        }
        jdbc.update("UPDATE billing_bank_match SET match_status='REVERSED',remark=CONCAT(COALESCE(remark,''),' [解除：',?,']') WHERE id=?", reason, matchId);
        long statementId = number(match.get("statement_id"), "statementId");
        Map<String,Object> statement = statementRow(statementId, true);
        BigDecimal activeMatched = jdbc.queryForObject("SELECT COALESCE(SUM(matched_amount),0) FROM billing_bank_match WHERE statement_id=? AND match_status='MATCHED'", BigDecimal.class, statementId);
        BigDecimal total = amount(statement.get("amount"));
        BigDecimal matched = activeMatched == null ? BigDecimal.ZERO : activeMatched.setScale(2, RoundingMode.HALF_UP);
        String status = matched.compareTo(BigDecimal.ZERO) == 0 ? "IMPORTED" : matched.compareTo(total) >= 0 ? "MATCHED" : "PARTIAL";
        jdbc.update("UPDATE billing_bank_statement SET matched_amount=?,statement_status=?,updated_by=? WHERE id=?", matched, status, operator, statementId);
        return statement(statementId);
    }

    @Transactional
    public Map<String,Object> createVoucher(Map<String,Object> body, String operator) {
        Long billId = longOrNull(body.get("billId")); Long paymentId = longOrNull(body.get("paymentId"));
        if (billId == null && paymentId == null) throw new BusinessException("凭证必须关联账单或收款记录");
        Map<String,Object> source = billId != null ? billing.bill(billId) : payment(paymentId);
        if (billId != null) periodGuard.assertBillWritable(billId); else periodGuard.assertPaymentWritable(paymentId);
        long orgId = billId == null ? number(source.get("org_id"), "orgId") : orgForBill(billId); assertOrg(orgId);
        String type = textOr(body.get("voucherType"), paymentId == null ? "AR_RECEIVABLE" : "RECEIPT");
        if (billId != null && "AR_RECEIVABLE".equals(type) && !"ISSUED".equalsIgnoreCase(String.valueOf(source.get("bill_status")))) throw new BusinessException("只有已发布账单可以生成应收凭证");
        if (paymentId != null && !"SUCCESS".equalsIgnoreCase(String.valueOf(source.get("payment_status")))) throw new BusinessException("只有成功收款可以生成收款凭证");
        String uniqueSql = paymentId == null ? "SELECT id FROM billing_voucher WHERE bill_id=? AND voucher_type=? AND voucher_status<>'VOID' LIMIT 1" : "SELECT id FROM billing_voucher WHERE payment_id=? AND voucher_type=? AND voucher_status<>'VOID' LIMIT 1";
        // queryForObject 会在“尚未生成过凭证”的正常场景抛 EmptyResultDataAccessException，
        // 这里应把无记录视为可新建，而不是让正式应收凭证流程直接返回 500。
        List<Map<String,Object>> existingRows = jdbc.queryForList(uniqueSql, paymentId == null ? billId : paymentId, type);
        if (!existingRows.isEmpty()) return voucher(number(existingRows.get(0).get("id"), "voucherId"));
        BigDecimal total = amount(paymentId == null ? source.get("total_amount") : source.get("pay_amount"));
        String voucherNo = "VCH" + (paymentId == null ? "A" : "R") + LocalDateTime.now().toString().replaceAll("[^0-9]", "").substring(0, 14) + String.format("%06d", Math.floorMod((billId == null ? paymentId : billId), 1_000_000L));
        jdbc.update("INSERT INTO billing_voucher (voucher_no,org_id,bill_id,payment_id,voucher_type,voucher_status,voucher_date,summary,total_debit,total_credit,created_by) VALUES (?,?,?,?,?,'DRAFT',?,?,?,?,?)", voucherNo, orgId, billId, paymentId, type, Date.valueOf(LocalDate.now()), textOr(body.get("summary"), "园区能源结算"), total, total, operator);
        Long voucherId = jdbc.queryForObject("SELECT id FROM billing_voucher WHERE voucher_no=?", Long.class, voucherNo);
        if (voucherId == null) throw new BusinessException("凭证创建失败");
        if ("RECEIPT".equals(type)) {
            line(voucherId, 1, textOr(body.get("debitAccountCode"), "1002"), textOr(body.get("debitAccountName"), "银行存款"), "收取能源结算款", total, BigDecimal.ZERO);
            line(voucherId, 2, textOr(body.get("creditAccountCode"), "1122"), textOr(body.get("creditAccountName"), "应收账款"), "核销能源结算应收", BigDecimal.ZERO, total);
        } else {
            line(voucherId, 1, textOr(body.get("debitAccountCode"), "1122"), textOr(body.get("debitAccountName"), "应收账款"), "确认能源结算应收", total, BigDecimal.ZERO);
            line(voucherId, 2, textOr(body.get("creditAccountCode"), "6001"), textOr(body.get("creditAccountName"), "能源结算收入"), "确认能源结算收入", BigDecimal.ZERO, total);
        }
        jdbc.update("INSERT INTO billing_archive_record (org_id,bill_id,statement_id,voucher_id,archive_type,archive_no,archived_by) VALUES (?,?,?,?, 'VOUCHER',?,?)", orgId, billId, null, voucherId, "ARCH-" + voucherNo, operator);
        return voucher(voucherId);
    }

    public Map<String,Object> voucher(long id) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT * FROM billing_voucher WHERE id=?", id); if (rows.isEmpty()) throw new BusinessException(404, "凭证不存在");
        Map<String,Object> result = new LinkedHashMap<>(rows.get(0)); result.put("lines", jdbc.queryForList("SELECT * FROM billing_voucher_line WHERE voucher_id=? ORDER BY line_no", id));
        return result;
    }

    @Transactional
    public Map<String,Object> postVoucher(long id, String operator) {
        Map<String,Object> row = voucher(id);
        Long billId = longOrNull(row.get("bill_id"));
        Long paymentId = longOrNull(row.get("payment_id"));
        if (billId != null) periodGuard.assertBillWritable(billId); else if (paymentId != null) periodGuard.assertPaymentWritable(paymentId);
        String status = String.valueOf(row.get("voucher_status"));
        if ("VOID".equalsIgnoreCase(status)) throw new BusinessException("已作废凭证不能入账");
        if ("POSTED".equalsIgnoreCase(status) || "EXPORTED".equalsIgnoreCase(status)) return row;
        if (!"DRAFT".equalsIgnoreCase(status)) throw new BusinessException("只有草稿凭证可以确认入账");
        jdbc.update("UPDATE billing_voucher SET voucher_status='POSTED',posted_time=NOW(),posted_by=?,updated_by=? WHERE id=?", operator, operator, id);
        return voucher(id);
    }

    @Transactional
    public Map<String,Object> exportVoucher(long id, Map<String,Object> body, String operator) {
        Map<String,Object> voucher = voucher(id); String system = textOr(body.get("externalSystem"), "OTHER");
        if ("DRAFT".equalsIgnoreCase(String.valueOf(voucher.get("voucher_status")))) throw new BusinessException("草稿凭证请先确认入账后再导出");
        if ("VOID".equalsIgnoreCase(String.valueOf(voucher.get("voucher_status")))) throw new BusinessException("已作废凭证不能导出");
        String externalNo = textOr(body.get("externalVoucherNo"), "EXT-" + voucher.get("voucher_no"));
        Map<String,Object> exported = new LinkedHashMap<>(voucher);
        exported.put("external_system", system);
        exported.put("external_voucher_no", externalNo);
        exported.put("exported_by", operator);
        exported.put("exportData", exportData(exported));
        return exported;
    }

    private Map<String,Object> exportData(Map<String,Object> voucher) {
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("voucherNo", voucher.get("voucher_no")); data.put("voucherDate", voucher.get("voucher_date")); data.put("voucherType", voucher.get("voucher_type"));
        data.put("summary", voucher.get("summary")); data.put("totalDebit", voucher.get("total_debit")); data.put("totalCredit", voucher.get("total_credit")); data.put("lines", voucher.get("lines"));
        StringBuilder csv = new StringBuilder("凭证号,日期,凭证类型,科目编码,科目名称,摘要,借方,贷方\n");
        Object lines = voucher.get("lines");
        if (lines instanceof List<?> list) for (Object value : list) if (value instanceof Map<?,?> line) {
            csv.append(csvCell(voucher.get("voucher_no"))).append(',').append(csvCell(voucher.get("voucher_date"))).append(',').append(csvCell(voucher.get("voucher_type"))).append(',')
                    .append(csvCell(line.get("account_code"))).append(',').append(csvCell(line.get("account_name"))).append(',').append(csvCell(line.get("summary"))).append(',')
                    .append(csvCell(line.get("debit_amount"))).append(',').append(csvCell(line.get("credit_amount"))).append('\n');
        }
        data.put("csv", csv.toString());
        return data;
    }
    private String csvCell(Object value){String text=Objects.toString(value,"").replace("\"","\"\"");return "\""+text+"\"";}

    private void line(long voucherId,int no,String code,String name,String summary,BigDecimal debit,BigDecimal credit){jdbc.update("INSERT INTO billing_voucher_line (voucher_id,line_no,account_code,account_name,summary,debit_amount,credit_amount) VALUES (?,?,?,?,?,?,?)",voucherId,no,code,name,summary,debit,credit);}
    private Map<String,Object> payment(long id){List<Map<String,Object>> rows=jdbc.queryForList("SELECT p.*,b.account_id,a.org_id FROM billing_payment p JOIN billing_bill b ON b.id=p.bill_id JOIN billing_account a ON a.id=b.account_id WHERE p.id=?",id);if(rows.isEmpty())throw new BusinessException(404,"收款记录不存在");return rows.get(0);}
    private void bindStatementPeriod(Map<String,Object> statement,long billId,String operator){
        List<Long> ids=jdbc.queryForList("SELECT p.id FROM billing_bill b JOIN billing_account a ON a.id=b.account_id JOIN billing_period p ON p.org_id=a.org_id AND BINARY p.period_code=BINARY b.bill_cycle WHERE b.id=?",Long.class,billId);
        if(ids.isEmpty())throw new BusinessException("账单所属结算账期不存在");
        long periodId=ids.get(0);Long current=longOrNull(statement.get("target_period_id"));
        if(current!=null&&current!=periodId)throw new BusinessException("该银行流水已经归属于其他结算账期，不能跨账期勾兑");
        periodGuard.assertPeriodWritable(periodId);
        if(current==null)jdbc.update("UPDATE billing_bank_statement SET target_period_id=?,updated_by=? WHERE id=?",periodId,operator,statement.get("id"));
    }
    private long orgForBill(long billId){Long org=jdbc.queryForObject("SELECT a.org_id FROM billing_bill b JOIN billing_account a ON a.id=b.account_id WHERE b.id=?",Long.class,billId);if(org==null)throw new BusinessException(404,"账单所属园区不存在");return org;}
    private Map<String,Object> statementRow(long id,boolean lock){List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM billing_bank_statement WHERE id=?"+(lock?" FOR UPDATE":""),id);if(rows.isEmpty())throw new BusinessException(404,"银行流水不存在");return rows.get(0);}
    private void assertOrg(long id){if(!access.hasOrgAccess(id))throw new BusinessException(403,"没有该园区财务权限");}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(JsonProcessingException e){return "{}";}}
    private LocalDateTime dateTime(Object value){String raw=required(value,"statementTime").trim().replace("Z","");String normalized=raw.replace('/','-').replace(" ","T");List<DateTimeFormatter> formats=List.of(DateTimeFormatter.ISO_LOCAL_DATE_TIME,DateTimeFormatter.ofPattern("yyyy-M-d'T'H:m"),DateTimeFormatter.ofPattern("yyyy-M-d'T'H:m:s"),DateTimeFormatter.ofPattern("yyyy-M-d"));for(DateTimeFormatter format:formats){try{if(format==formats.get(3))return LocalDate.parse(normalized,format).atStartOfDay();return LocalDateTime.parse(normalized,format);}catch(Exception ignored){}}throw new BusinessException("入账时间格式不合法，请使用 yyyy-MM-dd HH:mm:ss");}
    private BigDecimal amount(Object value){try{return new BigDecimal(String.valueOf(value)).setScale(2,RoundingMode.HALF_UP);}catch(Exception e){throw new BusinessException("金额不合法");}}
    private Long longOrNull(Object v){if(v==null||String.valueOf(v).isBlank())return null;try{return Long.parseLong(String.valueOf(v));}catch(Exception e){throw new BusinessException("ID必须为数字");}}
    private long number(Object v,String n){Long x=longOrNull(v);if(x==null)throw new BusinessException(n+"不能为空");return x;}
    private int positive(String v,int d){try{int n=Integer.parseInt(v);return n>0?n:d;}catch(Exception e){return d;}}
    private Object first(Map<String,Object> row,String... names){for(String name:names)if(row.containsKey(name)&&row.get(name)!=null)return row.get(name);return null;}
    private String required(Object v,String n){String s=text(v);if(s==null)throw new BusinessException(n+"不能为空");return s;}
    private String text(Object v){if(v==null)return null;String s=Objects.toString(v,"").trim();return s.isEmpty()?null:s;}
    private String textOr(Object v,String d){String s=text(v);return s==null?d:s;}
}
