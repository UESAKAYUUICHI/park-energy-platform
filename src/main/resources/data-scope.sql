USE `park_energy_system`;

CREATE TABLE IF NOT EXISTS `sys_user_org_scope` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  `org_id` bigint unsigned NOT NULL COMMENT '授权组织ID',
  `scope_mode` varchar(20) NOT NULL DEFAULT 'SELF' COMMENT '范围模式：SELF, SUBTREE',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_org_scope` (`user_id`, `org_id`, `scope_mode`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_org_id` (`org_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户组织数据权限表';

INSERT INTO `sys_user_org_scope` (`user_id`, `org_id`, `scope_mode`)
SELECT 1, 1, 'SUBTREE'
WHERE EXISTS (SELECT 1 FROM `sys_user` WHERE `id` = 1)
  AND EXISTS (SELECT 1 FROM `dev_org` WHERE `id` = 1)
ON DUPLICATE KEY UPDATE
  `scope_mode` = VALUES(`scope_mode`);
