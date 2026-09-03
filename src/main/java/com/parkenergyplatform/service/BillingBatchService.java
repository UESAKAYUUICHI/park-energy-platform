package com.parkenergyplatform.service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

@Service
public class BillingBatchService {
    private final JdbcTemplate jdbcTemplate;
    private final BillingService billingService;
    private final BusinessDataAccessService accessService;
    private final BillingPeriodGuardService periodGuard;

    public BillingBatchService(JdbcTemplate jdbcTemplate, BillingService billingService, BusinessDataAccessService accessService,
                               BillingPeriodGuardService periodGuard) {
        this.jdbcTemplate = jdbcTemplate; this.billingService = billingService; this.accessService = accessService; this.periodGuard = periodGuard;
    }

    public PageResult<Map<String,Object>> page(Map<String,String> params) {
        List<Object> args=new ArrayList<>(); StringBuilder where=new StringBuilder(" WHERE 1=1");
        if (text(params.get("orgId")) != null) { where.append(" AND b.org_id=?"); args.add(Long.parseLong(params.get("orgId"))); }
        if (text(params.get("status")) != null) { where.append(" AND b.status=?"); args.add(params.get("status")); }
        if (text(params.get("keyword")) != null) { where.append(" AND (b.batch_no LIKE ? OR b.batch_name LIKE ? OR b.bill_cycle LIKE ?)"); for(int i=0;i<3;i++) args.add("%"+params.get("keyword").trim()+"%"); }
        where.append(accessService.scopeSql("b.org_id",args)); int n=positive(params.get("pageNum"),1), size=Math.min(positive(params.get("pageSize"),20),200);
        String from=" FROM billing_batch b LEFT JOIN dev_org o ON o.id=b.org_id"; Long total=jdbcTemplate.queryForObject("SELECT COUNT(*)"+from+where,Long.class,args.toArray());
        List<Object> pageArgs=new ArrayList<>(args);pageArgs.add(size);pageArgs.add((n-1)*size);
        return PageResult.of(jdbcTemplate.queryForList("SELECT b.*,o.org_name"+from+where+" ORDER BY b.id DESC LIMIT ? OFFSET ?",pageArgs.toArray()),total==null?0:total,n,size);
    }

