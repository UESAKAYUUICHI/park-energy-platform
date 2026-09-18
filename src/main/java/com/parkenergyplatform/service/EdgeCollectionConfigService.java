package com.parkenergyplatform.service;

import cn.dev33.satoken.stp.StpUtil;
import com.parkenergyplatform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class EdgeCollectionConfigService {
    private final JdbcTemplate jdbcTemplate;
    private final BusinessDataAccessService accessService;
    private final RemoteServiceClient remoteServiceClient;
    private final EdgeGatewaySyncService gatewaySyncService;

    public EdgeCollectionConfigService(JdbcTemplate jdbcTemplate, BusinessDataAccessService accessService,
                                       RemoteServiceClient remoteServiceClient,
                                       EdgeGatewaySyncService gatewaySyncService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessService = accessService;
        this.remoteServiceClient = remoteServiceClient;
        this.gatewaySyncService = gatewaySyncService;
    }

    public Map<String, Object> overview(Map<String, String> params) {
        Long orgId = longOrNull(params.get("orgId"));
        boolean includeChildren = !"false".equalsIgnoreCase(params.getOrDefault("includeChildren", "true"));
        List<Object> args = new ArrayList<>();
        String scope = orgId == null
                ? accessService.scopeSql("g.org_id", args)
                : accessService.orgFilterSql("g.org_id", orgId, includeChildren, args);
        List<Map<String, Object>> gateways = jdbcTemplate.queryForList("""
                SELECT g.id AS gatewayId, g.gateway_sn AS gatewaySn, g.gateway_name AS gatewayName,
                       g.status, g.org_id AS orgId, o.org_name AS orgName,
                       s.desired_revision AS desiredRevision, s.applied_revision AS appliedRevision,
                       s.desired_checksum AS desiredChecksum, s.applied_checksum AS appliedChecksum,
                       COALESCE(s.apply_status,'NEVER') AS applyStatus, s.last_error AS lastError,
                       s.last_sync_time AS lastSyncTime,
                       COUNT(d.id) AS deviceCount,
                       SUM(CASE WHEN d.status=1 THEN 1 ELSE 0 END) AS enabledDeviceCount,
                       SUM(CASE WHEN d.edge_channel_id IS NULL OR d.edge_channel_id='' THEN 1 ELSE 0 END) AS missingChannelCount
                FROM dev_gateway g
                LEFT JOIN dev_org o ON o.id=g.org_id
                LEFT JOIN dev_gateway_config_state s ON s.gateway_id=g.id
                LEFT JOIN dev_device d ON d.gateway_id=g.id
                WHERE 1=1
                """ + scope + """
                GROUP BY g.id,g.gateway_sn,g.gateway_name,g.status,g.org_id,o.org_name,
                         s.desired_revision,s.applied_revision,s.desired_checksum,s.applied_checksum,
                         s.apply_status,s.last_error,s.last_sync_time
                ORDER BY g.id
                """, args.toArray());
        for (Map<String, Object> gateway : gateways) {
            long gatewayId = number(gateway.get("gatewayId"), 0);
            if (gatewayId <= 0) continue;
            Map<String, Object> check = precheck(gatewayId);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> checkIssues = (List<Map<String, Object>>) check.getOrDefault("issues", List.of());
            gateway.put("precheckPassed", check.get("passed"));
            gateway.put("precheckIssueCount", checkIssues.size());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gateways", gateways);
        result.put("summary", summarize(gateways));
        return result;
    }

    public Map<String, Object> gatewayDetail(long gatewayId) {
        accessService.assertGatewayAccess(gatewayId);
        Map<String, Object> gateway = single("""
                SELECT g.id AS gatewayId, g.gateway_sn AS gatewaySn, g.gateway_name AS gatewayName,
                       g.status, g.org_id AS orgId, o.org_name AS orgName,
                       s.desired_revision AS desiredRevision, s.applied_revision AS appliedRevision,
                       s.desired_checksum AS desiredChecksum, s.applied_checksum AS appliedChecksum,
                       COALESCE(s.apply_status,'NEVER') AS applyStatus, s.last_error AS lastError,
                       s.last_sync_time AS lastSyncTime
                FROM dev_gateway g
                LEFT JOIN dev_org o ON o.id=g.org_id
                LEFT JOIN dev_gateway_config_state s ON s.gateway_id=g.id
                WHERE g.id=?
                """, gatewayId);
        List<Map<String, Object>> devices = devices(gatewayId);
        List<Map<String, Object>> models = models(gatewayId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gateway", gateway);
        result.put("devices", devices);
        result.put("channels", channels(gatewayId, devices));
        result.put("models", models);
        result.put("resources", resources(gatewayId));
        result.put("ports", ports(gatewayId));
        result.put("releases", releases(gatewayId));
        result.put("audit", audit(gatewayId));
        result.put("precheck", precheck(gatewayId));
        return result;
    }

    public Map<String, Object> precheck(long gatewayId) {
        accessService.assertGatewayAccess(gatewayId);
        List<Map<String, Object>> devices = devices(gatewayId);
        List<Map<String, Object>> points = jdbcTemplate.queryForList("""
                SELECT d.device_sn AS deviceSn, d.edge_channel_id AS channelId,
                       CAST(d.protocol_addr AS UNSIGNED) AS modbusAddr,
                       pp.profile_code AS profileKey, b.id AS modelPointId, b.point_code AS pointCode,
                       b.point_code AS standardPointCode,
                       pd.id AS standardDefinitionId,
                       rb.function_code AS functionCode, rb.start_address+f.register_offset AS registerAddress,
                       f.register_length AS registerLength, f.value_type AS valueType,
                       f.byte_order AS byteOrder,f.bit_offset AS bitOffset,f.bit_length AS bitLength
                FROM dev_device d
                LEFT JOIN dev_device_model_version v ON v.id=d.model_version_id
                LEFT JOIN dev_device_model m ON m.id=v.model_id
                LEFT JOIN dev_protocol_profile_version pv ON pv.id=v.protocol_profile_version_id
                LEFT JOIN dev_protocol_profile pp ON pp.id=pv.profile_id
                LEFT JOIN dev_model_point_binding b ON b.model_version_id=v.id
                LEFT JOIN dev_protocol_field f ON f.id=b.protocol_field_id
                LEFT JOIN dev_protocol_read_block rb ON rb.id=f.read_block_id
                LEFT JOIN dev_point_definition pd ON pd.device_type_id=v.device_type_id
                  AND BINARY pd.point_code=BINARY b.point_code AND pd.enabled=1
                WHERE d.gateway_id=?
                ORDER BY d.id,b.sort,b.id
                """, gatewayId);
        List<Map<String, Object>> issues = new ArrayList<>();
        List<Map<String, Object>> channelRows = jdbcTemplate.queryForList("""
                SELECT channel_id AS channelId,protocol,serial_port AS serialPort,baud_rate AS baudRate,
                       data_bits AS dataBits,stop_bits AS stopBits,parity,timeout_ms AS timeoutMs,
                       retry_count AS retryCount,enabled
                FROM dev_gateway_channel WHERE gateway_id=? ORDER BY channel_id
                """, gatewayId);
        Map<String, String> portOwner = new LinkedHashMap<>();
        for (Map<String, Object> channel : channelRows) {
            String channelId = text(channel.get("channelId"));
            String protocol = text(channel.get("protocol")).toUpperCase(Locale.ROOT);
            String serialPort = text(channel.get("serialPort"));
            if (!"MODBUS_RTU".equals(protocol)) {
                issues.add(issue("ERROR", channelId, "当前网关运行时只支持 Modbus RTU 主站"));
            }
            if (serialPort.isBlank()) {
                issues.add(issue("ERROR", channelId, "通道未选择网关串口"));
            }
            if (number(channel.get("enabled"), 0) == 1 && !serialPort.isBlank()) {
                String previous = portOwner.putIfAbsent(serialPort.toLowerCase(Locale.ROOT), channelId);
                if (previous != null) {
                    issues.add(issue("ERROR", channelId, "同一串口已被启用通道占用：" + previous));
                }
            }
        }
        if (channelRows.isEmpty()) {
            issues.add(issue("ERROR", "GATEWAY", "网关至少需要一个采集通道"));
        }
        Map<String, String> addressOwner = new LinkedHashMap<>();
        for (Map<String, Object> device : devices) {
            String sn = text(device.get("deviceSn"));
            String channel = text(device.get("channelId"));
            int addr = number(device.get("modbusAddr"), 0);
            if (channel.isBlank()) {
                issues.add(issue("ERROR", sn, "设备未绑定 RS485 通道"));
            }
            if (addr < 1 || addr > 247) {
                issues.add(issue("ERROR", sn, "Modbus 从站地址必须在 1-247"));
            }
            String key = channel + ":" + addr;
            if (!channel.isBlank() && addr > 0) {
                String previous = addressOwner.putIfAbsent(key, sn);
                if (previous != null) {
                    issues.add(issue("ERROR", sn, "同一 RS485 通道存在重复从站地址，已占用设备：" + previous));
                }
            }
            if (number(device.get("pointCount"), 0) == 0) {
                issues.add(issue("ERROR", sn, "物模型没有可下发的 Modbus 采集点"));
            }
        }
        for (Map<String, Object> point : points) {
            String pointCode = text(point.get("pointCode"));
            if (pointCode.isBlank()) continue;
            String owner = text(point.get("deviceSn")) + "/" + pointCode;
            String standardPointCode = text(point.get("standardPointCode"));
            if (standardPointCode.isBlank() || point.get("standardDefinitionId") == null) {
                issues.add(issue("ERROR", owner, "标准测点 " + standardPointCode + " 未在当前设备类型中定义"));
            }
            int functionCode = number(point.get("functionCode"), 0);
            int length = number(point.get("registerLength"), 0);
            int address = number(point.get("registerAddress"), -1);
            String valueType = text(point.get("valueType")).toLowerCase(Locale.ROOT);
            String byteOrder = text(point.get("byteOrder")).toUpperCase(Locale.ROOT);
            if (functionCode != 3 && functionCode != 4) {
                issues.add(issue("ERROR", owner, "功能码只允许 03 或 04"));
            }
            if (address < 0 || address + length > 65_536) {
                issues.add(issue("ERROR", owner, "寄存器地址越界"));
            }
            int expectedLength = switch (valueType) {
                case "u16", "uint16", "i16", "int16", "boolean" -> 1;
                case "u32", "uint32", "i32", "int32", "f32", "float32" -> 2;
                default -> -1;
            };
            if (expectedLength < 0) {
                issues.add(issue("ERROR", owner, "数值类型不支持：" + valueType));
            } else if (length != expectedLength) {
                issues.add(issue("ERROR", owner, "寄存器长度与数值类型不匹配"));
            }
            if (!validByteOrder(length, byteOrder)) {
                issues.add(issue("ERROR", owner, "字节序与寄存器长度不匹配：" + byteOrder));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", issues.stream().noneMatch(item -> "ERROR".equals(item.get("level"))));
        result.put("issues", issues);
        result.put("deviceCount", devices.size());
        result.put("pointCount", points.stream()
                .filter(row -> row.get("modelPointId") != null)
                .map(row -> row.get("modelPointId"))
                .distinct().count());
        return result;
    }

    public Map<String, Object> deviceCandidates(long gatewayId, Map<String, String> params) {
        accessService.assertGatewayAccess(gatewayId);
        Map<String, Object> gateway = single("SELECT org_id AS orgId FROM dev_gateway WHERE id=?", gatewayId);
        Long orgId = longOrNull(params.get("orgId"));
        if (orgId == null) orgId = longOrNull(gateway.get("orgId"));
        String keyword = "%" + text(params.get("keyword")) + "%";
        List<Object> args = new ArrayList<>();
        String scope = accessService.orgFilterSql("d.org_id", orgId, true, args);
        args.add(gatewayId);
        args.add(keyword);
        args.add(keyword);
        args.add(keyword);
        args.add(gatewayId);
        List<Map<String, Object>> devices = jdbcTemplate.queryForList("""
                SELECT d.id AS deviceId,d.device_sn AS deviceSn,d.device_name AS deviceName,
                       d.gateway_id AS gatewayId,g.gateway_name AS gatewayName,g.gateway_sn AS gatewaySn,
                       d.edge_channel_id AS channelId,CAST(d.protocol_addr AS UNSIGNED) AS modbusAddr,
                       d.collect_interval_seconds AS collectIntervalSeconds,d.status,
                       v.id AS modelVersionId,v.version_name AS modelVersion,
                       m.model_code AS profileKey,m.model_name AS modelName,
                       o.org_name AS orgName
                FROM dev_device d
                LEFT JOIN dev_gateway g ON g.id=d.gateway_id
                LEFT JOIN dev_org o ON o.id=d.org_id
                LEFT JOIN dev_device_model_version v ON v.id=d.model_version_id
                LEFT JOIN dev_device_model m ON m.id=v.model_id
                WHERE 1=1
                """ + scope + """
                  AND (d.gateway_id IS NULL OR d.gateway_id=?)
                  AND (?='%%' OR d.device_sn LIKE ? OR d.device_name LIKE ?)
                ORDER BY CASE WHEN d.gateway_id=? THEN 0 ELSE 1 END,d.device_sn
                LIMIT 500
                """, args.toArray());
        return Map.of("gatewayId", gatewayId, "devices", devices);
    }

    @Transactional
    public Map<String, Object> createChannel(long gatewayId, Map<String, Object> body) {
        accessService.assertGatewayAccess(gatewayId);
        Map<String, Object> channel = normalizeChannel(body, null);
        jdbcTemplate.update("""
                INSERT INTO dev_gateway_channel
                  (gateway_id,channel_id,channel_name,protocol,serial_port,baud_rate,data_bits,
                   stop_bits,parity,timeout_ms,retry_count,poll_interval_seconds,enabled,remark,
                   create_by,update_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, gatewayId, channel.get("channelId"), channel.get("channelName"), channel.get("protocol"),
                channel.get("serialPort"), channel.get("baudRate"), channel.get("dataBits"), channel.get("stopBits"),
                channel.get("parity"), channel.get("timeoutMs"), channel.get("retryCount"),
                channel.get("pollIntervalSeconds"), channel.get("enabled"), channel.get("remark"),
                operator(), operator());
        touchDraft(gatewayId, "CHANNEL_CREATE", 0L, null, channel);
        return gatewayDetail(gatewayId);
    }

    @Transactional
    public Map<String, Object> updateChannel(long gatewayId, String channelId, Map<String, Object> body) {
        accessService.assertGatewayAccess(gatewayId);
        Map<String, Object> before = single("SELECT * FROM dev_gateway_channel WHERE gateway_id=? AND channel_id=?", gatewayId, channelId);
        Map<String, Object> channel = normalizeChannel(body, channelId);
        jdbcTemplate.update("""
                UPDATE dev_gateway_channel
                SET channel_name=?,protocol=?,serial_port=?,baud_rate=?,data_bits=?,stop_bits=?,
                    parity=?,timeout_ms=?,retry_count=?,poll_interval_seconds=?,enabled=?,remark=?,
                    update_by=?,update_time=NOW()
                WHERE gateway_id=? AND channel_id=?
                """, channel.get("channelName"), channel.get("protocol"), channel.get("serialPort"),
                channel.get("baudRate"), channel.get("dataBits"), channel.get("stopBits"), channel.get("parity"),
                channel.get("timeoutMs"), channel.get("retryCount"), channel.get("pollIntervalSeconds"),
                channel.get("enabled"), channel.get("remark"), operator(), gatewayId, channelId);
        touchDraft(gatewayId, "CHANNEL_UPDATE", 0L, before, channel);
        return gatewayDetail(gatewayId);
    }

    @Transactional
    public Map<String, Object> deleteChannel(long gatewayId, String channelId) {
        accessService.assertGatewayAccess(gatewayId);
        Long used = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM dev_device WHERE gateway_id=? AND edge_channel_id=?
                """, Long.class, gatewayId, channelId);
        if (used != null && used > 0) throw new BusinessException("通道已绑定设备，不能删除");
        Map<String, Object> before = single("SELECT * FROM dev_gateway_channel WHERE gateway_id=? AND channel_id=?", gatewayId, channelId);
        jdbcTemplate.update("DELETE FROM dev_gateway_channel WHERE gateway_id=? AND channel_id=?", gatewayId, channelId);
        touchDraft(gatewayId, "CHANNEL_DELETE", 0L, before, null);
        return gatewayDetail(gatewayId);
    }

    @Transactional
    public Map<String, Object> updateDeviceBinding(long deviceId, Map<String, Object> body) {
        Map<String, Object> current = single("""
                SELECT d.id,d.gateway_id AS gatewayId,d.device_sn AS deviceSn,d.edge_channel_id AS channelId,
                       d.protocol_addr AS protocolAddr,d.collect_interval_seconds AS collectIntervalSeconds
                FROM dev_device d WHERE d.id=?
                """, deviceId);
        accessService.assertDeviceAccess(deviceId);
        long gatewayId = number(value(body, "gatewayId", "gateway_id"), 0);
        if (gatewayId <= 0 && current.get("gatewayId") != null) {
            gatewayId = ((Number) current.get("gatewayId")).longValue();
        }
        if (gatewayId <= 0) throw new BusinessException("绑定设备时必须选择网关");
        accessService.assertGatewayAccess(gatewayId);
        String channelId = text(value(body, "channelId", "edgeChannelId", "edge_channel_id"));
        if (channelId.isBlank()) throw new BusinessException("RS485 通道不能为空");
        Long channelExists = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM dev_gateway_channel WHERE gateway_id=? AND channel_id=? AND enabled=1
                """, Long.class, gatewayId, channelId);
        if (channelExists == null || channelExists == 0) throw new BusinessException("请选择已启用的网关通道");
        int modbusAddr = number(value(body, "modbusAddr", "protocolAddr", "protocol_addr"), 0);
        if (modbusAddr < 1 || modbusAddr > 247) throw new BusinessException("Modbus 从站地址必须在 1-247");
        int interval = number(value(body, "collectIntervalSeconds", "collect_interval_seconds"), 300);
        if (interval < 5 || interval > 86_400) throw new BusinessException("采集周期必须在 5-86400 秒");
        List<String> duplicated = jdbcTemplate.queryForList("""
                SELECT device_sn FROM dev_device
                WHERE gateway_id=? AND edge_channel_id=? AND CAST(protocol_addr AS UNSIGNED)=? AND id<>? AND status=1
                LIMIT 1
                """, String.class, gatewayId, channelId, modbusAddr, deviceId);
        if (!duplicated.isEmpty()) {
            throw new BusinessException("同一 RS485 通道已存在从站地址 " + modbusAddr + "：" + duplicated.get(0));
        }
        jdbcTemplate.update("""
                UPDATE dev_device
                SET gateway_id=?, edge_channel_id=?, protocol_addr=?, collect_interval_seconds=?, update_by=?, update_time=NOW()
                WHERE id=?
                """, gatewayId, channelId, String.valueOf(modbusAddr), interval, operator(), deviceId);
        Long sourceGatewayId = current.get("gatewayId") instanceof Number n ? n.longValue() : null;
        if (sourceGatewayId != null && sourceGatewayId != gatewayId) {
            touchDraft(sourceGatewayId, "DEVICE_MOVED_OUT", deviceId, current, Map.of("targetGatewayId", gatewayId));
        }
        touchDraft(gatewayId, "DEVICE_BINDING", deviceId, current, Map.of(
                "channelId", channelId, "modbusAddr", modbusAddr, "collectIntervalSeconds", interval));
        return gatewayDetail(gatewayId);
    }

    public Map<String, Object> probeDevice(long deviceId) {
        Map<String, Object> device = single("""
                SELECT d.id AS deviceId,d.gateway_id AS gatewayId,g.gateway_sn AS gatewaySn,d.device_sn AS deviceSn,
                       d.edge_channel_id AS channelId,CAST(d.protocol_addr AS UNSIGNED) AS modbusAddr,
                       m.model_code AS profileKey,v.version_name AS modelVersion
                FROM dev_device d
                JOIN dev_gateway g ON g.id=d.gateway_id
                LEFT JOIN dev_device_model_version v ON v.id=d.model_version_id
                LEFT JOIN dev_device_model m ON m.id=v.model_id
                WHERE d.id=?
                """, deviceId);
        long gatewayId = ((Number) device.get("gatewayId")).longValue();
        accessService.assertGatewayAccess(gatewayId);
        String channel = text(device.get("channelId"));
        int addr = number(device.get("modbusAddr"), 0);
        if (channel.isBlank() || addr < 1 || addr > 247) {
            throw new BusinessException("试采前必须先配置 RS485 通道和 1-247 的从站地址");
        }
        String sn = text(device.get("deviceSn"));
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("gatewayId", gatewayId);
        command.put("topicGatewayId", String.valueOf(gatewayId));
        command.put("targetType", "DEVICE");
        command.put("targetId", deviceId);
        command.put("targetSn", sn);
        command.put("commandType", "READ_NOW");
        command.put("commandPayload", Map.of(
                "channelId", channel,
                "modbusAddr", addr,
                "profileKey", text(device.get("profileKey")),
                "modelVersion", text(device.get("modelVersion"))
        ));
        command.put("requestUserId", operatorUserId());
        command.put("requestUsername", operator());
        audit(gatewayId, "PROBE", deviceId, null, command);
        Map<String, Object> response = remoteServiceClient.postAccess("/api/access/commands", command);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("device", device);
        result.put("command", response.get("data"));
        result.put("message", "试采指令已下发，网关回执和采样结果将通过接入链路返回");
        return result;
    }

    @Transactional
    public Map<String, Object> markPending(long gatewayId) {
        accessService.assertGatewayAccess(gatewayId);
        Map<String, Object> check = precheck(gatewayId);
        if (!Boolean.TRUE.equals(check.get("passed"))) {
            throw new BusinessException("发布前检查未通过");
        }
        Map<String, Object> snapshot = gatewaySyncService.publishSnapshot(gatewayId, operator());
        String revision = text(snapshot.get("desiredRevision"));
        String checksum = text(snapshot.get("configChecksum"));
        audit(gatewayId, "PUBLISH", gatewayId, null,
                Map.of("desiredRevision", revision, "desiredChecksum", checksum));
        return Map.of("gatewayId", gatewayId, "desiredRevision", revision,
                "desiredChecksum", checksum, "applyStatus", "PENDING");
    }

    private List<Map<String, Object>> devices(long gatewayId) {
        return jdbcTemplate.queryForList("""
                SELECT d.id AS deviceId, d.device_sn AS deviceSn, d.device_name AS deviceName,
                       d.edge_channel_id AS channelId, CAST(d.protocol_addr AS UNSIGNED) AS modbusAddr,
                       d.collect_interval_seconds AS collectIntervalSeconds, d.status AS status,
                       v.id AS modelVersionId, v.version_name AS modelVersion,
                       pp.profile_code AS profileKey, m.model_name AS modelName,
                       COUNT(b.id) AS pointCount
                FROM dev_device d
                LEFT JOIN dev_device_model_version v ON v.id=d.model_version_id
                LEFT JOIN dev_device_model m ON m.id=v.model_id
                LEFT JOIN dev_protocol_profile_version pv ON pv.id=v.protocol_profile_version_id
                LEFT JOIN dev_protocol_profile pp ON pp.id=pv.profile_id
                LEFT JOIN dev_model_point_binding b ON b.model_version_id=v.id
                WHERE d.gateway_id=?
                GROUP BY d.id,d.device_sn,d.device_name,d.edge_channel_id,d.protocol_addr,
                         d.collect_interval_seconds,d.status,v.id,v.version_name,pp.profile_code,m.model_name
                ORDER BY d.edge_channel_id,CAST(d.protocol_addr AS UNSIGNED),d.id
                """, gatewayId);
    }

    private List<Map<String, Object>> models(long gatewayId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT DISTINCT v.id AS modelVersionId, pp.profile_code AS profileKey, m.model_name AS modelName,
                       v.version_name AS modelVersion,pv.version_name AS protocolVersion,
                       COUNT(DISTINCT b.id) AS pointCount,
                       MIN(rb.start_address) AS minRegisterAddress,
                       MAX(rb.start_address + rb.register_count - 1) AS maxRegisterAddress
                FROM dev_device d
                JOIN dev_device_model_version v ON v.id=d.model_version_id
                JOIN dev_device_model m ON m.id=v.model_id
                LEFT JOIN dev_protocol_profile_version pv ON pv.id=v.protocol_profile_version_id
                LEFT JOIN dev_protocol_profile pp ON pp.id=pv.profile_id
                LEFT JOIN dev_model_point_binding b ON b.model_version_id=v.id
                LEFT JOIN dev_protocol_field f ON f.id=b.protocol_field_id
                LEFT JOIN dev_protocol_read_block rb ON rb.id=f.read_block_id
                WHERE d.gateway_id=?
                GROUP BY v.id,pp.profile_code,m.model_name,v.version_name,pv.version_name
                ORDER BY v.id
                """, gatewayId);
        for (Map<String, Object> row : rows) {
            row.put("points", jdbcTemplate.queryForList("""
                    SELECT b.id,b.point_code AS pointCode,p.point_name AS pointName,p.unit,
                           pf.field_code AS fieldCode,pf.field_name AS fieldName,pf.document_address AS documentAddress,
                           rb.function_code AS functionCode,rb.start_address+pf.register_offset AS registerAddress,
                           pf.register_length AS registerLength,pf.value_type AS valueType,pf.byte_order AS byteOrder,
                           pf.bit_offset AS bitOffset,pf.bit_length AS bitLength,pf.decode_factor AS decodeFactor,
                           b.canonical_factor AS canonicalFactor,b.display_factor AS displayFactor,b.display_unit AS displayUnit,
                           b.required,b.sort
                    FROM dev_model_point_binding b
                    JOIN dev_point_definition p ON p.device_type_id=(SELECT device_type_id FROM dev_device_model_version WHERE id=b.model_version_id)
                      AND BINARY p.point_code=BINARY b.point_code
                    JOIN dev_protocol_field pf ON pf.id=b.protocol_field_id
                    JOIN dev_protocol_read_block rb ON rb.id=pf.read_block_id
                    WHERE b.model_version_id=? ORDER BY b.sort,b.id
                    """, row.get("modelVersionId")));
        }
        return rows;
    }

    private List<Map<String, Object>> resources(long gatewayId) {
        return jdbcTemplate.queryForList("""
                SELECT resource_type AS resourceType, resource_key AS resourceKey, apply_status AS applyStatus,
                       config_revision AS configRevision, config_checksum AS configChecksum, error_message AS errorMessage,
                       applied_time AS appliedTime, update_time AS updateTime
                FROM dev_gateway_config_resource
                WHERE gateway_id=?
                ORDER BY update_time DESC, resource_type, resource_key
                LIMIT 100
                """, gatewayId);
    }

    private List<Map<String, Object>> ports(long gatewayId) {
        return jdbcTemplate.queryForList("""
                SELECT port_key AS portKey,system_path AS systemPath,port_type AS portType,
                       available,last_seen_time AS lastSeenTime
                FROM dev_gateway_port_inventory
                WHERE gateway_id=? ORDER BY available DESC,port_key
                """, gatewayId);
    }

    private List<Map<String, Object>> releases(long gatewayId) {
        return jdbcTemplate.queryForList("""
                SELECT id,revision,checksum,release_status AS releaseStatus,error_message AS errorMessage,
                       published_by AS publishedBy,publish_time AS publishTime,applied_time AS appliedTime
                FROM dev_gateway_config_release
                WHERE gateway_id=? ORDER BY id DESC LIMIT 30
                """, gatewayId);
    }

    private List<Map<String, Object>> audit(long gatewayId) {
        return jdbcTemplate.queryForList("""
                SELECT action_type AS actionType, target_type AS targetType, target_id AS targetId,
                       operator_name AS operatorName, create_time AS createTime
                FROM dev_edge_config_audit
                WHERE gateway_id=?
                ORDER BY id DESC
                LIMIT 30
                """, gatewayId);
    }

    private List<Map<String, Object>> channels(long gatewayId, List<Map<String, Object>> devices) {
        Map<String, Map<String, Object>> channels = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                SELECT id,channel_id AS channelId,channel_name AS channelName,protocol,serial_port AS serialPort,
                       baud_rate AS baudRate,data_bits AS dataBits,stop_bits AS stopBits,parity,
                       timeout_ms AS timeoutMs,retry_count AS retryCount,poll_interval_seconds AS pollIntervalSeconds,
                       enabled,remark
                FROM dev_gateway_channel
                WHERE gateway_id=?
                ORDER BY channel_id
                """, gatewayId)) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("deviceCount", 0);
            item.put("addresses", new ArrayList<Integer>());
            channels.put(text(row.get("channelId")), item);
        }
        for (Map<String, Object> device : devices) {
            String channelId = text(device.get("channelId"));
            if (channelId.isBlank()) channelId = "未绑定";
            Map<String, Object> channel = channels.computeIfAbsent(channelId, key -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("channelId", key);
                item.put("deviceCount", 0);
                item.put("addresses", new ArrayList<Integer>());
                return item;
            });
            channel.put("deviceCount", number(channel.get("deviceCount"), 0) + 1);
            @SuppressWarnings("unchecked")
            List<Integer> addresses = (List<Integer>) channel.get("addresses");
            int addr = number(device.get("modbusAddr"), 0);
            if (addr > 0) addresses.add(addr);
        }
        return new ArrayList<>(channels.values());
    }

    private Map<String, Object> normalizeChannel(Map<String, Object> body, String fixedChannelId) {
        String channelId = fixedChannelId == null ? text(value(body, "channelId", "channel_id")) : fixedChannelId;
        if (!channelId.matches("[A-Za-z0-9_-]{2,40}")) throw new BusinessException("通道编号只能包含字母、数字、下划线和短横线，长度 2-40");
        String protocol = text(value(body, "protocol"));
        if (protocol.isBlank()) protocol = "MODBUS_RTU";
        if (!"MODBUS_RTU".equals(protocol.toUpperCase(Locale.ROOT))) {
            throw new BusinessException("当前网关运行时只支持 MODBUS_RTU 主站");
        }
        String serialPort = text(value(body, "serialPort", "serial_port"));
        if (serialPort.isBlank()) throw new BusinessException("请选择网关实际串口");
        int baudRate = number(value(body, "baudRate", "baud_rate"), 9600);
        int dataBits = number(value(body, "dataBits", "data_bits"), 8);
        int stopBits = number(value(body, "stopBits", "stop_bits"), 1);
        int timeoutMs = number(value(body, "timeoutMs", "timeout_ms"), 1000);
        int retryCount = number(value(body, "retryCount", "retry_count"), 2);
        int pollInterval = number(value(body, "pollIntervalSeconds", "poll_interval_seconds"), 300);
        if (baudRate <= 0) throw new BusinessException("波特率必须大于 0");
        if (dataBits < 5 || dataBits > 8) throw new BusinessException("数据位必须在 5-8");
        if (stopBits < 1 || stopBits > 2) throw new BusinessException("停止位必须为 1 或 2");
        if (timeoutMs < 100 || timeoutMs > 60_000) throw new BusinessException("超时时间必须在 100-60000 毫秒");
        if (retryCount < 0 || retryCount > 10) throw new BusinessException("重试次数必须在 0-10");
        if (pollInterval < 5 || pollInterval > 86_400) throw new BusinessException("默认周期必须在 5-86400 秒");
        Map<String, Object> channel = new LinkedHashMap<>();
        channel.put("channelId", channelId);
        channel.put("channelName", text(value(body, "channelName", "channel_name")).isBlank() ? channelId : text(value(body, "channelName", "channel_name")));
        channel.put("protocol", protocol.toUpperCase(Locale.ROOT));
        channel.put("serialPort", serialPort);
        channel.put("baudRate", baudRate);
        channel.put("dataBits", dataBits);
        channel.put("stopBits", stopBits);
        String parity = text(value(body, "parity")).toUpperCase(Locale.ROOT);
        parity = switch (parity) {
            case "", "NONE" -> "N";
            case "EVEN" -> "E";
            case "ODD" -> "O";
            default -> parity;
        };
        if (!List.of("N", "E", "O").contains(parity)) {
            throw new BusinessException("校验位必须为无校验、偶校验或奇校验");
        }
        channel.put("parity", parity);
        channel.put("timeoutMs", timeoutMs);
        channel.put("retryCount", retryCount);
        channel.put("pollIntervalSeconds", pollInterval);
        channel.put("enabled", boolInt(value(body, "enabled"), true));
        channel.put("remark", text(value(body, "remark")));
        return channel;
    }

    private Map<String, Object> summarize(List<Map<String, Object>> gateways) {
        long pending = gateways.stream().filter(row -> "PENDING".equals(row.get("applyStatus"))).count();
        long applied = gateways.stream().filter(row -> "APPLIED".equals(row.get("applyStatus"))).count();
        long issue = gateways.stream().filter(row -> Boolean.FALSE.equals(row.get("precheckPassed"))
                || number(row.get("missingChannelCount"), 0) > 0).count();
        return Map.of("gatewayCount", gateways.size(), "pendingCount", pending,
                "appliedCount", applied, "issueGatewayCount", issue);
    }

    private Map<String, Object> issue(String level, String target, String message) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("level", level);
        issue.put("target", target);
        issue.put("message", message);
        return issue;
    }

    private boolean validByteOrder(int length, String order) {
        if (order.isBlank()) return true;
        return switch (length) {
            case 1 -> List.of("AB", "BA", "ABCD").contains(order);
            case 2 -> List.of("ABCD", "BADC", "CDAB", "DCBA").contains(order);
            default -> false;
        };
    }

    private void validatePoint(Map<String, Object> point) {
        String code = text(point.get("pointCode"));
        if (code.isBlank()) throw new BusinessException("点位编码不能为空");
        if (text(point.get("standardPointCode")).isBlank()) throw new BusinessException("平台标准点位不能为空：" + code);
        if (text(point.get("pointName")).isBlank()) throw new BusinessException("点位名称不能为空：" + code);
        int functionCode = number(point.get("functionCode"), 0);
        int address = number(point.get("registerAddress"), -1);
        int length = number(point.get("registerLength"), 0);
        String valueType = text(point.get("valueType")).toLowerCase(Locale.ROOT);
        String byteOrder = text(point.get("byteOrder")).toUpperCase(Locale.ROOT);
        if (functionCode != 3 && functionCode != 4) throw new BusinessException(code + " 功能码只允许 03 或 04");
        if (address < 0 || address + length > 65_536) throw new BusinessException(code + " 寄存器地址越界");
        int expectedLength = switch (valueType) {
            case "u16", "i16" -> 1;
            case "u32", "i32", "f32", "float32" -> 2;
            default -> -1;
        };
        if (expectedLength < 0) throw new BusinessException(code + " 数值类型不支持：" + valueType);
        if (length != expectedLength) throw new BusinessException(code + " 寄存器长度与数值类型不匹配");
        if (!validByteOrder(length, byteOrder)) throw new BusinessException(code + " 字节序与寄存器长度不匹配");
    }

    private void touchDraft(long gatewayId, String action, long targetId, Object before, Object after) {
        String checksum = checksum(gatewayId);
        long revision = Long.parseUnsignedLong(checksum.substring(0, 15), 16);
        jdbcTemplate.update("""
                INSERT INTO dev_gateway_config_state(gateway_id,desired_revision,desired_checksum,apply_status,last_sync_time)
                VALUES (?,?,?,'PENDING',NOW())
                ON DUPLICATE KEY UPDATE desired_revision=VALUES(desired_revision),
                  desired_checksum=VALUES(desired_checksum),apply_status='PENDING',last_sync_time=NOW()
                """, gatewayId, revision, checksum);
        audit(gatewayId, action, targetId, before, after);
    }

    private void audit(long gatewayId, String action, long targetId, Object before, Object after) {
        jdbcTemplate.update("""
                INSERT INTO dev_edge_config_audit
                  (gateway_id, action_type, target_type, target_id, before_json, after_json,
                   operator_user_id, operator_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, gatewayId, action, action, targetId, json(before), json(after), operatorUserId(), operator());
    }

    private String checksum(long gatewayId) {
        StringBuilder canonical = new StringBuilder();
        for (Map<String, Object> channel : jdbcTemplate.queryForList("""
                SELECT channel_id,protocol,serial_port,baud_rate,data_bits,stop_bits,parity,
                       timeout_ms,retry_count,poll_interval_seconds,enabled
                FROM dev_gateway_channel WHERE gateway_id=? ORDER BY channel_id
                """, gatewayId)) {
            canonical.append("channel:")
                    .append(channel.values().stream().map(Objects::toString).reduce((a, b) -> a + "|" + b).orElse(""))
                    .append('\n');
        }
        for (Map<String, Object> device : devices(gatewayId)) {
            canonical.append("device:")
                    .append(text(device.get("deviceSn"))).append('|')
                    .append(text(device.get("channelId"))).append('|')
                    .append(number(device.get("modbusAddr"), 0)).append('|')
                    .append(text(device.get("profileKey"))).append('|')
                    .append(text(device.get("modelVersion"))).append('|')
                    .append(number(device.get("collectIntervalSeconds"), 0)).append('\n');
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                SELECT v.id,rb.block_code,rb.function_code,rb.start_address,rb.register_count,
                       pf.field_code,pf.register_offset,pf.register_length,pf.value_type,pf.byte_order,
                       pf.bit_offset,pf.bit_length,pf.decode_factor,pf.decode_offset,
                       b.point_code,b.canonical_factor,b.canonical_offset,b.required
                FROM dev_device d JOIN dev_device_model_version v ON v.id=d.model_version_id
                JOIN dev_protocol_read_block rb ON rb.protocol_version_id=v.protocol_profile_version_id AND rb.poll_mode='CYCLIC'
                JOIN dev_protocol_field pf ON pf.read_block_id=rb.id
                JOIN dev_model_point_binding b ON b.model_version_id=v.id AND b.protocol_field_id=pf.id
                WHERE d.gateway_id=? GROUP BY v.id,rb.id,pf.id,b.id ORDER BY v.id,rb.sort,pf.sort,b.sort
                """, gatewayId)) {
            canonical.append("point:")
                    .append(row.values().stream().map(Objects::toString).reduce((a, b) -> a + "|" + b).orElse(""))
                    .append('\n');
        }
        return sha256(canonical.toString());
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (Exception ex) {
            throw new BusinessException("配置指纹计算失败");
        }
    }

    private Map<String, Object> single(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        if (rows.isEmpty()) throw new BusinessException(404, "数据不存在");
        return rows.get(0);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Object value(Map<?, ?> body, String... keys) {
        for (String key : keys) {
            if (body.containsKey(key)) return body.get(key);
        }
        return null;
    }

    private int number(Object value, int fallback) {
        try {
            if (value == null || String.valueOf(value).isBlank()) return fallback;
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Long longOrNull(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        return Long.valueOf(String.valueOf(value));
    }

    private BigDecimal decimal(Object value, BigDecimal fallback) {
        try {
            if (value == null || String.valueOf(value).isBlank()) return fallback;
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int boolInt(Object value, boolean fallback) {
        if (value == null || String.valueOf(value).isBlank()) return fallback ? 1 : 0;
        if (value instanceof Boolean flag) return flag ? 1 : 0;
        return "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value)) ? 1 : 0;
    }

    private String json(Object value) {
        if (value == null) return "null";
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception ignored) {
            return "\"unavailable\"";
        }
    }

    private Long operatorUserId() {
        return StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
    }

    private String operator() {
        if (!StpUtil.isLogin()) return "system";
        Object username = StpUtil.getSession().get("username");
        return username == null ? String.valueOf(StpUtil.getLoginId()) : String.valueOf(username);
    }
}
