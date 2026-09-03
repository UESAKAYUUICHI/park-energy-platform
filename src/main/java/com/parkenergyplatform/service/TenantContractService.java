package com.parkenergyplatform.service;

import java.sql.Date;
import java.time.LocalDate;
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

/**
 * Contract lifecycle service. A contract is the only business object allowed to own settlement meters.
 */
@Service
public class TenantContractService {
    private final JdbcTemplate jdbcTemplate;
    private final BusinessDataAccessService accessService;
    private final BillingSpaceScopeService spaceScopeService;
    private final TenantService tenantService;

    public TenantContractService(JdbcTemplate jdbcTemplate, BusinessDataAccessService accessService, BillingSpaceScopeService spaceScopeService, TenantService tenantService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessService = accessService;
        this.spaceScopeService = spaceScopeService;
        this.tenantService = tenantService;
    }

    public PageResult<Map<String, Object>> page(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (text(params.get("status")) != null) {
            where.append(" AND c.status = ?");
            args.add(params.get("status"));
        }
        if (text(params.get("orgId")) != null) {
            where.append(" AND c.org_id = ?");
            args.add(Long.parseLong(params.get("orgId")));
        }
        if (text(params.get("keyword")) != null) {
            where.append(" AND (c.contract_no LIKE ? OR c.contract_name LIKE ? OR t.tenant_name LIKE ?)");
            for (int i = 0; i < 3; i++) args.add("%" + params.get("keyword").trim() + "%");
        }
        where.append(accessService.scopeSql("c.org_id", args));
        where.append(tenantService.scopeSql("c.tenant_id", args));
        int pageNum = positive(params.get("pageNum"), 1), pageSize = Math.min(positive(params.get("pageSize"), 20), 200);
        String from = " FROM leasing_contract c JOIN crm_tenant t ON t.id=c.tenant_id LEFT JOIN dev_org o ON o.id=c.org_id";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((pageNum - 1) * pageSize);
        String select = "SELECT c.id, c.contract_no, c.contract_name, c.tenant_id, c.org_id, c.start_date, c.end_date, c.status, c.create_time, "
                + "t.tenant_name, t.contact_name, o.org_name";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(select + from + where + " ORDER BY c.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        hydrateListRelations(rows);
        return PageResult.of(rows, total == null ? 0 : total, pageNum, pageSize);
    }

    private void hydrateListRelations(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return;
        List<Long> ids = rows.stream().map(row -> Long.valueOf(String.valueOf(row.get("id")))).toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Map<Long, Map<String, Object>> summaries = new LinkedHashMap<>();
        for (Long id : ids) summaries.put(id, new LinkedHashMap<>());
        jdbcTemplate.queryForList("SELECT cs.contract_id, GROUP_CONCAT(DISTINCT s.space_name ORDER BY s.space_name SEPARATOR '、') AS names, COUNT(DISTINCT cs.space_id) AS total FROM leasing_contract_space cs JOIN park_space s ON s.id=cs.space_id WHERE cs.contract_id IN (" + placeholders + ") GROUP BY cs.contract_id", ids.toArray())
                .forEach(row -> { Map<String, Object> summary = summaries.get(Long.valueOf(String.valueOf(row.get("contract_id")))); summary.put("space_names", row.get("names")); summary.put("space_count", row.get("total")); });
        jdbcTemplate.queryForList("SELECT cm.contract_id, GROUP_CONCAT(DISTINCT CONCAT(COALESCE(d.device_name,d.device_sn),'（',d.device_sn,'）') ORDER BY d.device_name SEPARATOR '、') AS names, COUNT(DISTINCT cm.device_id) AS total FROM leasing_contract_meter cm JOIN dev_device d ON d.id=cm.device_id WHERE cm.contract_id IN (" + placeholders + ") GROUP BY cm.contract_id", ids.toArray())
                .forEach(row -> { Map<String, Object> summary = summaries.get(Long.valueOf(String.valueOf(row.get("contract_id")))); summary.put("device_names", row.get("names")); summary.put("meter_count", row.get("total")); });
        jdbcTemplate.queryForList("SELECT ba.contract_id, GROUP_CONCAT(DISTINCT br.rule_name ORDER BY br.rule_name SEPARATOR '、') AS names, COUNT(*) AS total FROM billing_account ba JOIN billing_rule br ON br.account_id=ba.id AND br.enabled=1 WHERE ba.contract_id IN (" + placeholders + ") GROUP BY ba.contract_id", ids.toArray())
                .forEach(row -> { Map<String, Object> summary = summaries.get(Long.valueOf(String.valueOf(row.get("contract_id")))); summary.put("rule_names", row.get("names")); summary.put("rule_count", row.get("total")); });
        for (Map<String, Object> row : rows) {
            Map<String, Object> summary = summaries.get(Long.valueOf(String.valueOf(row.get("id"))));
            row.putAll(summary);
            row.putIfAbsent("space_count", 0); row.putIfAbsent("meter_count", 0); row.putIfAbsent("rule_count", 0);
        }
    }

    public Map<String, Object> detail(long id) {
        Map<String, Object> contract = required(id);
        assertAccess(contract);
        Map<String, Object> result = new LinkedHashMap<>(contract);
        List<Map<String, Object>> contractSpaces = jdbcTemplate.queryForList("SELECT cs.*, s.space_code, s.space_name, s.space_type FROM leasing_contract_space cs JOIN park_space s ON s.id=cs.space_id WHERE cs.contract_id=? ORDER BY s.space_name, s.id", id);
        List<Map<String, Object>> contractMeters = jdbcTemplate.queryForList("SELECT cm.*, d.device_sn, d.device_name, d.meter_role, d.device_type_id, dt.type_code, dt.type_name, d.space_id, s.space_code, s.space_name FROM leasing_contract_meter cm JOIN dev_device d ON d.id=cm.device_id LEFT JOIN dev_device_type dt ON dt.id=d.device_type_id LEFT JOIN park_space s ON s.id=d.space_id WHERE cm.contract_id=? ORDER BY d.device_name, d.id", id);
        List<Map<String, Object>> contractRules = jdbcTemplate.queryForList("SELECT br.id, br.rule_name, br.price_mode, br.billing_cycle, br.enabled, br.create_time FROM billing_rule br JOIN billing_account ba ON ba.id=br.account_id WHERE ba.contract_id=? ORDER BY br.id", id);
        result.put("spaces", contractSpaces);
        result.put("meters", contractMeters);
        result.put("spaceCount", contractSpaces.size());
        result.put("meterCount", contractMeters.size());
        result.put("account", singleOrNull("SELECT * FROM billing_account WHERE contract_id=?", id));
        result.put("rules", contractRules);
        result.put("ruleCount", contractRules.size());
        return result;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        validate(body);
        tenantService.assertSelectable(longValue(body.get("tenantId"), "tenantId"));
        long orgId = longValue(body.get("orgId"), "orgId");
        assertOrg(orgId);
        String number = requiredText(body.get("contractNo"), "contractNo");
        if (count("SELECT COUNT(*) FROM leasing_contract WHERE contract_no=?", number) > 0)
            throw new BusinessException(409, "合同编号已存在");
        jdbcTemplate.update("INSERT INTO leasing_contract (contract_no, tenant_id, org_id, contract_name, start_date, end_date, settlement_day, deposit_amount, status, remark) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?)",
                number, longValue(body.get("tenantId"), "tenantId"), orgId, requiredText(body.get("contractName"), "contractName"), date(body.get("startDate")), date(body.get("endDate")), numberOr(body.get("settlementDay"), 1), decimal(body.get("depositAmount"), java.math.BigDecimal.ZERO), text(body.get("remark")));
        long id = jdbcTemplate.queryForObject("SELECT id FROM leasing_contract WHERE contract_no=?", Long.class, number);
        replaceRelations(id, body);
        return detail(id);
    }

    @Transactional
    public Map<String, Object> update(long id, Map<String, Object> body) {
        Map<String, Object> existing = required(id);
        assertAccess(existing);
        if (!"DRAFT".equalsIgnoreCase(String.valueOf(existing.get("status"))))
            throw new BusinessException("只有草稿合同允许直接修改；生效合同请走终止或续签流程");
        validate(body);
        tenantService.assertSelectable(longValue(body.get("tenantId"), "tenantId"));
        assertOrg(longValue(body.get("orgId"), "orgId"));
        jdbcTemplate.update("UPDATE leasing_contract SET tenant_id=?, org_id=?, contract_name=?, start_date=?, end_date=?, settlement_day=?, deposit_amount=?, remark=? WHERE id=?",
                longValue(body.get("tenantId"), "tenantId"), longValue(body.get("orgId"), "orgId"), requiredText(body.get("contractName"), "contractName"), date(body.get("startDate")), date(body.get("endDate")), numberOr(body.get("settlementDay"), 1), decimal(body.get("depositAmount"), java.math.BigDecimal.ZERO), text(body.get("remark")), id);
        replaceRelations(id, body);
        return detail(id);
    }

    @Transactional
    public Map<String, Object> activate(long id) {
        Map<String, Object> contract = required(id);
        assertAccess(contract);
        if (!"DRAFT".equalsIgnoreCase(String.valueOf(contract.get("status"))))
            throw new BusinessException("仅草稿合同可以生效");
        Long spaces = count("SELECT COUNT(*) FROM leasing_contract_space WHERE contract_id=?", id);
        Long meters = count("SELECT COUNT(*) FROM leasing_contract_meter WHERE contract_id=? AND status='ACTIVE'", id);
        if (spaces == 0 || meters == 0) throw new BusinessException("合同必须至少分配一个空间和一块结算表计");
        LocalDate start = ((Date) contract.get("start_date")).toLocalDate();
        LocalDate end = ((Date) contract.get("end_date")).toLocalDate();
        if (count("SELECT COUNT(*) FROM leasing_contract_space x JOIN leasing_contract c ON c.id=x.contract_id JOIN leasing_contract_space mine ON mine.space_id=x.space_id WHERE mine.contract_id=? AND c.status='ACTIVE' AND x.rent_start_date <= ? AND (x.rent_end_date IS NULL OR x.rent_end_date >= ?)", id, Date.valueOf(end), Date.valueOf(start)) > 0)
            throw new BusinessException("合同空间与已有生效合同重叠");
        if (count("SELECT COUNT(*) FROM leasing_contract_meter x JOIN leasing_contract c ON c.id=x.contract_id JOIN leasing_contract_meter mine ON mine.device_id=x.device_id WHERE mine.contract_id=? AND c.status='ACTIVE' AND x.start_date <= ? AND (x.end_date IS NULL OR x.end_date >= ?)", id, Date.valueOf(end), Date.valueOf(start)) > 0)
            throw new BusinessException("结算表计已被其他生效合同占用");
        jdbcTemplate.update("UPDATE leasing_contract SET status='ACTIVE', signed_by=?, signed_time=NOW() WHERE id=?", "platform", id);
        ensureBillingAccount(contract);
        return detail(id);
    }

    @Transactional
    public Map<String, Object> terminate(long id, Map<String, Object> body) {
        Map<String, Object> contract = required(id);
        assertAccess(contract);
        LocalDate end = body.get("endDate") == null || text(body.get("endDate")) == null
                ? LocalDate.now() : date(body.get("endDate")).toLocalDate();
        if (end.isBefore(((Date) contract.get("start_date")).toLocalDate()))
            throw new BusinessException("终止日期不能早于合同开始日期");
        jdbcTemplate.update("UPDATE leasing_contract SET status='TERMINATED', end_date=?, remark=CONCAT(COALESCE(remark,''),' [TERMINATED] ',?) WHERE id=?", Date.valueOf(end), text(body.get("remark")), id);
        jdbcTemplate.update("UPDATE leasing_contract_meter SET status='ENDED', end_date=? WHERE contract_id=? AND (end_date IS NULL OR end_date>?)", Date.valueOf(end), id, Date.valueOf(end));
        return detail(id);
    }

    @Transactional
    public void delete(long id) {
        Map<String, Object> contract = required(id);
        assertAccess(contract);
        if (!"TERMINATED".equalsIgnoreCase(String.valueOf(contract.get("status"))))
            throw new BusinessException("只有已终止合同可以删除");
        if (count("SELECT COUNT(*) FROM billing_bill b JOIN billing_account a ON a.id=b.account_id WHERE a.contract_id=?", id) > 0)
            throw new BusinessException("合同已经产生账单，不能删除；请保留合同档案");
        // 账户和规则可能已经被其他财务辅助表引用。合同删除时保留其技术账户但解除合同归属，
        // 同时停用规则，避免为了删除一条已终止合同破坏历史关联或触发外键 500。
        List<Map<String, Object>> accounts = jdbcTemplate.queryForList("SELECT id FROM billing_account WHERE contract_id=?", id);
        for (Map<String, Object> account : accounts) {
            jdbcTemplate.update("UPDATE billing_rule SET enabled=0 WHERE account_id=?", account.get("id"));
        }
        jdbcTemplate.update("UPDATE billing_account SET contract_id=NULL, status=0 WHERE contract_id=?", id);
        jdbcTemplate.update("DELETE FROM leasing_contract_space WHERE contract_id=?", id);
        jdbcTemplate.update("DELETE FROM leasing_contract_meter WHERE contract_id=?", id);
        jdbcTemplate.update("DELETE FROM leasing_contract WHERE id=?", id);
    }

    private void replaceRelations(long contractId, Map<String, Object> body) {
        List<Map<String, Object>> spaces = list(body.get("spaces"), "spaces");
        List<Map<String, Object>> meters = list(body.get("meters"), "meters");
        List<Long> spaceIds = spaces.stream().map(x -> longValue(x.get("spaceId"), "spaceId")).toList();
        List<Long> meterIds = meters.stream().map(x -> longValue(x.get("deviceId"), "deviceId")).toList();
        spaceScopeService.assertDevicesWithinSpaces(spaceIds, meterIds);
        jdbcTemplate.update("DELETE FROM leasing_contract_space WHERE contract_id=?", contractId);
        jdbcTemplate.update("DELETE FROM leasing_contract_meter WHERE contract_id=?", contractId);
        for (Map<String, Object> space : spaces) {
            long spaceId = longValue(space.get("spaceId"), "spaceId");
            Map<String, Object> spaceRow = singleOrNull("SELECT id, status FROM park_space WHERE id=?", spaceId);
            if (spaceRow == null) throw new BusinessException("绑定空间不存在");
            String status = String.valueOf(spaceRow.get("status"));
            if ("DISABLED".equalsIgnoreCase(status) || "INACTIVE".equalsIgnoreCase(status))
                throw new BusinessException("不能绑定已停用空间，请重新选择可用空间");
            jdbcTemplate.update("INSERT INTO leasing_contract_space (contract_id, space_id, rent_start_date, rent_end_date) VALUES (?, ?, ?, ?)", contractId, spaceId, date(space.getOrDefault("startDate", body.get("startDate"))), nullableDate(space.get("endDate")));
        }
        for (Map<String, Object> meter : meters) {
            long deviceId = longValue(meter.get("deviceId"), "deviceId");
            java.math.BigDecimal factor = decimal(meter.get("meterFactor"), deviceMeterFactor(deviceId));
            if (factor.compareTo(java.math.BigDecimal.ZERO) <= 0) throw new BusinessException("结算表计倍率必须大于 0");
            jdbcTemplate.update("INSERT INTO leasing_contract_meter (contract_id, device_id, start_date, end_date, meter_factor, status, remark) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)", contractId, deviceId, date(meter.getOrDefault("startDate", body.get("startDate"))), nullableDate(meter.get("endDate")), factor, text(meter.get("remark")));
        }
    }

    private void ensureBillingAccount(Map<String, Object> contract) {
        long id = ((Number) contract.get("id")).longValue();
        if (count("SELECT COUNT(*) FROM billing_account WHERE contract_id=?", id) > 0) return;
        Map<String, Object> tenant = single("SELECT * FROM crm_tenant WHERE id=?", contract.get("tenant_id"));
        jdbcTemplate.update("INSERT INTO billing_account (account_name, org_id, tenant_id, contract_id, contact_name, contact_phone, status) VALUES (?, ?, ?, ?, ?, ?, 1)", tenant.get("tenant_name") + "-" + contract.get("contract_no"), contract.get("org_id"), contract.get("tenant_id"), id, tenant.get("contact_name"), tenant.get("contact_phone"));
    }

    private java.math.BigDecimal deviceMeterFactor(long deviceId) {
        java.math.BigDecimal factor = jdbcTemplate.queryForObject("SELECT meter_factor FROM dev_device WHERE id=?", java.math.BigDecimal.class, deviceId);
        return factor == null || factor.compareTo(java.math.BigDecimal.ZERO) <= 0 ? java.math.BigDecimal.ONE : factor;
    }

    private void validate(Map<String, Object> body) {
        LocalDate endDate = date(body.get("endDate")).toLocalDate();
        LocalDate startDate = date(body.get("startDate")).toLocalDate();
        if (endDate.isBefore(startDate))
            throw new BusinessException("合同结束日期不能早于开始日期");
        if (list(body.get("spaces"), "spaces").isEmpty() || list(body.get("meters"), "meters").isEmpty())
            throw new BusinessException("合同必须选择空间和结算表计");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Object value, String field) {
        if (!(value instanceof List<?> rows)) throw new BusinessException(field + " 必须为数组");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) throw new BusinessException(field + " 格式错误");
            Map<String, Object> m = new LinkedHashMap<>();
            map.forEach((k, v) -> m.put(String.valueOf(k), v));
            result.add(m);
        }
        return result;
    }

    private Map<String, Object> required(long id) {
        Map<String, Object> row = singleOrNull("SELECT c.*,t.tenant_name,t.contact_name,o.org_name FROM leasing_contract c JOIN crm_tenant t ON t.id=c.tenant_id JOIN dev_org o ON o.id=c.org_id WHERE c.id=?", id);
        if (row == null) throw new BusinessException(404, "合同不存在");
        return row;
    }

    private Map<String, Object> single(String sql, Object... args) {
        Map<String, Object> row = singleOrNull(sql, args);
        if (row == null) throw new BusinessException(404, "数据不存在");
        return row;
    }

    private Map<String, Object> singleOrNull(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void assertAccess(Map<String, Object> c) {
        assertOrg(((Number) c.get("org_id")).longValue());
        tenantService.assertVisible(((Number) c.get("tenant_id")).longValue());
    }

    private void assertOrg(long orgId) {
        if (!accessService.hasOrgAccess(orgId)) throw new BusinessException(403, "没有该园区合同操作权限");
    }

    private Long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private String text(Object v) {
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).trim();
    }

    private String requiredText(Object v, String n) {
        String s = text(v);
        if (s == null) throw new BusinessException(n + " 不能为空");
        return s;
    }

    private long longValue(Object v, String n) {
        if (v == null) throw new BusinessException(n + " 不能为空");
        return Long.parseLong(String.valueOf(v));
    }

    private int numberOr(Object v, int d) {
        try {
            return v == null ? d : Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return d;
        }
    }

    private java.math.BigDecimal decimal(Object v, java.math.BigDecimal fallback) {
        return v == null || String.valueOf(v).isBlank() ? fallback : new java.math.BigDecimal(String.valueOf(v));
    }

    private Date date(Object v) {
        return Date.valueOf(LocalDate.parse(requiredText(v, "日期")));
    }

    private Date nullableDate(Object v) {
        String s = text(v);
        return s == null ? null : Date.valueOf(LocalDate.parse(s));
    }

    private int positive(String v, int d) {
        try {
            int n = Integer.parseInt(v);
            return n > 0 ? n : d;
        } catch (Exception e) {
            return d;
        }
    }
}
