package com.parkenergyplatform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;

    public EdgeGatewaySyncService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> pull(String gatewaySn, String gatewaySecret) {
        Map<String, Object> gateway = authenticate(gatewaySn, gatewaySecret);
        long gatewayId = ((Number) gateway.get("id")).longValue();
        List<String> releases = jdbcTemplate.queryForList("""
                SELECT CAST(config_json AS CHAR) FROM dev_gateway_config_release
                WHERE gateway_id=? AND release_status IN ('PENDING','APPLIED','FAILED')
                ORDER BY id DESC LIMIT 1
                """, String.class, gatewayId);
        if (!releases.isEmpty()) {
            try {
                Map<String, Object> released = objectMapper.readValue(releases.get(0), new TypeReference<>() {});
                jdbcTemplate.update("UPDATE dev_gateway_config_state SET last_sync_time=NOW() WHERE gateway_id=?", gatewayId);
                return released;
            } catch (Exception exception) {
                throw new BusinessException("已发布的网关配置快照无法解析");
            }
        }
        throw new BusinessException("该网关尚无已发布配置，请先在采集配置工作台完成校验并发布");
    }

    public Map<String, Object> publishSnapshot(long gatewayId, String operator) {
        Map<String, Object> gateway = single("SELECT * FROM dev_gateway WHERE id=?", gatewayId);
        Map<String, Object> snapshot = snapshotForGateway(gatewayId, gateway);
        String revision = text(snapshot.get("desiredRevision"), "0");
        String checksum = text(snapshot.get("configChecksum"), null);
        try {
            jdbcTemplate.update("""
                    INSERT INTO dev_gateway_config_release
                      (gateway_id,revision,checksum,release_status,config_json,published_by)
                    VALUES (?,?,?,'PENDING',?,?)
                    ON DUPLICATE KEY UPDATE checksum=VALUES(checksum),config_json=VALUES(config_json),
                      release_status='PENDING',error_message=NULL,published_by=VALUES(published_by),publish_time=NOW()
                    """, gatewayId, revision, checksum, objectMapper.writeValueAsString(snapshot), operator);
        } catch (Exception exception) {
            throw new BusinessException("网关配置发布快照生成失败");
        }
        return snapshot;
    }

    private Map<String, Object> snapshotForGateway(long gatewayId, Map<String, Object> gateway) {
        List<Map<String, Object>> devices = jdbcTemplate.queryForList("""
                SELECT d.id AS platformDeviceId,d.device_sn AS deviceSn,d.device_name AS deviceName,
                       CAST(d.protocol_addr AS UNSIGNED) AS modbusAddr,d.edge_channel_id AS channelId,
                       d.collect_interval_seconds AS collectIntervalS,d.status=1 AS enabled,
                       v.id AS modelVersionId,v.version_name AS modelVersion,pp.profile_code AS profileKey,
                       pv.id AS protocolVersionId
                FROM dev_device d
                JOIN dev_device_model_version v ON v.id=d.model_version_id AND v.status='PUBLISHED'
                JOIN dev_device_model m ON m.id=v.model_id
                JOIN dev_protocol_profile_version pv ON pv.id=v.protocol_profile_version_id AND pv.status='PUBLISHED'
                JOIN dev_protocol_profile pp ON pp.id=pv.profile_id AND pp.enabled=1
                WHERE d.gateway_id=? ORDER BY d.id
                """, gatewayId);
        List<Map<String, Object>> channels = jdbcTemplate.queryForList("""
                SELECT channel_id AS channelId,channel_name AS channelName,protocol,serial_port AS serialPort,
                       baud_rate AS baudRate,data_bits AS dataBits,stop_bits AS stopBits,parity,
                       timeout_ms AS timeoutMs,retry_count AS retryCount,
                       poll_interval_seconds AS pollIntervalSeconds,enabled=1 AS enabled
                FROM dev_gateway_channel
                WHERE gateway_id=? AND enabled=1
                ORDER BY channel_id
                """, gatewayId);
        List<Map<String, Object>> modelRows = jdbcTemplate.queryForList("""
                SELECT DISTINCT v.id AS modelVersionId,pp.profile_code AS profileKey,m.model_name AS modelName,
                       v.version_name AS version,pv.id AS protocolVersionId,pv.version_name AS protocolVersion
                FROM dev_device d JOIN dev_device_model_version v ON v.id=d.model_version_id
                JOIN dev_device_model m ON m.id=v.model_id
                JOIN dev_protocol_profile_version pv ON pv.id=v.protocol_profile_version_id AND pv.status='PUBLISHED'
                JOIN dev_protocol_profile pp ON pp.id=pv.profile_id AND pp.enabled=1
                WHERE d.gateway_id=? AND v.status='PUBLISHED' ORDER BY v.id
                """, gatewayId);
        List<Map<String, Object>> models = new ArrayList<>();
        for (Map<String, Object> model : modelRows) {
            Map<String, Object> item = new LinkedHashMap<>(model);
            List<Map<String, Object>> blocks = jdbcTemplate.queryForList("""
                    SELECT id,block_code AS blockCode,block_name AS blockName,function_code AS functionCode,
                           start_address AS startAddress,register_count AS registerCount,poll_mode AS pollMode,
                           interval_seconds AS intervalSeconds,required=1 AS required
                    FROM dev_protocol_read_block WHERE protocol_version_id=? AND poll_mode='CYCLIC' ORDER BY sort,id
                    """, model.get("protocolVersionId"));
            List<Map<String, Object>> fields = jdbcTemplate.queryForList("""
                    SELECT f.id,f.read_block_id AS readBlockId,f.field_code AS fieldCode,f.field_name AS fieldName,
                           f.register_offset AS registerOffset,f.register_length AS registerLength,f.value_type AS valueType,
                           f.byte_order AS byteOrder,f.bit_offset AS bitOffset,f.bit_length AS bitLength,
                           f.decode_factor AS decodeFactor,f.decode_offset AS decodeOffset,f.required=1 AS required
                    FROM dev_protocol_field f JOIN dev_protocol_read_block rb ON rb.id=f.read_block_id
                    WHERE f.protocol_version_id=? AND rb.poll_mode='CYCLIC' ORDER BY f.sort,f.id
                    """, model.get("protocolVersionId"));
            List<Map<String, Object>> bindings = jdbcTemplate.queryForList("""
                    SELECT point_code AS pointCode,protocol_field_id AS protocolFieldId,
                           canonical_factor AS canonicalFactor,canonical_offset AS canonicalOffset,required=1 AS required
                    FROM dev_model_point_binding WHERE model_version_id=? ORDER BY sort,id
                    """, model.get("modelVersionId"));
            if (blocks.isEmpty() || fields.isEmpty() || bindings.isEmpty()) {
                throw new BusinessException("产品模型 " + model.get("modelName") + " 的协议读块、字段或测点绑定不完整");
            }
            item.put("readBlocks", blocks);
            item.put("fields", fields);
            item.put("bindings", bindings);
            item.put("commands", jdbcTemplate.queryForList("""
                    SELECT command_code AS commandCode,command_name AS commandName,function_code AS functionCode,
                           register_address AS registerAddress,encode_type AS encodeType,value_type AS valueType,
                           fixed_value AS fixedValue,parameter_json AS parameterJson
                    FROM dev_protocol_command WHERE protocol_version_id=? AND enabled=1 ORDER BY sort,id
                    """, model.get("protocolVersionId")));
            models.add(item);
        }
        normalizeAndValidateDevices(devices, channels);
        String checksum = configChecksum(gateway, channels, devices, models);
        long revision = revisionFromChecksum(checksum);
        jdbcTemplate.update("""
                INSERT INTO dev_gateway_config_state(gateway_id,desired_revision,desired_checksum,apply_status,last_sync_time)
                VALUES (?,?,?,'PENDING',NOW())
                ON DUPLICATE KEY UPDATE desired_revision=VALUES(desired_revision),last_sync_time=NOW(),
                  desired_checksum=VALUES(desired_checksum),
                  apply_status=IF(applied_revision=VALUES(desired_revision),'APPLIED','PENDING')
                """, gatewayId, revision, checksum);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gatewayId", gatewayId);
        result.put("gatewaySn", gateway.get("gateway_sn"));
        result.put("desiredRevision", String.valueOf(revision));
        result.put("configChecksum", checksum);
        result.put("channels", channels);
        result.put("devices", devices);
        result.put("models", models);
        return result;
    }

    private void normalizeAndValidateDevices(List<Map<String, Object>> devices, List<Map<String, Object>> channels) {
        Set<String> addresses = new HashSet<>();
        Set<String> enabledChannels = new HashSet<>();
        for (Map<String, Object> channel : channels) {
            enabledChannels.add(text(channel.get("channelId"), ""));
        }
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
            if (!enabledChannels.contains(channel)) {
                throw new BusinessException("设备 " + sn + " 绑定的通道不存在或未启用：" + channel);
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
        String checksum = text(body.get("configChecksum"), null);
        String status = text(body.get("status"), "FAILED").toUpperCase();
        if (!List.of("APPLIED", "FAILED", "RESTART_REQUIRED").contains(status)) {
            throw new BusinessException("不支持的应用状态");
        }
        long appliedRevision = "APPLIED".equals(status) ? applied : 0;
        jdbcTemplate.update("""
                INSERT INTO dev_gateway_config_state(gateway_id,desired_revision,applied_revision,applied_checksum,apply_status,last_error,last_sync_time)
                VALUES (?,?,?,?,?,?,NOW())
                ON DUPLICATE KEY UPDATE
                  applied_revision=IF(VALUES(apply_status)='APPLIED',VALUES(applied_revision),applied_revision),
                  apply_status=VALUES(apply_status),
                  applied_checksum=IF(VALUES(apply_status)='APPLIED',VALUES(applied_checksum),applied_checksum),
                  last_error=VALUES(last_error),last_sync_time=NOW()
                """, gatewayId, applied, appliedRevision, checksum, status, text(body.get("error"), null));
        jdbcTemplate.update("""
                UPDATE dev_gateway_config_release
                SET release_status=?,error_message=?,applied_time=IF(?='APPLIED',NOW(),applied_time)
                WHERE gateway_id=? AND revision=?
                """, status, text(body.get("error"), null), status, gatewayId, String.valueOf(applied));
        recordPortInventory(gatewayId, body);
        recordResources(gatewayId, applied, checksum, body);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("appliedRevision", String.valueOf(applied));
        result.put("status", status);
        if (checksum != null) result.put("configChecksum", checksum);
        return result;
    }

    private void recordPortInventory(long gatewayId, Map<String, Object> body) {
        Object inventory = body.get("portInventory");
        if (!(inventory instanceof List<?> rows)) return;
        jdbcTemplate.update("UPDATE dev_gateway_port_inventory SET available=0 WHERE gateway_id=?", gatewayId);
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> row)) continue;
            String portKey = text(row.get("portKey"), null);
            String systemPath = text(row.get("systemPath"), portKey);
            if (portKey == null || systemPath == null) continue;
            jdbcTemplate.update("""
                    INSERT INTO dev_gateway_port_inventory
                      (gateway_id,port_key,system_path,port_type,available,last_seen_time)
                    VALUES (?,?,?,?,?,NOW())
                    ON DUPLICATE KEY UPDATE system_path=VALUES(system_path),port_type=VALUES(port_type),
                      available=VALUES(available),last_seen_time=NOW()
                    """, gatewayId, portKey, systemPath, text(row.get("portType"), null),
                    bool(row.get("available"), true) ? 1 : 0);
        }
    }

    private void recordResources(long gatewayId, long revision, String checksum, Map<String, Object> body) {
        Object resources = body.get("resources");
        if (!(resources instanceof List<?> rows)) return;
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> row)) continue;
            String type = text(row.get("resourceType"), text(row.get("type"), null));
            String key = text(row.get("resourceKey"), text(row.get("key"), null));
            if (type == null || key == null) continue;
            String status = text(row.get("status"), "APPLIED").toUpperCase();
            String message = text(row.get("message"), null);
            jdbcTemplate.update("""
                    INSERT INTO dev_gateway_config_resource
                      (gateway_id, resource_type, resource_key, config_revision, config_checksum,
                       apply_status, error_message, applied_time)
                    VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
                    ON DUPLICATE KEY UPDATE config_revision=VALUES(config_revision),
                      config_checksum=VALUES(config_checksum), apply_status=VALUES(apply_status),
                      error_message=VALUES(error_message), applied_time=VALUES(applied_time)
                    """, gatewayId, type, key, String.valueOf(revision), checksum, status, message);
        }
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

    private String configChecksum(Map<String, Object> gateway,
                                  List<Map<String, Object>> channels,
                                  List<Map<String, Object>> devices,
                                  List<Map<String, Object>> models) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("gateway:")
                .append(text(gateway.get("gateway_sn"), ""))
                .append('\n');
        for (Map<String, Object> channel : channels) {
            canonical.append("channel:")
                    .append(text(channel.get("channelId"), ""))
                    .append('|').append(text(channel.get("protocol"), ""))
                    .append('|').append(text(channel.get("serialPort"), ""))
                    .append('|').append(number(channel.get("baudRate"), 0))
                    .append('|').append(number(channel.get("dataBits"), 0))
                    .append('|').append(number(channel.get("stopBits"), 0))
                    .append('|').append(text(channel.get("parity"), ""))
                    .append('|').append(number(channel.get("timeoutMs"), 0))
                    .append('|').append(number(channel.get("retryCount"), 0))
                    .append('|').append(number(channel.get("pollIntervalSeconds"), 0))
                    .append('|').append(text(channel.get("enabled"), ""))
                    .append('\n');
        }
        for (Map<String, Object> device : devices) {
            canonical.append("device:")
                    .append(text(device.get("deviceSn"), ""))
                    .append('|').append(text(device.get("channelId"), ""))
                    .append('|').append(number(device.get("modbusAddr"), 0))
                    .append('|').append(text(device.get("profileKey"), ""))
                    .append('|').append(text(device.get("modelVersion"), ""))
                    .append('|').append(number(device.get("collectIntervalS"), 0))
                    .append('|').append(text(device.get("enabled"), ""))
                    .append('\n');
        }
        for (Map<String, Object> model : models) {
            canonical.append("model:")
                    .append(text(model.get("profileKey"), ""))
                    .append('|').append(text(model.get("version"), ""))
                    .append('\n');
            appendModelRows(canonical, "readBlock", model.get("readBlocks"),
                    "id", "blockCode", "functionCode", "startAddress", "registerCount", "sort");
            appendModelRows(canonical, "field", model.get("fields"),
                    "id", "readBlockId", "fieldCode", "fieldName", "registerOffset", "registerLength",
                    "bitOffset", "bitLength", "valueType", "byteOrder", "decodeFactor", "decodeOffset", "sort");
            appendModelRows(canonical, "binding", model.get("bindings"),
                    "pointCode", "protocolFieldId", "canonicalFactor", "canonicalOffset", "required", "sort");
            appendModelRows(canonical, "command", model.get("commands"),
                    "commandCode", "commandName", "functionCode", "registerAddress", "encodeType",
                    "fixedValue", "parameterJson", "sort");
        }
        return sha256Hex(canonical.toString());
    }

    private void appendModelRows(StringBuilder canonical, String prefix, Object rawRows, String... keys) {
        if (!(rawRows instanceof List<?> rows)) return;
        for (Object rawRow : rows) {
            if (!(rawRow instanceof Map<?, ?> row)) continue;
            canonical.append(prefix).append(':');
            for (String key : keys) {
                canonical.append(text(row.get(key), "")).append('|');
            }
            canonical.append('\n');
        }
    }

    private long revisionFromChecksum(String checksum) {
        return Long.parseUnsignedLong(checksum.substring(0, 15), 16);
    }

    private Map<String, Object> single(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        if (rows.isEmpty()) throw new BusinessException("网关不存在");
        return rows.get(0);
    }

    private String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new BusinessException("配置指纹计算失败");
        }
    }

    private long number(Object value, long fallback) {
        try { return value == null ? fallback : Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private String text(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }
    private boolean bool(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean booleanValue) return booleanValue;
        String normalized = String.valueOf(value).trim();
        return "1".equals(normalized) || "true".equalsIgnoreCase(normalized)
                || (!"0".equals(normalized) && !"false".equalsIgnoreCase(normalized) && fallback);
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
