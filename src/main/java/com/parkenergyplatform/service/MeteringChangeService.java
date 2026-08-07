package com.parkenergyplatform.service;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import cn.dev33.satoken.stp.StpUtil;
import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.common.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit-controlled meter lifecycle changes.  Historic telemetry is never rewritten here: a manual
 * reading is a signed operational fact, while already-issued bill corrections remain in BillingAdjustmentService.
 */
@Service
public class MeteringChangeService {
    private final JdbcTemplate jdbcTemplate;
    private final BusinessDataAccessService accessService;

    public MeteringChangeService(JdbcTemplate jdbcTemplate, BusinessDataAccessService accessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessService = accessService;
    }

    public PageResult<Map<String, Object>> page(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        equals(where, args, "m.status", params.get("status"));
        equals(where, args, "m.change_type", params.get("changeType"));
        equals(where, args, "m.org_id", params.get("orgId"));
        String keyword = text(params.get("keyword"));
        if (keyword != null) {
            where.append(" AND (m.change_no LIKE ? OR m.reason LIKE ? OR s.device_name LIKE ? OR s.device_sn LIKE ?)");
            for (int i = 0; i < 4; i++) args.add("%" + keyword + "%");
        }
        where.append(accessService.scopeSql("m.org_id", args));
        int pageNum = positive(params.get("pageNum"), 1);
        int pageSize = Math.min(positive(params.get("pageSize"), 20), 200);
        String from = " FROM billing_meter_change_order m JOIN dev_device s ON s.id=m.source_device_id "
                + "LEFT JOIN dev_device t ON t.id=m.target_device_id LEFT JOIN leasing_contract c ON c.id=m.contract_id "
                + "LEFT JOIN dev_org o ON o.id=m.org_id";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((pageNum - 1) * pageSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT m.*, o.org_name, c.contract_no, c.contract_name, "
                + "s.device_sn AS source_device_sn, s.device_name AS source_device_name, "
                + "t.device_sn AS target_device_sn, t.device_name AS target_device_name" + from + where
                + " ORDER BY m.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        return PageResult.of(rows, total == null ? 0 : total, pageNum, pageSize);
    }

    public Map<String, Object> detail(long id) {
        Map<String, Object> row = required(id, false);
        row.put("logs", jdbcTemplate.queryForList("SELECT * FROM billing_meter_change_log WHERE change_order_id=? ORDER BY id", id));
        return row;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        long orgId = requiredLong(body.get("orgId"), "orgId");
        assertOrg(orgId);
        String type = requiredText(body.get("changeType"), "changeType").toUpperCase();
        if (!List.of("FACTOR_CHANGE", "REPLACE", "RESET", "MANUAL_READING").contains(type)) {
            throw new BusinessException("Unsupported meter change type");
        }
        long sourceDeviceId = requiredLong(body.get("sourceDeviceId"), "sourceDeviceId");
        Map<String, Object> source = device(sourceDeviceId);
        assertDeviceOrg(source, orgId, "sourceDeviceId");
        Long targetDeviceId = nullableLong(body.get("targetDeviceId"));
        if ("REPLACE".equals(type)) {
            if (targetDeviceId == null || targetDeviceId == sourceDeviceId) throw new BusinessException("Replacement requires a different targetDeviceId");
            Map<String, Object> target = device(targetDeviceId);
            assertDeviceOrg(target, orgId, "targetDeviceId");
            if (number(target.get("settlement_enabled")).intValue() != 1) throw new BusinessException("Replacement target must be a settlement meter");
        } else if (targetDeviceId != null) {
            throw new BusinessException("Only REPLACE may specify targetDeviceId");
        }
        Long contractId = nullableLong(body.get("contractId"));
        if (contractId != null) assertContractOrg(contractId, orgId);
        LocalDateTime effectiveTime = requiredDateTime(body.get("effectiveTime"), "effectiveTime");
        BigDecimal oldFactor = decimal(source.get("meter_factor"), BigDecimal.ONE);
        BigDecimal newFactor = nullableDecimal(body.get("newFactor"));
        if ("FACTOR_CHANGE".equals(type) && (newFactor == null || newFactor.compareTo(BigDecimal.ZERO) <= 0)) {
            throw new BusinessException("FACTOR_CHANGE requires newFactor greater than zero");
        }
        if ("REPLACE".equals(type) && newFactor == null) {
            newFactor = decimal(device(targetDeviceId).get("meter_factor"), BigDecimal.ONE);
        }
        if (newFactor != null && newFactor.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("newFactor must be greater than zero");
        BigDecimal oldReading = nullableDecimal(body.get("oldReading"));
        BigDecimal newReading = nullableDecimal(body.get("newReading"));
        if (("REPLACE".equals(type) || "RESET".equals(type) || "MANUAL_READING".equals(type)) && newReading == null) {
            throw new BusinessException(type + " requires newReading");
        }
        Actor actor = actor();
        String no = textOr(body.get("changeNo"), "MC" + System.currentTimeMillis());
        if (count("SELECT COUNT(*) FROM billing_meter_change_order WHERE change_no=?", no) > 0) throw new BusinessException("Meter change number already exists");
        jdbcTemplate.update("INSERT INTO billing_meter_change_order (change_no, org_id, contract_id, change_type, source_device_id, target_device_id, point_code, effective_time, old_factor, new_factor, old_reading, new_reading, reason, evidence_urls, applicant_user_id, applicant_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                no, orgId, contractId, type, sourceDeviceId, targetDeviceId, textOr(body.get("pointCode"), "total_energy"), Timestamp.valueOf(effectiveTime),
                oldFactor, newFactor, oldReading, newReading, requiredText(body.get("reason"), "reason"), text(body.get("evidenceUrls")), actor.userId(), actor.name());
        Long id = jdbcTemplate.queryForObject("SELECT id FROM billing_meter_change_order WHERE change_no=?", Long.class, no);
        log(id, "CREATE", null, "PENDING", actor, text(body.get("reason")));
        return detail(id);
    }

    @Transactional
    public Map<String, Object> action(long id, String action, Map<String, Object> body) {
        Map<String, Object> order = required(id, true);
        Actor actor = actor();
        String status = textOr(order.get("status"), "PENDING");
        String normalized = requiredText(action, "action").toUpperCase();
        switch (normalized) {
            case "APPROVE" -> {
                requireStatus(status, "PENDING");
                if (actor.userId() != null && Objects.equals(actor.userId(), nullableLong(order.get("applicant_user_id")))) {
                    throw new BusinessException("Applicant and approver must be different users");
                }
                jdbcTemplate.update("UPDATE billing_meter_change_order SET status='APPROVED', approver_user_id=?, approver_name=?, approve_time=NOW(), approve_remark=? WHERE id=?",
                        actor.userId(), actor.name(), text(body.get("remark")), id);
                log(id, "APPROVE", status, "APPROVED", actor, text(body.get("remark")));
            }
            case "REJECT" -> {
                requireStatus(status, "PENDING");
                String remark = requiredText(body.get("remark"), "remark");
                jdbcTemplate.update("UPDATE billing_meter_change_order SET status='REJECTED', approver_user_id=?, approver_name=?, approve_time=NOW(), approve_remark=? WHERE id=?",
                        actor.userId(), actor.name(), remark, id);
                log(id, "REJECT", status, "REJECTED", actor, remark);
            }
            case "CANCEL" -> {
                requireStatus(status, "PENDING", "APPROVED");
                String remark = requiredText(body.get("remark"), "remark");
                jdbcTemplate.update("UPDATE billing_meter_change_order SET status='CANCELLED', execute_remark=? WHERE id=?", remark, id);
                log(id, "CANCEL", status, "CANCELLED", actor, remark);
            }
            case "EXECUTE" -> execute(order, actor, text(body.get("remark")));
            default -> throw new BusinessException("Unsupported meter change action: " + action);
        }
        return detail(id);
    }

    private void execute(Map<String, Object> order, Actor actor, String remark) {
        String status = textOr(order.get("status"), "PENDING");
        requireStatus(status, "APPROVED");
        LocalDate effectiveDate = timestamp(order.get("effective_time")).toLocalDateTime().toLocalDate();
        if (!effectiveDate.equals(LocalDate.now())) throw new BusinessException("Execute the change on its effective date to keep billing periods auditable");
        String type = textOr(order.get("change_type"), "");
        long sourceDeviceId = number(order.get("source_device_id")).longValue();
        if ("FACTOR_CHANGE".equals(type)) {
            if (effectiveDate.getDayOfMonth() != 1) throw new BusinessException("Meter factor changes must take effect on the first day of a billing month");
            BigDecimal newFactor = decimal(order.get("new_factor"), BigDecimal.ZERO);
            if (newFactor.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("Invalid new meter factor");
            jdbcTemplate.update("UPDATE dev_device SET meter_factor=? WHERE id=?", newFactor, sourceDeviceId);
            updateActiveContractFactor(order, newFactor, effectiveDate);
        } else if ("REPLACE".equals(type)) {
            replaceBindings(order, effectiveDate);
        }
        long id = number(order.get("id")).longValue();
        jdbcTemplate.update("UPDATE billing_meter_change_order SET status='EXECUTED', executor_user_id=?, executor_name=?, execute_time=NOW(), execute_remark=? WHERE id=?",
                actor.userId(), actor.name(), remark, id);
        log(id, "EXECUTE", status, "EXECUTED", actor, remark);
    }

    private void updateActiveContractFactor(Map<String, Object> order, BigDecimal newFactor, LocalDate effectiveDate) {
        StringBuilder sql = new StringBuilder("UPDATE leasing_contract_meter SET meter_factor=? WHERE device_id=? AND status='ACTIVE' AND start_date<=? AND (end_date IS NULL OR end_date>=?)");
        List<Object> args = new ArrayList<>(List.of(newFactor, number(order.get("source_device_id")).longValue(), Date.valueOf(effectiveDate), Date.valueOf(effectiveDate)));
        Long contractId = nullableLong(order.get("contract_id"));
        if (contractId != null) { sql.append(" AND contract_id=?"); args.add(contractId); }
        jdbcTemplate.update(sql.toString(), args.toArray());
    }

    private void replaceBindings(Map<String, Object> order, LocalDate effectiveDate) {
        long sourceDeviceId = number(order.get("source_device_id")).longValue();
        long targetDeviceId = number(order.get("target_device_id")).longValue();
        List<Object> args = new ArrayList<>(List.of(sourceDeviceId, Date.valueOf(effectiveDate), Date.valueOf(effectiveDate)));
        String sql = "SELECT cm.* FROM leasing_contract_meter cm JOIN leasing_contract c ON c.id=cm.contract_id WHERE cm.device_id=? AND cm.status='ACTIVE' AND c.status='ACTIVE' AND cm.start_date<=? AND (cm.end_date IS NULL OR cm.end_date>=?)";
        Long contractId = nullableLong(order.get("contract_id"));
        if (contractId != null) { sql += " AND cm.contract_id=?"; args.add(contractId); }
        List<Map<String, Object>> bindings = jdbcTemplate.queryForList(sql + " FOR UPDATE", args.toArray());
        for (Map<String, Object> binding : bindings) {
            long bindingContractId = number(binding.get("contract_id")).longValue();
            Long occupied = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM leasing_contract_meter cm JOIN leasing_contract c ON c.id=cm.contract_id WHERE cm.device_id=? AND cm.status='ACTIVE' AND c.status='ACTIVE' AND cm.contract_id<>? AND cm.start_date<=? AND (cm.end_date IS NULL OR cm.end_date>=?)", Long.class,
                    targetDeviceId, bindingContractId, Date.valueOf(effectiveDate), Date.valueOf(effectiveDate));
            if (occupied != null && occupied > 0) throw new BusinessException("Target meter is already bound to another active contract");
            jdbcTemplate.update("UPDATE leasing_contract_meter SET status='ENDED', end_date=? WHERE id=?", Date.valueOf(effectiveDate.minusDays(1)), binding.get("id"));
            BigDecimal factor = order.get("new_factor") == null ? decimal(binding.get("meter_factor"), BigDecimal.ONE) : decimal(order.get("new_factor"), BigDecimal.ONE);
            jdbcTemplate.update("INSERT INTO leasing_contract_meter (contract_id, device_id, start_date, end_date, meter_factor, status, remark) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)",
                    bindingContractId, targetDeviceId, Date.valueOf(effectiveDate), binding.get("end_date"), factor,
                    "Meter change order " + order.get("change_no"));
        }
    }

    private Map<String, Object> required(long id, boolean lock) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM billing_meter_change_order WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw new BusinessException(404, "Meter change order not found");
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        assertOrg(number(row.get("org_id")).longValue());
        return row;
    }

    private Map<String, Object> device(long id) {
        accessService.assertDeviceAccess(id);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM dev_device WHERE id=?", id);
        if (rows.isEmpty()) throw new BusinessException(404, "Device not found");
        return rows.get(0);
    }

    private void assertDeviceOrg(Map<String, Object> device, long orgId, String field) {
        if (!Objects.equals(number(device.get("org_id")).longValue(), orgId)) throw new BusinessException(field + " must belong to the selected org");
    }

    private void assertContractOrg(long contractId, long orgId) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM leasing_contract WHERE id=? AND org_id=?", Long.class, contractId, orgId);
        if (count == null || count == 0) throw new BusinessException("Contract does not belong to the selected org");
    }

