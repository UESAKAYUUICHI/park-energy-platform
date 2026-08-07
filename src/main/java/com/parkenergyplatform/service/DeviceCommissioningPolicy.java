package com.parkenergyplatform.service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

import com.parkenergyplatform.common.BusinessException;
import org.springframework.util.StringUtils;

final class DeviceCommissioningPolicy {
    private static final Set<String> METER_ROLES = Set.of("SETTLEMENT", "INTERNAL", "SUB_METER");

    private DeviceCommissioningPolicy() {
    }

    static void validateProtocolAddress(String protocolType, Long gatewayId, String protocolAddress) {
        if (gatewayId == null) return;
        String protocol = StringUtils.hasText(protocolType) ? protocolType.toUpperCase(Locale.ROOT) : "JSON";
        if (!protocol.startsWith("MODBUS")) return;
        if (!StringUtils.hasText(protocolAddress)) throw new BusinessException("MODBUS 设备部署时必须填写从站地址");
        try {
            int address = Integer.parseInt(protocolAddress);
            if (address < 1 || address > 247) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
            throw new BusinessException("MODBUS 从站地址必须是 1 到 247 的整数");
        }
    }

    static void validateCommissioning(int settlementEnabled, Long gatewayId, String meterRole,
                                      BigDecimal meterFactor, long billableTotalPointCount) {
        if (!METER_ROLES.contains(meterRole)) throw new BusinessException("计量角色不合法");
        if (meterFactor == null || meterFactor.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("表计倍率必须大于 0");
        if (settlementEnabled != 1) return;
        if (gatewayId == null) throw new BusinessException("设备部署到网关后才能启用结算");
        if (billableTotalPointCount <= 0) throw new BusinessException("当前型号没有已发布的累计计费测点，不能启用结算");
    }
}
