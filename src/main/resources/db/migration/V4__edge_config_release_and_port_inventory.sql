-- Immutable edge configuration releases and gateway-reported serial ports.

CREATE TABLE IF NOT EXISTS dev_gateway_port_inventory (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  gateway_id BIGINT UNSIGNED NOT NULL,
  port_key VARCHAR(120) NOT NULL,
  system_path VARCHAR(160) NOT NULL,
  port_type VARCHAR(80) NULL,
  available TINYINT NOT NULL DEFAULT 1,
  last_seen_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_gateway_port(gateway_id, port_key),
  KEY idx_gateway_port_available(gateway_id, available),
  CONSTRAINT fk_gateway_port_gateway
    FOREIGN KEY(gateway_id) REFERENCES dev_gateway(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Serial ports reported by the gateway runtime';

CREATE TABLE IF NOT EXISTS dev_gateway_config_release (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  gateway_id BIGINT UNSIGNED NOT NULL,
  revision VARCHAR(40) NOT NULL,
  checksum CHAR(64) NOT NULL,
  release_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  config_json JSON NOT NULL,
  error_message VARCHAR(500) NULL,
  published_by VARCHAR(80) NULL,
  publish_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  applied_time DATETIME NULL,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_gateway_release_revision(gateway_id, revision),
  KEY idx_gateway_release_status(gateway_id, release_status, publish_time),
  CONSTRAINT fk_gateway_release_gateway
    FOREIGN KEY(gateway_id) REFERENCES dev_gateway(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Immutable edge configuration release snapshot';
