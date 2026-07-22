package com.parkenergyplatform.service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.common.PageResult;
import com.parkenergyplatform.service.TableRegistry.TableDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SimpleTableService {
    private final JdbcTemplate jdbcTemplate;
    private final TableRegistry tableRegistry;
    private final ObjectMapper objectMapper;
    private final DataScopeService dataScopeService;
    private final BusinessDataAccessService accessService;

    public SimpleTableService(JdbcTemplate jdbcTemplate, TableRegistry tableRegistry, ObjectMapper objectMapper,
                              DataScopeService dataScopeService, BusinessDataAccessService accessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.tableRegistry = tableRegistry;
        this.objectMapper = objectMapper;
        this.dataScopeService = dataScopeService;
        this.accessService = accessService;
    }

    public PageResult<Map<String, Object>> page(String resource, Map<String, String> params) {
        TableDefinition definition = tableRegistry.get(resource);
        int pageNum = parsePositive(params.get("pageNum"), 1);
        int pageSize = Math.min(parsePositive(params.get("pageSize"), 20), 200);
        List<Object> args = new ArrayList<>();
        String where = buildWhere(definition, params, args);
        where = appendDataScope(definition, where, args);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + definition.table() + where, Long.class, args.toArray());
        String sql = "SELECT * FROM " + definition.table() + where + " ORDER BY id DESC LIMIT ? OFFSET ?";
        args.add(pageSize);
        args.add((pageNum - 1) * pageSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray());
        return PageResult.of(rows, total == null ? 0 : total, pageNum, pageSize);
    }

    public Map<String, Object> get(String resource, long id) {
        TableDefinition definition = tableRegistry.get(resource);
        List<Object> args = new ArrayList<>();
        args.add(id);
        String where = appendDataScope(definition, " WHERE id = ?", args);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM " + definition.table() + where, args.toArray());
        if (rows.isEmpty()) {
            throw new BusinessException(404, "数据不存在: " + id);
        }
        return rows.get(0);
    }

    @Transactional
    public Map<String, Object> create(String resource, Map<String, Object> body) {
        TableDefinition definition = tableRegistry.get(resource);
        Map<String, Object> values = writableValues(definition, body, false);
        if (values.isEmpty()) {
            throw new BusinessException("没有可保存字段");
        }
        checkWritableDataScope(definition, values);
        StringJoiner columns = new StringJoiner(", ");
        StringJoiner placeholders = new StringJoiner(", ");
        List<Object> args = new ArrayList<>();
        values.forEach((column, value) -> {
            columns.add(column);
            placeholders.add("?");
            args.add(normalizeValue(value));
        });
        String sql = "INSERT INTO " + definition.table() + " (" + columns + ") VALUES (" + placeholders + ")";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? values : get(resource, key.longValue());
    }

    @Transactional
    public Map<String, Object> update(String resource, long id, Map<String, Object> body) {
        TableDefinition definition = tableRegistry.get(resource);
        get(resource, id);
        Map<String, Object> values = writableValues(definition, body, true);
        checkWritableDataScope(definition, values);
        if (values.isEmpty()) {
            return get(resource, id);
        }
        StringJoiner sets = new StringJoiner(", ");
        List<Object> args = new ArrayList<>();
        values.forEach((column, value) -> {
            sets.add(column + " = ?");
            args.add(normalizeValue(value));
        });
        args.add(id);
        jdbcTemplate.update("UPDATE " + definition.table() + " SET " + sets + " WHERE id = ?", args.toArray());
        return get(resource, id);
    }

    @Transactional
    public void delete(String resource, long id) {
        TableDefinition definition = tableRegistry.get(resource);
        get(resource, id);
        jdbcTemplate.update("DELETE FROM " + definition.table() + " WHERE id = ?", id);
    }

    private String appendDataScope(TableDefinition definition, String where, List<Object> args) {
        if (!StpUtil.isLogin()) {
            return where;
        }
        Set<Long> visibleOrgIds = dataScopeService.visibleOrgIds(StpUtil.getLoginIdAsLong());
        if (visibleOrgIds == null) {
            return where;
        }
        String scopeSql = relatedDataScopeSql(definition, visibleOrgIds, args);
        if (scopeSql == null && definition.dataScopeColumn() != null) {
            scopeSql = dataScopeService.inClause(definition.table() + "." + definition.dataScopeColumn(), visibleOrgIds, args);
        }
        if (scopeSql == null) {
            return where;
        }
        if (scopeSql.isBlank()) {
            return where;
        }
        if (where == null || where.isBlank()) {
            return " WHERE " + scopeSql.substring(" AND ".length());
        }
        return where + scopeSql;
    }

    private void checkWritableDataScope(TableDefinition definition, Map<String, Object> values) {
        if (!StpUtil.isLogin()) {
            return;
        }
        if (definition.dataScopeColumn() != null && values.containsKey(definition.dataScopeColumn())) {
            Long orgId = longOrNull(values.get(definition.dataScopeColumn()));
            assertOrgAccess(orgId);
        }
        checkRelatedWritableDataScope(definition, values);
    }

    private String relatedDataScopeSql(TableDefinition definition, Set<Long> visibleOrgIds, List<Object> args) {
        return switch (definition.resource()) {
            case "alarm-rules" -> alarmRuleScopeSql(visibleOrgIds, args);
            case "billing-rules" -> existsSql("""
                    SELECT 1
                    FROM billing_account a
                    WHERE a.id = billing_rule.account_id
                    """, "a.org_id", visibleOrgIds, args);
            case "billing-rule-scopes" -> billingRuleScopeSql(visibleOrgIds, args);
            case "billing-price-items" -> existsSql("""
                    SELECT 1
                    FROM billing_rule r
                    JOIN billing_account a ON a.id = r.account_id
                    WHERE r.id = billing_price_item.rule_id
                    """, "a.org_id", visibleOrgIds, args);
            case "billing-bills" -> existsSql("""
                    SELECT 1
                    FROM billing_account a
                    WHERE a.id = billing_bill.account_id
                    """, "a.org_id", visibleOrgIds, args);
            case "billing-bill-details" -> existsSql("""
                    SELECT 1
                    FROM billing_bill b
                    JOIN billing_account a ON a.id = b.account_id
                    WHERE b.id = billing_bill_detail.bill_id
                    """, "a.org_id", visibleOrgIds, args);
            case "billing-payments" -> existsSql("""
                    SELECT 1
                    FROM billing_bill b
                    JOIN billing_account a ON a.id = b.account_id
                    WHERE b.id = billing_payment.bill_id
                    """, "a.org_id", visibleOrgIds, args);
            case "command-records" -> commandScopeSql(visibleOrgIds, args);
            default -> null;
        };
    }

    private String alarmRuleScopeSql(Set<Long> visibleOrgIds, List<Object> args) {
        String orgScope = dataScopeService.inClause("alarm_rule.org_id", visibleOrgIds, args);
        String deviceScope = existsSql("""
                SELECT 1
                FROM dev_device d
                WHERE d.id = alarm_rule.device_id
                """, "d.org_id", visibleOrgIds, args);
        return " AND (alarm_rule.rule_scope = 1"
                + (orgScope.isBlank() ? "" : orgScope.replaceFirst(" AND ", " OR "))
                + (deviceScope.isBlank() ? "" : deviceScope.replaceFirst(" AND ", " OR "))
                + ")";
    }

    private String billingRuleScopeSql(Set<Long> visibleOrgIds, List<Object> args) {
        String accountScope = existsSql("""
                SELECT 1
                FROM billing_rule r
                JOIN billing_account a ON a.id = r.account_id
                WHERE r.id = billing_rule_scope.rule_id
                """, "a.org_id", visibleOrgIds, args);
        String orgScope = dataScopeService.inClause("billing_rule_scope.scope_id", visibleOrgIds, args);
        String deviceScope = existsSql("""
                SELECT 1
                FROM dev_device d
                WHERE d.id = billing_rule_scope.scope_id
                """, "d.org_id", visibleOrgIds, args);
        if (accountScope.isBlank()) {
            return accountScope;
        }
        return accountScope
                + " AND (billing_rule_scope.scope_type NOT IN ('ORG', 'DEVICE')"
                + (orgScope.isBlank() ? "" : orgScope.replaceFirst(" AND ", " OR (billing_rule_scope.scope_type = 'ORG' AND ") + ")")
                + (deviceScope.isBlank() ? "" : deviceScope.replaceFirst(" AND ", " OR (billing_rule_scope.scope_type = 'DEVICE' AND ") + ")")
                + ")";
    }

    private String commandScopeSql(Set<Long> visibleOrgIds, List<Object> args) {
        String gatewayScope = existsSql("""
                SELECT 1
                FROM dev_gateway g
                WHERE g.id = command_record.gateway_id
                """, "g.org_id", visibleOrgIds, args);
        String deviceScope = existsSql("""
                SELECT 1
                FROM dev_device d
                WHERE d.id = command_record.target_id
                """, "d.org_id", visibleOrgIds, args);
        if (gatewayScope.isBlank() && deviceScope.isBlank()) {
            return " AND 1 = 0";
        }
        return " AND ("
                + (gatewayScope.isBlank() ? "1 = 0" : gatewayScope.substring(" AND ".length()))
                + (deviceScope.isBlank() ? "" : " OR " + deviceScope.substring(" AND ".length()))
                + ")";
    }

    private String existsSql(String existsBody, String orgColumn, Set<Long> visibleOrgIds, List<Object> args) {
        String scope = dataScopeService.inClause(orgColumn, visibleOrgIds, args);
        if (scope.isBlank()) {
            return scope;
        }
        return " AND EXISTS (" + existsBody + scope + ")";
    }

    private void checkRelatedWritableDataScope(TableDefinition definition, Map<String, Object> values) {
        switch (definition.resource()) {
            case "orgs" -> assertParentOrgAccess(values);
            case "devices" -> assertGatewayFieldAccess(values);
            case "alarm-rules" -> assertAlarmRuleWritableAccess(values);
            case "billing-rules", "billing-bills" -> assertAccountFieldAccess(values);
            case "billing-rule-scopes" -> assertBillingRuleScopeWritableAccess(values);
            case "billing-price-items" -> assertRuleFieldAccess(values);
            case "billing-bill-details" -> {
                assertBillFieldAccess(values);
                assertDeviceFieldAccess(values);
            }
            case "billing-payments" -> assertBillFieldAccess(values);
            case "command-records" -> {
                assertGatewayFieldAccess(values);
                assertDeviceTargetFieldAccess(values);
            }
            default -> {
            }
        }
    }

    private void assertParentOrgAccess(Map<String, Object> values) {
        if (values.containsKey("parent_id")) {
            Long parentId = longOrNull(values.get("parent_id"));
            if (parentId != null && parentId > 0) {
                assertOrgAccess(parentId);
            }
        }
    }

    private void assertAlarmRuleWritableAccess(Map<String, Object> values) {
        if (values.containsKey("org_id")) {
            Long orgId = longOrNull(values.get("org_id"));
            if (orgId != null) {
                assertOrgAccess(orgId);
            }
        }
        if (values.containsKey("device_id")) {
            Long deviceId = longOrNull(values.get("device_id"));
            if (deviceId != null) {
                accessService.assertDeviceAccess(deviceId);
            }
        }
    }

    private void assertBillingRuleScopeWritableAccess(Map<String, Object> values) {
        assertRuleFieldAccess(values);
        String scopeType = Objects.toString(values.get("scope_type"), "");
        Long scopeId = longOrNull(values.get("scope_id"));
        if ("ORG".equalsIgnoreCase(scopeType) && scopeId != null) {
            assertOrgAccess(scopeId);
        } else if ("DEVICE".equalsIgnoreCase(scopeType) && scopeId != null) {
            accessService.assertDeviceAccess(scopeId);
        }
    }

    private void assertGatewayFieldAccess(Map<String, Object> values) {
        if (values.containsKey("gateway_id")) {
            accessService.assertGatewayAccess(longOrNull(values.get("gateway_id")));
        }
    }

    private void assertDeviceFieldAccess(Map<String, Object> values) {
        if (values.containsKey("device_id")) {
            accessService.assertDeviceAccess(longOrNull(values.get("device_id")));
        }
    }

    private void assertDeviceTargetFieldAccess(Map<String, Object> values) {
        String targetType = Objects.toString(values.get("target_type"), "");
        if ("DEVICE".equalsIgnoreCase(targetType) && values.containsKey("target_id")) {
            accessService.assertDeviceAccess(longOrNull(values.get("target_id")));
        }
    }

    private void assertAccountFieldAccess(Map<String, Object> values) {
        if (values.containsKey("account_id")) {
            accessService.assertBillingAccountAccess(longOrNull(values.get("account_id")));
        }
    }

    private void assertRuleFieldAccess(Map<String, Object> values) {
        if (values.containsKey("rule_id")) {
            Long accountId = queryLong("""
                    SELECT account_id
                    FROM billing_rule
                    WHERE id = ?
                    """, longOrNull(values.get("rule_id")));
            accessService.assertBillingAccountAccess(accountId);
        }
    }

    private void assertBillFieldAccess(Map<String, Object> values) {
        if (values.containsKey("bill_id")) {
            accessService.assertBillAccess(longOrNull(values.get("bill_id")));
        }
    }

    private void assertOrgAccess(Long orgId) {
        if (orgId != null && !dataScopeService.hasOrgAccess(StpUtil.getLoginIdAsLong(), orgId)) {
            throw new BusinessException(403, "没有该组织的数据操作权限");
        }
    }

    private Long queryLong(String sql, Long id) {
        if (id == null) {
            throw new BusinessException("关联数据 ID 不能为空");
        }
        List<Long> rows = jdbcTemplate.queryForList(sql, Long.class, id);
        if (rows.isEmpty()) {
            throw new BusinessException(404, "关联数据不存在: " + id);
        }
        return rows.get(0);
    }

    private String buildWhere(TableDefinition definition, Map<String, String> params, List<Object> args) {
        StringJoiner where = new StringJoiner(" AND ");
        params.forEach((key, value) -> {
            if (value == null || value.isBlank() || isReservedParam(key)) {
                return;
            }
            if ("orgId".equals(key) && definition.dataScopeColumn() != null) {
                return;
            }
            String column = toSnake(key);
            if (definition.hasColumn(column)) {
                where.add(column + " = ?");
                args.add(value.trim());
            }
        });
        String keyword = params.get("keyword");
        if (keyword != null && !keyword.isBlank() && !definition.keywordColumns().isEmpty()) {
            StringJoiner keywordWhere = new StringJoiner(" OR ", "(", ")");
            for (String column : definition.keywordColumns()) {
                keywordWhere.add(column + " LIKE ?");
                args.add("%" + keyword.trim() + "%");
            }
            where.add(keywordWhere.toString());
        }
        String text = where.toString();
        String orgFilter = explicitOrgFilter(definition, params, args);
        if (text.isBlank() && orgFilter.isBlank()) {
            return "";
        }
        String base = text.isBlank() ? " WHERE 1 = 1" : " WHERE " + text;
        return base + orgFilter;
    }

    private String explicitOrgFilter(TableDefinition definition, Map<String, String> params, List<Object> args) {
        if (definition.dataScopeColumn() == null) {
            return "";
        }
        Long orgId = longOrNull(params.get("orgId"));
        boolean includeChildren = Boolean.parseBoolean(Objects.toString(params.getOrDefault("includeChildren", "false")));
        return accessService.orgFilterSql(definition.table() + "." + definition.dataScopeColumn(), orgId, includeChildren, args);
    }

    private Map<String, Object> writableValues(TableDefinition definition, Map<String, Object> body, boolean update) {
        Map<String, Object> values = new LinkedHashMap<>();
        body.forEach((key, value) -> {
            String column = toSnake(key);
            if (!definition.hasColumn(column) || "id".equals(column) || "create_time".equals(column) || "update_time".equals(column)) {
                return;
            }
            values.put(column, value);
        });
        return values;
    }

    private Object normalizeValue(Object value) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (JsonProcessingException ex) {
                throw new BusinessException("JSON 字段序列化失败");
            }
        }
        return value;
    }

    private boolean isReservedParam(String key) {
        return "pageNum".equals(key) || "pageSize".equals(key) || "keyword".equals(key) || "includeChildren".equals(key);
    }

    private int parsePositive(String input, int defaultValue) {
        try {
            int value = Integer.parseInt(input);
            return value > 0 ? value : defaultValue;
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private Long longOrNull(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return Long.valueOf(value.toString());
    }

    private String toSnake(String input) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isUpperCase(c)) {
                out.append('_').append(Character.toLowerCase(c));
            } else {
                out.append(c == '-' ? '_' : Character.toLowerCase(c));
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
