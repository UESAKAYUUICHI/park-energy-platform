package com.parkenergyplatform.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

import com.parkenergyplatform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlarmService {
    private final JdbcTemplate jdbcTemplate;
    private final BusinessDataAccessService accessService;

    public AlarmService(JdbcTemplate jdbcTemplate, BusinessDataAccessService accessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessService = accessService;
    }

    @Transactional
    public Map<String, Object> deal(long alarmId, Map<String, Object> request) {
        accessService.assertAlarmAccess(alarmId);
        String dealUser = Objects.toString(request.getOrDefault("dealUser", "admin"), "admin");
        String dealRemark = Objects.toString(request.getOrDefault("dealRemark", ""), "");
        int updated = jdbcTemplate.update("""
                UPDATE log_alarm
                SET deal_status = 1, deal_time = ?, deal_user = ?, deal_remark = ?
                WHERE id = ?
                """, Timestamp.valueOf(LocalDateTime.now()), dealUser, dealRemark, alarmId);
        if (updated == 0) {
            throw new BusinessException(404, "告警不存在: " + alarmId);
        }
        return jdbcTemplate.queryForList("SELECT * FROM log_alarm WHERE id = ?", alarmId).get(0);
    }
}
