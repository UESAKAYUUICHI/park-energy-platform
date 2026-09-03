package com.parkenergyplatform.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.dev33.satoken.stp.StpUtil;
import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.common.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant master data with explicit user ownership. */
@Service
public class TenantService {
    private final JdbcTemplate jdbcTemplate;
    private final DataScopeService dataScopeService;

    public TenantService(JdbcTemplate jdbcTemplate, DataScopeService dataScopeService) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataScopeService = dataScopeService;
    }

    public PageResult<Map<String, Object>> page(Map<String, String> params) {
        int pageNum = positive(params.get("pageNum"), 1);
        int pageSize = Math.min(positive(params.get("pageSize"), 20), 200);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        String keyword = text(params.get("keyword"));
        if (keyword != null) {
            where.append(" AND (t.tenant_code LIKE ? OR t.tenant_name LIKE ? OR t.contact_name LIKE ?)");
            for (int i = 0; i < 3; i++) args.add("%" + keyword + "%");
        }
        String status = text(params.get("status"));
        if (status != null) { where.append(" AND t.status=?"); args.add(status); }
        if (!isAdmin()) { where.append(" AND t.owner_user_id=?"); args.add(currentUserId()); }
        String from = " FROM crm_tenant t JOIN sys_user u ON u.id=t.owner_user_id";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize); pageArgs.add((pageNum - 1) * pageSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT t.*, u.username AS owner_username, u.nickname AS owner_name
                """ + from + where + " ORDER BY t.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        return PageResult.of(rows, total == null ? 0 : total, pageNum, pageSize);
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        requireAdmin();
        String name = required(body.get("tenantName"), "租户名称");
        long ownerId = ownerId(body);
        assertActiveUser(ownerId);
        String code = text(body.get("tenantCode"));
        if (code == null) code = "TENANT-" + System.currentTimeMillis();
        if (count("SELECT COUNT(*) FROM crm_tenant WHERE tenant_code=?", code) > 0) throw new BusinessException(409, "租户编码已存在");
        jdbcTemplate.update("""
                INSERT INTO crm_tenant (tenant_code,tenant_name,tenant_type,unified_social_credit_code,contact_name,contact_phone,contact_email,billing_address,status,owner_user_id,remark,create_by,update_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, code, name, value(body, "tenantType", "ENTERPRISE"), text(body.get("unifiedSocialCreditCode")),
                required(body.get("contactName"), "联系人"), required(body.get("contactPhone"), "联系电话"), text(body.get("contactEmail")),
                text(body.get("billingAddress")), value(body, "status", "ACTIVE"), ownerId, text(body.get("remark")), operator(), operator());
        Long id = jdbcTemplate.queryForObject("SELECT id FROM crm_tenant WHERE tenant_code=?", Long.class, code);
        return detail(id == null ? 0 : id);
    }

    @Transactional
    public Map<String, Object> update(long id, Map<String, Object> body) {
        requireAdmin();
        detail(id);
        long ownerId = ownerId(body);
        assertActiveUser(ownerId);
        String code = required(body.get("tenantCode"), "租户编码");
        if (count("SELECT COUNT(*) FROM crm_tenant WHERE tenant_code=? AND id<>?", code, id) > 0) throw new BusinessException(409, "租户编码已存在");
        jdbcTemplate.update("""
                UPDATE crm_tenant SET tenant_code=?,tenant_name=?,tenant_type=?,unified_social_credit_code=?,contact_name=?,contact_phone=?,contact_email=?,billing_address=?,status=?,owner_user_id=?,remark=?,update_by=? WHERE id=?
                """, code, required(body.get("tenantName"), "租户名称"), value(body, "tenantType", "ENTERPRISE"), text(body.get("unifiedSocialCreditCode")),
                required(body.get("contactName"), "联系人"), required(body.get("contactPhone"), "联系电话"), text(body.get("contactEmail")), text(body.get("billingAddress")),
                value(body, "status", "ACTIVE"), ownerId, text(body.get("remark")), operator(), id);
        return detail(id);
    }

    @Transactional
    public void delete(long id) {
        requireAdmin();
        detail(id);
        if (count("SELECT COUNT(*) FROM leasing_contract WHERE tenant_id=?", id) > 0 || count("SELECT COUNT(*) FROM billing_account WHERE tenant_id=?", id) > 0)
            throw new BusinessException("租户已关联合同或财务账户，不能删除；请停用该租户保留历史记录");
        jdbcTemplate.update("DELETE FROM crm_tenant WHERE id=?", id);
    }

    /** Invoked by contract lifecycle; ordinary users can only bind their own active tenants. */
    public void assertSelectable(long tenantId) {
        Map<String, Object> tenant = detail(tenantId);
        if (!"ACTIVE".equalsIgnoreCase(String.valueOf(tenant.get("status")))) throw new BusinessException("租户已停用，不能用于新建或修改合同");
        assertVisible(tenant);
    }

    public void assertVisible(long tenantId) { assertVisible(detail(tenantId)); }
    public String scopeSql(String tenantColumn, List<Object> args) {
        if (isAdmin()) return "";
        args.add(currentUserId());
        return " AND EXISTS (SELECT 1 FROM crm_tenant tenant_scope WHERE tenant_scope.id=" + tenantColumn + " AND tenant_scope.owner_user_id=?)";
    }

    private Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT t.*,u.username AS owner_username,u.nickname AS owner_name FROM crm_tenant t JOIN sys_user u ON u.id=t.owner_user_id WHERE t.id=?", id);
        if (rows.isEmpty()) throw new BusinessException(404, "租户不存在");
        return new LinkedHashMap<>(rows.get(0));
    }
    private void requireAdmin() { if (!isAdmin()) throw new BusinessException(403, "仅管理员可以配置租户档案"); }
    private void assertVisible(Map<String, Object> tenant) { if (!isAdmin() && ((Number) tenant.get("owner_user_id")).longValue() != currentUserId()) throw new BusinessException(403, "只能访问本人名下的租户"); }
    private boolean isAdmin() { return StpUtil.isLogin() && dataScopeService.isSuperAdmin(currentUserId()); }
    private long currentUserId() { return StpUtil.getLoginIdAsLong(); }
    private long ownerId(Map<String, Object> body) { try { return Long.parseLong(String.valueOf(body.get("ownerUserId"))); } catch (Exception e) { throw new BusinessException("请选择租户归属用户"); } }
    private void assertActiveUser(long id) { Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=? AND status=1", Long.class, id); if (value == null || value == 0) throw new BusinessException("归属用户不存在或已禁用"); }
    private Long count(String sql, Object... args) { Long value = jdbcTemplate.queryForObject(sql, Long.class, args); return value == null ? 0 : value; }
    private String required(Object value, String label) { String result = text(value); if (result == null) throw new BusinessException(label + "不能为空"); return result; }
    private String text(Object value) { return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim(); }
    private String value(Map<String, Object> body, String key, String fallback) { String result = text(body.get(key)); return result == null ? fallback : result; }
    private int positive(String value, int fallback) { try { return value == null ? fallback : Math.max(1, Integer.parseInt(value)); } catch (Exception ignored) { return fallback; } }
    private String operator() { Object username = StpUtil.getSession().get("username"); return username == null ? String.valueOf(currentUserId()) : String.valueOf(username); }
}
