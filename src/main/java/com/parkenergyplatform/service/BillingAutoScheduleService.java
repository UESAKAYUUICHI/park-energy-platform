package com.parkenergyplatform.service;

import com.parkenergyplatform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class BillingAutoScheduleService {
    private final JdbcTemplate jdbc;
    private final BusinessDataAccessService access;
    private final BillingBatchService batches;

    public BillingAutoScheduleService(JdbcTemplate jdbc, BusinessDataAccessService access, BillingBatchService batches) {
        this.jdbc = jdbc;
        this.access = access;
        this.batches = batches;
    }

    public List<Map<String, Object>> list(Long orgId) {
        List<Object> args = new java.util.ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (orgId != null) {
            if (!access.hasOrgAccess(orgId)) throw new BusinessException(403, "没有该园区定时出账权限");
            where.append(" AND s.org_id=?"); args.add(orgId);
        }
        where.append(access.scopeSql("s.org_id", args));
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT s.*, o.org_name FROM billing_auto_schedule s LEFT JOIN dev_org o ON o.id=s.org_id" + where + " ORDER BY s.org_id, s.id", args.toArray());
        for (Map<String, Object> row : rows) {
            List<Map<String, Object>> contracts = jdbc.queryForList("""
                    SELECT c.id, c.contract_no, c.contract_name, c.status, t.tenant_name
                    FROM billing_auto_schedule_contract sc
                    JOIN leasing_contract c ON c.id=sc.contract_id
                    LEFT JOIN crm_tenant t ON t.id=c.tenant_id
                    WHERE sc.schedule_id=? ORDER BY c.id
                    """, row.get("id"));
            row.put("contracts", contracts);
            row.put("contract_ids", contracts.stream().map(item -> item.get("id")).toList());
            row.put("contract_names", contracts.stream().map(item -> String.valueOf(item.getOrDefault("contract_name", item.get("contract_no")))).toList());
        }
        return rows;
    }

    @Transactional
    public Map<String, Object> save(Map<String, Object> body, Long id, String operator) {
        long orgId = number(body.get("orgId"), "orgId");
        assertOrg(orgId);
        String name = required(body.get("scheduleName"), "scheduleName");
        int day = integer(body.getOrDefault("executeDay", 1), 1);
        if (day < 1 || day > 28) throw new BusinessException("执行日必须在 1 到 28 之间");
        String time = textOr(body.get("executeTime"), "02:00:00");
        LocalTime.parse(time.length() == 5 ? time + ":00" : time);
        int term = integer(body.getOrDefault("paymentTermDays", 15), 15);
        if (term < 0 || term > 90) throw new BusinessException("付款期限必须在 0 到 90 天之间");
        List<Long> contractIds = ids(body.get("contractIds"));
        if (contractIds.isEmpty()) throw new BusinessException("至少选择一份启用中的合同");
        String placeholders = String.join(",", java.util.Collections.nCopies(contractIds.size(), "?"));
        List<Long> validIds = jdbc.queryForList("SELECT id FROM leasing_contract WHERE org_id=? AND status='ACTIVE' AND id IN (" + placeholders + ")", Long.class, concat(orgId, contractIds));
        if (validIds.size() != contractIds.size()) throw new BusinessException("只能选择当前园区下已启用的合同");
        if (id == null) {
            jdbc.update("""
                    INSERT INTO billing_auto_schedule
                      (org_id,schedule_name,enabled,execute_day,execute_time,bill_cycle_rule,payment_term_days,timezone,remark,created_by,updated_by)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, orgId, name, bool(body.getOrDefault("enabled", 1)), day, time,
                    textOr(body.get("billCycleRule"), "PREVIOUS_MONTH"), term,
                    textOr(body.get("timezone"), "Asia/Shanghai"), text(body.get("remark")), operator, operator);
            id = jdbc.queryForObject("SELECT id FROM billing_auto_schedule WHERE org_id=? ORDER BY id DESC LIMIT 1", Long.class, orgId);
        } else {
            Map<String, Object> current = required(id);
            assertOrg(number(current.get("org_id"), "orgId"));
            jdbc.update("""
                    UPDATE billing_auto_schedule
                    SET org_id=?,schedule_name=?,enabled=?,execute_day=?,execute_time=?,bill_cycle_rule=?,payment_term_days=?,timezone=?,remark=?,updated_by=?
                    WHERE id=?
                    """, orgId, name, bool(body.getOrDefault("enabled", 1)), day, time,
                    textOr(body.get("billCycleRule"), "PREVIOUS_MONTH"), term,
                    textOr(body.get("timezone"), "Asia/Shanghai"), text(body.get("remark")), operator, id);
        }
        jdbc.update("DELETE FROM billing_auto_schedule_contract WHERE schedule_id=?", id);
        for (Long contractId : contractIds) jdbc.update("INSERT INTO billing_auto_schedule_contract (schedule_id,contract_id,created_by) VALUES (?,?,?)", id, contractId, operator);
        return required(id);
    }

    @Transactional
    public void delete(long id) {
        Map<String, Object> row = required(id);
        assertOrg(number(row.get("org_id"), "orgId"));
        jdbc.update("DELETE FROM billing_auto_schedule WHERE id=?", id);
    }

    public Map<String, Object> run(long id, String operator) {
        Map<String, Object> schedule = required(id);
        assertOrg(number(schedule.get("org_id"), "orgId"));
        try {
            Map<String, Object> result = execute(schedule, operator, false);
            if ("FAILED".equalsIgnoreCase(String.valueOf(result.get("status")))) {
                throw new BusinessException(String.valueOf(result.getOrDefault("message", "自动出账失败，未生成账单")));
            }
            return result;
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // 让前端得到可处理的业务错误，而不是把 JDBC 异常统一包装成“服务内部错误”。
            throw new BusinessException("自动出账失败：" + rootMessage(exception));
        }
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void runDueSchedules() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        // 不再只查询“今天的执行日”。next_run_at 是计划的业务游标，允许服务重启或短暂故障后补偿执行。
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM billing_auto_schedule WHERE enabled=1");
        if (rows.isEmpty()) return;
        List<Long> adminRows = jdbc.queryForList("SELECT id FROM sys_user WHERE username='admin' ORDER BY id LIMIT 1", Long.class);
        if (adminRows.isEmpty()) adminRows = jdbc.queryForList("SELECT id FROM sys_user ORDER BY id LIMIT 1", Long.class);
        Long adminId = adminRows.isEmpty() ? null : adminRows.get(0);
        if (adminId == null) return;
        access.runAsSystem(() -> {
            for (Map<String, Object> row : rows) {
                if (!isDue(row, now)) continue;
                try {
                    execute(row, "system", true);
                } catch (RuntimeException exception) {
                    // 定时任务失败不能静默丢失，计划列表需要能显示最后一次失败原因。
                    recordFailure(number(row.get("id"), "scheduleId"), exception);
                }
            }
            return null;
        });
    }

    private Map<String, Object> execute(Map<String, Object> schedule, String operator, boolean scheduled) {
        long scheduleId = number(schedule.get("id"), "scheduleId");
        long orgId = number(schedule.get("org_id"), "orgId");
        YearMonth month = YearMonth.now().minusMonths(1);
        String cycle = month.toString();
        LocalDate start = month.atDay(1), end = month.atEndOfMonth();
        Map<String, Object> result = new LinkedHashMap<>();
        List<Long> contractIds = jdbc.queryForList("SELECT contract_id FROM billing_auto_schedule_contract WHERE schedule_id=? ORDER BY contract_id", Long.class, scheduleId);
        if (contractIds.isEmpty()) throw new BusinessException("自动出账计划未绑定合同");
        List<Map<String, Object>> existingRows = jdbc.queryForList("SELECT id,status FROM billing_batch WHERE org_id=? AND bill_cycle=? LIMIT 1", orgId, cycle);
        Long existing = existingRows.isEmpty() ? null : number(existingRows.get(0).get("id"), "batchId");
        String existingStatus = existingRows.isEmpty() ? null : String.valueOf(existingRows.get(0).get("status"));
        String periodStatus = jdbc.queryForList("SELECT status FROM billing_period WHERE org_id=? AND period_code=?",orgId,cycle).stream()
                .map(row -> String.valueOf(row.get("status"))).findFirst().orElse("OPEN");
        // 已存在但未关账的草稿/待审核批次可只补计划中新加入且未出账的合同；定稿账期绝不回写。
        if (existing != null) {
            if (!"CLOSED".equalsIgnoreCase(periodStatus) && List.of("DRAFT","FAILED","REVIEWING").contains(existingStatus.toUpperCase())) {
                Map<String,Object> batch=batches.generateMissing(existing,contractIds);
                int generated=integer(batch.get("generatedCount"),0);
                String message=Objects.toString(batch.getOrDefault("message", generated>0?"已补出账单":"没有未出账合同"));
                updateRun(scheduleId,generated>0?"SUCCESS":"SKIPPED",existing,message);
                result.put("status",generated>0?"SUCCESS":"SKIPPED"); result.put("batchId",existing); result.put("billCycle",cycle); result.put("contractIds",contractIds); result.put("generatedCount",generated); result.put("message",message); return result;
            }
            String message="CLOSED".equalsIgnoreCase(periodStatus)
                    ? "原账期已关账，请在结算档案发起补充结算"
                    : "原批次已定稿，请在结算档案发起补充结算";
            updateRun(scheduleId, "SKIPPED", existing, message);
            result.put("status", "SKIPPED"); result.put("batchId", existing); result.put("billCycle",cycle); result.put("contractIds",contractIds); result.put("message",message); return result;
        }
        Long batchId = existing;
        if (batchId == null) {
            jdbc.update("INSERT IGNORE INTO billing_period (period_code,org_id,start_date,end_date,status,remark,create_by,update_by) VALUES (?,?,?,?, 'OPEN',?,?,?)",
                    cycle, orgId, Date.valueOf(start), Date.valueOf(end), scheduled ? "定时任务创建账期" : "手动执行自动出账计划", operator, operator);
            String batchNo = "AUTO" + LocalDateTime.now().toString().replaceAll("[^0-9]", "").substring(0, 14) + orgId;
            jdbc.update("INSERT INTO billing_batch (batch_no,batch_name,org_id,bill_cycle,start_date,end_date,status,remark,create_by,update_by) VALUES (?,?,?,?,?,?,'DRAFT',?,?,?)",
                    batchNo, cycle + " 自动出账", orgId, cycle, Date.valueOf(start), Date.valueOf(end), "AUTO_SCHEDULE:" + scheduleId, operator, operator);
            batchId = jdbc.queryForObject("SELECT id FROM billing_batch WHERE batch_no=?", Long.class, batchNo);
            if (batchId == null) throw new BusinessException("自动出账批次创建失败");
        }
        Map<String, Object> batch = batches.generate(batchId, contractIds);
        int termDays = integer(schedule.get("payment_term_days"), 15);
        jdbc.update("UPDATE billing_bill SET due_date=DATE_ADD(?, INTERVAL " + termDays + " DAY) WHERE batch_id=?", Date.valueOf(end), batchId);
        String status = String.valueOf(batch.getOrDefault("status", "FAILED"));
        int generatedCount = integer(batch.get("generated_count"), 0);
        int failedCount = integer(batch.get("failed_count"), 0);
        String failureSummary = text(batch.get("failure_summary"));
        String runStatus = generatedCount == 0 || "FAILED".equalsIgnoreCase(status)
                ? "FAILED" : failedCount > 0 ? "PARTIAL" : "SUCCESS";
        String message = "SUCCESS".equals(runStatus)
                ? "已生成 " + generatedCount + " 张 " + cycle + " 账单"
                : "PARTIAL".equals(runStatus)
                ? "已生成 " + generatedCount + " 张账单，" + failedCount + " 个账户失败：" + textOr(failureSummary, "请查看出账批次")
                : "出账失败，未生成账单：" + textOr(failureSummary, "当前账期没有可计费数据");
        updateRun(scheduleId, runStatus, batchId, message);
        result.put("status", runStatus);
        result.put("batchStatus", status);
        result.put("billCycle", cycle);
        result.put("generatedCount", generatedCount);
        result.put("failedCount", failedCount);
        result.put("message", message);
        result.put("batchId", batchId);
        result.put("batch", batch);
        return result;
    }

    private void updateRun(long id, String status, Long batchId, String message) {
        Map<String, Object> schedule = required(id);
        YearMonth nextMonth = YearMonth.now().plusMonths(1);
        int executeDay = Math.min(integer(schedule.get("execute_day"), 1), nextMonth.lengthOfMonth());
        LocalTime executeTime = timeValue(schedule.get("execute_time"));
        jdbc.update("UPDATE billing_auto_schedule SET last_run_at=NOW(),last_run_status=?,last_run_batch_id=?,last_run_message=?,next_run_at=? WHERE id=?",
                status, batchId, message, nextMonth.atDay(executeDay).atTime(executeTime), id);
    }

    /**
     * 判断计划是否到期。兼容历史数据 next_run_at 为空的情况：先根据本月执行日和时间计算一次，
     * 到期后即可补偿执行，不要求调度线程恰好落在某一分钟内。
     */
    private boolean isDue(Map<String, Object> schedule, LocalDateTime now) {
        LocalDateTime nextRun = dateTimeValue(schedule.get("next_run_at"));
        if (nextRun == null) {
            int day = Math.min(Math.max(integer(schedule.get("execute_day"), 1), 1), now.toLocalDate().lengthOfMonth());
            nextRun = now.toLocalDate().withDayOfMonth(day).atTime(timeValue(schedule.get("execute_time")));
        }
        if (now.isBefore(nextRun)) return false;
        LocalDateTime lastRun = dateTimeValue(schedule.get("last_run_at"));
        // 同一计划同一执行周期只允许执行一次，避免 fixedDelay 重复出账。
        return lastRun == null || !sameBillingCycle(lastRun, nextRun);
    }

    private boolean sameBillingCycle(LocalDateTime first, LocalDateTime second) {
        return first.getYear() == second.getYear() && first.getMonthValue() == second.getMonthValue();
    }

    private void recordFailure(long id, RuntimeException exception) {
        String message = "自动出账失败：" + rootMessage(exception);
        if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
        if (message.length() > 500) message = message.substring(0, 500);
        jdbc.update("UPDATE billing_auto_schedule SET last_run_at=NOW(),last_run_status='FAILED',last_run_batch_id=NULL,last_run_message=? WHERE id=?", message, id);
    }

    private String rootMessage(Throwable exception) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        if (message == null || message.isBlank()) message = root.getClass().getSimpleName();
        return message.length() > 400 ? message.substring(0, 400) : message;
    }

    private Map<String, Object> required(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM billing_auto_schedule WHERE id=?", id);
        if (rows.isEmpty()) throw new BusinessException(404, "定时出账计划不存在");
        return rows.get(0);
    }
    private List<Long> ids(Object value) {
        List<Long> result = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) result.add(number(item, "contractId"));
        } else if (value != null) {
            for (String item : String.valueOf(value).split(",")) if (!item.isBlank()) result.add(number(item.trim(), "contractId"));
        }
        return result.stream().distinct().toList();
    }
    private Object[] concat(long first, List<Long> rest) { List<Object> values = new ArrayList<>(); values.add(first); values.addAll(rest); return values.toArray(); }
    private void assertOrg(long id) { if (!access.hasOrgAccess(id)) throw new BusinessException(403, "没有该园区定时出账权限"); }
    private long number(Object value, String name) { try { return Long.parseLong(String.valueOf(value)); } catch (Exception e) { throw new BusinessException(name + "必须为数字"); } }
    private int integer(Object value, int fallback) { try { return Integer.parseInt(String.valueOf(value)); } catch (Exception e) { return fallback; } }
    private int bool(Object value) { return Boolean.FALSE.equals(value) || "0".equals(String.valueOf(value)) || "false".equalsIgnoreCase(String.valueOf(value)) ? 0 : 1; }
    private String required(Object value, String name) { String text = text(value); if (text == null) throw new BusinessException(name + "不能为空"); return text; }
    private String text(Object value) { if (value == null) return null; String text = String.valueOf(value).trim(); return text.isBlank() ? null : text; }
    private String textOr(Object value, String fallback) { String text = text(value); return text == null ? fallback : text; }
    private LocalTime timeValue(Object value) {
        if (value instanceof LocalTime time) return time;
        String text = textOr(value, "02:00:00");
        return LocalTime.parse(text.length() == 5 ? text + ":00" : text);
    }
    private LocalDateTime dateTimeValue(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime time) return time;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
        try { return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T')); } catch (Exception ignored) { return null; }
    }
}
