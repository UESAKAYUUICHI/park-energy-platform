package com.parkenergyplatform.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.parkenergyplatform.common.BusinessException;
import org.springframework.stereotype.Component;

@Component
public class TableRegistry {
    private final Map<String, TableDefinition> tables = new LinkedHashMap<>();

    public TableRegistry() {
        register("orgs", "dev_org", List.of("id", "parent_id", "org_name", "org_type", "address", "leader", "phone", "sort", "create_time", "update_time"), List.of("org_name", "leader", "phone"), "id");
        register("gateways", "dev_gateway", List.of("id", "gateway_sn", "gateway_name", "mqtt_secret", "org_id", "install_location", "ip_address", "heartbeat_interval", "online_status", "last_online_time", "firmware_version", "status", "create_time", "update_time"), List.of("gateway_sn", "gateway_name", "install_location"), "org_id");
        register("device-types", "dev_device_type", List.of("id", "type_code", "type_name", "protocol_type", "description", "enabled", "create_time", "update_time"), List.of("type_code", "type_name"));
        register("devices", "dev_device", List.of("id", "device_sn", "device_name", "gateway_id", "org_id", "space_id", "device_type_id", "model_version_id", "protocol_addr", "install_location", "device_model", "install_time", "settlement_enabled", "meter_role", "meter_factor", "collect_interval_seconds", "quality_threshold_pct", "quality_gate_start_date", "status", "create_time", "update_time"), List.of("device_sn", "device_name", "install_location"), "org_id");
        register("spaces", "park_space", List.of("id", "org_id", "parent_id", "space_code", "space_name", "space_type", "building_no", "floor_no", "area", "rentable_area", "status", "remark", "create_time", "update_time"), List.of("space_code", "space_name"), "org_id");
        register("point-definitions", "dev_point_definition", List.of("id", "device_type_id", "point_code", "point_name", "data_type", "unit", "precision_scale", "business_role", "billable", "stat_enabled", "sort", "enabled", "create_time", "update_time"), List.of("point_code", "point_name"));
        register("billing-accounts", "billing_account", List.of("id", "account_name", "org_id", "contact_name", "contact_phone", "status", "create_time", "update_time"), List.of("account_name", "contact_name", "contact_phone"), "org_id");
        register("billing-tenants", "crm_tenant", List.of("id", "tenant_code", "tenant_name", "tenant_type", "unified_social_credit_code", "contact_name", "contact_phone", "contact_email", "billing_address", "status", "remark", "create_time", "update_time"), List.of("tenant_code", "tenant_name", "contact_name", "contact_phone"));
        register("billing-rules", "billing_rule", List.of("id", "account_id", "rule_name", "device_type_id", "metric_point_code", "billing_cycle", "price_mode", "tariff_plan_id", "enabled", "remark", "create_time", "update_time"), List.of("rule_name", "metric_point_code"));
        register("billing-rule-scopes", "billing_rule_scope", List.of("id", "rule_id", "scope_type", "scope_id", "create_time"), List.of("scope_type"));
        register("billing-price-items", "billing_price_item", List.of("id", "rule_id", "price_label", "start_time", "end_time", "tier_min", "tier_max", "unit_price", "sort", "create_time"), List.of("price_label"));
        register("billing-meter-templates", "billing_meter_template", List.of("id", "template_name", "owner_user_id", "owner_username", "org_id", "device_type_id", "point_codes", "all_points", "description", "enabled", "created_by", "created_time", "updated_by", "updated_time"), List.of("template_name", "owner_username", "description"));
        register("billing-bills", "billing_bill", List.of("id", "bill_no", "account_id", "bill_cycle", "start_date", "end_date", "total_amount", "pay_status", "pay_time", "pay_way", "remark", "create_time", "update_time"), List.of("bill_no", "bill_cycle", "remark"));
        register("billing-bill-details", "billing_bill_detail", List.of("id", "bill_id", "rule_id", "device_id", "device_type_id", "point_code", "usage_value", "unit_price", "amount", "calculation_snapshot", "create_time"), List.of("point_code"));
        register("billing-payments", "billing_payment", List.of("id", "bill_id", "pay_amount", "pay_way", "pay_time", "operator", "remark", "create_time"), List.of("pay_way", "operator", "remark"));
        register("daily-stats", "stats_daily_point", List.of("id", "device_id", "device_type_id", "org_id", "point_code", "stat_date", "start_value", "end_value", "usage_value", "max_value", "min_value", "avg_value", "data_complete_rate", "create_time", "update_time"), List.of("point_code"), "org_id");
        register("alarm-events", "log_alarm", List.of("id", "alarm_source", "source_gateway_id", "source_event_id", "rule_id", "device_id", "org_id", "alarm_type", "alarm_level", "point_code", "alarm_value", "threshold_value", "alarm_time", "deal_status", "deal_time", "deal_user", "deal_remark", "create_time"), List.of("point_code", "alarm_value", "deal_user", "source_event_id"), "org_id");
        register("command-records", "command_record", List.of("id", "command_id", "gateway_id", "target_type", "target_id", "target_sn", "command_type", "command_payload", "status", "request_user_id", "request_username", "request_time", "send_time", "response_time", "response_payload", "fail_reason", "create_time", "update_time"), List.of("command_id", "target_sn", "command_type"));
        register("operation-logs", "log_operation", List.of("id", "user_id", "username", "module", "operation", "method", "request_url", "request_param", "ip_address", "cost_time", "status", "error_msg", "create_time"), List.of("username", "module", "operation"));
    }

    public TableDefinition get(String resource) {
        TableDefinition definition = tables.get(resource);
        if (definition == null) {
            throw new BusinessException(404, "不支持的资源: " + resource);
        }
        return definition;
    }

    public Set<String> resources() {
        return tables.keySet();
    }

    private void register(String resource, String table, List<String> columns, List<String> keywordColumns) {
        register(resource, table, columns, keywordColumns, null);
    }

    private void register(String resource, String table, List<String> columns, List<String> keywordColumns, String dataScopeColumn) {
        tables.put(resource, new TableDefinition(resource, table, Set.copyOf(columns), keywordColumns, dataScopeColumn));
    }

    public record TableDefinition(String resource, String table, Set<String> columns, List<String> keywordColumns,
                                  String dataScopeColumn) {
        public boolean hasColumn(String column) {
            return columns.contains(column);
        }
    }
}
