package com.parkenergyplatform.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.dev33.satoken.stp.StpUtil;
import com.parkenergyplatform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Settlement-meter admission gate: configuration stays explicit and auditable. */
@Service
public class SettlementMeterService {
    private final JdbcTemplate jdbcTemplate;
    private final BusinessDataAccessService accessService;

    public SettlementMeterService(JdbcTemplate jdbcTemplate, BusinessDataAccessService accessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessService = accessService;
    }

    public List<Map<String, Object>> candidates() {
        List<Object> args = new ArrayList<>();
        String scope = accessService.scopeSql("d.org_id", args);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT d.id,d.device_sn,d.device_name,d.org_id,d.space_id,sp.space_name,d.gateway_id,d.status,d.energy_carrier,
                       d.settlement_enabled,d.meter_role,d.meter_factor,d.quality_threshold_pct,
                       o.org_name,g.gateway_sn,g.gateway_name,g.status AS gateway_status,
                       (SELECT COUNT(*) FROM dev_point_definition p WHERE p.device_type_id=d.device_type_id
                         AND p.enabled=1 AND p.billable=1 AND p.stat_enabled=1
                         AND UPPER(COALESCE(p.business_role,''))='TOTAL_ACCUMULATED') AS accumulated_point_count,
                       (SELECT p.point_code FROM dev_point_definition p WHERE p.device_type_id=d.device_type_id
                         AND p.enabled=1 AND p.billable=1 AND p.stat_enabled=1
                         AND UPPER(COALESCE(p.business_role,''))='TOTAL_ACCUMULATED' ORDER BY p.id LIMIT 1) AS metric_point_code,
                       (SELECT COUNT(*) FROM leasing_contract_meter cm JOIN leasing_contract c ON c.id=cm.contract_id
                         WHERE cm.device_id=d.id AND c.status='ACTIVE') AS active_contract_count,
                       (SELECT COUNT(*) FROM billing_account a WHERE a.org_id=d.org_id AND a.status=1) AS active_account_count,
                       (SELECT COUNT(*) FROM billing_tariff_plan tp WHERE tp.org_id=d.org_id AND tp.status='ACTIVE'
                         AND tp.effective_start_date<=CURDATE() AND (tp.effective_end_date IS NULL OR tp.effective_end_date>=CURDATE())) AS active_tariff_count,
                       (SELECT MAX(stat_date) FROM stats_daily_point s WHERE s.device_id=d.id
                         AND s.point_code=(SELECT p.point_code FROM dev_point_definition p WHERE p.device_type_id=d.device_type_id
                         AND p.enabled=1 AND p.billable=1 AND p.stat_enabled=1
                         AND UPPER(COALESCE(p.business_role,''))='TOTAL_ACCUMULATED' ORDER BY p.id LIMIT 1)) AS latest_stat_date
                FROM dev_device d
                LEFT JOIN dev_org o ON o.id=d.org_id
                LEFT JOIN park_space sp ON sp.id=d.space_id
                LEFT JOIN dev_gateway g ON g.id=d.gateway_id
                WHERE UPPER(COALESCE(d.energy_carrier,'ELECTRICITY'))='ELECTRICITY'
                """ + scope + " ORDER BY d.settlement_enabled DESC,d.id", args.toArray());
        return rows.stream().map(this::decorate).toList();
    }

    public Map<String, Object> detail(long deviceId) {
        accessService.assertDeviceAccess(deviceId);
        return candidates().stream().filter(row -> longValue(row.get("id")) == deviceId).findFirst()
                .orElseThrow(() -> new BusinessException(404, "结算表计不存在或无访问权限"));
    }

    @Transactional
    public Map<String, Object> enable(long deviceId, Map<String, Object> body) {
        Map<String, Object> meter = detail(deviceId);
        @SuppressWarnings("unchecked")
        List<String> blockers = (List<String>) meter.get("blockers");
        if (!blockers.isEmpty()) throw new BusinessException("暂不能启用结算表计：" + String.join("；", blockers));
        String role = String.valueOf(body.getOrDefault("meterRole", "MAIN")).trim().toUpperCase();
        if (!List.of("MAIN", "SUB", "PROCESS").contains(role)) throw new BusinessException("结算表计层级仅支持 MAIN/SUB/PROCESS");
        String operator = StpUtil.isLogin() && StpUtil.getSession().get("username") != null
                ? String.valueOf(StpUtil.getSession().get("username")) : "system";
        jdbcTemplate.update("UPDATE dev_device SET settlement_enabled=1,meter_role=?,update_by=? WHERE id=?",
                role, operator, deviceId);
        return detail(deviceId);
    }

    @Transactional
    public Map<String, Object> disable(long deviceId) {
        Map<String, Object> meter = detail(deviceId);
        Long bound = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM leasing_contract_meter cm JOIN leasing_contract c ON c.id=cm.contract_id WHERE cm.device_id=? AND c.status='ACTIVE'", Long.class, deviceId);
        if (bound != null && bound > 0) throw new BusinessException("该表计仍关联生效合同，请先通过计量变更单完成替换或解除绑定");
        String operator = StpUtil.isLogin() && StpUtil.getSession().get("username") != null
                ? String.valueOf(StpUtil.getSession().get("username")) : "system";
        jdbcTemplate.update("UPDATE dev_device SET settlement_enabled=0,meter_role='INTERNAL',update_by=? WHERE id=?", operator, deviceId);
        return detail(deviceId);
    }

    private Map<String, Object> decorate(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (number(source.get("status")) != 1) blockers.add("设备已停用");
        if (source.get("gateway_id") == null) blockers.add("设备未绑定网关");
        else if (number(source.get("gateway_status")) != 1) blockers.add("绑定网关已停用");
        if (number(source.get("accumulated_point_count")) != 1) blockers.add("缺少唯一的可结算累计测点");
        if (number(source.get("active_contract_count")) == 0) blockers.add("未关联生效合同");
        if (number(source.get("active_account_count")) == 0) blockers.add("园区尚无启用的计费账户");
        if (number(source.get("active_tariff_count")) == 0) blockers.add("园区尚无生效的分时电价方案");
        if (source.get("latest_stat_date") == null) warnings.add("尚无日统计，启用后无法立即重建 TOU");
        else if (LocalDate.now().minusDays(1).isAfter(LocalDate.parse(String.valueOf(source.get("latest_stat_date"))))) warnings.add("采集统计不是最新数据，请先恢复网关上报再结算");
        result.put("blockers", blockers);
        result.put("warnings", warnings);
        result.put("ready", blockers.isEmpty());
        return result;
    }

    private long number(Object value) { return value == null ? 0L : Long.parseLong(String.valueOf(value)); }
    private long longValue(Object value) { return number(value); }
}
