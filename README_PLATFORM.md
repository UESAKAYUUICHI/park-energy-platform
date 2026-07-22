# park-energy-platform 第一版实现说明

## 定位

`park-energy-platform` 是园区能耗系统的业务平台后端，端口 `8103`。它不接入 NanoMQ，不消费 RabbitMQ，不负责 IoTDB 主链路写入，而是面向前端统一提供业务 API。

## 已实现能力

- 基础 Spring Boot Web 工程
- MySQL 配置：`root / 123456`
- 统一返回结构：`ApiResponse`
- 全局异常处理
- CORS 配置
- 档案 CRUD
- 告警规则管理与告警处理
- 能源数据聚合代理
- access 指令与接入数据代理
- 计费账号、规则、价格、账单、缴费基础接口
- 账单生成与全额缴费登记
- 首页汇总接口

## 服务配置

默认配置在 `src/main/resources/application.yml`：

```yaml
server:
  port: 8103

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/park_energy_system
    username: root
    password: 123456

park:
  platform:
    data-base-url: http://localhost:8102
    access-base-url: http://localhost:8101
```

## 档案接口

统一路径：

```text
GET    /api/platform/archive/{resource}
GET    /api/platform/archive/{resource}/{id}
POST   /api/platform/archive/{resource}
PUT    /api/platform/archive/{resource}/{id}
DELETE /api/platform/archive/{resource}/{id}
```

支持的 `resource`：

```text
orgs
gateways
device-types
devices
point-definitions
point-mappings
```

列表查询支持：

```text
pageNum
pageSize
keyword
任意白名单字段过滤，例如 orgId、gatewayId、deviceTypeId、status
```

带 `orgId` 的业务查询统一支持：

```text
orgId=1&includeChildren=true
```

`includeChildren=true` 时按 `dev_org.parent_id` 展开子树，并与当前登录用户的 `sys_user_org_scope` 可见范围取交集。

## 能源数据聚合接口

这些接口由 platform 调用 data 服务：

```text
GET /api/platform/energy/realtime/devices/{deviceId}
GET /api/platform/energy/history?deviceId=1&pointCode=voltage_a&startTime=...&endTime=...
GET /api/platform/energy/statistics/daily?deviceId=1&pointCode=total_active_energy&startDate=...&endDate=...
GET /api/platform/energy/alarms?deviceId=1&dealStatus=0
POST /api/platform/statistics/daily/rebuild?statDate=2026-07-18&deviceId=1
```

## 接入与指令聚合接口

这些接口由 platform 调用 access 服务：

```text
GET  /api/platform/access/gateways/status
GET  /api/platform/access/raw-messages
POST /api/platform/access/commands
GET  /api/platform/access/commands
GET  /api/platform/access/commands/{commandId}
```

## 告警接口

```text
GET  /api/platform/alarms/rules
POST /api/platform/alarms/rules
PUT  /api/platform/alarms/rules/{id}
POST /api/platform/alarms/events/{alarmId}/deal
```

告警处理请求示例：

```json
{
  "dealUser": "admin",
  "dealRemark": "现场确认后恢复正常"
}
```

## 计费接口

基础表查询：

```text
GET /api/platform/billing/accounts
GET /api/platform/billing/rules
GET /api/platform/billing/rule-scopes
GET /api/platform/billing/price-items
GET /api/platform/billing/bills
GET /api/platform/billing/payments
```

账单生成：

```text
POST /api/platform/billing/bills/generate
```

请求示例：

```json
{
  "accountId": 1,
  "billCycle": "2026-07",
  "startDate": "2026-07-01",
  "endDate": "2026-07-31",
  "remark": "7月电费"
}
```

缴费登记：

```text
POST /api/platform/billing/bills/{billId}/pay
```

请求示例：

```json
{
  "payAmount": 45.12,
  "payWay": "现金",
  "operator": "admin",
  "remark": "全额缴费"
}
```

当前按文档约束实现：不支持部分缴费，缴费金额必须等于账单应收金额。

账单状态：`0` 未缴费、`1` 已缴费、`2` 逾期、`3` 作废。

## 看板接口

```text
GET /api/platform/dashboard/summary
```

返回组织、网关、设备、在线网关、未处理告警、未缴费账单等汇总信息。

## 后续建议

第一版暂未实现完整 JWT/RBAC，只提供业务接口闭环。下一步建议补：

- 登录接口
- JWT 过滤器
- 用户角色权限接口
- 操作日志 AOP
- 前端 Vue 管理页面