    private void assertOrg(long orgId) { if (!accessService.hasOrgAccess(orgId)) throw new BusinessException(403, "No access to this org"); }
    private void log(long orderId, String action, String from, String to, Actor actor, String content) { jdbcTemplate.update("INSERT INTO billing_meter_change_log (change_order_id, action, from_status, to_status, operator_user_id, operator_name, content) VALUES (?, ?, ?, ?, ?, ?, ?)", orderId, action, from, to, actor.userId(), actor.name(), content); }
    private Actor actor() { if (!StpUtil.isLogin()) return new Actor(null, "system"); Long userId = StpUtil.getLoginIdAsLong(); List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT username, nickname FROM sys_user WHERE id=?", userId); if (rows.isEmpty()) return new Actor(userId, "user-" + userId); Map<String,Object> row=rows.get(0); return new Actor(userId, textOr(row.get("nickname"), textOr(row.get("username"), "user-" + userId))); }
    private void requireStatus(String status, String... values) { for (String value : values) if (value.equalsIgnoreCase(status)) return; throw new BusinessException("Action is not allowed while order status is " + status); }
    private void equals(StringBuilder where, List<Object> args, String column, String value) { if (text(value) != null) { where.append(" AND ").append(column).append("=?"); args.add(value.trim()); } }
    private Long count(String sql, Object... args) { Long value = jdbcTemplate.queryForObject(sql, Long.class, args); return value == null ? 0 : value; }
    private Number number(Object value) { if (value instanceof Number n) return n; return Long.parseLong(String.valueOf(value)); }
    private String text(Object value) { return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim(); }
    private String textOr(Object value, String fallback) { String result = text(value); return result == null ? fallback : result; }
    private String requiredText(Object value, String field) { String result = text(value); if (result == null) throw new BusinessException(field + " is required"); return result; }
    private long requiredLong(Object value, String field) { if (value == null || String.valueOf(value).isBlank()) throw new BusinessException(field + " is required"); return Long.parseLong(String.valueOf(value)); }
    private Long nullableLong(Object value) { String result = text(value); return result == null ? null : Long.parseLong(result); }
    private BigDecimal decimal(Object value, BigDecimal fallback) { if (value == null || String.valueOf(value).isBlank()) return fallback; return new BigDecimal(String.valueOf(value)); }
    private BigDecimal nullableDecimal(Object value) { String result = text(value); return result == null ? null : new BigDecimal(result); }
    private LocalDateTime requiredDateTime(Object value, String field) { String result = requiredText(value, field).replace(' ', 'T'); try { return LocalDateTime.parse(result); } catch (Exception e) { throw new BusinessException(field + " must be an ISO datetime"); } }
    private Timestamp timestamp(Object value) { if (value instanceof Timestamp t) return t; if (value instanceof java.util.Date d) return new Timestamp(d.getTime()); return Timestamp.valueOf(String.valueOf(value)); }
    private int positive(String value, int fallback) { try { int result = Integer.parseInt(value); return result > 0 ? result : fallback; } catch (Exception e) { return fallback; } }
    private record Actor(Long userId, String name) { }
}
