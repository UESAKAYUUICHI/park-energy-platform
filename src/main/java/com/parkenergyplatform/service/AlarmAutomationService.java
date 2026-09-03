package com.parkenergyplatform.service;

import java.util.List;
import java.util.Map;

import com.parkenergyplatform.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Database-backed orchestration between alarm facts and operational work orders. */
@Service
public class AlarmAutomationService {
    private static final Logger log = LoggerFactory.getLogger(AlarmAutomationService.class);
    private final JdbcTemplate jdbcTemplate;
    private final OperationsService operationsService;
    private final int defaultRecoveryHoldSeconds;

    public AlarmAutomationService(JdbcTemplate jdbcTemplate, OperationsService operationsService,
                                  @Value("${park.alarm.default-recovery-hold-seconds:8}") int defaultRecoveryHoldSeconds) {
        this.jdbcTemplate = jdbcTemplate;
        this.operationsService = operationsService;
        this.defaultRecoveryHoldSeconds = Math.max(defaultRecoveryHoldSeconds, 1);
    }

    @Scheduled(fixedDelayString = "${park.alarm.automation-interval-ms:1000}", initialDelayString = "${park.alarm.automation-initial-delay-ms:3000}")
    @Transactional
    public void orchestrate() {
        Integer locked = jdbcTemplate.queryForObject("SELECT GET_LOCK('park_alarm_automation',0)", Integer.class);
        if (locked == null || locked != 1) return;
        try {
            createPendingWorkOrders();
            closeStableRecoveredEvents();
        } finally {
            jdbcTemplate.queryForObject("SELECT RELEASE_LOCK('park_alarm_automation')", Integer.class);
        }
    }

    void createPendingWorkOrders() {
        List<Long> alarmIds = jdbcTemplate.queryForList("""
                SELECT a.id
                FROM log_alarm a JOIN alarm_rule_version v ON v.id=a.rule_version_id
                WHERE v.auto_work_order=1
                  AND a.condition_status='ACTIVE'
                  AND a.event_status IN ('NEW','ACKNOWLEDGED')
                  AND NOT EXISTS (SELECT 1 FROM ops_work_order w WHERE w.source_type='ALARM' AND w.source_id=a.id
                                  AND w.status NOT IN ('CLOSED','CANCELLED'))
                ORDER BY a.alarm_level DESC,a.first_occurrence_time
                LIMIT 100
                """, Long.class);
        for (Long alarmId : alarmIds) {
            try {
                operationsService.createFromAlarmSystem(alarmId);
            } catch (BusinessException concurrentStateChange) {
                log.debug("Skip alarm {} while creating automatic work order: {}", alarmId, concurrentStateChange.getMessage());
            } catch (RuntimeException failure) {
                log.error("Automatic work order creation failed for alarm {}", alarmId, failure);
            }
        }
    }

    void closeStableRecoveredEvents() {
        List<Map<String, Object>> candidates = jdbcTemplate.queryForList("""
                SELECT a.id,COALESCE(v.recovery_hold_seconds,r.recovery_hold_seconds,?) AS recovery_hold_seconds
                FROM log_alarm a LEFT JOIN alarm_rule r ON r.id=a.rule_id
                LEFT JOIN alarm_rule_version v ON v.id=a.rule_version_id
                WHERE a.event_status='RECOVERED' AND a.condition_status='CLEARED'
                  AND TIMESTAMPDIFF(SECOND,a.recovery_time,NOW())>=COALESCE(v.recovery_hold_seconds,r.recovery_hold_seconds,?)
                ORDER BY a.recovery_time
                LIMIT 100
                """, defaultRecoveryHoldSeconds, defaultRecoveryHoldSeconds);
        for (Map<String, Object> candidate : candidates) {
            long alarmId = ((Number) candidate.get("id")).longValue();
            int holdSeconds = Math.max(((Number) candidate.get("recovery_hold_seconds")).intValue(), 1);
            try {
                operationsService.autoCloseRecoveredAlarm(alarmId, holdSeconds);
            } catch (BusinessException concurrentStateChange) {
                log.debug("Skip alarm {} while closing recovered event: {}", alarmId, concurrentStateChange.getMessage());
            } catch (RuntimeException failure) {
                log.error("Automatic recovery closure failed for alarm {}", alarmId, failure);
            }
        }
    }
}
