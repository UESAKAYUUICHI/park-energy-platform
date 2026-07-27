USE `park_energy_system`;

UPDATE `sys_permission`
SET `status` = 0
WHERE `id` BETWEEN 1000 AND 3999;

INSERT INTO `sys_permission`
(`id`, `parent_id`, `perm_type`, `perm_name`, `perm_code`, `route_path`, `component_path`, `icon`, `sort`, `status`)
VALUES
(1000, 0, 1, '经营总览', NULL, NULL, NULL, 'Compass', 10, 1),
(1001, 1000, 2, '经营总览', 'dashboard:view', '/dashboard', 'DashboardView', 'LayoutDashboard', 11, 1),

(1100, 0, 1, '设备档案', NULL, NULL, NULL, 'Archive', 20, 1),
(1101, 1100, 2, '组织档案树', 'archive:list', '/device-archive/org-tree', 'DeviceArchiveView', 'Network', 21, 1),
(1102, 1100, 2, '设备档案', 'archive:list', '/device-archive/devices', 'DeviceArchiveView', 'Gauge', 22, 1),
(1191, 1100, 3, '档案新增', 'archive:add', NULL, NULL, 'Plus', 28, 1),
(1192, 1100, 3, '档案编辑', 'archive:edit', NULL, NULL, 'Pencil', 29, 1),
(1193, 1100, 3, '档案删除', 'archive:delete', NULL, NULL, 'Trash2', 30, 1),

(1200, 0, 1, '能源运营', NULL, NULL, NULL, 'Zap', 40, 1),
(1201, 1200, 2, '实时监控工作台', 'energy:view', '/monitor/realtime', 'EnergyView', 'Activity', 41, 1),
(1202, 1200, 2, '历史数据分析', 'energy:view', '/analysis/history', 'EnergyView', 'History', 42, 1),
(1203, 1200, 2, '能耗统计与数据质量', 'energy:view', '/analysis/quality', 'EnergyView', 'BarChart3', 43, 1),
(1291, 1200, 3, '日统计重建', 'energy:statistics:rebuild', NULL, NULL, 'RefreshCw', 49, 1),

(1300, 0, 1, '告警处置', NULL, NULL, NULL, 'Siren', 60, 1),
(1301, 1300, 2, '告警事件中心', 'alarm:rule:list', '/alarms/events', 'AlarmView', 'Bell', 61, 1),
(1302, 1300, 2, '告警处置台', 'alarm:rule:list', '/alarms/workbench', 'AlarmView', 'ClipboardCheck', 62, 1),
(1303, 1300, 2, '告警规则配置', 'alarm:rule:list', '/alarms/rules', 'AlarmView', 'BadgeCheck', 63, 1),
(1391, 1300, 3, '告警规则新增', 'alarm:rule:add', NULL, NULL, 'Plus', 68, 1),
(1392, 1300, 3, '告警规则编辑', 'alarm:rule:edit', NULL, NULL, 'Pencil', 69, 1),
(1393, 1300, 3, '告警事件处理', 'alarm:event:deal', NULL, NULL, 'ClipboardCheck', 70, 1),

(1400, 0, 1, '结算收款', NULL, NULL, NULL, 'CircleDollarSign', 80, 1),
(1401, 1400, 2, '结算工作台', 'billing:list', '/billing/settlement', 'BillingView', 'ReceiptText', 81, 1),
(1402, 1400, 2, '账单中心', 'billing:bill:list', '/billing/bills', 'BillingView', 'FileText', 82, 1),
(1403, 1400, 2, '计费账户', 'billing:list', '/billing/accounts', 'ResourceView', 'WalletCards', 83, 1),
(1404, 1400, 2, '计费规则', 'billing:list', '/billing/rules', 'ResourceView', 'ScrollText', 84, 1),
(1405, 1400, 2, '规则适用范围', 'billing:list', '/billing/rule-scopes', 'ResourceView', 'GitFork', 85, 1),
(1406, 1400, 2, '阶梯价格明细', 'billing:list', '/billing/price-items', 'ResourceView', 'ListOrdered', 86, 1),
(1491, 1400, 3, '计费新增', 'billing:add', NULL, NULL, 'Plus', 91, 1),
(1492, 1400, 3, '计费编辑', 'billing:edit', NULL, NULL, 'Pencil', 92, 1),
(1493, 1400, 3, '计费删除', 'billing:delete', NULL, NULL, 'Trash2', 93, 1),
(1494, 1400, 3, '账单生成', 'billing:bill:generate', NULL, NULL, 'FilePlus2', 94, 1),
(1495, 1400, 3, '账单缴费', 'billing:bill:pay', NULL, NULL, 'BadgeDollarSign', 95, 1),

(1500, 0, 1, '设备运维', NULL, NULL, NULL, 'HardHat', 100, 1),
(1501, 1500, 2, '接入诊断', 'access:view', '/access/diagnostic', 'AccessView', 'RadioReceiver', 101, 1),
(1502, 1500, 2, '设备控制台', 'access:command', '/access/control', 'AccessView', 'SlidersHorizontal', 102, 1),
(1503, 1500, 2, '指令追踪', 'access:view', '/access/commands', 'AccessView', 'Send', 103, 1),
(1591, 1500, 3, '发送指令', 'access:command', NULL, NULL, 'Send', 109, 1),

