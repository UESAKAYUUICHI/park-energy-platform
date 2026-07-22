package com.parkenergyplatform.service;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {
    private final JdbcTemplate jdbcTemplate;
    private final DataScopeService dataScopeService;

    public DashboardService(JdbcTemplate jdbcTemplate, DataScopeService dataScopeService) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataScopeService = dataScopeService;
    }

    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("orgCount", countScoped("dev_org", "id", ""));
        summary.put("gatewayCount", countScoped("dev_gateway", "org_id", ""));
        summary.put("deviceCount", countScoped("dev_device", "org_id", ""));
        summary.put("onlineGatewayCount", countScoped("dev_gateway", "org_id", "online_status = 1"));
        summary.put("enabledAlarmRuleCount", countScoped("alarm_rule", "org_id", "enabled = 1"));
        summary.put("unhandledAlarmCount", countScoped("log_alarm", "org_id", "deal_status = 0"));
        summary.put("unpaidBillCount", countJoinScoped("""
                SELECT COUNT(*)
                FROM billing_bill b
                JOIN billing_account a ON a.id = b.account_id
                WHERE b.pay_status = 0
                """, "a.org_id"));
        summary.put("commandCount", countJoinScoped("""
                SELECT COUNT(*)
                FROM command_record c
                JOIN dev_gateway g ON g.id = c.gateway_id
                WHERE 1 = 1
                """, "g.org_id"));
        summary.put("latestAlarms", queryJoinScoped("""
                SELECT * FROM log_alarm
                WHERE 1 = 1
                """, "org_id", " ORDER BY alarm_time DESC LIMIT 5"));
        summary.put("latestBills", queryJoinScoped("""
                SELECT b.*
                FROM billing_bill b
                JOIN billing_account a ON a.id = b.account_id
                WHERE 1 = 1
                """, "a.org_id", " ORDER BY b.id DESC LIMIT 5"));
        return summary;
    }

    private long countScoped(String table, String orgColumn, String where) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE 1 = 1";
        if (where != null && !where.isBlank()) {
            sql += " AND " + where;
        }
        sql += scopeSql(orgColumn, args);
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
        return value == null ? 0 : value;
    }

    private long countJoinScoped(String baseSql, String orgColumn) {
        List<Object> args = new ArrayList<>();
        Long value = jdbcTemplate.queryForObject(baseSql + scopeSql(orgColumn, args), Long.class, args.toArray());
        return value == null ? 0 : value;
    }

    private List<Map<String, Object>> queryJoinScoped(String baseSql, String orgColumn, String suffix) {
        List<Object> args = new ArrayList<>();
        return jdbcTemplate.queryForList(baseSql + scopeSql(orgColumn, args) + suffix, args.toArray());
    }

    private String scopeSql(String orgColumn, List<Object> args) {
        if (!StpUtil.isLogin()) {
            return "";
        }
        Set<Long> visibleOrgIds = dataScopeService.visibleOrgIds(StpUtil.getLoginIdAsLong());
        return dataScopeService.inClause(orgColumn, visibleOrgIds, args);
    }
}
