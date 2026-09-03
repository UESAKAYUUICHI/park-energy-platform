package com.parkenergyplatform.service;

import com.parkenergyplatform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 网关专用配置同步。网关只通过此 API 读取自己的设备，不直接连接平台数据库。 */
@Service
public class EdgeGatewaySyncService {
    private final JdbcTemplate jdbcTemplate;

    public EdgeGatewaySyncService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Map<String, Object> pull(String gatewaySn, String gatewaySecret) {
        Map<String, Object> gateway = authenticate(gatewaySn, gatewaySecret);
        long gatewayId = ((Number) gateway.get("id")).longValue();
        long revision = desiredRevision(gatewayId);
        jdbcTemplate.update("""
                INSERT INTO dev_gateway_config_state(gateway_id,desired_revision,apply_status,last_sync_time)
                VALUES (?,?,'PENDING',NOW())
                ON DUPLICATE KEY UPDATE desired_revision=VALUES(desired_revision),last_sync_time=NOW(),
                  apply_status=IF(applied_revision=VALUES(desired_revision),'APPLIED','PENDING')
                """, gatewayId, revision);

        List<Map<String, Object>> devices = jdbcTemplate.queryForList("""
                SELECT d.id AS platformDeviceId,d.device_sn AS deviceSn,d.device_name AS deviceName,
                       CAST(d.protocol_addr AS UNSIGNED) AS modbusAddr,d.edge_channel_id AS channelId,
                       d.collect_interval_seconds AS collectIntervalS,d.status=1 AS enabled,
                       v.id AS modelVersionId,v.version_name AS modelVersion,m.model_code AS profileKey
                FROM dev_device d
                JOIN dev_device_model_version v ON v.id=d.model_version_id AND v.status='PUBLISHED'
                JOIN dev_device_model m ON m.id=v.model_id
                WHERE d.gateway_id=? ORDER BY d.id
                """, gatewayId);
        List<Map<String, Object>> modelRows = jdbcTemplate.queryForList("""
                SELECT DISTINCT v.id AS modelVersionId,m.model_code AS profileKey,m.model_name AS modelName,
                       v.version_name AS version
                FROM dev_device d JOIN dev_device_model_version v ON v.id=d.model_version_id
                JOIN dev_device_model m ON m.id=v.model_id
                WHERE d.gateway_id=? AND v.status='PUBLISHED' ORDER BY v.id
                """, gatewayId);
        List<Map<String, Object>> models = new ArrayList<>();
        for (Map<String, Object> model : modelRows) {
            Map<String, Object> item = new LinkedHashMap<>(model);
            List<Map<String, Object>> points = jdbcTemplate.queryForList("""
                    SELECT point_code AS pointCode,point_name AS pointName,unit,function_code AS functionCode,
                           register_address AS registerAddress,register_length AS registerLength,
                           value_type AS valueType,byte_order AS byteOrder,scale_factor AS scaleFactor,
                           offset_value AS offsetValue,required
                    FROM dev_thing_model_point WHERE model_version_id=? AND enabled=1 ORDER BY sort,id
                    """, model.get("modelVersionId"));
            if (points.isEmpty()) {
                throw new BusinessException("物模型 " + model.get("profileKey") + " 没有可用的采集测点");
            }
            item.put("points", points);
            models.add(item);
        }
        normalizeAndValidateDevices(devices);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gatewayId", gatewayId);
        result.put("gatewaySn", gateway.get("gateway_sn"));
        result.put("desiredRevision", String.valueOf(revision));
        result.put("devices", devices);
        result.put("models", models);
        return result;
    }

    private void normalizeAndValidateDevices(List<Map<String, Object>> devices) {
        Set<String> addresses = new HashSet<>();
        for (Map<String, Object> device : devices) {
            String sn = text(device.get("deviceSn"), "未命名设备");
            int address = (int) number(device.get("modbusAddr"), 0);
            if (address < 1 || address > 247) {
                throw new BusinessException("设备 " + sn + " 的 Modbus 地址必须在 1-247 之间");
            }
            String channel = text(device.get("channelId"), null);
            if (channel == null) {
                throw new BusinessException("设备 " + sn + " 未绑定 RS485 通道");
            }
            if (!addresses.add(channel + ":" + address)) {
                throw new BusinessException("RS485 通道 " + channel + " 存在重复地址 " + address);
            }
            Object enabled = device.get("enabled");
            boolean enabledFlag = enabled instanceof Boolean value
                    ? value : number(enabled, 0) == 1;
            device.put("enabled", enabledFlag);
            long interval = number(device.get("collectIntervalS"), 0);
            if (interval < 5 || interval > 86400) {
                throw new BusinessException("设备 " + sn + " 的采集周期必须在 5-86400 秒之间");
            }
        }
    }

