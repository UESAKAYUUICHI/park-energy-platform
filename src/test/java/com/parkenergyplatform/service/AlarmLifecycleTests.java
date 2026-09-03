package com.parkenergyplatform.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AlarmLifecycleTests {
    @Test
    void permitsOperationalClosureOnlyAfterRecovery() {
        assertThat(AlarmService.transitionAllowed(AlarmEventStatus.NEW, AlarmEventStatus.ACKNOWLEDGED)).isTrue();
        assertThat(AlarmService.transitionAllowed(AlarmEventStatus.ACKNOWLEDGED, AlarmEventStatus.IN_PROGRESS)).isTrue();
        assertThat(AlarmService.transitionAllowed(AlarmEventStatus.IN_PROGRESS, AlarmEventStatus.RECOVERED)).isTrue();
        assertThat(AlarmService.transitionAllowed(AlarmEventStatus.RECOVERED, AlarmEventStatus.CLOSED)).isTrue();

        assertThat(AlarmService.transitionAllowed(AlarmEventStatus.NEW, AlarmEventStatus.CLOSED)).isFalse();
        assertThat(AlarmService.transitionAllowed(AlarmEventStatus.IN_PROGRESS, AlarmEventStatus.CLOSED)).isFalse();
    }

    @Test
    void supportsSuppressionFalsePositiveAndControlledReopen() {
        assertThat(AlarmService.transitionAllowed(AlarmEventStatus.NEW, AlarmEventStatus.SUPPRESSED)).isTrue();
        assertThat(AlarmService.transitionAllowed(AlarmEventStatus.ACKNOWLEDGED, AlarmEventStatus.FALSE_POSITIVE)).isTrue();
        assertThat(AlarmService.transitionAllowed(AlarmEventStatus.CLOSED, AlarmEventStatus.NEW)).isTrue();
        assertThat(AlarmService.transitionAllowed(AlarmEventStatus.IN_PROGRESS, AlarmEventStatus.NEW)).isFalse();
    }

    @Test
    void closesOnlyAfterTheEightSecondStableRecoveryWindow() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 12, 12, 0, 0);
        assertThat(OperationsService.stableRecoveryReached(Timestamp.valueOf(now.minusSeconds(7)), 8, now)).isFalse();
        assertThat(OperationsService.stableRecoveryReached(Timestamp.valueOf(now.minusSeconds(8)), 8, now)).isTrue();
        assertThat(OperationsService.stableRecoveryReached(Timestamp.valueOf(now.minusSeconds(20)), 8, now)).isTrue();
    }

    @Test
    void treatsOnlyOneWorkOrderAsActiveForTheSameAlarmSource() {
        assertThat(OperationsService.activeSourceKey("ALARM", 42L)).isEqualTo("ALARM:42");
        assertThat(OperationsService.activeSourceKey("alarm", 42L)).isEqualTo("ALARM:42");
        assertThat(OperationsService.isActiveWorkOrderStatus("PENDING")).isTrue();
        assertThat(OperationsService.isActiveWorkOrderStatus("VERIFYING")).isTrue();
        assertThat(OperationsService.isActiveWorkOrderStatus("CLOSED")).isFalse();
        assertThat(OperationsService.isActiveWorkOrderStatus("CANCELLED")).isFalse();
    }
}
