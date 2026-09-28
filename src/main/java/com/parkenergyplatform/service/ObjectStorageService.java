package com.parkenergyplatform.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Date;

import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.config.ParkCosProperties;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.DeleteObjectRequest;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Centralizes object-storage access so business services do not duplicate COS details. */
@Service
public class ObjectStorageService {
    private final ObjectProvider<COSClient> clientProvider;
    private final ParkCosProperties properties;

    public ObjectStorageService(ObjectProvider<COSClient> clientProvider, ParkCosProperties properties) {
        this.clientProvider = clientProvider;
        this.properties = properties;
    }

    public String bucket() {
        return properties.bucket();
    }

    public String objectPrefix() {
        String prefix = properties.objectPrefix();
        if (prefix == null || prefix.isBlank()) return "device-models/";
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    public URL presignedUrl(String objectKey, HttpMethodName method) {
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket(), objectKey, method);
        request.setExpiration(new Date(System.currentTimeMillis()
                + Math.max(60L, properties.urlExpireSeconds()) * 1000L));
        return client().generatePresignedUrl(request);
    }

    public String previewUrlOrNull(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return null;
        try {
            return presignedUrl(objectKey, HttpMethodName.GET).toString();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public void put(String objectKey, InputStream input, long contentLength, String contentType) throws IOException {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(contentLength);
        metadata.setContentType(contentType);
        client().putObject(new PutObjectRequest(bucket(), objectKey, input, metadata));
    }

    public void deleteQuietly(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return;
        try {
            client().deleteObject(new DeleteObjectRequest(bucket(), objectKey));
        } catch (RuntimeException ignored) {
            // A stale object must not make a successful metadata update fail.
        }
    }

    private COSClient client() {
        COSClient client = clientProvider.getIfAvailable();
        if (client == null) throw new BusinessException("COS 未启用，请先配置 park.cos.enabled=true 和密钥");
        return client;
    }
}
