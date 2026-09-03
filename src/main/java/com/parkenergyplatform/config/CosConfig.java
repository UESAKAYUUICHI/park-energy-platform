package com.parkenergyplatform.config;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.region.Region;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ParkCosProperties.class)
public class CosConfig {
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "park.cos", name = "enabled", havingValue = "true")
    COSClient cosClient(ParkCosProperties properties) {
        if (properties.secretId() == null || properties.secretId().isBlank()
                || properties.secretKey() == null || properties.secretKey().isBlank()
                || properties.bucket() == null || properties.bucket().isBlank()
                || properties.region() == null || properties.region().isBlank()) {
            throw new IllegalStateException("park.cos enabled but bucket, region, secretId or secretKey is missing");
        }
        return new COSClient(
                new BasicCOSCredentials(properties.secretId(), properties.secretKey()),
                new ClientConfig(new Region(properties.region()))
        );
    }
}
