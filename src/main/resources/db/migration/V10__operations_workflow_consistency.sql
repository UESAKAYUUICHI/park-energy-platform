-- Keep the existing work-order states for backward compatibility while adding
-- optimistic locking and an independent device-recovery observation.
ALTER TABLE ops_work_order
    ADD COLUMN workflow_version INT NOT NULL DEFAULT 0 COMMENT '工单并发版本号' AFTER status,
    ADD COLUMN device_state VARCHAR(30) NULL COMMENT '最近一次设备事实状态' AFTER evidence_urls,
    ADD COLUMN device_state_time DATETIME NULL COMMENT '设备事实状态更新时间' AFTER device_state;

CREATE INDEX idx_ops_work_order_device_state
    ON ops_work_order (device_state, device_state_time);
