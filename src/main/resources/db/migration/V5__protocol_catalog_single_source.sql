-- Protocol catalog is the only source of fieldbus decoding configuration.

CREATE TABLE dev_protocol_profile (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  profile_code VARCHAR(80) NOT NULL,
  profile_name VARCHAR(120) NOT NULL,
  manufacturer VARCHAR(120) NULL,
  transport_type VARCHAR(24) NOT NULL,
  description VARCHAR(500) NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_protocol_profile_code (profile_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Versioned vendor protocol catalog';

CREATE TABLE dev_protocol_profile_version (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  profile_id BIGINT UNSIGNED NOT NULL,
  version_no INT NOT NULL,
  version_name VARCHAR(60) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  address_base VARCHAR(24) NOT NULL DEFAULT 'PDU_ZERO_BASED',
  source_file_name VARCHAR(255) NULL,
  checksum CHAR(64) NULL,
  remark VARCHAR(500) NULL,
  published_time DATETIME NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_protocol_profile_version (profile_id, version_no),
  KEY idx_protocol_version_status (status),
  CONSTRAINT fk_protocol_version_profile FOREIGN KEY (profile_id) REFERENCES dev_protocol_profile(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Immutable vendor protocol versions';

CREATE TABLE dev_protocol_read_block (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  protocol_version_id BIGINT UNSIGNED NOT NULL,
  block_code VARCHAR(80) NOT NULL,
  block_name VARCHAR(120) NOT NULL,
  function_code INT NOT NULL,
  start_address INT NOT NULL COMMENT 'Zero-based Modbus PDU address',
  register_count INT NOT NULL,
  poll_mode VARCHAR(20) NOT NULL DEFAULT 'CYCLIC',
  interval_seconds INT NULL,
  required TINYINT NOT NULL DEFAULT 1,
  sort INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_protocol_read_block (protocol_version_id, block_code),
  KEY idx_protocol_read_block_order (protocol_version_id, sort),
  CONSTRAINT fk_read_block_version FOREIGN KEY (protocol_version_id) REFERENCES dev_protocol_profile_version(id) ON DELETE CASCADE,
  CONSTRAINT chk_read_block_fc CHECK (function_code IN (3,4)),
  CONSTRAINT chk_read_block_range CHECK (start_address BETWEEN 0 AND 65535 AND register_count BETWEEN 1 AND 125)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Compiled Modbus read requests';

CREATE TABLE dev_protocol_field (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  protocol_version_id BIGINT UNSIGNED NOT NULL,
  read_block_id BIGINT UNSIGNED NULL,
  field_code VARCHAR(80) NOT NULL,
  field_name VARCHAR(120) NOT NULL,
  document_address VARCHAR(40) NULL COMMENT 'Address as printed by vendor document',
  register_offset INT NOT NULL DEFAULT 0,
  register_length INT NOT NULL DEFAULT 1,
  value_type VARCHAR(24) NOT NULL DEFAULT 'UINT16',
  byte_order VARCHAR(12) NOT NULL DEFAULT 'ABCD',
  bit_offset INT NULL,
  bit_length INT NULL,
  decode_factor DECIMAL(24,9) NOT NULL DEFAULT 1,
  decode_offset DECIMAL(24,9) NOT NULL DEFAULT 0,
  raw_unit VARCHAR(32) NULL,
  enum_json JSON NULL,
  access_mode VARCHAR(8) NOT NULL DEFAULT 'R',
  required TINYINT NOT NULL DEFAULT 1,
  sort INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_protocol_field (protocol_version_id, field_code),
  KEY idx_protocol_field_block (read_block_id, sort),
  CONSTRAINT fk_protocol_field_version FOREIGN KEY (protocol_version_id) REFERENCES dev_protocol_profile_version(id) ON DELETE CASCADE,
  CONSTRAINT fk_protocol_field_block FOREIGN KEY (read_block_id) REFERENCES dev_protocol_read_block(id) ON DELETE CASCADE,
  CONSTRAINT chk_protocol_field_bits CHECK (bit_offset IS NULL OR (bit_offset BETWEEN 0 AND 63 AND bit_length BETWEEN 1 AND 64))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Vendor protocol fields decoded from read blocks';

ALTER TABLE dev_device_model_version
  ADD COLUMN protocol_profile_version_id BIGINT UNSIGNED NULL AFTER device_type_id,
  ADD KEY idx_model_protocol_version (protocol_profile_version_id),
  ADD CONSTRAINT fk_model_protocol_version FOREIGN KEY (protocol_profile_version_id)
    REFERENCES dev_protocol_profile_version(id);

CREATE TABLE dev_model_point_binding (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  model_version_id BIGINT UNSIGNED NOT NULL,
  point_code VARCHAR(64) NOT NULL,
  protocol_field_id BIGINT UNSIGNED NOT NULL,
  canonical_factor DECIMAL(24,9) NOT NULL DEFAULT 1,
  canonical_offset DECIMAL(24,9) NOT NULL DEFAULT 0,
  display_factor DECIMAL(24,9) NOT NULL DEFAULT 1,
  display_unit VARCHAR(32) NULL,
  required TINYINT NOT NULL DEFAULT 1,
  sort INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_model_point_binding (model_version_id, point_code),
  UNIQUE KEY uk_model_field_binding (model_version_id, protocol_field_id),
  CONSTRAINT fk_binding_model_version FOREIGN KEY (model_version_id) REFERENCES dev_device_model_version(id) ON DELETE CASCADE,
  CONSTRAINT fk_binding_protocol_field FOREIGN KEY (protocol_field_id) REFERENCES dev_protocol_field(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Product business point to vendor protocol field binding';

CREATE TABLE dev_protocol_command (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  protocol_version_id BIGINT UNSIGNED NOT NULL,
  command_code VARCHAR(80) NOT NULL,
  command_name VARCHAR(120) NOT NULL,
  function_code INT NOT NULL,
  register_address INT NOT NULL,
  encode_type VARCHAR(24) NOT NULL DEFAULT 'FIXED',
  value_type VARCHAR(24) NOT NULL DEFAULT 'UINT16',
  fixed_value BIGINT NULL,
  parameter_json JSON NULL,
  verify_field_id BIGINT UNSIGNED NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  sort INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_protocol_command (protocol_version_id, command_code),
  CONSTRAINT fk_protocol_command_version FOREIGN KEY (protocol_version_id) REFERENCES dev_protocol_profile_version(id) ON DELETE CASCADE,
  CONSTRAINT fk_protocol_command_verify FOREIGN KEY (verify_field_id) REFERENCES dev_protocol_field(id),
  CONSTRAINT chk_protocol_command_fc CHECK (function_code=6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Whitelisted fieldbus write commands';

-- Migrate every actually configured edge model. Legacy JSON mappings are intentionally not
-- migrated: northbound payloads are already normalized points and Data consumes them directly.
INSERT INTO dev_protocol_profile(profile_code,profile_name,transport_type,description)
SELECT CONCAT('MIGRATED_MODEL_', p.model_version_id), CONCAT(MAX(m.model_name),' 现场协议'), 'MODBUS_RTU',
       'Migrated from the former model point table'
FROM dev_thing_model_point p
JOIN dev_device_model_version v ON v.id=p.model_version_id
JOIN dev_device_model m ON m.id=v.model_id
GROUP BY p.model_version_id;

INSERT INTO dev_protocol_profile_version(profile_id,version_no,version_name,status,address_base,remark,published_time)
SELECT pp.id,1,'V1','PUBLISHED','PDU_ZERO_BASED','Migrated configuration',NOW()
FROM dev_protocol_profile pp WHERE pp.profile_code LIKE 'MIGRATED_MODEL_%';

UPDATE dev_device_model_version v
JOIN dev_protocol_profile pp ON pp.profile_code=CONCAT('MIGRATED_MODEL_',v.id)
JOIN dev_protocol_profile_version pv ON pv.profile_id=pp.id AND pv.version_no=1
SET v.protocol_profile_version_id=pv.id;

UPDATE dev_device_type t
JOIN dev_device_model_version v ON v.device_type_id=t.id
JOIN dev_protocol_profile pp ON pp.profile_code=CONCAT('MIGRATED_MODEL_',v.id)
SET t.protocol_type='MODBUS_RTU';

INSERT INTO dev_protocol_read_block(protocol_version_id,block_code,block_name,function_code,start_address,register_count,sort)
SELECT pv.id,CONCAT('READ_',p.point_code),p.point_name,p.function_code,p.register_address,p.register_length,p.sort
FROM dev_thing_model_point p
JOIN dev_protocol_profile pp ON pp.profile_code=CONCAT('MIGRATED_MODEL_',p.model_version_id)
JOIN dev_protocol_profile_version pv ON pv.profile_id=pp.id AND pv.version_no=1;

INSERT INTO dev_protocol_field(protocol_version_id,read_block_id,field_code,field_name,document_address,
  register_offset,register_length,value_type,byte_order,decode_factor,decode_offset,raw_unit,required,sort)
SELECT pv.id,rb.id,p.point_code,p.point_name,CONCAT('0x',LPAD(HEX(p.register_address),4,'0')),
       0,p.register_length,
       CASE UPPER(p.value_type) WHEN 'U16' THEN 'UINT16' WHEN 'I16' THEN 'INT16'
         WHEN 'U32' THEN 'UINT32' WHEN 'I32' THEN 'INT32' WHEN 'F32' THEN 'FLOAT32'
         WHEN 'F64' THEN 'FLOAT64' ELSE UPPER(p.value_type) END,
       p.byte_order,p.scale_factor,p.offset_value,p.unit,p.required,p.sort
FROM dev_thing_model_point p
JOIN dev_protocol_profile pp ON pp.profile_code=CONCAT('MIGRATED_MODEL_',p.model_version_id)
JOIN dev_protocol_profile_version pv ON pv.profile_id=pp.id AND pv.version_no=1
JOIN dev_protocol_read_block rb ON rb.protocol_version_id=pv.id AND rb.block_code=CONCAT('READ_',p.point_code);

INSERT INTO dev_model_point_binding(model_version_id,point_code,protocol_field_id,sort)
SELECT p.model_version_id,p.standard_point_code,pf.id,p.sort
FROM dev_thing_model_point p
JOIN dev_protocol_profile pp ON pp.profile_code=CONCAT('MIGRATED_MODEL_',p.model_version_id)
JOIN dev_protocol_profile_version pv ON pv.profile_id=pp.id AND pv.version_no=1
JOIN dev_protocol_field pf ON pf.protocol_version_id=pv.id AND pf.field_code=p.point_code;

-- Standard 16-loop intelligent lighting controller from the supplied 2026 workbook.
INSERT INTO dev_protocol_profile(profile_code,profile_name,manufacturer,transport_type,description)
VALUES('STD_LIGHTING_16CH_2026','16路智能照明控制器标准协议','通用','MODBUS_RTU',
       'FC03 读取 0x007A 输出状态；FC06 写 0x5050/0xA0A0 控制回路');
SET @lighting_profile_id=LAST_INSERT_ID();
INSERT INTO dev_protocol_profile_version(profile_id,version_no,version_name,status,address_base,source_file_name,remark,published_time)
VALUES(@lighting_profile_id,1,'2026标准版','PUBLISHED','PDU_ZERO_BASED','智能照明协议-标准Modbus+CAN+485协议2026.xlsx2.xlsx',
       '从站地址 1-247；工作簿中的 007b 注释与报文 0x007A 冲突，以完整 RTU 报文和寄存器表 0x007A 为准',NOW());
SET @lighting_version_id=LAST_INSERT_ID();
INSERT INTO dev_protocol_read_block(protocol_version_id,block_code,block_name,function_code,start_address,register_count,poll_mode,sort)
VALUES(@lighting_version_id,'OUTPUT_STATUS','16路输出状态',3,122,1,'CYCLIC',10),
      (@lighting_version_id,'DEVICE_MODEL','设备型号和回路数',3,123,1,'ON_DEMAND',20),
      (@lighting_version_id,'CLOCK','设备时钟',3,124,4,'ON_DEMAND',30);
SET @status_block_id=(SELECT id FROM dev_protocol_read_block WHERE protocol_version_id=@lighting_version_id AND block_code='OUTPUT_STATUS');
INSERT INTO dev_protocol_field(protocol_version_id,read_block_id,field_code,field_name,document_address,register_offset,
  register_length,value_type,byte_order,bit_offset,bit_length,decode_factor,raw_unit,access_mode,sort)
SELECT @lighting_version_id,@status_block_id,CONCAT('loop_',n,'_status'),CONCAT('回路',n,'状态'),'0x007A',0,
       1,'BOOLEAN','AB',16-n,1,1,NULL,'R',n*10
FROM (
 SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8
 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15 UNION ALL SELECT 16
) loops;
INSERT INTO dev_protocol_command(protocol_version_id,command_code,command_name,function_code,register_address,encode_type,value_type,fixed_value,sort)
SELECT @lighting_version_id,CONCAT('LOOP_',n,'_ON'),CONCAT('开启回路',n),6,20560,'FIXED','UINT16',POW(2,16-n),n*20-1
FROM (SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15 UNION ALL SELECT 16) loops
UNION ALL
SELECT @lighting_version_id,CONCAT('LOOP_',n,'_OFF'),CONCAT('关闭回路',n),6,41120,'FIXED','UINT16',POW(2,16-n),n*20
FROM (SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15 UNION ALL SELECT 16) loops;

-- Create the product model represented by the supplied protocol. A physical device row is not
-- fabricated because gateway, channel and slave id are installation-specific facts.
INSERT INTO dev_device_category(parent_id,category_code,category_name,description,sort,enabled)
VALUES(0,'SMART_LIGHTING','智能照明','Modbus 智能照明控制设备',90,1)
ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id),enabled=1;
SET @lighting_category_id=LAST_INSERT_ID();
INSERT INTO dev_brand(brand_code,brand_name,description,enabled)
VALUES('GENERIC_LIGHTING','通用照明','厂家名称待现场确认',1)
ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id),enabled=1;
SET @lighting_brand_id=LAST_INSERT_ID();
INSERT IGNORE INTO dev_device_category_brand(category_id,brand_id)
VALUES(@lighting_category_id,@lighting_brand_id);
INSERT INTO dev_product_series(category_id,brand_id,series_code,series_name,description,enabled)
VALUES(@lighting_category_id,@lighting_brand_id,'MODBUS_LIGHTING_16CH','Modbus 16路智能照明','2026 标准协议',1)
ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id),enabled=1;
SET @lighting_series_id=LAST_INSERT_ID();
INSERT INTO dev_device_model(series_id,model_code,model_name,description,status)
VALUES(@lighting_series_id,'STD_LIGHTING_16CH','16路智能照明控制器','依据上传的 2026 标准 Modbus 协议建立','PUBLISHED')
ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id),status='PUBLISHED';
SET @lighting_model_id=LAST_INSERT_ID();
INSERT INTO dev_device_type(type_code,type_name,protocol_type,description,enabled)
VALUES('SMART_LIGHTING_16CH_V1','16路智能照明控制器 V1','MODBUS_RTU','产品目录发布版本',1)
ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id),protocol_type='MODBUS_RTU',enabled=1;
SET @lighting_device_type_id=LAST_INSERT_ID();
INSERT INTO dev_device_model_version(model_id,version_no,version_name,device_type_id,protocol_profile_version_id,
  status,collect_interval_seconds,quality_threshold_pct,published_time,remark)
VALUES(@lighting_model_id,1,'V1',@lighting_device_type_id,@lighting_version_id,'PUBLISHED',60,95,NOW(),'协议文件初始版本')
ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id),protocol_profile_version_id=@lighting_version_id,status='PUBLISHED';
SET @lighting_model_version_id=LAST_INSERT_ID();
UPDATE dev_device_model SET current_published_version_id=@lighting_model_version_id,status='PUBLISHED'
WHERE id=@lighting_model_id;

INSERT INTO dev_standard_point_group(parent_id,group_code,group_name,sort,enabled)
VALUES(0,'LIGHTING_STATUS','照明回路状态',90,1)
ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id),enabled=1;
SET @lighting_point_group_id=LAST_INSERT_ID();
INSERT INTO dev_standard_point(group_id,category_id,point_code,point_name,data_type,business_role,description,
  protocol_type,value_mode,realtime_key,enabled)
SELECT @lighting_point_group_id,@lighting_category_id,CONCAT('LOOP_',n,'_STATUS'),CONCAT('回路',n,'状态'),
       'BOOLEAN','STATUS',CONCAT('第',n,'路开关状态'),'MODBUS_RTU','REALTIME',CONCAT('loop_',n,'_status'),1
FROM (SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15 UNION ALL SELECT 16) loops
ON DUPLICATE KEY UPDATE point_name=VALUES(point_name),enabled=1;

INSERT INTO dev_point_definition(device_type_id,standard_point_id,point_code,point_name,data_type,unit,
  precision_scale,business_role,energy_dimension,billable,stat_enabled,sort,enabled)
SELECT @lighting_device_type_id,sp.id,sp.point_code,sp.point_name,'BOOLEAN',NULL,0,'STATUS','OTHER',0,1,n*10,1
FROM (SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15 UNION ALL SELECT 16) loops
JOIN dev_standard_point sp ON sp.point_code=CONCAT('LOOP_',n,'_STATUS')
ON DUPLICATE KEY UPDATE point_name=VALUES(point_name),enabled=1;

INSERT INTO dev_model_point_binding(model_version_id,point_code,protocol_field_id,canonical_factor,
  canonical_offset,display_factor,display_unit,required,sort)
SELECT @lighting_model_version_id,CONCAT('LOOP_',n,'_STATUS'),f.id,1,0,1,NULL,1,n*10
FROM (SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15 UNION ALL SELECT 16) loops
JOIN dev_protocol_field f ON f.protocol_version_id=@lighting_version_id AND f.field_code=CONCAT('loop_',n,'_status')
ON DUPLICATE KEY UPDATE protocol_field_id=VALUES(protocol_field_id),sort=VALUES(sort);

-- Superseded configuration sources. An external SQL backup is required before production rollout.
DROP TABLE dev_thing_model_point;
DROP TABLE dev_point_mapping;
