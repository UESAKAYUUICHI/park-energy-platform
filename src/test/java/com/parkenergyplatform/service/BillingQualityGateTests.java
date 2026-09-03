package com.parkenergyplatform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class BillingQualityGateTests {
    @Test
    void acceptsDataOnlyWhenRateGapAndQualityAllPass() {
        assertThat(BillingService.settlementQualityAccepted(
                new BigDecimal("95.00"), new BigDecimal("95.00"), 900, 300, false)).isTrue();
        assertThat(BillingService.settlementQualityAccepted(
                new BigDecimal("94.99"), new BigDecimal("95.00"), 900, 300, false)).isFalse();
        assertThat(BillingService.settlementQualityAccepted(
                new BigDecimal("100.00"), new BigDecimal("95.00"), 901, 300, false)).isFalse();
        assertThat(BillingService.settlementQualityAccepted(
                new BigDecimal("100.00"), new BigDecimal("95.00"), 60, 300, true)).isFalse();
    }
}
