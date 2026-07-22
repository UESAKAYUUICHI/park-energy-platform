package com.parkenergyplatform.service;

import java.util.Map;

import com.parkenergyplatform.config.ParkPlatformProperties;
import com.parkenergyplatform.common.BusinessException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class RemoteServiceClient {
    private final RestClient dataClient;
    private final RestClient accessClient;

    public RemoteServiceClient(ParkPlatformProperties properties) {
        this.dataClient = RestClient.builder().baseUrl(properties.dataBaseUrl()).build();
        this.accessClient = RestClient.builder().baseUrl(properties.accessBaseUrl()).build();
    }

    public Map<String, Object> getData(String uri) {
        return exchange(dataClient.get().uri(uri), "data", uri);
    }

    public Map<String, Object> postData(String uri, Object body) {
        return exchange(dataClient.post().uri(uri).body(body), "data", uri);
    }

    public Map<String, Object> getAccess(String uri) {
        return exchange(accessClient.get().uri(uri), "access", uri);
    }

    public Map<String, Object> postAccess(String uri, Object body) {
        return exchange(accessClient.post().uri(uri).body(body), "access", uri);
    }

    private ParameterizedTypeReference<Map<String, Object>> mapType() {
        return new ParameterizedTypeReference<>() {
        };
    }

    private Map<String, Object> exchange(RestClient.RequestHeadersSpec<?> request, String serviceName, String uri) {
        try {
            return request.retrieve().body(mapType());
        } catch (RestClientException ex) {
            throw new BusinessException(503, serviceName + " 服务调用失败: " + uri);
        }
    }
}
