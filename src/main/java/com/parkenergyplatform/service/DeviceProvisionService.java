package com.parkenergyplatform.service;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import cn.dev33.satoken.stp.StpUtil;
import com.parkenergyplatform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class DeviceProvisionService {
    private final JdbcTemplate jdbcTemplate;
    private final BusinessDataAccessService accessService;
    private final DataScopeService dataScopeService;
    private final DeviceCatalogService catalogService;

    public DeviceProvisionService(JdbcTemplate jdbcTemplate, BusinessDataAccessService accessService,
                                  DataScopeService dataScopeService, DeviceCatalogService catalogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessService = accessService;
        this.dataScopeService = dataScopeService;
        this.catalogService = catalogService;
    }

    @Transactional
    public Map<String, Object> provision(Map<String, Object> body) {
        Long versionId = longValue(value(body, "modelVersionId", "model_version_id"));
        if (versionId == null) throw new BusinessException("请选择已发布的设备型号");
        Map<String, Object> version = single("""
                SELECT v.*,m.model_name,m.model_code,m.status AS model_status,t.protocol_type,
                       s.enabled AS series_enabled,b.enabled AS brand_enabled,c.enabled AS category_enabled
                FROM dev_device_model_version v
                JOIN dev_device_model m ON m.id=v.model_id
                JOIN dev_product_series s ON s.id=m.series_id
                JOIN dev_brand b ON b.id=s.brand_id
                JOIN dev_device_category c ON c.id=s.category_id
                JOIN dev_device_type t ON t.id=v.device_type_id
                WHERE v.id=?
                """, versionId);
        if (!"PUBLISHED".equals(text(version.get("status")))
                || "DISABLED".equals(text(version.get("model_status")))
                || intValue(version.get("series_enabled"), 0) != 1
                || intValue(version.get("brand_enabled"), 0) != 1
                || intValue(version.get("category_enabled"), 0) != 1) {
            throw new BusinessException("设备只能引用目录链路完整、已发布且未停用的型号版本");
        }
        String deviceSn = requiredText(value(body, "deviceSn", "device_sn"), "设备 SN 不能为空");
        assertUniqueSn(deviceSn, null);
        Long gatewayId = longValue(value(body, "gatewayId", "gateway_id"));
        Long orgId = longValue(value(body, "orgId", "org_id"));
        orgId = validateOrgGateway(orgId, gatewayId);
        validateSpace(orgId, longValue(value(body, "spaceId", "space_id")));

        String protocolAddress = text(value(body, "protocolAddr", "protocol_addr"));
        int status = intValue(value(body, "status"), 1);
        validateProtocolAddress(text(version.get("protocol_type")), gatewayId, protocolAddress, null, status);
        int settlementEnabled = boolInt(value(body, "settlementEnabled", "settlement_enabled"));
        String meterRole = normalizeMeterRole(value(body, "meterRole", "meter_role"));
        BigDecimal meterFactor = decimal(value(body, "meterFactor", "meter_factor"), BigDecimal.ONE);
        validateCommissioning(settlementEnabled, gatewayId, longValue(version.get("device_type_id")), meterRole, meterFactor);

        String deviceName = text(value(body, "deviceName", "device_name"));
        if (!StringUtils.hasText(deviceName)) deviceName = version.get("model_name") + " " + deviceSn;
        Long spaceId = longValue(value(body, "spaceId", "space_id"));
        String installLocation = text(value(body, "installLocation", "install_location"));
        String installTime = text(value(body, "installTime", "install_time"));
        String qualityGateDate = normalizeDate(text(value(body, "qualityGateStartDate", "quality_gate_start_date")), "质量门禁日期");
        if (!StringUtils.hasText(qualityGateDate)) qualityGateDate = LocalDateTime.now().toLocalDate().toString();
        long collectInterval = longOrDefault(version.get("collect_interval_seconds"), 300);
        BigDecimal qualityThreshold = decimal(version.get("quality_threshold_pct"), new BigDecimal("95"));
        KeyHolder holder = new GeneratedKeyHolder();
        Long finalOrgId = orgId;
        String finalDeviceName = deviceName;
        String finalQualityGateDate = qualityGateDate;
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO dev_device
                    (device_sn,device_name,gateway_id,org_id,space_id,device_type_id,model_version_id,
                     protocol_addr,install_location,device_model,install_time,settlement_enabled,meter_role,meter_factor,
                     collect_interval_seconds,quality_threshold_pct,quality_gate_start_date,status)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setObject(1, deviceSn);
            statement.setObject(2, finalDeviceName);
            statement.setObject(3, gatewayId);
            statement.setObject(4, finalOrgId);
            statement.setObject(5, spaceId);
            statement.setObject(6, longValue(version.get("device_type_id")));
            statement.setObject(7, versionId);
            statement.setObject(8, protocolAddress);
            statement.setObject(9, installLocation);
            statement.setObject(10, version.get("model_code"));
            statement.setObject(11, StringUtils.hasText(installTime) ? installTime : null);
            statement.setObject(12, settlementEnabled);
            statement.setObject(13, meterRole);
            statement.setObject(14, meterFactor);
            statement.setObject(15, collectInterval);
            statement.setObject(16, qualityThreshold);
            statement.setObject(17, finalQualityGateDate);
            statement.setObject(18, status);
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) throw new BusinessException("设备创建失败，未返回主键");
        if (gatewayId != null) recordDeployment(key.longValue(), "DEPLOY", null, gatewayId, protocolAddress, "设备登记时同步部署");
        return deviceDetail(key.longValue());
    }

    @Transactional
    public Map<String, Object> updateContext(long deviceId, Map<String, Object> body) {
        accessService.assertDeviceAccess(deviceId);
        Map<String, Object> device = single("""
                SELECT d.*,t.protocol_type FROM dev_device d
                JOIN dev_device_type t ON t.id=d.device_type_id WHERE d.id=?
                """, deviceId);
        String deviceSn = bodyText(body, device, "deviceSn", "device_sn");
        if (!StringUtils.hasText(deviceSn)) throw new BusinessException("设备 SN 不能为空");
        assertUniqueSn(deviceSn, deviceId);
        String deviceName = bodyText(body, device, "deviceName", "device_name");
        if (!StringUtils.hasText(deviceName)) deviceName = deviceSn;
        Long orgId = bodyLong(body, device, "orgId", "org_id");
        Long gatewayId = bodyLong(body, device, "gatewayId", "gateway_id");
        Long spaceId = bodyLong(body, device, "spaceId", "space_id");
        orgId = validateOrgGateway(orgId, gatewayId);
        validateSpace(orgId, spaceId);
        String protocolAddress = bodyText(body, device, "protocolAddr", "protocol_addr");
        if (gatewayId == null) protocolAddress = null;
        int status = bodyInt(body, device, 1, "status");
        validateProtocolAddress(text(device.get("protocol_type")), gatewayId, protocolAddress, deviceId, status);

        int settlementEnabled = bodyInt(body, device, 0, "settlementEnabled", "settlement_enabled");
        String meterRole = normalizeMeterRole(bodyObject(body, device, "meterRole", "meter_role"));
        BigDecimal meterFactor = decimal(bodyObject(body, device, "meterFactor", "meter_factor"), BigDecimal.ONE);
        validateCommissioning(settlementEnabled, gatewayId, longValue(device.get("device_type_id")), meterRole, meterFactor);
        String installLocation = bodyText(body, device, "installLocation", "install_location");
        String installTime = bodyText(body, device, "installTime", "install_time");
        String qualityGateDate = normalizeDate(bodyText(body, device, "qualityGateStartDate", "quality_gate_start_date"), "质量门禁日期");

        Long sourceGatewayId = longValue(device.get("gateway_id"));
        String sourceAddress = text(device.get("protocol_addr"));
        jdbcTemplate.update("""
                UPDATE dev_device
                SET device_sn=?,device_name=?,org_id=?,space_id=?,gateway_id=?,protocol_addr=?,
                    install_location=?,install_time=?,settlement_enabled=?,meter_role=?,meter_factor=?,
                    quality_gate_start_date=?,status=?
                WHERE id=?
                """, deviceSn, deviceName, orgId, spaceId, gatewayId, protocolAddress, installLocation,
                blankToNull(installTime), settlementEnabled, meterRole, meterFactor, blankToNull(qualityGateDate), status, deviceId);
        if (!Objects.equals(sourceGatewayId, gatewayId) || !Objects.equals(sourceAddress, protocolAddress)) {
            String action = gatewayId == null ? "UNBIND" : sourceGatewayId == null ? "DEPLOY" : "REDEPLOY";
            recordDeployment(deviceId, action, sourceGatewayId, gatewayId, protocolAddress, text(value(body, "remark")));
        }
        return deviceDetail(deviceId);
    }

    @Transactional
    public Map<String, Object> deploy(long deviceId, Map<String, Object> body) {
        accessService.assertDeviceAccess(deviceId);
        Map<String, Object> device = single("""
                SELECT d.*,t.protocol_type FROM dev_device d
                JOIN dev_device_type t ON t.id=d.device_type_id WHERE d.id=?
                """, deviceId);
        Long gatewayId = longValue(value(body, "gatewayId", "gateway_id"));
        if (gatewayId == null) throw new BusinessException("请选择目标网关");
        validateOrgGateway(longValue(device.get("org_id")), gatewayId);
        String protocolAddress = text(value(body, "protocolAddr", "protocol_addr"));
        validateProtocolAddress(text(device.get("protocol_type")), gatewayId, protocolAddress, deviceId,
                intValue(device.get("status"), 1));
        Long sourceGatewayId = longValue(device.get("gateway_id"));
        jdbcTemplate.update("UPDATE dev_device SET gateway_id=?,protocol_addr=?,install_time=COALESCE(install_time,CURRENT_DATE) WHERE id=?",
                gatewayId, protocolAddress, deviceId);
        recordDeployment(deviceId, sourceGatewayId == null ? "DEPLOY" : "REDEPLOY", sourceGatewayId, gatewayId,
                protocolAddress, text(value(body, "remark")));
        return deviceDetail(deviceId);
    }

    @Transactional
    public Map<String, Object> unbind(long deviceId, Map<String, Object> body) {
        accessService.assertDeviceAccess(deviceId);
        Map<String, Object> device = single("SELECT * FROM dev_device WHERE id=?", deviceId);
        Long sourceGatewayId = longValue(device.get("gateway_id"));
        if (sourceGatewayId == null) return device;
        if (intValue(device.get("settlement_enabled"), 0) == 1) {
            throw new BusinessException("结算设备解绑前必须先在设备上下文中关闭计量投运");
        }
        jdbcTemplate.update("UPDATE dev_device SET gateway_id=NULL,protocol_addr=NULL WHERE id=?", deviceId);
        recordDeployment(deviceId, "UNBIND", sourceGatewayId, null, null, text(value(body, "remark")));
        return deviceDetail(deviceId);
    }

    @Transactional
    public List<Map<String, Object>> replaceAttributeOverrides(long deviceId, Map<String, Object> body) {
        accessService.assertDeviceAccess(deviceId);
        Long fixedCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM dev_device d
                JOIN dev_device_model_version v ON v.id=d.model_version_id
                JOIN dev_model_attribute_value mv ON mv.model_version_id=v.id
                JOIN dev_attribute_definition a ON a.id=mv.attribute_id
                WHERE d.id=? AND a.value_mode='FIXED'
                """, Long.class, deviceId);
        if (fixedCount != null && fixedCount > 0) {
            throw new BusinessException("全域属性为产品模板绑定的固定值，设备实例不能覆盖");
        }
        Map<String, Object> device = single("""
                SELECT d.id,s.category_id FROM dev_device d
                JOIN dev_device_model_version v ON v.id=d.model_version_id
                JOIN dev_device_model m ON m.id=v.model_id
                JOIN dev_product_series s ON s.id=m.series_id
                WHERE d.id=?
                """, deviceId);
        Object raw = value(body, "values", "attributes");
        if (!(raw instanceof List<?> values)) throw new BusinessException("实例属性提交格式不合法");
        Set<Long> submitted = new HashSet<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> map)) throw new BusinessException("实例属性项格式不合法");
            Long attributeId = longValue(mapValue(map, "attributeId", "attribute_id", "id"));
            if (attributeId == null || !submitted.add(attributeId)) throw new BusinessException("实例属性存在空值或重复项");
            Map<String, Object> definition = single("""
                    SELECT * FROM dev_attribute_definition
                    WHERE id=? AND enabled=1 AND allow_override=1 AND (category_id IS NULL OR category_id=?)
                    """, attributeId, longValue(device.get("category_id")));
            String attributeValue = text(mapValue(map, "attributeValue", "attribute_value", "value"));
            if (StringUtils.hasText(attributeValue)) {
                String validationError = catalogService.validateAttributeValue(definition, attributeValue);
                if (validationError != null) throw new BusinessException(definition.get("attribute_name") + "：" + validationError);
            }
        }
        jdbcTemplate.update("DELETE FROM dev_device_attribute_value WHERE device_id=?", deviceId);
        for (Object item : values) {
            Map<?, ?> map = (Map<?, ?>) item;
            Long attributeId = longValue(mapValue(map, "attributeId", "attribute_id", "id"));
            String attributeValue = text(mapValue(map, "attributeValue", "attribute_value", "value"));
            if (StringUtils.hasText(attributeValue)) {
                jdbcTemplate.update("INSERT INTO dev_device_attribute_value(device_id,attribute_id,attribute_value) VALUES(?,?,?)",
                        deviceId, attributeId, attributeValue);
            }
        }
        return jdbcTemplate.queryForList("""
                SELECT dv.attribute_id,a.attribute_code,a.attribute_name,dv.attribute_value
                FROM dev_device_attribute_value dv
                JOIN dev_attribute_definition a ON a.id=dv.attribute_id
                WHERE dv.device_id=? ORDER BY a.sort,a.id
                """, deviceId);
    }

    private Long validateOrgGateway(Long orgId, Long gatewayId) {
        if (gatewayId != null) {
            accessService.assertGatewayAccess(gatewayId);
            Map<String, Object> gateway = single("SELECT id,org_id,status FROM dev_gateway WHERE id=?", gatewayId);
            if (intValue(gateway.get("status"), 0) != 1) throw new BusinessException("目标网关已停用");
            Long gatewayOrgId = longValue(gateway.get("org_id"));
            if (orgId == null) orgId = gatewayOrgId;
            if (!Objects.equals(orgId, gatewayOrgId)) throw new BusinessException("设备管理组织必须与接入网关所属组织一致");
        }
        if (orgId == null && StpUtil.isLogin()) {
            orgId = dataScopeService.defaultRootOrgId(StpUtil.getLoginIdAsLong());
        }
        if (orgId == null) throw new BusinessException("请选择设备所属组织");
        if (StpUtil.isLogin() && !dataScopeService.hasOrgAccess(StpUtil.getLoginIdAsLong(), orgId)) {
            throw new BusinessException(403, "没有目标组织的数据操作权限");
        }
        return orgId;
    }

    private void validateSpace(Long orgId, Long spaceId) {
        if (spaceId == null) return;
        Map<String, Object> space = single("SELECT id,org_id,status FROM park_space WHERE id=?", spaceId);
        if (!Objects.equals(orgId, longValue(space.get("org_id")))) throw new BusinessException("安装空间不属于设备管理组织");
        if ("DISABLED".equalsIgnoreCase(text(space.get("status")))) throw new BusinessException("不能选择已停用空间");
    }

    private void validateProtocolAddress(String protocolType, Long gatewayId, String protocolAddress,
                                         Long currentDeviceId, int deviceStatus) {
        if (gatewayId == null) return;
        String protocol = text(protocolType) == null ? "JSON" : protocolType.toUpperCase(Locale.ROOT);
        if (!protocol.startsWith("MODBUS")) return;
        DeviceCommissioningPolicy.validateProtocolAddress(protocol, gatewayId, protocolAddress);
        if (deviceStatus == 1) {
            Long duplicate = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM dev_device
                    WHERE gateway_id=? AND BINARY protocol_addr=BINARY ? AND status=1 AND (? IS NULL OR id<>?)
                    """, Long.class, gatewayId, protocolAddress, currentDeviceId, currentDeviceId);
            if (duplicate != null && duplicate > 0) throw new BusinessException("同一网关下 MODBUS 从站地址不能重复");
        }
    }

    private void validateCommissioning(int settlementEnabled, Long gatewayId, Long deviceTypeId,
                                       String meterRole, BigDecimal meterFactor) {
        long pointCount = 0;
        if (settlementEnabled == 1) {
            Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM dev_point_definition
                WHERE device_type_id=? AND enabled=1 AND billable=1 AND stat_enabled=1
                  AND UPPER(COALESCE(business_role,''))='TOTAL_ACCUMULATED'
                  AND UPPER(data_type) IN ('DOUBLE','INTEGER','LONG','DECIMAL')
                """, Long.class, deviceTypeId);
            pointCount = count == null ? 0 : count;
        }
        DeviceCommissioningPolicy.validateCommissioning(settlementEnabled, gatewayId, meterRole, meterFactor, pointCount);
    }

    private void assertUniqueSn(String deviceSn, Long currentDeviceId) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dev_device WHERE device_sn=? AND (? IS NULL OR id<>?)",
                Long.class, deviceSn, currentDeviceId, currentDeviceId);
        if (count != null && count > 0) throw new BusinessException("设备 SN 已存在: " + deviceSn);
    }

    private Map<String, Object> deviceDetail(long deviceId) {
        return single("""
                SELECT d.*,m.model_code,m.model_name,v.version_name,b.brand_name,s.series_name,o.org_name,
                       g.gateway_name,g.gateway_sn,sp.space_name,sp.space_code
                FROM dev_device d
                LEFT JOIN dev_device_model_version v ON v.id=d.model_version_id
                LEFT JOIN dev_device_model m ON m.id=v.model_id
                LEFT JOIN dev_product_series s ON s.id=m.series_id
                LEFT JOIN dev_brand b ON b.id=s.brand_id
                LEFT JOIN dev_org o ON o.id=d.org_id
                LEFT JOIN dev_gateway g ON g.id=d.gateway_id
                LEFT JOIN park_space sp ON sp.id=d.space_id
                WHERE d.id=?
                """, deviceId);
    }

    private void recordDeployment(long deviceId, String actionType, Long sourceGatewayId, Long targetGatewayId,
                                  String protocolAddress, String remark) {
        Long userId = StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        jdbcTemplate.update("""
                INSERT INTO dev_device_deployment_log
                (device_id,action_type,source_gateway_id,target_gateway_id,protocol_addr,operator_user_id,remark,create_time)
                VALUES(?,?,?,?,?,?,?,?)
                """, deviceId, actionType, sourceGatewayId, targetGatewayId, protocolAddress, userId, remark, LocalDateTime.now());
    }

    private Map<String, Object> single(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        if (rows.isEmpty()) throw new BusinessException(404, "关联数据不存在");
        return rows.get(0);
    }

    private Object bodyObject(Map<String, Object> body, Map<String, Object> current, String... keys) {
        for (String key : keys) if (body.containsKey(key)) return body.get(key);
        for (String key : keys) if (current.containsKey(key)) return current.get(key);
        return null;
    }

    private String bodyText(Map<String, Object> body, Map<String, Object> current, String... keys) {
        return text(bodyObject(body, current, keys));
    }

    private Long bodyLong(Map<String, Object> body, Map<String, Object> current, String... keys) {
        return longValue(bodyObject(body, current, keys));
    }

    private int bodyInt(Map<String, Object> body, Map<String, Object> current, int fallback, String... keys) {
        return intValue(bodyObject(body, current, keys), fallback);
    }

    private Object mapValue(Map<?, ?> body, String... keys) {
        for (String key : keys) if (body.containsKey(key)) return body.get(key);
        return null;
    }

    private Object value(Map<String, Object> body, String... keys) {
        for (String key : keys) if (body.containsKey(key)) return body.get(key);
        return null;
    }

    private String requiredText(Object value, String message) {
        String parsed = text(value);
        if (!StringUtils.hasText(parsed)) throw new BusinessException(message);
        return parsed;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private Object blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    /** HTML date fields need a calendar date, while JDBC map rows are serialized as ISO instants. */
    private String normalizeDate(String value, String label) {
        if (!StringUtils.hasText(value)) return null;
        String datePart = value.trim().length() >= 10 ? value.trim().substring(0, 10) : value.trim();
        try {
            return LocalDate.parse(datePart).toString();
        } catch (Exception exception) {
            throw new BusinessException(label + "格式不合法，应为 YYYY-MM-DD");
        }
    }

    private Long longValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private long longOrDefault(Object value, long fallback) {
        Long parsed = longValue(value);
        return parsed == null ? fallback : parsed;
    }

    private int intValue(Object value, int fallback) {
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        if (value instanceof Boolean bool) return bool ? 1 : 0;
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private int boolInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Boolean bool) return bool ? 1 : 0;
        return "1".equals(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value)) ? 1 : 0;
    }

    private BigDecimal decimal(Object value, BigDecimal fallback) {
        if (value == null || !StringUtils.hasText(text(value))) return fallback;
        try {
            return new BigDecimal(text(value));
        } catch (NumberFormatException exception) {
            throw new BusinessException("表计倍率格式不合法");
        }
    }

    private String normalizeMeterRole(Object value) {
        String role = text(value);
        return StringUtils.hasText(role) ? role.toUpperCase(Locale.ROOT) : "INTERNAL";
    }
}
