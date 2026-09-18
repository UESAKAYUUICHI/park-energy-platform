DROP TABLE IF EXISTS dev_protocol_import_job;

UPDATE dev_protocol_field SET value_type='UINT16' WHERE value_type='U16';
UPDATE dev_protocol_field SET value_type='INT16' WHERE value_type='I16';
UPDATE dev_protocol_field SET value_type='UINT32' WHERE value_type='U32';
UPDATE dev_protocol_field SET value_type='INT32' WHERE value_type='I32';
UPDATE dev_protocol_field SET value_type='FLOAT32' WHERE value_type='F32';
UPDATE dev_protocol_field SET value_type='FLOAT64' WHERE value_type='F64';

ALTER TABLE dev_protocol_read_block DROP CHECK chk_read_block_fc;
ALTER TABLE dev_protocol_read_block ADD CONSTRAINT chk_read_block_fc CHECK (function_code IN (3,4));
ALTER TABLE dev_protocol_command DROP CHECK chk_protocol_command_fc;
ALTER TABLE dev_protocol_command ADD CONSTRAINT chk_protocol_command_fc CHECK (function_code=6);
