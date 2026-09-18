ALTER TABLE dev_protocol_field
  ADD COLUMN standard_point_id BIGINT UNSIGNED NULL AFTER field_name,
  ADD KEY idx_protocol_field_standard_point (standard_point_id),
  ADD CONSTRAINT fk_protocol_field_standard_point FOREIGN KEY (standard_point_id)
    REFERENCES dev_standard_point(id);

CREATE TABLE dev_protocol_attribute_template (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  protocol_version_id BIGINT UNSIGNED NOT NULL,
  attribute_id BIGINT UNSIGNED NOT NULL,
  attribute_value_option_id BIGINT UNSIGNED NULL,
  attribute_value VARCHAR(255) NULL,
  required TINYINT NOT NULL DEFAULT 0,
  sort INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_protocol_attribute_template (protocol_version_id, attribute_id),
  KEY idx_protocol_attribute_template_attr (attribute_id),
  CONSTRAINT fk_protocol_attribute_template_version FOREIGN KEY (protocol_version_id)
    REFERENCES dev_protocol_profile_version(id) ON DELETE CASCADE,
  CONSTRAINT fk_protocol_attribute_template_attr FOREIGN KEY (attribute_id)
    REFERENCES dev_attribute_definition(id),
  CONSTRAINT fk_protocol_attribute_template_option FOREIGN KEY (attribute_value_option_id)
    REFERENCES dev_attribute_value_option(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Protocol static attribute template references';

UPDATE dev_protocol_field f
JOIN dev_standard_point sp ON sp.point_code COLLATE utf8mb4_general_ci=UPPER(f.field_code) COLLATE utf8mb4_general_ci
SET f.standard_point_id=sp.id
WHERE f.standard_point_id IS NULL;
