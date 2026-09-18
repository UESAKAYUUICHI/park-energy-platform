-- Gateway channel configuration.

CREATE TABLE IF NOT EXISTS dev_gateway_channel (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  gateway_id BIGINT UNSIGNED NOT NULL,
  channel_id VARCHAR(40) NOT NULL COMMENT 'Logical channel id, for example rs485-1',
  channel_name VARCHAR(100) NOT NULL,
  protocol VARCHAR(24) NOT NULL DEFAULT 'MODBUS_RTU',
  serial_port VARCHAR(80) NULL COMMENT 'Physical port, for example /dev/ttyS4 or COM1',
  baud_rate INT NOT NULL DEFAULT 9600,
  data_bits INT NOT NULL DEFAULT 8,
  stop_bits INT NOT NULL DEFAULT 1,
  parity VARCHAR(8) NOT NULL DEFAULT 'N',
  timeout_ms INT NOT NULL DEFAULT 1000,
  retry_count INT NOT NULL DEFAULT 2,
  poll_interval_seconds INT NOT NULL DEFAULT 300,
  enabled TINYINT NOT NULL DEFAULT 1,
  remark VARCHAR(255) NULL,
  create_by VARCHAR(80) NULL,
  update_by VARCHAR(80) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_gateway_channel(gateway_id, channel_id),
  KEY idx_gateway_channel_enabled(gateway_id, enabled),
  CONSTRAINT fk_gateway_channel_gateway
    FOREIGN KEY(gateway_id) REFERENCES dev_gateway(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Gateway Modbus channel configuration';

INSERT INTO dev_gateway_channel
  (gateway_id, channel_id, channel_name, protocol, baud_rate, data_bits, stop_bits,
   parity, timeout_ms, retry_count, poll_interval_seconds, enabled, create_by, update_by)
SELECT DISTINCT d.gateway_id,
       COALESCE(NULLIF(d.edge_channel_id, ''), 'rs485-1') AS channel_id,
       COALESCE(NULLIF(d.edge_channel_id, ''), 'rs485-1') AS channel_name,
       'MODBUS_RTU',
       9600, 8, 1, 'N', 1000, 2,
       COALESCE(NULLIF(d.collect_interval_seconds, 0), 300),
       1, 'migration', 'migration'
FROM dev_device d
WHERE d.gateway_id IS NOT NULL
ON DUPLICATE KEY UPDATE
  channel_name = dev_gateway_channel.channel_name;