    @Transactional
    public Map<String, Object> acknowledge(String gatewaySn, String gatewaySecret, Map<String, Object> body) {
        Map<String, Object> gateway = authenticate(gatewaySn, gatewaySecret);
        long gatewayId = ((Number) gateway.get("id")).longValue();
        long applied = number(body.get("appliedRevision"), 0);
        String status = text(body.get("status"), "FAILED").toUpperCase();
        if (!List.of("APPLIED", "FAILED", "RESTART_REQUIRED").contains(status)) {
            throw new BusinessException("不支持的应用状态");
        }
        jdbcTemplate.update("""
                INSERT INTO dev_gateway_config_state(gateway_id,desired_revision,applied_revision,apply_status,last_error,last_sync_time)
                VALUES (?,?,?, ?,?,NOW())
                ON DUPLICATE KEY UPDATE applied_revision=VALUES(applied_revision),apply_status=VALUES(apply_status),
                  last_error=VALUES(last_error),last_sync_time=NOW()
                """, gatewayId, applied, applied, status, text(body.get("error"), null));
        return Map.of("accepted", true, "appliedRevision", String.valueOf(applied), "status", status);
    }

    public Map<String, Object> activeAlarms(String gatewaySn, String gatewaySecret) {
        Map<String, Object> gateway = authenticate(gatewaySn, gatewaySecret);
        long gatewayId = ((Number) gateway.get("id")).longValue();
        List<Map<String, Object>> alarms = jdbcTemplate.queryForList("""
                SELECT CONCAT('PLATFORM:',a.id) AS eventId,
                       CASE a.alarm_type WHEN 1 THEN 'VOLTAGE' WHEN 2 THEN 'CURRENT'
                         WHEN 3 THEN 'POWER' WHEN 4 THEN 'OFFLINE' ELSE 'GENERAL' END AS alarmType,
                       CASE a.alarm_level WHEN 4 THEN 'CRITICAL' WHEN 3 THEN 'MAJOR'
                         WHEN 2 THEN 'MINOR' ELSE 'INFO' END AS level,
                       d.device_sn AS deviceSn,a.point_code AS pointCode,
                       CONCAT(COALESCE(a.alarm_type,'ALARM'),
                         CASE WHEN a.alarm_value IS NULL THEN '' ELSE CONCAT('：',a.alarm_value) END) AS message,
                       CAST(UNIX_TIMESTAMP(a.alarm_time)*1000 AS UNSIGNED) AS alarmTimeMs
                FROM log_alarm a LEFT JOIN dev_device d ON d.id=a.device_id
                WHERE a.alarm_source='PLATFORM' AND a.condition_status='ACTIVE'
                  AND (a.source_gateway_id=? OR d.gateway_id=?)
                ORDER BY a.alarm_time DESC,a.id DESC LIMIT 200
                """, gatewayId, gatewayId);
        return Map.of("gatewayId", gatewayId, "alarms", alarms, "serverTime", System.currentTimeMillis());
    }