(2000, 0, 1, '系统管理', NULL, NULL, NULL, 'Settings2', 120, 1),
(2100, 2000, 1, '基础档案', NULL, NULL, NULL, 'Archive', 121, 1),
(2101, 2100, 2, '组织管理', 'archive:list', '/archive/orgs', 'ResourceView', 'Building2', 122, 1),
(2102, 2100, 2, '网关管理', 'archive:list', '/archive/gateways', 'ResourceView', 'Router', 123, 1),
(2103, 2100, 2, '设备管理', 'archive:list', '/archive/devices', 'ResourceView', 'Cpu', 124, 1),
(2104, 2100, 2, '设备类型', 'archive:list', '/archive/device-types', 'ResourceView', 'Tags', 125, 1),
(2105, 2100, 2, '测点定义', 'archive:list', '/archive/point-definitions', 'ResourceView', 'Crosshair', 126, 1),
(2106, 2100, 2, '协议映射', 'archive:list', '/archive/point-mappings', 'ResourceView', 'Cable', 127, 1),

(2200, 2000, 1, '业务参数配置', NULL, NULL, NULL, 'SlidersHorizontal', 140, 1),
(2201, 2200, 2, '告警规则配置', 'alarm:rule:list', '/alarms/rules', 'AlarmView', 'BadgeCheck', 141, 1),
(2202, 2200, 2, '计费账户', 'billing:list', '/billing/accounts', 'ResourceView', 'WalletCards', 142, 1),
(2203, 2200, 2, '计费规则', 'billing:list', '/billing/rules', 'ResourceView', 'ScrollText', 143, 1),
(2204, 2200, 2, '规则适用范围', 'billing:list', '/billing/rule-scopes', 'ResourceView', 'GitFork', 144, 1),
(2205, 2200, 2, '阶梯价格明细', 'billing:list', '/billing/price-items', 'ResourceView', 'ListOrdered', 145, 1),

(2300, 2000, 1, '权限与审计', NULL, NULL, NULL, 'ShieldCheck', 160, 1),
(2301, 2300, 2, '用户管理', 'system:user:list', '/system/users', 'SystemView', 'Users', 161, 1),
(2302, 2300, 2, '角色管理', 'system:role:list', '/system/roles', 'SystemView', 'ShieldCheck', 162, 1),
(2303, 2300, 2, '权限字典', 'system:permission:list', '/system/permissions', 'SystemView', 'KeyRound', 163, 1),
(2304, 2300, 2, '模块权限分配', 'system:role:list', '/system/rbac-workbench', 'RbacWorkbenchView', 'Wrench', 164, 1),
(2305, 2300, 2, '用户组织绑定', 'system:user:scope:list', '/system/user-org-bindings', 'UserOrgBindingView', 'Building2', 165, 1),
(2306, 2300, 2, '操作审计', 'system:operation:list', '/system/audit', 'SystemView', 'FileClock', 166, 1),
(2311, 2301, 3, '用户新增', 'system:user:add', NULL, NULL, 'UserPlus', 171, 1),
(2312, 2301, 3, '用户编辑', 'system:user:edit', NULL, NULL, 'Pencil', 172, 1),
(2313, 2301, 3, '用户删除', 'system:user:delete', NULL, NULL, 'Trash2', 173, 1),
(2314, 2301, 3, '用户角色分配', 'system:user:edit', NULL, NULL, 'UserCog', 174, 1),
(2315, 2301, 3, '用户密码重置', 'system:user:edit', NULL, NULL, 'KeyRound', 175, 1),
(2321, 2302, 3, '角色新增', 'system:role:add', NULL, NULL, 'Plus', 181, 1),
(2322, 2302, 3, '角色编辑', 'system:role:edit', NULL, NULL, 'Pencil', 182, 1),
(2323, 2302, 3, '角色删除', 'system:role:delete', NULL, NULL, 'Trash2', 183, 1),
(2324, 2302, 3, '角色权限分配', 'system:role:edit', NULL, NULL, 'ShieldPlus', 184, 1),
(2331, 2303, 3, '权限新增', 'system:permission:add', NULL, NULL, 'Plus', 191, 1),
(2332, 2303, 3, '权限编辑', 'system:permission:edit', NULL, NULL, 'Pencil', 192, 1),
(2333, 2303, 3, '权限删除', 'system:permission:delete', NULL, NULL, 'Trash2', 193, 1),
(2334, 2303, 3, '新增子项目', 'system:permission:add', NULL, NULL, 'PlusCircle', 194, 1),
(2341, 2305, 3, '组织范围查看', 'system:user:scope:list', NULL, NULL, 'Eye', 201, 1),
(2342, 2305, 3, '组织范围编辑', 'system:user:scope:edit', NULL, NULL, 'GitBranch', 202, 1),
(2351, 2306, 3, '操作日志查询', 'system:operation:list', NULL, NULL, 'Search', 211, 1),

(3000, 0, 1, '个人中心', NULL, NULL, NULL, 'UserCircle', 300, 1),
(3001, 3000, 2, '个人中心', NULL, '/profile', 'ProfileView', 'UserCircle', 301, 1)
ON DUPLICATE KEY UPDATE
`parent_id` = VALUES(`parent_id`),
`perm_type` = VALUES(`perm_type`),
`perm_name` = VALUES(`perm_name`),
`perm_code` = VALUES(`perm_code`),
`route_path` = VALUES(`route_path`),
`component_path` = VALUES(`component_path`),
`icon` = VALUES(`icon`),
`sort` = VALUES(`sort`),
`status` = VALUES(`status`);
