-- Edge collection configuration governance.
-- Run on the platform database before deploying the first-stage refactor.

ALTER TABLE dev_device
  ADD COLUMN edge_channel_id VARCHAR(40) NOT NULL DEFAULT 'rs485-1'
    COMMENT 'Edge RS485 channel id, for example rs485-1' AFTER protocol_addr;

CREATE INDEX idx_dev_device_gateway_channel_addr
  ON dev_device(gateway_id, edge_channel_id, protocol_addr);

CREATE TABLE IF NOT EXISTS dev_thing_model_point (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  model_version_id BIGINT UNSIGNED NOT NULL,
  point_code VARCHAR(64) NOT NULL COMMENT 'Gateway points key, for example voltage_a',
  standard_point_code VARCHAR(64) NOT NULL COMMENT 'Platform standard point code, for example VOLTAGE_A',
  point_name VARCHAR(100) NOT NULL,
  unit VARCHAR(32) NULL,
  function_code INT NOT NULL DEFAULT 3 COMMENT 'Modbus function code, 3 or 4',
  register_address INT NOT NULL COMMENT 'Zero-based Modbus register address',
  register_length INT NOT NULL DEFAULT 1 COMMENT 'Register quantity',
  value_type VARCHAR(32) NOT NULL COMMENT 'u16/i16/u32/i32/f32',
  byte_order VARCHAR(20) NOT NULL DEFAULT 'ABCD' COMMENT 'AB/BA or ABCD/BADC/CDAB/DCBA',
  scale_factor DECIMAL(18,6) NOT NULL DEFAULT 1,
  offset_value DECIMAL(18,6) NOT NULL DEFAULT 0,
  required TINYINT NOT NULL DEFAULT 1,
  sort INT NOT NULL DEFAULT 0,
  enabled TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_thing_model_point(model_version_id, point_code),
  KEY idx_thing_model_point_version_enabled(model_version_id, enabled, sort),
  CONSTRAINT fk_thing_model_point_version
    FOREIGN KEY(model_version_id) REFERENCES dev_device_model_version(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Edge Modbus collection points by model version';

CREATE TABLE IF NOT EXISTS dev_gateway_config_state (
  gateway_id BIGINT UNSIGNED NOT NULL,
  desired_revision BIGINT NOT NULL DEFAULT 0,
  applied_revision BIGINT NOT NULL DEFAULT 0,
  desired_checksum CHAR(64) NULL,
  applied_checksum CHAR(64) NULL,
  apply_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  last_error VARCHAR(500) NULL,
  last_sync_time DATETIME NULL,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(gateway_id),
  CONSTRAINT fk_gateway_config_state
    FOREIGN KEY(gateway_id) REFERENCES dev_gateway(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Gateway desired/applied edge config state';

INSERT INTO dev_thing_model_point
  (model_version_id, point_code, standard_point_code, point_name, unit, function_code,
   register_address, register_length, value_type, byte_order, scale_factor, offset_value, sort)
VALUES
  (6, 'voltage_a', 'VOLTAGE_A', 'A相电压', 'V', 3, 0, 2, 'u32', 'ABCD', 0.1, 0, 10),
  (6, 'voltage_b', 'VOLTAGE_B', 'B相电压', 'V', 3, 2, 2, 'u32', 'ABCD', 0.1, 0, 20),
  (6, 'voltage_c', 'VOLTAGE_C', 'C相电压', 'V', 3, 4, 2, 'u32', 'ABCD', 0.1, 0, 30),
  (6, 'current_a', 'CURRENT_A', 'A相电流', 'A', 3, 6, 2, 'u32', 'ABCD', 0.001, 0, 40),
  (6, 'current_b', 'CURRENT_B', 'B相电流', 'A', 3, 8, 2, 'u32', 'ABCD', 0.001, 0, 50),
  (6, 'current_c', 'CURRENT_C', 'C相电流', 'A', 3, 10, 2, 'u32', 'ABCD', 0.001, 0, 60),
  (6, 'active_power_total', 'ACTIVE_POWER_TOTAL', '总有功功率', 'kW', 3, 18, 2, 'i32', 'ABCD', 0.01, 0, 70),
  (6, 'reactive_power_total', 'REACTIVE_POWER_TOTAL', '总无功功率', 'kvar', 3, 22, 2, 'i32', 'ABCD', 0.01, 0, 80),
  (6, 'apparent_power_total', 'APPARENT_POWER_TOTAL', '总视在功率', 'kVA', 3, 26, 2, 'i32', 'ABCD', 0.01, 0, 90),
  (6, 'power_factor_total', 'POWER_FACTOR_TOTAL', '总功率因数', '', 3, 41, 1, 'u16', 'ABCD', 0.001, 0, 100),
  (6, 'frequency', 'FREQUENCY', '频率', 'Hz', 3, 70, 1, 'u16', 'ABCD', 0.01, 0, 110),
  (6, 'forward_active_energy', 'FORWARD_ACTIVE_ENERGY', '正向有功电能', 'kWh', 3, 256, 2, 'u32', 'ABCD', 0.01, 0, 120)
ON DUPLICATE KEY UPDATE
  standard_point_code = VALUES(standard_point_code),
  point_name = VALUES(point_name),
  unit = VALUES(unit),
  function_code = VALUES(function_code),
  register_address = VALUES(register_address),
  register_length = VALUES(register_length),
  value_type = VALUES(value_type),
  byte_order = VALUES(byte_order),
  scale_factor = VALUES(scale_factor),
  offset_value = VALUES(offset_value),
  sort = VALUES(sort),
  enabled = 1;