    public Map<String, Object> alarmRules(String gatewaySn, String gatewaySecret) {
        Map<String, Object> gateway = authenticate(gatewaySn, gatewaySecret);
        long gatewayId = ((Number) gateway.get("id")).longValue();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT r.id,r.rule_key,r.rule_name,r.alarm_level,d.device_sn,r.point_code,r.compare_operator,
                       r.threshold_value,r.threshold_min,r.threshold_max,r.duration_seconds,r.enabled,r.rule_scope,
                       r.update_time
                FROM alarm_rule r
                LEFT JOIN dev_device d ON d.id=r.device_id
                WHERE r.enabled=1 AND r.published_version_id IS NOT NULL
                  AND (
                    r.rule_scope=1
                    OR d.gateway_id=?
                    OR EXISTS (
                      SELECT 1 FROM dev_device scoped
                      WHERE scoped.gateway_id=?
                        AND (
                          (r.rule_scope=2 AND scoped.org_id=r.org_id)
                          OR (r.rule_scope=4 AND scoped.space_id=r.space_id)
                        )
                    )
                  )
                ORDER BY r.update_time DESC,r.id DESC
                LIMIT 500
                """, gatewayId, gatewayId);
        List<Map<String, Object>> rules = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String pointCode = normalizePointCode(text(row.get("point_code"), ""));
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("ruleCode", text(row.get("rule_key"), "AR-" + row.get("id")));
            rule.put("name", text(row.get("rule_name"), "未命名告警规则"));
            rule.put("level", level(row.get("alarm_level")));
            rule.put("deviceSn", text(row.get("device_sn"), null));
            rule.put("pointCode", pointCode);
            rule.put("operator", operator(row));
            rule.put("threshold", threshold(row));
            rule.put("unit", unit(pointCode));
            rule.put("durationS", number(row.get("duration_seconds"), 0));
            rule.put("enabled", number(row.get("enabled"), 0) == 1);
            rules.add(rule);
        }
        return Map.of("gatewayId", gatewayId, "rules", rules, "serverTime", System.currentTimeMillis());
    }

    private Map<String, Object> authenticate(String gatewaySn, String secret) {
        if (gatewaySn == null || gatewaySn.isBlank() || secret == null || secret.isBlank()) {
            throw new BusinessException(401, "网关凭据缺失");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id,gateway_sn,mqtt_secret,status,update_time FROM dev_gateway WHERE gateway_sn=? LIMIT 1",
                gatewaySn.trim());
        if (rows.isEmpty() || ((Number) rows.get(0).get("status")).intValue() != 1 ||
                !MessageDigest.isEqual(String.valueOf(rows.get(0).get("mqtt_secret")).getBytes(StandardCharsets.UTF_8),
                        secret.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(401, "网关认证失败");
        }
        return rows.get(0);
    }

    private long desiredRevision(long gatewayId) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT CAST(UNIX_TIMESTAMP(GREATEST(
                  g.update_time,
                  COALESCE((SELECT MAX(d.update_time) FROM dev_device d WHERE d.gateway_id=g.id),g.update_time),
                  COALESCE((SELECT MAX(p.update_time) FROM dev_thing_model_point p JOIN dev_device d
                    ON d.model_version_id=p.model_version_id WHERE d.gateway_id=g.id),g.update_time)
                ))*1000 AS UNSIGNED) FROM dev_gateway g WHERE g.id=?
                """, Long.class, gatewayId);
        return value == null ? 0 : value;
    }

    private long number(Object value, long fallback) {
        try { return value == null ? fallback : Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private String text(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    private String normalizePointCode(String pointCode) {
        return pointCode == null ? "" : pointCode.trim().toLowerCase();
    }

    private String level(Object value) {
        long level = number(value, 2);
        if (level >= 3) return "ERROR";
        if (level == 1) return "INFO";
        return "WARN";
    }

    private String operator(Map<String, Object> row) {
        String op = text(row.get("compare_operator"), ">");
        if ("=".equals(op)) return "==";
        if ("between".equalsIgnoreCase(op)) return ">=";
        return op;
    }

    private double threshold(Map<String, Object> row) {
        Object value = row.get("threshold_value");
        if (value == null && "between".equalsIgnoreCase(text(row.get("compare_operator"), ""))) {
            value = row.get("threshold_min");
        }
        if (value == null) value = row.get("threshold_min");
        if (value == null) value = row.get("threshold_max");
        try { return value == null ? 0D : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0D; }
    }

    private String unit(String pointCode) {
        return switch (pointCode) {
            case "voltage_a", "voltage_b", "voltage_c" -> "V";
            case "current_a", "current_b", "current_c" -> "A";
            case "active_power_total" -> "kW";
            case "reactive_power_total" -> "kvar";
            case "apparent_power_total" -> "kVA";
            case "forward_active_energy" -> "kWh";
            case "frequency" -> "Hz";
            default -> "";
        };
    }
}
