package com.parkenergyplatform.service;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkenergyplatform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class MeterTemplateService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DataScopeService dataScopeService;

    public MeterTemplateService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, DataScopeService dataScopeService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.dataScopeService = dataScopeService;
    }

    public List<Map<String, Object>> list() {
        long userId = StpUtil.getLoginIdAsLong();
        if (dataScopeService.isSuperAdmin(userId)) {
            return jdbcTemplate.queryForList("""
                    SELECT id, template_name, owner_user_id, owner_username, org_id, device_type_id,
                           point_codes, all_points, description, enabled, created_by, created_time, updated_by, updated_time
                    FROM billing_meter_template
                    WHERE enabled = 1
                    ORDER BY updated_time DESC, id DESC
                    """);
        }
        return jdbcTemplate.queryForList("""
                SELECT id, template_name, owner_user_id, owner_username, org_id, device_type_id,
                       point_codes, all_points, description, enabled, created_by, created_time, updated_by, updated_time
                FROM billing_meter_template
                WHERE enabled = 1 AND owner_user_id = ?
                ORDER BY updated_time DESC, id DESC
                """, userId);
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        long userId = StpUtil.getLoginIdAsLong();
        String username = username();
        String name = text(body.get("template_name"));
        if (name.isBlank()) throw new BusinessException("模板名称不能为空");
        Object rawCodes = body.getOrDefault("point_codes", body.get("pointCodes"));
        String pointCodes = jsonArray(rawCodes);
        Long orgId = longValue(body.get("org_id"));
        Long deviceTypeId = longValue(body.getOrDefault("device_type_id", body.get("deviceTypeId")));
        int allPoints = number(body.getOrDefault("all_points", body.getOrDefault("allPoints", 0)));
        jdbcTemplate.update("""
                INSERT INTO billing_meter_template
                  (template_name, owner_user_id, owner_username, org_id, device_type_id, point_codes,
                   all_points, description, enabled, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                """, name, userId, username, orgId, deviceTypeId, pointCodes, allPoints,
                text(body.get("description")), username, username);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return jdbcTemplate.queryForMap("SELECT * FROM billing_meter_template WHERE id = ?", id);
    }

    @Transactional
    public void delete(long id) {
        long userId = StpUtil.getLoginIdAsLong();
        boolean admin = dataScopeService.isSuperAdmin(userId);
        int count = admin
                ? jdbcTemplate.update("UPDATE billing_meter_template SET enabled = 0, updated_by = ? WHERE id = ?", username(), id)
                : jdbcTemplate.update("UPDATE billing_meter_template SET enabled = 0, updated_by = ? WHERE id = ? AND owner_user_id = ?", username(), id, userId);
        if (count == 0) throw new BusinessException(404, "模板不存在或无权删除");
    }

    private String username() {
        Object value = StpUtil.getSession().get("username");
        return value == null ? String.valueOf(StpUtil.getLoginId()) : String.valueOf(value);
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private Long longValue(Object value) { return value == null || text(value).isBlank() ? null : Long.valueOf(text(value)); }
    private int number(Object value) { try { return Integer.parseInt(text(value)); } catch (Exception ignored) { return 0; } }

    private String jsonArray(Object value) {
        if (value == null) return "[]";
        if (value instanceof String string) {
            try { objectMapper.readTree(string); return string; } catch (Exception ignored) { return "[]"; }
        }
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new BusinessException("模板测点保存失败"); }
    }
}
