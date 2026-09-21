INSERT INTO dev_protocol_command(
  protocol_version_id,command_code,command_name,function_code,register_address,
  encode_type,value_type,fixed_value,enabled,sort
)
SELECT DISTINCT v.protocol_profile_version_id,'BREAKER_CLOSE','合闸',6,8704,
       'FIXED','UINT16',1,1,900
FROM dev_device_model_version v
JOIN dev_device_model m ON m.id=v.model_id
WHERE v.protocol_profile_version_id IS NOT NULL
  AND (m.model_code='PD666-3S3' OR m.model_name LIKE '%PD666-3S3%')
ON DUPLICATE KEY UPDATE
  command_name=VALUES(command_name),
  function_code=VALUES(function_code),
  register_address=VALUES(register_address),
  encode_type=VALUES(encode_type),
  value_type=VALUES(value_type),
  fixed_value=VALUES(fixed_value),
  enabled=VALUES(enabled),
  sort=VALUES(sort);

INSERT INTO dev_protocol_command(
  protocol_version_id,command_code,command_name,function_code,register_address,
  encode_type,value_type,fixed_value,enabled,sort
)
SELECT DISTINCT v.protocol_profile_version_id,'BREAKER_OPEN','分闸',6,8704,
       'FIXED','UINT16',0,1,910
FROM dev_device_model_version v
JOIN dev_device_model m ON m.id=v.model_id
WHERE v.protocol_profile_version_id IS NOT NULL
  AND (m.model_code='PD666-3S3' OR m.model_name LIKE '%PD666-3S3%')
ON DUPLICATE KEY UPDATE
  command_name=VALUES(command_name),
  function_code=VALUES(function_code),
  register_address=VALUES(register_address),
  encode_type=VALUES(encode_type),
  value_type=VALUES(value_type),
  fixed_value=VALUES(fixed_value),
  enabled=VALUES(enabled),
  sort=VALUES(sort);
