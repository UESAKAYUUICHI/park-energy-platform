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

    public EdgeCollectionConfigService(JdbcTemplate jdbcTemplate, BusinessDataAccessService accessService,
                                       RemoteServiceClient remoteServiceClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessService = accessService;
        this.remoteServiceClient = remoteServiceClient;
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
        result.put("channels", channels(devices));
        result.put("models", models);
        result.put("resources", resources(gatewayId));
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
                       m.model_code AS profileKey, p.point_code AS pointCode,
                       p.function_code AS functionCode, p.register_address AS registerAddress,
                       p.register_length AS registerLength, p.value_type AS valueType,
                       p.byte_order AS byteOrder
                FROM dev_device d
                LEFT JOIN dev_device_model_version v ON v.id=d.model_version_id
                LEFT JOIN dev_device_model m ON m.id=v.model_id
                LEFT JOIN dev_thing_model_point p ON p.model_version_id=d.model_version_id AND p.enabled=1
                WHERE d.gateway_id=?
                ORDER BY d.id,p.sort,p.id
                """, gatewayId);
        List<Map<String, Object>> issues = new ArrayList<>();
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
                case "u16", "i16" -> 1;
                case "u32", "i32", "f32", "float32" -> 2;
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
        result.put("pointCount", points.stream().filter(row -> !text(row.get("pointCode")).isBlank()).count());
        return result;
    }

    @Transactional
    public Map<String, Object> updateDeviceBinding(long deviceId, Map<String, Object> body) {
        Map<String, Object> current = single("""
                SELECT d.id,d.gateway_id AS gatewayId,d.device_sn AS deviceSn,d.edge_channel_id AS channelId,
                       d.protocol_addr AS protocolAddr,d.collect_interval_seconds AS collectIntervalSeconds
                FROM dev_device d WHERE d.id=?
                """, deviceId);
        long gatewayId = ((Number) current.get("gatewayId")).longValue();
        accessService.assertGatewayAccess(gatewayId);
        String channelId = text(value(body, "channelId", "edgeChannelId", "edge_channel_id"));
        if (channelId.isBlank()) throw new BusinessException("RS485 通道不能为空");
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
                SET edge_channel_id=?, protocol_addr=?, collect_interval_seconds=?, update_by=?, update_time=NOW()
                WHERE id=?
                """, channelId, String.valueOf(modbusAddr), interval, operator(), deviceId);
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
    @SuppressWarnings("unchecked")
    public Map<String, Object> replaceModelPoints(long modelVersionId, Map<String, Object> body) {
        Map<String, Object> version = single("""
                SELECT v.id AS modelVersionId,m.model_code AS profileKey,m.model_name AS modelName
                FROM dev_device_model_version v JOIN dev_device_model m ON m.id=v.model_id
                WHERE v.id=?
                """, modelVersionId);
        List<Long> gatewayIds = jdbcTemplate.queryForList("""
                SELECT DISTINCT gateway_id FROM dev_device WHERE model_version_id=? AND gateway_id IS NOT NULL
                """, Long.class, modelVersionId);
        for (Long gatewayId : gatewayIds) accessService.assertGatewayAccess(gatewayId);
        Object raw = body.get("points");
        if (!(raw instanceof List<?> rows)) throw new BusinessException("点表不能为空");
        List<Map<String, Object>> points = new ArrayList<>();
        int sort = 10;
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("pointCode", text(value(map, "pointCode", "point_code")).toLowerCase(Locale.ROOT));
            point.put("standardPointCode", text(value(map, "standardPointCode", "standard_point_code")).toUpperCase(Locale.ROOT));
            point.put("pointName", text(value(map, "pointName", "point_name")));
            point.put("unit", text(map.get("unit")));
            point.put("functionCode", number(value(map, "functionCode", "function_code"), 0));
            point.put("registerAddress", number(value(map, "registerAddress", "register_address", "address"), -1));
            point.put("registerLength", number(value(map, "registerLength", "register_length", "quantity"), 0));
            point.put("valueType", text(value(map, "valueType", "value_type", "dataType")).toLowerCase(Locale.ROOT));
            point.put("byteOrder", text(value(map, "byteOrder", "byte_order")).toUpperCase(Locale.ROOT));
            point.put("scaleFactor", decimal(value(map, "scaleFactor", "scale_factor", "scale"), BigDecimal.ONE));
            point.put("offsetValue", decimal(value(map, "offsetValue", "offset_value", "offset"), BigDecimal.ZERO));
            point.put("required", boolInt(value(map, "required"), true));
            point.put("enabled", boolInt(value(map, "enabled"), true));
            point.put("sort", number(value(map, "sort"), sort));
            validatePoint(point);
            points.add(point);
            sort += 10;
        }
        if (points.isEmpty()) throw new BusinessException("至少需要一个采集点");
        Map<String, String> seen = new LinkedHashMap<>();
        for (Map<String, Object> point : points) {
            String code = text(point.get("pointCode"));
            String existing = seen.putIfAbsent(code, code);
            if (existing != null) throw new BusinessException("点位编码重复：" + code);
        }
        List<Map<String, Object>> before = jdbcTemplate.queryForList(
                "SELECT * FROM dev_thing_model_point WHERE model_version_id=? ORDER BY sort,id", modelVersionId);
        jdbcTemplate.update("DELETE FROM dev_thing_model_point WHERE model_version_id=?", modelVersionId);
        for (Map<String, Object> point : points) {
            jdbcTemplate.update("""
                    INSERT INTO dev_thing_model_point
                      (model_version_id,point_code,standard_point_code,point_name,unit,function_code,
                       register_address,register_length,value_type,byte_order,scale_factor,offset_value,required,enabled,sort)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, modelVersionId, point.get("pointCode"), point.get("standardPointCode"),
                    point.get("pointName"), point.get("unit"), point.get("functionCode"),
                    point.get("registerAddress"), point.get("registerLength"), point.get("valueType"),
                    point.get("byteOrder"), point.get("scaleFactor"), point.get("offsetValue"),
                    point.get("required"), point.get("enabled"), point.get("sort"));
        }
        for (Long gatewayId : gatewayIds) {
            touchDraft(gatewayId, "MODEL_POINTS", modelVersionId, Map.of("model", version, "points", before),
                    Map.of("model", version, "points", points));
        }
        return Map.of("modelVersionId", modelVersionId, "pointCount", points.size(),
                "affectedGatewayCount", gatewayIds.size());
    }

    @Transactional
    public Map<String, Object> markPending(long gatewayId) {
        accessService.assertGatewayAccess(gatewayId);
        Map<String, Object> check = precheck(gatewayId);
        if (!Boolean.TRUE.equals(check.get("passed"))) {
            throw new BusinessException("发布前检查未通过");
        }
        String checksum = checksum(gatewayId);
        long revision = Long.parseUnsignedLong(checksum.substring(0, 15), 16);
        jdbcTemplate.update("""
                INSERT INTO dev_gateway_config_state(gateway_id,desired_revision,desired_checksum,apply_status,last_sync_time)
                VALUES (?,?,?,'PENDING',NOW())
                ON DUPLICATE KEY UPDATE desired_revision=VALUES(desired_revision),
                  desired_checksum=VALUES(desired_checksum),apply_status='PENDING',last_sync_time=NOW()
                """, gatewayId, revision, checksum);
        audit(gatewayId, "PUBLISH", gatewayId, null, Map.of("desiredRevision", revision, "desiredChecksum", checksum));
        return Map.of("gatewayId", gatewayId, "desiredRevision", String.valueOf(revision),
                "desiredChecksum", checksum, "applyStatus", "PENDING");
    }

    private List<Map<String, Object>> devices(long gatewayId) {
        return jdbcTemplate.queryForList("""
                SELECT d.id AS deviceId, d.device_sn AS deviceSn, d.device_name AS deviceName,
                       d.edge_channel_id AS channelId, CAST(d.protocol_addr AS UNSIGNED) AS modbusAddr,
                       d.collect_interval_seconds AS collectIntervalSeconds, d.status AS status,
                       v.id AS modelVersionId, v.version_name AS modelVersion,
                       m.model_code AS profileKey, m.model_name AS modelName,
                       COUNT(p.id) AS pointCount
                FROM dev_device d
                LEFT JOIN dev_device_model_version v ON v.id=d.model_version_id
                LEFT JOIN dev_device_model m ON m.id=v.model_id
                LEFT JOIN dev_thing_model_point p ON p.model_version_id=d.model_version_id AND p.enabled=1
                WHERE d.gateway_id=?
                GROUP BY d.id,d.device_sn,d.device_name,d.edge_channel_id,d.protocol_addr,
                         d.collect_interval_seconds,d.status,v.id,v.version_name,m.model_code,m.model_name
                ORDER BY d.edge_channel_id,CAST(d.protocol_addr AS UNSIGNED),d.id
                """, gatewayId);
    }

    private List<Map<String, Object>> models(long gatewayId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT DISTINCT v.id AS modelVersionId, m.model_code AS profileKey, m.model_name AS modelName,
                       v.version_name AS modelVersion,
                       COUNT(p.id) AS pointCount,
                       MIN(p.register_address) AS minRegisterAddress,
                       MAX(p.register_address + p.register_length - 1) AS maxRegisterAddress
                FROM dev_device d
                JOIN dev_device_model_version v ON v.id=d.model_version_id
                JOIN dev_device_model m ON m.id=v.model_id
                LEFT JOIN dev_thing_model_point p ON p.model_version_id=v.id AND p.enabled=1
                WHERE d.gateway_id=?
                GROUP BY v.id,m.model_code,m.model_name,v.version_name
                ORDER BY v.id
                """, gatewayId);
        for (Map<String, Object> row : rows) {
            row.put("points", jdbcTemplate.queryForList("""
                    SELECT id, point_code AS pointCode, standard_point_code AS standardPointCode,
                           point_name AS pointName, unit, function_code AS functionCode,
                           register_address AS registerAddress, register_length AS registerLength,
                           value_type AS valueType, byte_order AS byteOrder, scale_factor AS scaleFactor,
                           offset_value AS offsetValue, required, enabled, sort
                    FROM dev_thing_model_point
                    WHERE model_version_id=?
                    ORDER BY sort,id
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

    private List<Map<String, Object>> channels(List<Map<String, Object>> devices) {
        Map<String, Map<String, Object>> channels = new LinkedHashMap<>();
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

    private Map<String, Object> summarize(List<Map<String, Object>> gateways) {
        long pending = gateways.stream().filter(row -> "PENDING".equals(row.get("applyStatus"))).count();
        long applied = gateways.stream().filter(row -> "APPLIED".equals(row.get("applyStatus"))).count();
        long issue = gateways.stream().filter(row -> number(row.get("missingChannelCount"), 0) > 0).count();
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
                SELECT p.point_code,p.function_code,p.register_address,p.register_length,p.value_type,
                       p.byte_order,p.scale_factor,p.offset_value,p.required
                FROM dev_thing_model_point p
                JOIN dev_device d ON d.model_version_id=p.model_version_id
                WHERE d.gateway_id=? AND p.enabled=1
                GROUP BY p.model_version_id,p.point_code,p.function_code,p.register_address,p.register_length,
                         p.value_type,p.byte_order,p.scale_factor,p.offset_value,p.required
                ORDER BY p.model_version_id,p.sort,p.id
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
