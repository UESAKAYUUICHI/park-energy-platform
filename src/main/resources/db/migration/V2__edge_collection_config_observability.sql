-- Edge collection configuration edit audit and gateway apply detail.

CREATE TABLE IF NOT EXISTS dev_edge_config_audit (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  gateway_id BIGINT UNSIGNED NOT NULL,
  action_type VARCHAR(40) NOT NULL COMMENT 'DEVICE_BINDING/MODEL_POINTS/PUBLISH',
  target_type VARCHAR(40) NOT NULL,
  target_id BIGINT UNSIGNED NULL,
  before_json JSON NULL,
  after_json JSON NULL,
  operator_user_id BIGINT UNSIGNED NULL,
  operator_name VARCHAR(80) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  KEY idx_edge_config_audit_gateway_time(gateway_id, create_time),
  CONSTRAINT fk_edge_config_audit_gateway
    FOREIGN KEY(gateway_id) REFERENCES dev_gateway(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Edge collection config audit log';

CREATE TABLE IF NOT EXISTS dev_gateway_config_resource (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  gateway_id BIGINT UNSIGNED NOT NULL,
  resource_type VARCHAR(40) NOT NULL COMMENT 'DEVICE/MODEL/POINT/CHANNEL',
  resource_key VARCHAR(160) NOT NULL,
  config_revision VARCHAR(40) NULL,
  config_checksum CHAR(64) NULL,
  apply_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  error_message VARCHAR(500) NULL,
  applied_time DATETIME NULL,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_gateway_config_resource(gateway_id, resource_type, resource_key),
  KEY idx_gateway_config_resource_status(gateway_id, apply_status),
  CONSTRAINT fk_gateway_config_resource_gateway
    FOREIGN KEY(gateway_id) REFERENCES dev_gateway(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Gateway config apply resource detail';

ALTER TABLE data_ingest_item
  ADD COLUMN channel_id VARCHAR(40) NULL COMMENT 'Edge RS485 channel id from sample' AFTER device_sn,
  ADD COLUMN modbus_addr INT NULL COMMENT 'Modbus slave address from sample' AFTER channel_id,
  ADD COLUMN profile_key VARCHAR(80) NULL COMMENT 'Gateway profile key from sample' AFTER modbus_addr,
  ADD COLUMN model_version VARCHAR(80) NULL COMMENT 'Gateway model version from sample' AFTER profile_key,
  ADD COLUMN config_revision VARCHAR(40) NULL COMMENT 'Gateway applied config revision when collected' AFTER model_version;

CREATE INDEX idx_data_ingest_item_config_revision
  ON data_ingest_item(gateway_id, config_revision, device_sn);
