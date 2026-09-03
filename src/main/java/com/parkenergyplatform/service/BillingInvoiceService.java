package com.parkenergyplatform.service;

import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.common.PageResult;
import com.parkenergyplatform.config.ParkCosProperties;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class BillingInvoiceService {
    private final JdbcTemplate jdbc;
    private final BusinessDataAccessService access;
    private final BillingPeriodGuardService periodGuard;
    private final ObjectProvider<COSClient> cosProvider;
    private final ParkCosProperties cosProperties;

    public BillingInvoiceService(JdbcTemplate jdbc, BusinessDataAccessService access, BillingPeriodGuardService periodGuard,
                                 ObjectProvider<COSClient> cosProvider, ParkCosProperties cosProperties) {
        this.jdbc=jdbc;this.access=access;this.periodGuard=periodGuard;this.cosProvider=cosProvider;this.cosProperties=cosProperties;
    }

    public PageResult<Map<String,Object>> page(Map<String,String> params) {
        int page=positive(params.get("pageNum"),1),size=Math.min(positive(params.get("pageSize"),20),100);
        List<Object> args=new ArrayList<>();StringBuilder where=new StringBuilder(" WHERE 1=1");
        String org=text(params.get("orgId"));if(org!=null){where.append(" AND i.org_id=?");args.add(org);}
        String status=text(params.get("status"));if(status!=null){where.append(" AND i.invoice_status=?");args.add(status);}
        String keyword=text(params.get("keyword"));if(keyword!=null){where.append(" AND (i.invoice_request_no LIKE ? OR i.invoice_no LIKE ? OR i.invoice_title LIKE ?)");for(int n=0;n<3;n++)args.add("%"+keyword+"%");}
        where.append(access.scopeSql("i.org_id",args));
        String from=" FROM billing_invoice i JOIN billing_account a ON a.id=i.account_id LEFT JOIN dev_org o ON o.id=i.org_id";
        Long total=jdbc.queryForObject("SELECT COUNT(*)"+from+where,Long.class,args.toArray());
        List<Object> pageArgs=new ArrayList<>(args);pageArgs.add(size);pageArgs.add((page-1)*size);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT i.*,a.account_name,o.org_name,(SELECT GROUP_CONCAT(b.bill_no ORDER BY b.id SEPARATOR ', ') FROM billing_invoice_bill ib JOIN billing_bill b ON b.id=ib.bill_id WHERE ib.invoice_id=i.id) bill_nos"+from+where+" ORDER BY i.id DESC LIMIT ? OFFSET ?",pageArgs.toArray());
        return PageResult.of(rows,total==null?0:total,page,size);
    }

    public Map<String,Object> detail(long id) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT i.*,a.account_name,o.org_name FROM billing_invoice i JOIN billing_account a ON a.id=i.account_id LEFT JOIN dev_org o ON o.id=i.org_id WHERE i.id=?",id);
        if(rows.isEmpty())throw new BusinessException(404,"发票记录不存在");
        Map<String,Object> row=new LinkedHashMap<>(rows.get(0));assertOrg(number(row.get("org_id"),"orgId"));
        row.put("bills",jdbc.queryForList("SELECT b.id,b.bill_no,b.bill_cycle,b.total_amount,b.paid_amount,ib.invoiced_amount FROM billing_invoice_bill ib JOIN billing_bill b ON b.id=ib.bill_id WHERE ib.invoice_id=? ORDER BY b.id",id));
        String objectKey=text(row.get("file_url"));if(objectKey!=null)row.put("downloadUrl",signedUrl(objectKey));
        if(row.get("red_of_invoice_id")!=null)row.put("originalInvoice",jdbc.queryForList("SELECT id,invoice_request_no,invoice_code,invoice_no,invoice_status,invoice_amount FROM billing_invoice WHERE id=?",row.get("red_of_invoice_id")).stream().findFirst().orElse(Map.of()));
        row.put("redInvoice",jdbc.queryForList("SELECT id,invoice_request_no,invoice_code,invoice_no,invoice_status,invoice_amount,red_reason,red_time FROM billing_invoice WHERE red_of_invoice_id=?",id).stream().findFirst().orElse(Map.of()));
        return row;
    }

    @Transactional
    public Map<String,Object> request(Map<String,Object> body,String operator) {
        List<Long> billIds=ids(body.get("billIds"));if(billIds.isEmpty())throw new BusinessException("至少选择一张已结清账单");
        String placeholders=String.join(",",java.util.Collections.nCopies(billIds.size(),"?"));
        List<Map<String,Object>> bills=jdbc.queryForList("""
                SELECT b.id,b.bill_no,b.account_id,b.total_amount,b.paid_amount,b.pay_status,a.org_id
                FROM billing_bill b JOIN billing_account a ON a.id=b.account_id
                WHERE b.id IN ("""+placeholders+") ORDER BY b.id",billIds.toArray());
        if(bills.size()!=billIds.size())throw new BusinessException("部分账单不存在");
        long orgId=number(bills.get(0).get("org_id"),"orgId"),accountId=number(bills.get(0).get("account_id"),"accountId");assertOrg(orgId);
        BigDecimal total=BigDecimal.ZERO;
        for(Map<String,Object> bill:bills){long billId=number(bill.get("id"),"billId");periodGuard.assertBillWritable(billId);if(number(bill.get("org_id"),"orgId")!=orgId||number(bill.get("account_id"),"accountId")!=accountId)throw new BusinessException("合并开票的账单必须属于同一园区和计费账户");if(number(bill.get("pay_status"),"payStatus")!=1)throw new BusinessException("账单 "+bill.get("bill_no")+" 尚未结清，不能申请发票");Long active=jdbc.queryForObject("SELECT COUNT(*) FROM billing_invoice_bill ib JOIN billing_invoice i ON i.id=ib.invoice_id WHERE ib.bill_id=? AND i.invoice_status<>'RED' AND i.red_of_invoice_id IS NULL",Long.class,billId);if(active!=null&&active>0)throw new BusinessException("账单 "+bill.get("bill_no")+" 已存在未红冲发票");total=total.add(money(bill.get("total_amount")));}
        String requestNo="INVREQ"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))+String.format("%04d",Math.floorMod(accountId,10000));
        jdbc.update("""
                INSERT INTO billing_invoice
                  (invoice_request_no,org_id,account_id,invoice_type,invoice_title,taxpayer_no,invoice_amount,currency,invoice_status,email,mobile,request_remark,requested_by)
                VALUES (?,?,?,?,?,?,?,'CNY','REQUESTED',?,?,?,?)
                """,requestNo,orgId,accountId,textOr(body.get("invoiceType"),"NORMAL"),required(body.get("invoiceTitle"),"发票抬头"),text(body.get("taxpayerNo")),total,
                text(body.get("email")),text(body.get("mobile")),text(body.get("remark")),operator);
        Long invoiceId=jdbc.queryForObject("SELECT id FROM billing_invoice WHERE invoice_request_no=?",Long.class,requestNo);
        for(Map<String,Object> bill:bills)jdbc.update("INSERT INTO billing_invoice_bill (invoice_id,bill_id,invoiced_amount,created_by) VALUES (?,?,?,?)",invoiceId,bill.get("id"),bill.get("total_amount"),operator);
        return detail(invoiceId==null?0:invoiceId);
    }

    @Transactional
    public Map<String,Object> issue(long id,Map<String,Object> body,String operator) {
        Map<String,Object> invoice=requiredInvoice(id,true);assertMutableBills(id);
        if(!"REQUESTED".equals(invoice.get("invoice_status")))throw new BusinessException("仅待开具发票可执行开票");
        String code=invoiceCode(number(invoice.get("org_id"),"orgId"));
        String no=invoiceNumber(id);
        Long duplicate=jdbc.queryForObject("SELECT COUNT(*) FROM billing_invoice WHERE invoice_no=? AND id<>?",Long.class,no,id);if(duplicate!=null&&duplicate>0)throw new BusinessException(409,"系统生成的发票号码发生冲突，请重试");
        jdbc.update("UPDATE billing_invoice SET invoice_status='ISSUED',invoice_code=?,invoice_no=?,issued_time=NOW(),issued_by=?,updated_by=? WHERE id=?",code,no,operator,operator,id);
        return detail(id);
    }

    @Transactional
    public Map<String,Object> upload(long id,MultipartFile file,String operator) {
        Map<String,Object> invoice=requiredInvoice(id,true);assertMutableBills(id);
        if(!List.of("ISSUED","DELIVERED").contains(String.valueOf(invoice.get("invoice_status"))))throw new BusinessException("请先开具发票再上传电子票文件");
        if(file==null||file.isEmpty())throw new BusinessException("电子发票文件不能为空");if(file.getSize()>5L*1024*1024)throw new BusinessException("电子发票文件不能超过5MB");
        String filename=textOr(file.getOriginalFilename(),"invoice.pdf"),lower=filename.toLowerCase();if(!lower.endsWith(".pdf")&&!lower.endsWith(".ofd"))throw new BusinessException("仅支持 PDF 或 OFD 电子发票");
        String key="billing-invoices/"+id+"/"+UUID.randomUUID().toString().replace("-","")+ (lower.endsWith(".ofd")?".ofd":".pdf");ObjectMetadata metadata=new ObjectMetadata();metadata.setContentLength(file.getSize());metadata.setContentType(textOr(file.getContentType(),lower.endsWith(".ofd")?"application/ofd":"application/pdf"));
        try{cos().putObject(new PutObjectRequest(cosProperties.bucket(),key,file.getInputStream(),metadata));}catch(Exception exception){throw new BusinessException("电子发票上传失败："+exception.getMessage());}
        jdbc.update("UPDATE billing_invoice SET file_name=?,file_url=?,updated_by=? WHERE id=?",filename,key,operator,id);return detail(id);
    }

    @Transactional
    public Map<String,Object> deliver(long id,String operator) {Map<String,Object> invoice=requiredInvoice(id,true);assertMutableBills(id);if(!"ISSUED".equals(invoice.get("invoice_status")))throw new BusinessException("仅已开具发票可交付");if(text(invoice.get("file_url"))==null)throw new BusinessException("请先上传电子发票文件");jdbc.update("UPDATE billing_invoice SET invoice_status='DELIVERED',delivered_by=?,delivered_time=NOW(),updated_by=? WHERE id=?",operator,operator,id);return detail(id);}

    @Transactional
    public Map<String,Object> applyRed(long id,String reason,String operator) {Map<String,Object> invoice=requiredInvoice(id,true);assertMutableBills(id);if(!List.of("ISSUED","DELIVERED").contains(String.valueOf(invoice.get("invoice_status"))))throw new BusinessException("仅已开具或已交付发票可申请红冲");if(text(reason)==null)throw new BusinessException("红冲必须填写原因");jdbc.update("UPDATE billing_invoice SET invoice_status='RED_APPLIED',red_reason=?,updated_by=? WHERE id=?",reason,operator,id);return detail(id);}

    @Transactional
    public Map<String,Object> confirmRed(long id,Map<String,Object> body,String operator) {Map<String,Object> original=requiredInvoice(id,true);assertMutableBills(id);if(!"RED_APPLIED".equals(original.get("invoice_status")))throw new BusinessException("仅已申请红冲的发票可确认红冲");String redNo=invoiceNumber(id);String redCode=invoiceCode(number(original.get("org_id"),"orgId"));String requestNo="INVRED"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))+id;jdbc.update("""
            INSERT INTO billing_invoice (invoice_request_no,org_id,account_id,invoice_type,invoice_title,taxpayer_no,invoice_amount,currency,invoice_status,invoice_code,invoice_no,issued_time,requested_by,issued_by,red_of_invoice_id,red_reason,red_by,red_time,updated_by)
            VALUES (?,?,?,?,?,?,-?,'CNY','RED',?,?,NOW(),?,?,?,?,?,?,?)
            """,requestNo,original.get("org_id"),original.get("account_id"),original.get("invoice_type"),original.get("invoice_title"),original.get("taxpayer_no"),original.get("invoice_amount"),redCode,redNo,operator,operator,id,original.get("red_reason"),operator,Timestamp.valueOf(LocalDateTime.now()),operator);Long redId=jdbc.queryForObject("SELECT id FROM billing_invoice WHERE invoice_request_no=?",Long.class,requestNo);List<Map<String,Object>> links=jdbc.queryForList("SELECT * FROM billing_invoice_bill WHERE invoice_id=?",id);for(Map<String,Object> link:links)jdbc.update("INSERT INTO billing_invoice_bill(invoice_id,bill_id,invoiced_amount,created_by) VALUES (?,?,?,?)",redId,link.get("bill_id"),money(link.get("invoiced_amount")).negate(),operator);jdbc.update("UPDATE billing_invoice SET invoice_status='RED',red_by=?,red_time=NOW(),updated_by=? WHERE id=?",operator,operator,id);return detail(id);}

    private void assertMutableBills(long invoiceId){List<Long> ids=jdbc.queryForList("SELECT bill_id FROM billing_invoice_bill WHERE invoice_id=?",Long.class,invoiceId);for(Long billId:ids)periodGuard.assertBillWritable(billId);}
    private Map<String,Object> requiredInvoice(long id,boolean lock){List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM billing_invoice WHERE id=?"+(lock?" FOR UPDATE":""),id);if(rows.isEmpty())throw new BusinessException(404,"发票记录不存在");Map<String,Object> row=rows.get(0);assertOrg(number(row.get("org_id"),"orgId"));return row;}
    private COSClient cos(){COSClient value=cosProvider.getIfAvailable();if(value==null)throw new BusinessException("COS未启用，无法上传电子发票");return value;}
    private String signedUrl(String key){try{Date expiration=new Date(System.currentTimeMillis()+Math.max(60,cosProperties.urlExpireSeconds())*1000);return cos().generatePresignedUrl(cosProperties.bucket(),key,expiration, HttpMethodName.GET).toString();}catch(RuntimeException exception){return null;}}
    private List<Long> ids(Object value){if(!(value instanceof List<?> list))return List.of();List<Long> ids=new ArrayList<>();for(Object item:list){try{long id=Long.parseLong(String.valueOf(item));if(!ids.contains(id))ids.add(id);}catch(Exception ignored){}}return ids;}
    private void assertOrg(long id){if(!access.hasOrgAccess(id))throw new BusinessException(403,"没有该园区发票权限");}
    private long number(Object value,String name){try{return Long.parseLong(String.valueOf(value));}catch(Exception exception){throw new BusinessException(name+"必须为数字");}}
    private BigDecimal money(Object value){try{return new BigDecimal(String.valueOf(value));}catch(Exception exception){return BigDecimal.ZERO;}}
    private int positive(String value,int fallback){try{int n=Integer.parseInt(value);return n>0?n:fallback;}catch(Exception ignored){return fallback;}}
    private String invoiceCode(long orgId){return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMM"))+String.format("%06d",Math.floorMod(orgId,1_000_000));}
    private String invoiceNumber(long sourceId){return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))+String.format("%06d",Math.floorMod(sourceId,1_000_000));}
    private String required(Object value,String name){String result=text(value);if(result==null)throw new BusinessException(name+"不能为空");return result;}
    private String text(Object value){if(value==null)return null;String result= Objects.toString(value,"").trim();return result.isEmpty()?null:result;}
    private String textOr(Object value,String fallback){String result=text(value);return result==null?fallback:result;}
}
