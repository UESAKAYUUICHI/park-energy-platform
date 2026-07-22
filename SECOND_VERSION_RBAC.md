# park-energy-platform 第二版说明

## 本版完成内容

- 接入 Sa-Token 登录态与权限鉴定，接口统一使用 `Authorization: Bearer <token>`。
- RBAC 使用 `sys_user`、`sys_role`、`sys_permission`、`sys_user_role`、`sys_role_permission`。
- RBAC 核心表切换为 MyBatis-Plus 实体、Mapper、分页查询和维护接口。
- 增加操作日志 AOP，带 `@OperationLog` 的接口会写入 `log_operation`。
- 保留第一版通用档案、能源代理、接入代理、告警、计费接口，并加权限注解。
- `super_admin` 角色拥有通配权限，便于本地调试。
- 增加用户组织数据范围 `sys_user_org_scope`，RBAC 继续控制页面、按钮和接口权限，数据范围单独控制用户可见园区、网关、设备和业务数据。

## 默认登录

- 地址：`POST /api/platform/auth/login`
- 账号：`admin`
- 密码：`123456`

登录返回 `tokenValue`，前端会自动保存并放入 `Authorization` 请求头。

## 前端

- 项目目录：`D:\Code\EC-EnergySys\park-energy-web`
- 开发端口：`8104`
- 平台后端代理：`http://localhost:8103`

启动：

```bat
cd /d D:\Code\EC-EnergySys\park-energy-web
npm run serve
```

后端启动：

```bat
cd /d D:\Code\EC-EnergySys\park-energy-platform
mvnw.cmd spring-boot:run
```

## 权限种子

如果需要让普通角色可分配完整权限点，执行：

```sql
SOURCE D:/Code/EC-EnergySys/park-energy-platform/src/main/resources/rbac-permissions.sql;
```

`super_admin` 不依赖这份种子也能访问全部接口。

## 数据范围

已有数据库升级时执行：

```sql
SOURCE D:/Code/EC-EnergySys/park-energy-platform/src/main/resources/data-scope.sql;
```

用户组织范围接口：

```text
GET /api/platform/rbac/users/{id}/org-scopes
PUT /api/platform/rbac/users/{id}/org-scopes
```

请求示例：

```json
{
  "scopes": [
    {
      "orgId": 1,
      "scopeMode": "SUBTREE"
    }
  ]
}
```

`SELF` 只看当前组织，`SUBTREE` 看当前组织及全部下级组织。
