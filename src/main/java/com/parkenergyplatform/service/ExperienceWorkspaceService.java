package com.parkenergyplatform.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import cn.dev33.satoken.stp.StpUtil;
import com.parkenergyplatform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 面向业务工作台的查询模型。
 *
 * <p>这里有意不暴露数据库表，而是将档案、状态、事件和待办组合成前端可直接消费的业务上下文。
 * 写操作仍复用原有领域接口，避免聚合查询层复制业务规则。</p>
 */
@Service
public class ExperienceWorkspaceService {
    private final JdbcTemplate jdbcTemplate;
    private final BusinessDataAccessService accessService;
    private final BillingService billingService;
    private final RemoteServiceClient remoteServiceClient;

    public ExperienceWorkspaceService(JdbcTemplate jdbcTemplate, BusinessDataAccessService accessService,
                                      BillingService billingService, RemoteServiceClient remoteServiceClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessService = accessService;
        this.billingService = billingService;
        this.remoteServiceClient = remoteServiceClient;
    }

    public Map<String, Object> assetCenter(Long deviceId) {
        List<Map<String, Object>> devices = assetDevices();
        Map<String, Object> selected = select(devices, deviceId);
        Long selectedId = accessService.longOrNull(selected.get("id"));
        if (deviceId != null) {
            accessService.assertDeviceAccess(deviceId);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", assetSummary(devices));
        result.put("devices", devices);
        result.put("selectedDevice", selected);
        Map<String, Object> realtime = selectedId != null && StpUtil.hasPermission("energy:view")
                ? safeRealtime(selectedId) : Map.of();
        result.put("realtime", realtime);
        result.put("pointDefinitions", selectedId == null ? List.of() : pointDefinitions(selectedId));
        result.put("diagnostics", selectedId == null ? List.of() : assetDiagnostics(selected, realtime));
        result.put("recentAlarms", selectedId != null && StpUtil.hasPermission("alarm:rule:list")
                ? deviceAlarms(selectedId) : List.of());
        result.put("workOrders", selectedId != null && StpUtil.hasPermission("ops:workorder:list")
                ? deviceWorkOrders(selectedId) : List.of());
        result.put("recentCommands", selectedId != null && StpUtil.hasPermission("access:view")
                ? deviceCommands(selectedId) : List.of());
        result.put("capabilities", capabilities());
        return result;
    }

    private Map<String, Object> safeRealtime(Long deviceId) {
        try {
            Map<String, Object> response = remoteServiceClient.getData("/api/data/realtime/devices/" + deviceId);
            Object data = response == null ? null : response.get("data");
            if (!(data instanceof Map<?, ?> snapshot) || snapshot.isEmpty()) {
                return Map.of("success", false, "message", "设备尚未产生实时快照", "data", Map.of());
            }
            return response;
        } catch (RuntimeException exception) {
            return Map.of("success", false, "message", "实时数据服务暂不可用", "data", Map.of());
        }
    }

    private List<Map<String, Object>> pointDefinitions(Long deviceId) {
        return jdbcTemplate.queryForList("""
                SELECT p.point_code AS pointCode, p.point_name AS pointName, p.unit,
                       p.data_type AS dataType, p.sort
                FROM dev_point_definition p
                JOIN dev_device d ON d.device_type_id = p.device_type_id
                WHERE d.id = ? AND p.enabled = 1
                ORDER BY p.sort, p.id
                """, deviceId);
    }

    private List<Map<String, Object>> assetDiagnostics(Map<String, Object> device, Map<String, Object> realtime) {
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        if (device.get("gatewayId") == null) {
            diagnostics.add(checkIssue("BLOCKER", "NO_GATEWAY", "设备尚未绑定网关",
                    "没有网关时无法获得实时上报或下发控制指令。",
                    StpUtil.hasPermission("archive:edit") ? "/archive/devices" : ""));
        } else if (number(device.get("gatewayOnline")) != 1) {
            diagnostics.add(checkIssue("BLOCKER", "GATEWAY_OFFLINE", "设备网关当前离线",
                    "先检查网关心跳、MQTT连接和接入密钥。",
                    StpUtil.hasPermission("access:view") ? "/access/diagnostic" : ""));
        }
        String qualityAction = StpUtil.hasPermission("energy:quality:list") ? "/analysis/data-quality"
                : StpUtil.hasPermission("energy:view") ? "/analysis/quality" : "";
        if (device.get("qualityStatus") == null) {
            diagnostics.add(checkIssue("WARNING", "NO_QUALITY", "尚无采集质量统计",
                    "确认设备开始上报后生成日采集质量统计。", qualityAction));
        } else if (!"NORMAL".equalsIgnoreCase(text(device.get("qualityStatus")))) {
            diagnostics.add(checkIssue("WARNING", "QUALITY_RISK", "最近采集质量需要处理",
                    "当前状态为 " + text(device.get("qualityStatus")) + "，完整率 " + device.get("completeRate") + "% 。", qualityAction));
        }
        if (number(device.get("openAlarmCount")) > 0) {
            diagnostics.add(checkIssue("WARNING", "OPEN_ALARM", "存在 " + device.get("openAlarmCount") + " 条未处理告警",
                    "建议先研判告警，再决定直接处理或转运维工单。",
                    StpUtil.hasPermission("ops:workorder:list") ? "/center/operations" : "/alarms/workbench"));
        }
        Object remoteData = realtime.get("data");
        boolean realtimeAvailable = !Boolean.FALSE.equals(realtime.get("success"))
                && remoteData instanceof Map<?, ?> map && !map.isEmpty();
        if (StpUtil.hasPermission("energy:view") && !realtimeAvailable) {
            diagnostics.add(checkIssue("WARNING", "NO_REALTIME", "当前没有可用实时快照",
                    Objects.toString(realtime.get("message"), "检查设备是否已上报以及 data 服务是否可用。"), "/monitor/realtime"));
        }
        if (diagnostics.isEmpty()) {
            String detail = StpUtil.hasPermission("energy:view")
                    ? "网关、实时快照和最近采集质量均未发现明显问题。"
                    : "网关与最近采集质量均未发现明显问题。";
            diagnostics.add(Map.of("severity", "PASSED", "code", "HEALTHY", "title", "当前设备运行链路正常",
                    "detail", detail, "actionPath", "/monitor/realtime"));
        }
        return diagnostics;
    }

    public Map<String, Object> operationsCenter(Long deviceId) {
        if (deviceId != null) {
            accessService.assertDeviceAccess(deviceId);
        }
        List<Map<String, Object>> devices = operationDevices();
        List<Map<String, Object>> workOrders = workOrders(deviceId);
        List<Map<String, Object>> alarms = StpUtil.hasPermission("alarm:rule:list") ? alarms(deviceId) : List.of();
        List<Map<String, Object>> inspections = StpUtil.hasPermission("ops:inspection:list")
                ? inspections(deviceId) : List.of();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("urgent", workOrders.stream().filter(row -> "P1".equals(text(row.get("priority")))
                && !isClosed(text(row.get("status")))).count());
        summary.put("openWorkOrders", workOrders.stream().filter(row -> !isClosed(text(row.get("status")))).count());
        summary.put("openAlarms", alarms.stream().filter(row -> number(row.get("dealStatus")) == 0).count());
        summary.put("pendingInspections", inspections.stream().filter(row -> List.of("PENDING", "PROCESSING")
                .contains(text(row.get("status")))).count());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("devices", devices);
        result.put("selectedDevice", select(devices, deviceId));
        result.put("workOrders", workOrders);
        result.put("alarms", alarms);
        result.put("inspections", inspections);
        result.put("capabilities", capabilities());
        return result;
    }

    public Map<String, Object> revenueCenter(Long accountId, String billCycle) {
        if (accountId != null) {
            accessService.assertBillingAccountAccess(accountId);
        }
        List<Map<String, Object>> accounts = billingAccounts();
        Map<String, Object> selected = select(accounts, accountId);
        Long selectedId = accessService.longOrNull(selected.get("id"));

        List<Map<String, Object>> rules = selectedId == null ? List.of() : billingRules(selectedId);
        List<Map<String, Object>> bills = selectedId == null ? List.of() : bills(selectedId, billCycle);
        Long contractId = accessService.longOrNull(selected.get("contractId"));
        Long orgId = accessService.longOrNull(selected.get("orgId"));
        List<Map<String, Object>> meters = selectedId == null ? List.of() : billingMeters(selectedId, contractId);
        List<Map<String, Object>> batches = orgId == null || !StpUtil.hasPermission("billing:batch:list")
                ? List.of() : billingBatches(orgId, billCycle);
        List<Map<String, Object>> adjustments = selectedId == null || !StpUtil.hasPermission("billing:adjustment:list")
                ? List.of() : adjustments(selectedId);
        List<Map<String, Object>> collections = selectedId == null || !StpUtil.hasPermission("billing:collection:list")
                ? List.of() : collections(selectedId);
        List<Map<String, Object>> meteringOrders = orgId == null || !StpUtil.hasPermission("billing:metering:list")
                ? List.of() : meteringOrders(orgId, contractId);

        long activeRuleCount = rules.stream().filter(row -> number(row.get("enabled")) == 1).count();
        long unhealthyMeterCount = meters.stream().filter(row -> row.get("qualityStatus") == null
                || !"NORMAL".equals(text(row.get("qualityStatus")))).count();
        long outstandingCount = bills.stream().filter(row -> decimal(row.get("outstandingAmount")) > 0).count();
        long overdueCount = bills.stream().filter(row -> number(row.get("overdue")) == 1).count();

        Map<String, Object> readiness = new LinkedHashMap<>();
        readiness.put("accountReady", selectedId != null && number(selected.get("status")) == 1);
        readiness.put("contractReady", contractId == null ? !meters.isEmpty()
                : "ACTIVE".equals(text(selected.get("contractStatus"))));
        readiness.put("settlementModel", contractId == null ? "RULE_SCOPE" : "CONTRACT");
        readiness.put("meterReady", !meters.isEmpty());
        readiness.put("ruleReady", activeRuleCount > 0);
        readiness.put("qualityReady", !meters.isEmpty() && unhealthyMeterCount == 0);
        readiness.put("activeRuleCount", activeRuleCount);
        readiness.put("meterCount", meters.size());
        readiness.put("unhealthyMeterCount", unhealthyMeterCount);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("accountCount", accounts.size());
        summary.put("billCount", bills.size());
        summary.put("outstandingCount", outstandingCount);
        summary.put("overdueCount", overdueCount);
        summary.put("outstandingAmount", bills.stream().mapToDouble(row -> decimal(row.get("outstandingAmount"))).sum());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("accounts", accounts);
        result.put("selectedAccount", selected);
        result.put("readiness", readiness);
        result.put("rules", rules);
        result.put("meters", meters);
        result.put("bills", bills);
        result.put("batches", batches);
        result.put("adjustments", adjustments);
        result.put("collections", collections);
        result.put("meteringOrders", meteringOrders);
        result.put("capabilities", capabilities());
        return result;
    }

    public Map<String, Object> revenuePrecheck(long accountId, String billCycle) {
        accessService.assertBillingAccountAccess(accountId);
        YearMonth cycle;
        try {
            cycle = YearMonth.parse(billCycle);
        } catch (RuntimeException exception) {
            throw new BusinessException("billCycle 格式必须为 YYYY-MM");
        }
        LocalDate startDate = cycle.atDay(1);
        LocalDate endDate = cycle.atEndOfMonth();
        Map<String, Object> account = jdbcTemplate.queryForMap("""
                SELECT a.id, a.account_name AS accountName, a.status, a.contract_id AS contractId,
                       c.contract_name AS contractName, c.status AS contractStatus,
                       c.start_date AS contractStartDate, c.end_date AS contractEndDate
                FROM billing_account a LEFT JOIN leasing_contract c ON c.id = a.contract_id
                WHERE a.id = ?
                """, accountId);
        List<Map<String, Object>> rules = billingRules(accountId).stream()
                .filter(rule -> number(rule.get("enabled")) == 1)
                .toList();
        List<Map<String, Object>> meters = billingMeters(accountId, accessService.longOrNull(account.get("contractId")));
        List<Map<String, Object>> issues = new ArrayList<>();
        List<Map<String, Object>> passed = new ArrayList<>();

        if (number(account.get("status")) != 1) {
            issues.add(checkIssue("BLOCKER", "ACCOUNT_DISABLED", "计费账户未启用", "启用账户后才能进行本期试算。", "/billing/accounts"));
        } else {
            passed.add(checkPassed("账户状态", "计费账户已启用"));
        }
        Long existing = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM billing_bill WHERE account_id=? AND bill_cycle=?", Long.class, accountId, billCycle);
        if (existing != null && existing > 0) {
            issues.add(checkIssue("BLOCKER", "BILL_EXISTS", "本账期已存在账单", "系统禁止同一账户和账期重复生成账单。", "/billing/bills"));
        } else {
            passed.add(checkPassed("重复出账", "本账期尚未生成账单"));
        }
        if (rules.isEmpty()) {
            issues.add(checkIssue("BLOCKER", "NO_RULE", "缺少启用的计费规则", "至少需要一条启用规则才能确定计费测点和价格模式。", "/billing/rules"));
        } else {
            passed.add(checkPassed("计费规则", rules.size() + " 条规则已配置"));
        }
        if (meters.isEmpty()) {
            issues.add(checkIssue("BLOCKER", "NO_METER", "没有匹配到结算表计", "检查合同表计绑定，或计费规则的组织/设备适用范围。", account.get("contractId") == null ? "/billing/rule-scopes" : "/billing/contracts"));
        } else {
            passed.add(checkPassed("结算表计", meters.size() + " 台表计进入本期候选范围"));
        }

        for (Map<String, Object> rule : rules) {
            long ruleId = accessService.longOrNull(rule.get("id"));
            String ruleName = text(rule.get("ruleName"));
            String priceMode = text(rule.get("priceMode"));
            long matchedMeters = meters.stream().filter(meter -> Objects.equals(
                    accessService.longOrNull(meter.get("deviceTypeId")), accessService.longOrNull(rule.get("deviceTypeId")))).count();
            if (matchedMeters == 0) {
                issues.add(checkIssue("BLOCKER", "RULE_NO_METER", "规则没有匹配表计：" + ruleName,
                        "规则设备类型与当前结算表计不一致。", "/billing/rule-scopes"));
            }
            if (!"TIME_PERIOD".equalsIgnoreCase(priceMode)) {
                Long priceCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM billing_price_item WHERE rule_id=?", Long.class, ruleId);
                if (priceCount == null || priceCount == 0) {
                    issues.add(checkIssue("BLOCKER", "NO_PRICE", "规则缺少价格明细：" + ruleName,
                            "按量、固定或阶梯计费规则必须配置价格明细。", "/billing/price-items"));
                }
            }
        }

        if (account.get("contractId") != null) {
            String contractStatus = text(account.get("contractStatus"));
            LocalDate contractStart = localDate(account.get("contractStartDate"));
            LocalDate contractEnd = localDate(account.get("contractEndDate"));
            if (!"ACTIVE".equalsIgnoreCase(contractStatus) || contractStart == null || contractEnd == null
                    || contractStart.isAfter(endDate) || contractEnd.isBefore(startDate)) {
                issues.add(checkIssue("WARNING", "CONTRACT_RISK", "合同状态或有效期需要确认",
                        "当前合同可能未覆盖完整账期；最终计费以有效表计绑定区间为准。", "/billing/contracts"));
            } else {
                passed.add(checkPassed("合同有效期", "合同覆盖当前账期"));
            }
        } else {
            passed.add(checkPassed("结算模型", "当前账户按计费规则范围结算"));
        }

        long latestQualityRisks = meters.stream().filter(meter -> meter.get("qualityStatus") == null
                || !"NORMAL".equalsIgnoreCase(text(meter.get("qualityStatus")))).count();
        if (latestQualityRisks > 0) {
            issues.add(checkIssue("WARNING", "LATEST_QUALITY", latestQualityRisks + " 台表计最近质量状态需关注",
                    "正式试算将按质量门禁启用日逐日检查整个账期。", "/analysis/data-quality"));
        }

        Map<String, Object> preview = null;
        try {
            preview = billingService.preview(Map.of(
                    "accountId", accountId,
                    "billCycle", billCycle,
                    "startDate", startDate.toString(),
                    "endDate", endDate.toString()
            ));
            boolean touReady = !Boolean.FALSE.equals(preview.get("touReady"));
            int detailCount = preview.get("details") instanceof List<?> details ? details.size() : 0;
            if (!touReady) {
                issues.add(checkIssue("BLOCKER", "TOU_NOT_READY", "分时统计尚未就绪",
                        "存在需要分时计费但尚未完成时段统计的设备。", "/analysis/quality"));
            }
            if (detailCount == 0) {
                issues.add(checkIssue("BLOCKER", "NO_BILLABLE_DETAIL", "本账期没有可计费明细",
                        "检查账期统计、计费测点、表计有效期和规则适用范围。", "/billing/settlement"));
            }
            if (touReady && detailCount > 0) {
                passed.add(checkPassed("正式试算", "已产生 " + detailCount + " 条可计费明细，金额与账单服务计算一致"));
            }
        } catch (BusinessException exception) {
            issues.add(checkIssue("BLOCKER", "TRIAL_FAILED", "账单试算未通过", exception.getMessage(), precheckAction(exception.getMessage())));
        }

        long blockerCount = issues.stream().filter(issue -> "BLOCKER".equals(issue.get("severity"))).count();
        long warningCount = issues.stream().filter(issue -> "WARNING".equals(issue.get("severity"))).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountId", accountId);
        result.put("billCycle", billCycle);
        result.put("startDate", startDate);
        result.put("endDate", endDate);
        result.put("ready", blockerCount == 0 && preview != null);
        result.put("blockerCount", blockerCount);
        result.put("warningCount", warningCount);
        result.put("issues", issues);
        result.put("passed", passed);
        result.put("preview", preview == null ? Map.of() : Map.of(
                "totalAmount", preview.getOrDefault("totalAmount", 0),
                "detailCount", preview.get("details") instanceof List<?> details ? details.size() : 0,
                "touReady", preview.getOrDefault("touReady", true)
        ));
        return result;
    }

    private List<Map<String, Object>> assetDevices() {
        List<Object> args = new ArrayList<>();
        String alarmCount = StpUtil.hasPermission("alarm:rule:list")
                ? "(SELECT COUNT(*) FROM log_alarm a WHERE a.device_id = d.id AND a.deal_status = 0)"
                : "0";
        String workOrderCount = StpUtil.hasPermission("ops:workorder:list")
                ? "(SELECT COUNT(*) FROM ops_work_order w WHERE w.device_id = d.id AND w.status NOT IN ('CLOSED','CANCELLED'))"
                : "0";
        String sql = """
                SELECT d.id, d.device_sn AS deviceSn, d.device_name AS deviceName,
                       d.org_id AS orgId, o.org_name AS orgName, d.gateway_id AS gatewayId,
                       g.gateway_name AS gatewayName, g.online_status AS gatewayOnline,
                       g.last_online_time AS gatewayLastOnline, dt.type_name AS deviceTypeName,
                       d.install_location AS installLocation, d.status,
                       d.settlement_enabled AS settlementEnabled, d.meter_role AS meterRole,
                       d.meter_factor AS meterFactor, d.quality_threshold_pct AS qualityThresholdPct,
                       q.stat_date AS qualityDate, q.data_complete_rate AS completeRate,
                       q.quality_status AS qualityStatus, q.last_collect_time AS lastCollectTime,
                       %s AS openAlarmCount, %s AS openWorkOrderCount,
                       COALESCE((SELECT SUM(p.usage_value) FROM stats_daily_point p
                                 WHERE p.device_id = d.id AND p.stat_date = CURRENT_DATE), 0) AS todayUsage
                FROM dev_device d
                JOIN dev_org o ON o.id = d.org_id
                LEFT JOIN dev_gateway g ON g.id = d.gateway_id
                LEFT JOIN dev_device_type dt ON dt.id = d.device_type_id
                LEFT JOIN stats_collection_daily q ON q.id = (
                    SELECT q2.id FROM stats_collection_daily q2
                    WHERE q2.device_id = d.id ORDER BY q2.stat_date DESC LIMIT 1
                )
                WHERE 1 = 1
                """.formatted(alarmCount, workOrderCount)
                + accessService.scopeSql("d.org_id", args)
                + " ORDER BY openAlarmCount DESC, openWorkOrderCount DESC, d.device_name LIMIT 200";
        return jdbcTemplate.queryForList(sql, args.toArray());
    }

    private List<Map<String, Object>> operationDevices() {
        List<Object> args = new ArrayList<>();
        String alarmCount = StpUtil.hasPermission("alarm:rule:list")
                ? "(SELECT COUNT(*) FROM log_alarm a WHERE a.device_id = d.id AND a.deal_status = 0)"
                : "0";
        String sql = """
                SELECT d.id, d.device_name AS deviceName, d.device_sn AS deviceSn,
                       d.org_id AS orgId, o.org_name AS orgName,
                       (SELECT COUNT(*) FROM ops_work_order w WHERE w.device_id = d.id
                        AND w.status NOT IN ('CLOSED','CANCELLED')) AS openWorkOrderCount,
                       %s AS openAlarmCount
                FROM dev_device d
                JOIN dev_org o ON o.id = d.org_id
                WHERE d.status = 1
                """.formatted(alarmCount) + accessService.scopeSql("d.org_id", args)
                + " ORDER BY openAlarmCount DESC, openWorkOrderCount DESC, d.device_name LIMIT 200";
        return jdbcTemplate.queryForList(sql, args.toArray());
    }

    private List<Map<String, Object>> deviceAlarms(Long deviceId) {
        return jdbcTemplate.queryForList("""
                SELECT a.id, a.alarm_level AS alarmLevel, a.alarm_type AS alarmType,
                       a.point_code AS pointCode, a.alarm_value AS alarmValue,
                       a.threshold_value AS thresholdValue, a.alarm_time AS alarmTime,
                       a.deal_status AS dealStatus, a.deal_remark AS dealRemark,
                       a.work_order_id AS workOrderId
                FROM log_alarm a WHERE a.device_id = ?
                ORDER BY a.deal_status, a.alarm_time DESC LIMIT 20
                """, deviceId);
    }

    private List<Map<String, Object>> deviceWorkOrders(Long deviceId) {
        return jdbcTemplate.queryForList("""
                SELECT w.id, w.work_order_no AS workOrderNo, w.title, w.priority, w.status,
                       w.source_type AS sourceType, w.assignee_name AS assigneeName,
                       w.report_time AS reportTime, w.sla_due_time AS slaDueTime,
                       w.description, w.solution
                FROM ops_work_order w WHERE w.device_id = ?
                ORDER BY FIELD(w.status,'PENDING','ASSIGNED','ACCEPTED','PROCESSING','VERIFYING','CLOSED','CANCELLED'),
                         FIELD(w.priority,'P1','P2','P3'), w.report_time DESC LIMIT 20
                """, deviceId);
    }

    private List<Map<String, Object>> deviceCommands(Long deviceId) {
        return jdbcTemplate.queryForList("""
                SELECT c.command_id AS commandId, c.command_type AS commandType, c.status AS commandStatus,
                       c.request_time AS createTime, c.response_time AS executeTime, c.fail_reason AS resultMessage
                FROM command_record c
                WHERE c.target_type = 'DEVICE' AND c.target_id = ?
                ORDER BY c.create_time DESC LIMIT 12
                """, deviceId);
    }

    private List<Map<String, Object>> workOrders(Long deviceId) {
        List<Object> args = new ArrayList<>();
        String sql = """
                SELECT w.id, w.work_order_no AS workOrderNo, w.org_id AS orgId, o.org_name AS orgName,
                       w.device_id AS deviceId, d.device_name AS deviceName, w.source_type AS sourceType,
                       w.source_id AS sourceId, w.work_type AS workType, w.priority, w.status,
                       w.title, w.description, w.assignee_user_id AS assigneeUserId,
                       w.assignee_name AS assigneeName,
                       w.report_time AS reportTime, w.sla_due_time AS slaDueTime, w.solution
                FROM ops_work_order w
                JOIN dev_org o ON o.id = w.org_id
                LEFT JOIN dev_device d ON d.id = w.device_id
                WHERE 1 = 1
                """;
        if (deviceId != null) {
            sql += " AND w.device_id = ?";
            args.add(deviceId);
        }
        sql += accessService.scopeSql("w.org_id", args)
                + " ORDER BY FIELD(w.status,'PENDING','ASSIGNED','ACCEPTED','PROCESSING','VERIFYING','CLOSED','CANCELLED'),"
                + " FIELD(w.priority,'P1','P2','P3'), w.report_time DESC LIMIT 80";
        return jdbcTemplate.queryForList(sql, args.toArray());
    }

    private List<Map<String, Object>> alarms(Long deviceId) {
        List<Object> args = new ArrayList<>();
        String sql = """
                SELECT a.id, a.org_id AS orgId, o.org_name AS orgName, a.device_id AS deviceId,
                       d.device_name AS deviceName, a.alarm_level AS alarmLevel, a.alarm_type AS alarmType,
                       a.point_code AS pointCode, a.alarm_value AS alarmValue,
                       a.threshold_value AS thresholdValue, a.alarm_time AS alarmTime,
                       a.deal_status AS dealStatus, a.work_order_id AS workOrderId
                FROM log_alarm a
                JOIN dev_org o ON o.id = a.org_id
                JOIN dev_device d ON d.id = a.device_id
                WHERE 1 = 1
                """;
        if (deviceId != null) {
            sql += " AND a.device_id = ?";
            args.add(deviceId);
        }
        sql += accessService.scopeSql("a.org_id", args)
                + " ORDER BY a.deal_status, a.alarm_level DESC, a.alarm_time DESC LIMIT 80";
        return jdbcTemplate.queryForList(sql, args.toArray());
    }

    private List<Map<String, Object>> inspections(Long deviceId) {
        List<Object> args = new ArrayList<>();
        String sql = """
                SELECT t.id, t.task_no AS taskNo, t.org_id AS orgId, o.org_name AS orgName,
                       t.device_id AS deviceId, d.device_name AS deviceName, t.task_date AS taskDate,
                       t.status, t.assignee_user_id AS assigneeUserId,
                       t.assignee_name AS assigneeName, t.due_time AS dueTime,
                       t.result, t.result_remark AS resultRemark, t.work_order_id AS workOrderId
                FROM ops_inspection_task t
                JOIN dev_org o ON o.id = t.org_id
                JOIN dev_device d ON d.id = t.device_id
                WHERE 1 = 1
                """;
        if (deviceId != null) {
            sql += " AND t.device_id = ?";
            args.add(deviceId);
        }
        sql += accessService.scopeSql("t.org_id", args)
                + " ORDER BY FIELD(t.status,'PENDING','PROCESSING','ABNORMAL','COMPLETED','CANCELLED'), t.due_time LIMIT 80";
        return jdbcTemplate.queryForList(sql, args.toArray());
    }

    private List<Map<String, Object>> billingAccounts() {
        List<Object> args = new ArrayList<>();
        String sql = """
                SELECT a.id, a.account_name AS accountName, a.org_id AS orgId, o.org_name AS orgName,
                       a.tenant_id AS tenantId, t.tenant_name AS tenantName,
                       a.contract_id AS contractId, c.contract_no AS contractNo,
                       c.contract_name AS contractName, c.status AS contractStatus,
                       c.start_date AS contractStartDate, c.end_date AS contractEndDate,
                       c.settlement_day AS settlementDay, a.contact_name AS contactName,
                       a.contact_phone AS contactPhone, a.status,
                       (SELECT COUNT(*) FROM billing_bill b WHERE b.account_id = a.id
                        AND b.outstanding_amount > 0 AND b.bill_status <> 'VOID') AS outstandingBillCount,
                       COALESCE((SELECT SUM(b.outstanding_amount) FROM billing_bill b WHERE b.account_id = a.id
                        AND b.bill_status <> 'VOID'), 0) AS outstandingAmount
                FROM billing_account a
                JOIN dev_org o ON o.id = a.org_id
                LEFT JOIN crm_tenant t ON t.id = a.tenant_id
                LEFT JOIN leasing_contract c ON c.id = a.contract_id
                WHERE 1 = 1
                """ + accessService.scopeSql("a.org_id", args)
                + " ORDER BY outstandingBillCount DESC, a.account_name LIMIT 200";
        return jdbcTemplate.queryForList(sql, args.toArray());
    }

    private List<Map<String, Object>> billingRules(Long accountId) {
        return jdbcTemplate.queryForList("""
                SELECT r.id, r.rule_name AS ruleName, r.device_type_id AS deviceTypeId,
                       r.metric_point_code AS metricPointCode,
                       r.billing_cycle AS billingCycle, r.price_mode AS priceMode, r.enabled,
                       dt.type_name AS deviceTypeName, r.tariff_plan_id AS tariffPlanId
                FROM billing_rule r
                JOIN dev_device_type dt ON dt.id = r.device_type_id
                WHERE r.account_id = ? ORDER BY r.enabled DESC, r.rule_name
                """, accountId);
    }

    private List<Map<String, Object>> bills(Long accountId, String billCycle) {
        List<Object> args = new ArrayList<>();
        args.add(accountId);
        String sql = """
                SELECT b.id, b.bill_no AS billNo, b.bill_cycle AS billCycle,
                       b.bill_status AS billStatus, b.total_amount AS totalAmount,
                       b.paid_amount AS paidAmount, b.outstanding_amount AS outstandingAmount,
                       b.pay_status AS payStatus, b.due_date AS dueDate, b.create_time AS createTime,
                       CASE WHEN b.bill_status <> 'VOID' AND b.outstanding_amount > 0
                                  AND b.due_date IS NOT NULL AND b.due_date < CURRENT_DATE THEN 1 ELSE 0 END AS overdue
                FROM billing_bill b WHERE b.account_id = ?
                """;
        if (billCycle != null && !billCycle.isBlank()) {
            sql += " AND b.bill_cycle = ?";
            args.add(billCycle);
        }
        sql += " ORDER BY b.bill_cycle DESC, b.create_time DESC LIMIT 36";
        return jdbcTemplate.queryForList(sql, args.toArray());
    }

    private List<Map<String, Object>> billingMeters(Long accountId, Long contractId) {
        if (contractId == null) {
            return ruleScopedMeters(accountId);
        }
        return jdbcTemplate.queryForList("""
                SELECT d.id, d.device_name AS deviceName, d.device_sn AS deviceSn,
                       d.device_type_id AS deviceTypeId, d.meter_role AS meterRole, d.meter_factor AS meterFactor,
                       d.quality_threshold_pct AS qualityThresholdPct,
                       cm.status AS bindingStatus, q.stat_date AS qualityDate,
                       q.data_complete_rate AS completeRate, q.quality_status AS qualityStatus,
                       q.last_collect_time AS lastCollectTime, 'CONTRACT' AS billingSource
                FROM leasing_contract_meter cm
                JOIN dev_device d ON d.id = cm.device_id
                LEFT JOIN stats_collection_daily q ON q.id = (
                    SELECT q2.id FROM stats_collection_daily q2
                    WHERE q2.device_id = d.id ORDER BY q2.stat_date DESC LIMIT 1
                )
                WHERE cm.contract_id = ? AND cm.status = 'ACTIVE'
                ORDER BY d.device_name
                """, contractId).stream()
                .filter(row -> accessService.hasDeviceAccess(accessService.longOrNull(row.get("id"))))
                .toList();
    }

    private List<Map<String, Object>> ruleScopedMeters(Long accountId) {
        return jdbcTemplate.queryForList("""
                WITH RECURSIVE scoped_orgs AS (
                    SELECT rs.scope_id AS id
                    FROM billing_rule_scope rs
                    JOIN billing_rule r ON r.id = rs.rule_id
                    WHERE r.account_id = ? AND r.enabled = 1 AND rs.scope_type = 'ORG'
                    UNION ALL
                    SELECT o.id FROM dev_org o JOIN scoped_orgs parent_scope ON o.parent_id = parent_scope.id
                ), candidate_devices AS (
                    SELECT rs.scope_id AS id
                    FROM billing_rule_scope rs
                    JOIN billing_rule r ON r.id = rs.rule_id
                    WHERE r.account_id = ? AND r.enabled = 1 AND rs.scope_type = 'DEVICE'
                    UNION
                    SELECT d.id FROM dev_device d JOIN scoped_orgs scoped ON scoped.id = d.org_id
                )
                SELECT DISTINCT d.id, d.device_name AS deviceName, d.device_sn AS deviceSn,
                       d.device_type_id AS deviceTypeId, d.meter_role AS meterRole, d.meter_factor AS meterFactor,
                       d.quality_threshold_pct AS qualityThresholdPct, 'ACTIVE' AS bindingStatus,
                       q.stat_date AS qualityDate, q.data_complete_rate AS completeRate,
                       q.quality_status AS qualityStatus, q.last_collect_time AS lastCollectTime,
                       'RULE_SCOPE' AS billingSource
                FROM candidate_devices candidate
                JOIN dev_device d ON d.id = candidate.id
                JOIN billing_rule r ON r.account_id = ? AND r.enabled = 1
                     AND r.device_type_id = d.device_type_id
                LEFT JOIN stats_collection_daily q ON q.id = (
                    SELECT q2.id FROM stats_collection_daily q2
                    WHERE q2.device_id = d.id ORDER BY q2.stat_date DESC LIMIT 1
                )
                WHERE d.status = 1 AND d.settlement_enabled = 1
                ORDER BY d.device_name
                """, accountId, accountId, accountId).stream()
                .filter(row -> accessService.hasDeviceAccess(accessService.longOrNull(row.get("id"))))
                .toList();
    }

    private List<Map<String, Object>> billingBatches(Long orgId, String billCycle) {
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        String sql = """
                SELECT id, batch_no AS batchNo, batch_name AS batchName, bill_cycle AS billCycle,
                       status, account_total AS accountTotal, generated_count AS generatedCount,
                       failed_count AS failedCount, total_amount AS totalAmount, failure_summary AS failureSummary
                FROM billing_batch WHERE org_id = ?
                """;
        if (billCycle != null && !billCycle.isBlank()) {
            sql += " AND bill_cycle = ?";
            args.add(billCycle);
        }
        sql += " ORDER BY bill_cycle DESC, create_time DESC LIMIT 20";
        return jdbcTemplate.queryForList(sql, args.toArray());
    }

    private List<Map<String, Object>> adjustments(Long accountId) {
        return jdbcTemplate.queryForList("""
                SELECT a.id, a.adjustment_no AS adjustmentNo, a.bill_id AS billId, b.bill_no AS billNo,
                       a.adjustment_type AS adjustmentType, a.adjustment_amount AS adjustmentAmount,
                       a.status, a.reason, a.created_by AS createdBy, a.created_time AS createdTime
                FROM billing_adjustment a
                JOIN billing_bill b ON b.id = a.bill_id
                WHERE b.account_id = ? ORDER BY a.created_time DESC LIMIT 20
                """, accountId);
    }

    private List<Map<String, Object>> collections(Long accountId) {
        return jdbcTemplate.queryForList("""
                SELECT c.id, c.bill_id AS billId, b.bill_no AS billNo, c.collection_type AS collectionType,
                       c.collection_time AS collectionTime, c.operator, c.content, c.result,
                       c.next_follow_time AS nextFollowTime
                FROM billing_collection_record c
                JOIN billing_bill b ON b.id = c.bill_id
                WHERE b.account_id = ? ORDER BY c.collection_time DESC LIMIT 20
                """, accountId);
    }

    private List<Map<String, Object>> meteringOrders(Long orgId, Long contractId) {
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        String sql = """
                SELECT id, change_no AS changeNo, contract_id AS contractId, change_type AS changeType,
                       status, source_device_id AS sourceDeviceId, target_device_id AS targetDeviceId,
                       effective_time AS effectiveTime, applicant_name AS applicantName, reason
                FROM billing_meter_change_order WHERE org_id = ?
                """;
        if (contractId != null) {
            sql += " AND (contract_id = ? OR contract_id IS NULL)";
            args.add(contractId);
        }
        sql += " ORDER BY FIELD(status,'PENDING','APPROVED','EXECUTED','REJECTED','CANCELLED'), apply_time DESC LIMIT 20";
        return jdbcTemplate.queryForList(sql, args.toArray());
    }

    private Map<String, Object> assetSummary(List<Map<String, Object>> devices) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("deviceCount", devices.size());
        summary.put("onlineCount", devices.stream().filter(row -> number(row.get("gatewayOnline")) == 1).count());
        summary.put("abnormalCount", devices.stream().filter(row -> row.get("qualityStatus") == null
                || !"NORMAL".equals(text(row.get("qualityStatus")))).count());
        summary.put("settlementCount", devices.stream().filter(row -> number(row.get("settlementEnabled")) == 1).count());
        summary.put("openAlarmCount", devices.stream().mapToLong(row -> number(row.get("openAlarmCount"))).sum());
        return summary;
    }

