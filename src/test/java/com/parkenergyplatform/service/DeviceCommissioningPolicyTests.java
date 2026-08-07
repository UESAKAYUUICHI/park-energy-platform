package com.parkenergyplatform.service;

import java.math.BigDecimal;

import com.parkenergyplatform.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeviceCommissioningPolicyTests {
    @Test
    void acceptsValidModbusAndCommissioningContext() {
        assertDoesNotThrow(() -> DeviceCommissioningPolicy.validateProtocolAddress("MODBUS_RTU", 8L, "247"));
        assertDoesNotThrow(() -> DeviceCommissioningPolicy.validateCommissioning(
                1, 8L, "SETTLEMENT", new BigDecimal("1.5"), 1));
    }

    @Test
    void rejectsInvalidModbusAddress() {
        assertThrows(BusinessException.class,
                () -> DeviceCommissioningPolicy.validateProtocolAddress("MODBUS_TCP", 8L, "248"));
        assertThrows(BusinessException.class,
                () -> DeviceCommissioningPolicy.validateProtocolAddress("MODBUS_RTU", 8L, ""));
    }

    @Test
    void rejectsCommissioningWithoutDeploymentOrBillablePoint() {
        assertThrows(BusinessException.class, () -> DeviceCommissioningPolicy.validateCommissioning(
                1, null, "SETTLEMENT", BigDecimal.ONE, 1));
        assertThrows(BusinessException.class, () -> DeviceCommissioningPolicy.validateCommissioning(
                1, 8L, "SETTLEMENT", BigDecimal.ONE, 0));
    }
}
