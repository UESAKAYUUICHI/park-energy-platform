package com.parkenergyplatform.service;

import com.parkenergyplatform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 协议化告警配置。协议发布后由网关按设备绑定关系同步，数据服务按协议测点评估。 */
@Service
public class AlarmProtocolService {
    private final JdbcTemplate jdbcTemplate;

    public AlarmProtocolService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> protocols = jdbcTemplate.queryForList(
                "SELECT * FROM alarm_protocol ORDER BY update_time DESC,id DESC");
        for (Map<String, Object> protocol : protocols) {
            long id = number(protocol.get("id"));
            protocol.put("points", jdbcTemplate.queryForList(
                    "SELECT * FROM alarm_protocol_point WHERE protocol_id=? ORDER BY id", id));
            protocol.put("deviceIds", jdbcTemplate.queryForList(
                    "SELECT device_id FROM alarm_protocol_device WHERE protocol_id=? AND enabled=1 ORDER BY device_id",
                    id).stream().map(row -> number(row.get("device_id"))).toList());
        }
        return protocols;
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM alarm_protocol WHERE id=?", id);
        if (rows.isEmpty()) throw new BusinessException("告警协议不存在");
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("points", jdbcTemplate.queryForList(
                "SELECT * FROM alarm_protocol_point WHERE protocol_id=? ORDER BY id", id));
        result.put("deviceIds", jdbcTemplate.queryForList(
                "SELECT device_id FROM alarm_protocol_device WHERE protocol_id=? AND enabled=1 ORDER BY device_id",
                id).stream().map(row -> number(row.get("device_id"))).toList());
        return result;
    }

    @Transactional
    public Map<String, Object> save(Long id, Map<String, Object> body) {
        String key = requiredText(body, "protocolKey", "protocol_key");
        String name = requiredText(body, "protocolName", "protocol_name");
        List<Map<String, Object>> points = maps(body.get("points"));
        if (points.isEmpty()) throw new BusinessException("协议至少需要配置一个监测测点");
        if (id == null) {
            jdbcTemplate.update("""
                    INSERT INTO alarm_protocol(protocol_key,protocol_name,version_no,lifecycle_status,enabled,remark)
                    VALUES(?,?,1,'DRAFT',?,?)
                    """, key, name, bool(body, "enabled", true) ? 1 : 0, text(body, "remark", null));
            id = jdbcTemplate.queryForObject("SELECT id FROM alarm_protocol WHERE protocol_key=?",
                    Long.class, key);
        } else {
            requireExists(id);
            jdbcTemplate.update("""
                    UPDATE alarm_protocol SET protocol_key=?,protocol_name=?,enabled=?,remark=?,
                      lifecycle_status='DRAFT',update_time=NOW()
                    WHERE id=?
                    """, key, name, bool(body, "enabled", true) ? 1 : 0, text(body, "remark", null), id);
            jdbcTemplate.update("DELETE FROM alarm_protocol_point WHERE protocol_id=?", id);
            jdbcTemplate.update("DELETE FROM alarm_protocol_device WHERE protocol_id=?", id);
        }
        for (Map<String, Object> point : points) insertPoint(id, point);
        for (Long deviceId : longs(body.get("deviceIds"))) {
            jdbcTemplate.update(
                    "INSERT INTO alarm_protocol_device(protocol_id,device_id,enabled) VALUES(?,?,1)",
                    id, deviceId);
        }
        return detail(id);
    }

    @Transactional
    public void delete(long id) {
        requireExists(id);
        jdbcTemplate.update("DELETE FROM alarm_protocol WHERE id=?", id);
    }

    @Transactional
    public Map<String, Object> publish(long id) {
        Map<String, Object> protocol = detail(id);
        List<?> points = (List<?>) protocol.get("points");
        List<?> deviceIds = (List<?>) protocol.get("deviceIds");
        if (points == null || points.isEmpty()) throw new BusinessException("协议没有监测测点");
        if (deviceIds == null || deviceIds.isEmpty()) throw new BusinessException("协议没有绑定设备");
        jdbcTemplate.update("""
                UPDATE alarm_protocol
                SET lifecycle_status='PUBLISHED',version_no=version_no+1,update_time=NOW()
                WHERE id=?
                """, id);
        return detail(id);
    }

    public List<Map<String, Object>> protocolsForGateway(long gatewayId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT DISTINCT p.id,p.protocol_key,p.protocol_name,p.version_no,p.remark,
                       d.id AS device_id,d.device_sn
                FROM alarm_protocol p
                JOIN alarm_protocol_device pd ON pd.protocol_id=p.id AND pd.enabled=1
                JOIN dev_device d ON d.id=pd.device_id AND d.gateway_id=? AND d.status=1
                WHERE p.lifecycle_status='PUBLISHED' AND p.enabled=1
                ORDER BY p.id,d.id
                """, gatewayId);
        Map<Long, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long protocolId = number(row.get("id"));
            Map<String, Object> protocol = grouped.computeIfAbsent(protocolId, ignored -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("protocolId", protocolId);
                value.put("protocolKey", row.get("protocol_key"));
                value.put("protocolName", row.get("protocol_name"));
                value.put("versionNo", row.get("version_no"));
                value.put("remark", row.get("remark"));
                value.put("points", jdbcTemplate.queryForList("""
                        SELECT id AS pointId,point_code AS pointCode,point_name AS pointName,
                               alarm_type AS alarmType,evaluation_mode AS evaluationMode,
                               compare_operator AS compareOperator,threshold_value AS thresholdValue,
                               threshold_min AS thresholdMin,threshold_max AS thresholdMax,
                               recovery_threshold_value AS recoveryThresholdValue,
                               recovery_samples AS recoverySamples,freshness_seconds AS freshnessSeconds,
                               max_sample_gap_seconds AS maxSampleGapSeconds,
                               evaluation_window_samples AS evaluationWindowSamples,required_hits AS requiredHits,
                               window_seconds AS windowSeconds,duration_seconds AS durationSeconds,
                               alarm_level AS alarmLevel,enabled
                        FROM alarm_protocol_point WHERE protocol_id=? AND enabled=1 ORDER BY id
                        """, protocolId));
                value.put("devices", new ArrayList<Map<String, Object>>());
                return value;
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> devices = (List<Map<String, Object>>) protocol.get("devices");
            devices.add(Map.of("deviceId", row.get("device_id"), "deviceSn", row.get("device_sn")));
        }
        return new ArrayList<>(grouped.values());
    }

    private void insertPoint(long protocolId, Map<String, Object> point) {
        String code = requiredText(point, "pointCode", "point_code").toLowerCase();
        String pointName = text(point, "pointName", text(point, "point_name", code));
        jdbcTemplate.update("""
                INSERT INTO alarm_protocol_point
                  (protocol_id,point_code,point_name,alarm_type,evaluation_mode,compare_operator,
                   threshold_value,threshold_min,threshold_max,recovery_threshold_value,recovery_samples,
                   freshness_seconds,max_sample_gap_seconds,evaluation_window_samples,required_hits,window_seconds,
                   duration_seconds,alarm_level,enabled,remark)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, protocolId, code, pointName, integer(point, "alarmType", "alarm_type", 5),
                text(point, "evaluationMode", text(point, "evaluation_mode", "THRESHOLD")),
                text(point, "compareOperator", text(point, "compare_operator", ">")),
                decimal(point, "thresholdValue", "threshold_value"), decimal(point, "thresholdMin", "threshold_min"),
                decimal(point, "thresholdMax", "threshold_max"),
                decimal(point, "recoveryThresholdValue", "recovery_threshold_value"),
                integer(point, "recoverySamples", "recovery_samples", 3),
                integer(point, "freshnessSeconds", "freshness_seconds", 900),
                integer(point, "maxSampleGapSeconds", "max_sample_gap_seconds", 900),
                integer(point, "evaluationWindowSamples", "evaluation_window_samples", 5),
                integer(point, "requiredHits", "required_hits", 3),
                integer(point, "windowSeconds", "window_seconds", 300),
                integer(point, "durationSeconds", "duration_seconds", 0),
                integer(point, "alarmLevel", "alarm_level", 2),
                bool(point, "enabled", true) ? 1 : 0, text(point, "remark", null));
    }

    private void requireExists(long id) {
        if (jdbcTemplate.queryForObject("SELECT COUNT(*) FROM alarm_protocol WHERE id=?", Integer.class, id) == 0) {
            throw new BusinessException("告警协议不存在");
        }
    }

    private String requiredText(Map<String, Object> body, String... keys) {
        String value = null;
        for (String key : keys) value = text(body, key, value);
        if (value == null || value.isBlank()) throw new BusinessException("缺少协议配置字段：" + keys[0]);
        return value.trim();
    }

    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> map) {
            Map<String, Object> row = new LinkedHashMap<>();
            map.forEach((key, val) -> row.put(String.valueOf(key), val));
            result.add(row);
        }
        return result;
    }

    private List<Long> longs(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Long> result = new ArrayList<>();
        for (Object item : list) {
            try { result.add(Long.valueOf(String.valueOf(item))); }
            catch (NumberFormatException ignored) { }
        }
        return result;
    }

    private String text(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private int integer(Map<String, Object> map, String first, String second, int fallback) {
        String value = text(map, first, text(map, second, null));
        try { return value == null ? fallback : Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private BigDecimal decimal(Map<String, Object> map, String first, String second) {
        String value = text(map, first, text(map, second, null));
        try { return value == null ? null : new BigDecimal(value); }
        catch (NumberFormatException ignored) { return null; }
    }

    private boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value == null) return fallback;
        return value instanceof Boolean b ? b : "1".equals(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }
}