    private Map<String, Object> checkIssue(String severity, String code, String title, String detail, String actionPath) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("severity", severity);
        issue.put("code", code);
        issue.put("title", title);
        issue.put("detail", detail);
        issue.put("actionPath", actionPath);
        return issue;
    }

    private Map<String, Object> checkPassed(String title, String detail) {
        return Map.of("title", title, "detail", detail);
    }

    private String precheckAction(String message) {
        String value = Objects.toString(message, "");
        if (value.contains("质量") || value.contains("采集") || value.contains("统计")) return "/analysis/data-quality";
        if (value.contains("价格") || value.contains("电价")) return "/billing/price-items";
        if (value.contains("规则")) return "/billing/rules";
        if (value.contains("设备") || value.contains("表计")) return "/billing/rule-scopes";
        return "/billing/settlement";
    }

    private LocalDate localDate(Object value) {
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        if (value instanceof java.util.Date date) return new java.sql.Date(date.getTime()).toLocalDate();
        if (value == null) return null;
        try { return LocalDate.parse(value.toString()); } catch (RuntimeException exception) { return null; }
    }

    private Map<String, Object> capabilities() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("viewEnergy", StpUtil.hasPermission("energy:view"));
        result.put("viewArchive", StpUtil.hasPermission("archive:list"));
        result.put("viewAlarms", StpUtil.hasPermission("alarm:rule:list"));
        result.put("dealAlarms", StpUtil.hasPermission("alarm:event:deal"));
        result.put("viewWorkOrders", StpUtil.hasPermission("ops:workorder:list"));
        result.put("createWorkOrders", StpUtil.hasPermission("ops:workorder:create"));
        result.put("operateWorkOrders", StpUtil.hasPermission("ops:workorder:operate"));
        result.put("viewInspections", StpUtil.hasPermission("ops:inspection:list"));
        result.put("operateInspections", StpUtil.hasPermission("ops:inspection:operate"));
        result.put("viewAccess", StpUtil.hasPermission("access:view"));
        result.put("viewBilling", StpUtil.hasPermission("billing:list"));
        result.put("sendCommand", StpUtil.hasPermission("access:command"));
        result.put("editArchive", StpUtil.hasPermission("archive:edit"));
        result.put("generateBill", StpUtil.hasPermission("billing:bill:generate"));
        result.put("viewBills", StpUtil.hasPermission("billing:bill:list"));
        result.put("viewBatches", StpUtil.hasPermission("billing:batch:list"));
        result.put("viewCollections", StpUtil.hasPermission("billing:collection:list"));
        result.put("viewAdjustments", StpUtil.hasPermission("billing:adjustment:list"));
        result.put("viewMetering", StpUtil.hasPermission("billing:metering:list"));
        result.put("viewContracts", StpUtil.hasPermission("billing:contract:list"));
        return result;
    }

    private Map<String, Object> select(List<Map<String, Object>> rows, Long id) {
        if (rows.isEmpty()) {
            return new LinkedHashMap<>();
        }
        if (id == null) {
            return rows.get(0);
        }
        return rows.stream().filter(row -> Objects.equals(accessService.longOrNull(row.get("id")), id))
                .findFirst().orElseGet(LinkedHashMap::new);
    }

    private String text(Object value) {
        return Objects.toString(value, "");
    }

    private long number(Object value) {
        if (value == null || value.toString().isBlank()) {
            return 0;
        }
        return Long.parseLong(value.toString());
    }

    private double decimal(Object value) {
        if (value == null || value.toString().isBlank()) {
            return 0;
        }
        return Double.parseDouble(value.toString());
    }

    private boolean isClosed(String status) {
        return List.of("CLOSED", "CANCELLED").contains(status);
    }
}
