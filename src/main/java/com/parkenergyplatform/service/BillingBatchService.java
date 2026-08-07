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

    public BillingBatchService(JdbcTemplate jdbcTemplate, BillingService billingService, BusinessDataAccessService accessService) {
        this.jdbcTemplate = jdbcTemplate; this.billingService = billingService; this.accessService = accessService;
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
        if(end.isBefore(start)) throw new BusinessException("账期结束日期不能早于开始日期"); Long duplicate=jdbcTemplate.queryForObject("SELECT COUNT(*) FROM billing_batch WHERE org_id=? AND bill_cycle=?",Long.class,orgId,cycle); if(duplicate!=null&&duplicate>0) throw new BusinessException(409,"该园区账期已存在出账批次");
        String no="BATCH"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))+orgId;
        jdbcTemplate.update("INSERT INTO billing_batch (batch_no,batch_name,org_id,bill_cycle,start_date,end_date,remark) VALUES (?,?,?,?,?,?,?)",no,Objects.toString(body.getOrDefault("batchName",cycle+" 出账批次")),orgId,cycle,Date.valueOf(start),Date.valueOf(end),text(body.get("remark")));
        Long id=jdbcTemplate.queryForObject("SELECT id FROM billing_batch WHERE batch_no=?",Long.class,no); return detail(id);
    }

    @Transactional
    public Map<String,Object> generate(long id) {
        Map<String,Object> batch=required(id); assertOrg(((Number)batch.get("org_id")).longValue()); if(!"DRAFT".equalsIgnoreCase(String.valueOf(batch.get("status")))) throw new BusinessException("只有草稿批次可以生成账单");
        jdbcTemplate.update("UPDATE billing_batch SET status='RUNNING', failure_summary=NULL WHERE id=?",id);
        List<Map<String,Object>> accounts=jdbcTemplate.queryForList("""
                SELECT a.id FROM billing_account a
                LEFT JOIN leasing_contract c ON c.id = a.contract_id
                WHERE a.org_id=? AND a.status=1 AND (a.contract_id IS NULL OR c.status='ACTIVE')
                """,batch.get("org_id")); int success=0,failed=0; List<String> failures=new ArrayList<>();
        if (accounts.isEmpty()) throw new BusinessException("该园区没有可参与本次出账的启用计费账户");
        for(Map<String,Object> account:accounts) try { billingService.generate(Map.of("accountId",account.get("id"),"billCycle",batch.get("bill_cycle"),"startDate",batch.get("start_date").toString(),"endDate",batch.get("end_date").toString(),"batchId",id,"remark","BATCH:"+batch.get("batch_no"))); success++; } catch(RuntimeException ex) { failed++; failures.add("账户"+account.get("id")+":"+shortMessage(ex)); }
        BigDecimal total=jdbcTemplate.queryForObject("SELECT COALESCE(SUM(total_amount),0) FROM billing_bill WHERE batch_id=?",BigDecimal.class,id); String status=failed==0?"REVIEWING":"FAILED";
        jdbcTemplate.update("UPDATE billing_batch SET status=?,account_total=?,generated_count=?,failed_count=?,total_amount=?,failure_summary=? WHERE id=?",status,accounts.size(),success,failed,total==null?BigDecimal.ZERO:total,String.join("; ",failures).substring(0,Math.min(1000,String.join("; ",failures).length())),id);
        return detail(id);
    }

    @Transactional
    public Map<String,Object> review(long id,String operator) {
        Map<String,Object> batch=required(id); assertOrg(((Number)batch.get("org_id")).longValue()); if(!"REVIEWING".equalsIgnoreCase(String.valueOf(batch.get("status")))) throw new BusinessException("仅生成成功的待审核批次可审核");
        jdbcTemplate.update("UPDATE billing_bill SET bill_status='REVIEWED',reviewed_by=?,reviewed_time=NOW() WHERE batch_id=? AND bill_status='DRAFT'",operator,id);
        jdbcTemplate.update("UPDATE billing_batch SET status='REVIEWED',reviewed_by=?,reviewed_time=NOW() WHERE id=?",operator,id); return detail(id);
    }

    @Transactional
    public Map<String,Object> issue(long id,String operator) {
        Map<String,Object> batch=required(id); assertOrg(((Number)batch.get("org_id")).longValue()); if(!"REVIEWED".equalsIgnoreCase(String.valueOf(batch.get("status")))) throw new BusinessException("仅已审核批次允许发布"); Long waiting=jdbcTemplate.queryForObject("SELECT COUNT(*) FROM billing_bill WHERE batch_id=? AND bill_status<>'REVIEWED'",Long.class,id); if(waiting!=null&&waiting>0) throw new BusinessException("请先完成该批次全部账单审核");
        jdbcTemplate.update("UPDATE billing_bill SET bill_status='ISSUED',issued_by=?,issued_time=NOW() WHERE batch_id=?",operator,id); jdbcTemplate.update("UPDATE billing_batch SET status='ISSUED',issued_by=?,issued_time=NOW() WHERE id=?",operator,id); return detail(id);
    }

    public Map<String,Object> detail(long id) { Map<String,Object> batch=required(id); assertOrg(((Number)batch.get("org_id")).longValue()); Map<String,Object> data=new LinkedHashMap<>(batch); data.put("bills",jdbcTemplate.queryForList("SELECT id,bill_no,account_id,tenant_name_snapshot,total_amount,bill_status,pay_status FROM billing_bill WHERE batch_id=? ORDER BY id",id)); return data; }
    private Map<String,Object> required(long id) { List<Map<String,Object>> rows=jdbcTemplate.queryForList("SELECT * FROM billing_batch WHERE id=?",id);if(rows.isEmpty())throw new BusinessException(404,"出账批次不存在");return rows.get(0); }
    private void assertOrg(long orgId){if(!accessService.hasOrgAccess(orgId))throw new BusinessException(403,"没有该园区出账权限");} private long number(Object v,String n){if(v==null)throw new BusinessException(n+" 不能为空");return Long.parseLong(String.valueOf(v));} private String required(Object v,String n){String s=text(v);if(s==null)throw new BusinessException(n+" 不能为空");return s;}private String text(Object v){return v==null||String.valueOf(v).isBlank()?null:String.valueOf(v).trim();}private int positive(String v,int d){try{int n=Integer.parseInt(v);return n>0?n:d;}catch(Exception e){return d;}}private String shortMessage(RuntimeException e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
}
