package com.parkenergyplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "park.platform")
public record ParkPlatformProperties(String dataBaseUrl, String accessBaseUrl) {
}
