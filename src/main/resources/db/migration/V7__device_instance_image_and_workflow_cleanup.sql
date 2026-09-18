UPDATE dev_device
SET edge_channel_id=NULL
WHERE gateway_id IS NULL AND edge_channel_id IS NOT NULL;
