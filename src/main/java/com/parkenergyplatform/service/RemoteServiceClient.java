package com.parkenergyplatform.service;

import java.util.Map;

import com.parkenergyplatform.config.ParkPlatformProperties;
import com.parkenergyplatform.common.BusinessException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class RemoteServiceClient {
    private final RestClient dataClient;
    private final RestClient accessClient;

    public RemoteServiceClient(ParkPlatformProperties properties) {
        this.dataClient = client(properties.dataBaseUrl());
        this.accessClient = client(properties.accessBaseUrl());
    }

    private RestClient client(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(20_000);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public Map<String, Object> getData(String uri) {
        return exchange(dataClient.get().uri(uri), "data", uri);
    }

    public Object getDataPayload(String uri) {
        Map<String, Object> response = getData(uri);
        return dataPayload(response);
    }

    /** Uses the URI builder so query values are encoded exactly once. */
    public Object getDataPayload(String uri, Map<String, String> params) {
        Map<String, Object> response = getData(uri, params);
        return dataPayload(response);
    }

    private Object dataPayload(Map<String, Object> response) {
        if (Boolean.FALSE.equals(response.get("success"))) {
            throw new BusinessException(503,
                    String.valueOf(response.getOrDefault("message", "data service failed")));
        }
        return response.get("data");
    }

    public Map<String, Object> getData(String uri, Map<String, String> params) {
        return exchange(dataClient.get().uri(builder -> {
            var query = builder.path(uri);
            params.forEach((key, value) -> query.queryParam(key, value));
            return query.build();
        }), "data", uri);
    }

    public Map<String, Object> postData(String uri, Object body) {
        return exchange(dataClient.post().uri(uri).body(body), "data", uri);
    }

    public Map<String, Object> postData(String uri, Map<String, String> params) {
        return exchange(dataClient.post().uri(builder -> {
            var query = builder.path(uri);
            params.forEach((key, value) -> query.queryParam(key, value));
            return query.build();
        }), "data", uri);
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
            String detail = ex.getMessage();
            if (detail == null || detail.isBlank()) {
                detail = ex.getClass().getSimpleName();
            }
            throw new BusinessException(503, serviceName + " 服务调用失败: " + uri + " (" + detail + ")");
        }
    }
}
