-- 设备主数据允许先建档、后绑定网关。
-- 组织归属由后端默认填充当前可见组织；网关部署关系在组织档案树中绑定。
ALTER TABLE `dev_device`
  MODIFY COLUMN `gateway_id` bigint unsigned NULL COMMENT '所属网关ID，未部署时为空';
