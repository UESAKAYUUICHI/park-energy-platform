package com.parkenergyplatform.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.common.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 采集数据从原始报文到数据服务处理结果的质量审计查询。 */
@Service
public class DataQualityService {
    private final JdbcTemplate jdbcTemplate;
    private final BusinessDataAccessService accessService;
    private final OperationsService operationsService;

    public DataQualityService(JdbcTemplate jdbcTemplate, BusinessDataAccessService accessService, OperationsService operationsService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessService = accessService;
        this.operationsService = operationsService;
    }

    public Map<String, Object> summary() {
        List<Object> args = new ArrayList<>();
        String scope = accessService.scopeSql("g.org_id", args);
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> today = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS total_count,
                       COALESCE(SUM(e.status = 'SUCCESS'), 0) AS success_count,
                       COALESCE(SUM(e.status = 'INVALID'), 0) AS invalid_count,
                       COALESCE(SUM(e.status = 'DEAD_LETTER'), 0) AS dead_letter_count,
                       COALESCE(SUM(e.status = 'PROCESSING'), 0) AS processing_count
                FROM data_ingest_event e
                JOIN dev_gateway g ON g.id = e.gateway_id
                WHERE DATE(e.create_time) = CURDATE()
                """ + scope, args.toArray());
        result.put("today", today);
        List<Object> reasonArgs = new ArrayList<>();
        String reasonScope = accessService.scopeSql("g.org_id", reasonArgs);
        result.put("topFailures", jdbcTemplate.queryForList("""
                SELECT e.status, e.error_code, e.error_reason, COUNT(*) AS total
                FROM data_ingest_event e JOIN dev_gateway g ON g.id = e.gateway_id
                WHERE e.status IN ('INVALID', 'DEAD_LETTER')
                """ + reasonScope + " GROUP BY e.status, e.error_code, e.error_reason ORDER BY total DESC, MAX(e.update_time) DESC LIMIT 10", reasonArgs.toArray()));
        List<Object> collectionArgs = new ArrayList<>();
        String collectionScope = accessService.scopeSql("d.org_id", collectionArgs);
        result.put("collectionToday", jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS device_count,
                       COALESCE(SUM(c.quality_status = 'NORMAL'), 0) AS normal_count,
                       COALESCE(SUM(c.quality_status = 'INCOMPLETE'), 0) AS incomplete_count,
                       COALESCE(MAX(c.longest_gap_seconds), 0) AS max_gap_seconds
                FROM stats_collection_daily c
                JOIN dev_device d ON d.id = c.device_id
                WHERE c.stat_date = CURDATE()
                """ + collectionScope, collectionArgs.toArray()));
        return result;
    }

    public PageResult<Map<String, Object>> page(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        String status = text(params.get("status"));
        if (status != null) { where.append(" AND e.status = ?"); args.add(status); }
        String keyword = text(params.get("keyword"));
        if (keyword != null) { where.append(" AND (e.message_id LIKE ? OR g.gateway_sn LIKE ? OR e.error_reason LIKE ?)"); for (int i = 0; i < 3; i++) args.add("%" + keyword + "%"); }
        where.append(accessService.scopeSql("g.org_id", args));
        int pageNum = positive(params.get("pageNum"), 1), pageSize = Math.min(positive(params.get("pageSize"), 20), 200);
        String from = " FROM data_ingest_event e JOIN dev_gateway g ON g.id = e.gateway_id";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(pageSize); pageArgs.add((pageNum - 1) * pageSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT e.id, e.raw_log_id, e.message_id, e.status, e.received_at, e.processed_at, e.meter_count, e.error_code, e.error_reason,
                       g.gateway_sn, g.gateway_name, g.org_id
                """ + from + where + " ORDER BY e.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        return PageResult.of(rows, total == null ? 0 : total, pageNum, pageSize);
    }

    public PageResult<Map<String, Object>> collectionDaily(Map<String, String> params) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        String statDate = text(params.get("statDate"));
        if (statDate != null) { where.append(" AND c.stat_date = ?"); args.add(java.sql.Date.valueOf(statDate)); }
        String qualityStatus = text(params.get("qualityStatus"));
        if (qualityStatus != null) { where.append(" AND c.quality_status = ?"); args.add(qualityStatus); }
        String keyword = text(params.get("keyword"));
        if (keyword != null) { where.append(" AND (d.device_sn LIKE ? OR d.device_name LIKE ?)"); args.add("%" + keyword + "%"); args.add("%" + keyword + "%"); }
        where.append(accessService.scopeSql("d.org_id", args));
        int pageNum = positive(params.get("pageNum"), 1), pageSize = Math.min(positive(params.get("pageSize"), 20), 200);
        String from = " FROM stats_collection_daily c JOIN dev_device d ON d.id = c.device_id";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(pageSize); pageArgs.add((pageNum - 1) * pageSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT c.*, d.device_sn, d.device_name, d.collect_interval_seconds, d.quality_threshold_pct,
                       d.quality_gate_start_date
                """ + from + where + " ORDER BY c.stat_date DESC, c.quality_status, c.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        return PageResult.of(rows, total == null ? 0 : total, pageNum, pageSize);
    }

    public Map<String, Object> detail(long eventId) {
        List<Object> args = new ArrayList<>(); args.add(eventId);
        String scope = accessService.scopeSql("g.org_id", args);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT e.*, g.gateway_sn, g.gateway_name, g.org_id, r.topic AS raw_topic, r.payload AS raw_payload,
                       r.parse_status AS raw_parse_status, r.fail_code AS raw_fail_code, r.fail_reason AS raw_fail_reason
                FROM data_ingest_event e
                JOIN dev_gateway g ON g.id = e.gateway_id
                LEFT JOIN log_raw_message r ON r.id = e.raw_log_id
                WHERE e.id = ?
                """ + scope, args.toArray());
        if (rows.isEmpty()) throw new BusinessException(404, "采集事件不存在或无访问权限: " + eventId);
        return rows.get(0);
    }

    public Map<String, Object> requestReplay(long eventId) {
        Map<String, Object> event = detail(eventId);
        Map<String, Object> precheck = replayPrecheck(event);
        if (!Boolean.TRUE.equals(precheck.get("replayable"))) {
            throw new BusinessException(String.valueOf(precheck.get("message")));
        }
        String status = String.valueOf(event.get("status"));
        if (!"INVALID".equals(status) && !"DEAD_LETTER".equals(status) && !"REPLAY_REQUESTED".equals(status)) {
            throw new BusinessException("仅无效或死信采集事件允许重放");
        }
        Object rawLogId = event.get("raw_log_id");
        if (rawLogId == null) throw new BusinessException("该事件没有可重放的原始报文");
        jdbcTemplate.update("""
                UPDATE data_ingest_event
                SET status = 'REPLAY_REQUESTED', processed_at = NULL, error_reason = NULL
                WHERE id = ?
                """, eventId);
        return event;
    }

    public Map<String, Object> replayPrecheck(long eventId) {
        return replayPrecheck(detail(eventId));
    }

    public Map<String, Object> createWorkOrder(long eventId) {
        Map<String, Object> event = detail(eventId);
        event.put("workOrder", operationsService.createFromDataQuality(eventId));
        return event;
    }

    private Map<String, Object> replayPrecheck(Map<String, Object> event) {
        String status = String.valueOf(event.get("status"));
        if (!"INVALID".equals(status) && !"DEAD_LETTER".equals(status) && !"REPLAY_REQUESTED".equals(status)) {
            return Map.of("replayable", false, "action", "NONE", "message", "仅无效或死信采集事件允许重放");
        }
        if (event.get("raw_log_id") == null) {
            return Map.of("replayable", false, "action", "RAW_EVIDENCE_REQUIRED", "message", "该事件没有可重放的原始报文");
        }
        String errorCode = textValue(event.get("error_code"));
        if (errorCode == null) errorCode = textValue(event.get("raw_fail_code"));
        String reason = textValue(event.get("error_reason"));
        if ("RAW_PAYLOAD_INVALID".equals(errorCode) && reason != null && reason.toUpperCase().contains("NO DEVICE SAMPLE")) {
            errorCode = "DEVICE_SAMPLE_REJECTED";
        }
        if ("REQUIRED_POINT_MISSING".equals(errorCode)) {
            return Map.of("replayable", false, "action", "FIX_PAYLOAD", "message", "缺少必填测点，补齐报文后再重放", "errorCode", errorCode);
        }
        if ("DEVICE_SAMPLE_REJECTED".equals(errorCode)) {
            return Map.of("replayable", false, "action", "VERIFY_DEVICE_MAPPING", "message", "设备归档或网关绑定未通过校验，请先修复映射", "errorCode", errorCode);
        }
        return Map.of("replayable", true, "action", "REPLAY", "message", "原始报文可进入重放队列", "errorCode", errorCode);
    }

    private String text(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String textValue(Object value) { return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim(); }
    private int positive(String value, int defaultValue) { try { int result = Integer.parseInt(value); return result > 0 ? result : defaultValue; } catch (RuntimeException exception) { return defaultValue; } }
}
