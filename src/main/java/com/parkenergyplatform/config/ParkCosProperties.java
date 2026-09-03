package com.parkenergyplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "park.cos")
public record ParkCosProperties(
        boolean enabled,
        String bucket,
        String region,
        String secretId,
        String secretKey,
        String objectPrefix,
        long urlExpireSeconds
) {
}