    @Transactional
    public Map<String,Object> create(Map<String,Object> body) {
        long orgId=number(body.get("orgId"),"orgId"); assertOrg(orgId); String cycle=required(body.get("billCycle"),"billCycle"); LocalDate start=LocalDate.parse(required(body.get("startDate"),"startDate")), end=LocalDate.parse(required(body.get("endDate"),"endDate"));
        periodGuard.assertOrgCycleWritable(orgId, cycle);
        if(end.isBefore(start)) throw new BusinessException("账期结束日期不能早于开始日期"); Long duplicate=jdbcTemplate.queryForObject("SELECT COUNT(*) FROM billing_batch WHERE org_id=? AND bill_cycle=?",Long.class,orgId,cycle); if(duplicate!=null&&duplicate>0) throw new BusinessException(409,"该园区账期已存在出账批次");
        String no="BATCH"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))+orgId;
        jdbcTemplate.update("INSERT INTO billing_batch (batch_no,batch_name,org_id,bill_cycle,start_date,end_date,remark) VALUES (?,?,?,?,?,?,?)",no,Objects.toString(body.getOrDefault("batchName",cycle+" 出账批次")),orgId,cycle,Date.valueOf(start),Date.valueOf(end),text(body.get("remark")));
        Long id=jdbcTemplate.queryForObject("SELECT id FROM billing_batch WHERE batch_no=?",Long.class,no); return detail(id);
    }

    public Map<String,Object> generate(long id) {
        return generate(id, null);
    }

    public Map<String,Object> generate(long id, List<Long> contractIds) {
        Map<String,Object> batch=required(id); assertOrg(((Number)batch.get("org_id")).longValue());
        periodGuard.assertOrgCycleWritable(((Number)batch.get("org_id")).longValue(), String.valueOf(batch.get("bill_cycle")));
        String currentStatus = String.valueOf(batch.get("status"));
        if(!"DRAFT".equalsIgnoreCase(currentStatus) && !"FAILED".equalsIgnoreCase(currentStatus)) throw new BusinessException("只有草稿或生成失败的批次可以生成账单");
        if ("FAILED".equalsIgnoreCase(currentStatus)) cleanupDraftBills(id);
        jdbcTemplate.update("UPDATE billing_batch SET status='RUNNING', failure_summary=NULL WHERE id=?",id);
        List<Object> accountArgs = new ArrayList<>();
        accountArgs.add(batch.get("org_id"));
        String contractFilter = "";
        if (contractIds != null) {
            if (contractIds.isEmpty()) throw new BusinessException("自动出账计划未绑定合同");
            contractFilter = " AND a.contract_id IN (" + String.join(",", java.util.Collections.nCopies(contractIds.size(), "?")) + ")";
            accountArgs.addAll(contractIds);
        }
        List<Map<String,Object>> accounts=jdbcTemplate.queryForList("""
                SELECT a.id FROM billing_account a
                LEFT JOIN leasing_contract c ON c.id = a.contract_id
                WHERE a.org_id=? AND a.status=1 AND (a.contract_id IS NULL OR c.status='ACTIVE')
                """ + contractFilter, accountArgs.toArray()); int success=0,failed=0; List<String> failures=new ArrayList<>();
        if (accounts.isEmpty()) throw new BusinessException("该园区没有可参与本次出账的启用计费账户");
        for(Map<String,Object> account:accounts) try { billingService.generate(Map.of("accountId",account.get("id"),"billCycle",batch.get("bill_cycle"),"startDate",batch.get("start_date").toString(),"endDate",batch.get("end_date").toString(),"batchId",id,"remark","BATCH:"+batch.get("batch_no"))); success++; } catch(RuntimeException ex) { failed++; failures.add("账户"+account.get("id")+":"+shortMessage(ex)); }
        BigDecimal total=jdbcTemplate.queryForObject("SELECT COALESCE(SUM(total_amount),0) FROM billing_bill WHERE batch_id=?",BigDecimal.class,id); String status=failed==0?"REVIEWING":"FAILED";
        jdbcTemplate.update("UPDATE billing_batch SET status=?,account_total=?,generated_count=?,failed_count=?,total_amount=?,failure_summary=? WHERE id=?",status,accounts.size(),success,failed,total==null?BigDecimal.ZERO:total,String.join("; ",failures).substring(0,Math.min(1000,String.join("; ",failures).length())),id);
        return detail(id);
    }

    /** 同账期已有主批次时，只补尚未生成常规账单的合同，避免重复出账。 */
    @Transactional
    public Map<String,Object> generateMissing(long id, List<Long> contractIds) {
        Map<String,Object> batch=required(id); long orgId=((Number)batch.get("org_id")).longValue(); assertOrg(orgId);
        periodGuard.assertOrgCycleWritable(orgId, String.valueOf(batch.get("bill_cycle")));
        String currentStatus=String.valueOf(batch.get("status")).toUpperCase();
        if (!List.of("DRAFT","FAILED","REVIEWING").contains(currentStatus)) throw new BusinessException("原批次已定稿，请发起补充结算，不允许直接修改原批次");
        if (contractIds==null || contractIds.isEmpty()) throw new BusinessException("自动出账计划未绑定合同");
        String placeholders=String.join(",",java.util.Collections.nCopies(contractIds.size(),"?"));
        List<Object> args=new ArrayList<>(); args.add(orgId); args.addAll(contractIds); args.add(batch.get("bill_cycle"));
        String accountSql="SELECT a.id FROM billing_account a LEFT JOIN leasing_contract c ON c.id=a.contract_id "
                +"WHERE a.org_id=? AND a.status=1 AND c.status='ACTIVE' AND a.contract_id IN ("+placeholders+") "
                +"AND NOT EXISTS(SELECT 1 FROM billing_bill b WHERE b.account_id=a.id AND b.bill_cycle=? AND b.billing_key='REGULAR')";
        List<Map<String,Object>> accounts=jdbcTemplate.queryForList(accountSql,args.toArray());
        if (accounts.isEmpty()) { Map<String,Object> result=detail(id); result.put("generatedCount",0); result.put("message","计划合同均已出账，无需补出"); return result; }
        jdbcTemplate.update("UPDATE billing_batch SET status='RUNNING',failure_summary=NULL WHERE id=?",id);
        int success=0,failed=0; List<String> failures=new ArrayList<>();
        for(Map<String,Object> account:accounts) try { billingService.generate(Map.of("accountId",account.get("id"),"billCycle",batch.get("bill_cycle"),"startDate",batch.get("start_date").toString(),"endDate",batch.get("end_date").toString(),"batchId",id,"remark","BATCH-MISSING:"+batch.get("batch_no"))); success++; } catch(RuntimeException ex){failed++;failures.add("账户"+account.get("id")+":"+shortMessage(ex));}
        BigDecimal total=jdbcTemplate.queryForObject("SELECT COALESCE(SUM(total_amount),0) FROM billing_bill WHERE batch_id=?",BigDecimal.class,id);
        String next=failed==0?"REVIEWING":"FAILED";
        String summary=String.join("; ",failures);
        jdbcTemplate.update("UPDATE billing_batch SET status=?,account_total=?,generated_count=?,failed_count=?,total_amount=?,failure_summary=? WHERE id=?",next,
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM billing_bill WHERE batch_id=?",Long.class,id),success,failed,total==null?BigDecimal.ZERO:total,summary.substring(0,Math.min(1000,summary.length())),id);
        Map<String,Object> result=detail(id); result.put("generatedCount",success); result.put("failedCount",failed); result.put("message",success==0?"没有可补出的合同":"已补出 "+success+" 张账单"); return result;
    }

    private void cleanupDraftBills(long batchId) {
        List<Long> billIds = jdbcTemplate.queryForList(
                "SELECT id FROM billing_bill WHERE batch_id = ? AND bill_status = 'DRAFT'", Long.class, batchId);
        if (billIds.isEmpty()) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(billIds.size(), "?"));
        Object[] args = billIds.toArray();
        jdbcTemplate.update("DELETE FROM billing_bill_event WHERE bill_id IN (" + placeholders + ")", args);
        jdbcTemplate.update("DELETE FROM billing_bill_detail WHERE bill_id IN (" + placeholders + ")", args);
        jdbcTemplate.update("DELETE FROM billing_receivable_ledger WHERE bill_id IN (" + placeholders + ")", args);
        jdbcTemplate.update("DELETE FROM billing_bill WHERE id IN (" + placeholders + ")", args);
    }

    @Transactional
    public Map<String,Object> review(long id,String operator) {
        Map<String,Object> batch=required(id); assertOrg(((Number)batch.get("org_id")).longValue()); if(!"REVIEWING".equalsIgnoreCase(String.valueOf(batch.get("status")))) throw new BusinessException("仅生成成功的待审核批次可审核");
        List<Long> billIds = jdbcTemplate.queryForList("SELECT id FROM billing_bill WHERE batch_id = ? AND bill_status = 'DRAFT' ORDER BY id", Long.class, id);
        for (Long billId : billIds) billingService.review(billId, operator);
        jdbcTemplate.update("UPDATE billing_batch SET status='REVIEWED',reviewed_by=?,reviewed_time=NOW() WHERE id=?",operator,id); return detail(id);
    }

    @Transactional
    public Map<String,Object> issue(long id,String operator) {
        Map<String,Object> batch=required(id); assertOrg(((Number)batch.get("org_id")).longValue()); if(!"REVIEWED".equalsIgnoreCase(String.valueOf(batch.get("status")))) throw new BusinessException("仅已审核批次允许发布"); Long waiting=jdbcTemplate.queryForObject("SELECT COUNT(*) FROM billing_bill WHERE batch_id=? AND bill_status<>'REVIEWED'",Long.class,id); if(waiting!=null&&waiting>0) throw new BusinessException("请先完成该批次全部账单审核");
        List<Long> billIds = jdbcTemplate.queryForList("SELECT id FROM billing_bill WHERE batch_id = ? AND bill_status = 'REVIEWED' ORDER BY id", Long.class, id);
        for (Long billId : billIds) billingService.issue(billId, operator);
        jdbcTemplate.update("UPDATE billing_batch SET status='ISSUED',issued_by=?,issued_time=NOW() WHERE id=?",operator,id); return detail(id);
    }

    public Map<String,Object> detail(long id) { Map<String,Object> batch=required(id); assertOrg(((Number)batch.get("org_id")).longValue()); Map<String,Object> data=new LinkedHashMap<>(batch); data.put("bills",jdbcTemplate.queryForList("SELECT id,bill_no,account_id,tenant_name_snapshot,total_amount,bill_status,pay_status FROM billing_bill WHERE batch_id=? ORDER BY id",id)); return data; }
    private Map<String,Object> required(long id) { List<Map<String,Object>> rows=jdbcTemplate.queryForList("SELECT * FROM billing_batch WHERE id=?",id);if(rows.isEmpty())throw new BusinessException(404,"出账批次不存在");return rows.get(0); }
    private void assertOrg(long orgId){if(!accessService.hasOrgAccess(orgId))throw new BusinessException(403,"没有该园区出账权限");} private long number(Object v,String n){if(v==null)throw new BusinessException(n+" 不能为空");return Long.parseLong(String.valueOf(v));} private String required(Object v,String n){String s=text(v);if(s==null)throw new BusinessException(n+" 不能为空");return s;}private String text(Object v){return v==null||String.valueOf(v).isBlank()?null:String.valueOf(v).trim();}private int positive(String v,int d){try{int n=Integer.parseInt(v);return n>0?n:d;}catch(Exception e){return d;}}private String shortMessage(RuntimeException e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
}
