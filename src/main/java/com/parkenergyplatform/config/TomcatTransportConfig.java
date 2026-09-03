package com.parkenergyplatform.config;

import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Select NIO2 because the default NIO poller cannot open a loopback selector on this host. */
@Configuration
public class TomcatTransportConfig {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> nio2TomcatConnector() {
        return factory -> factory.setProtocol("org.apache.coyote.http11.Http11Nio2Protocol");
    }
}
