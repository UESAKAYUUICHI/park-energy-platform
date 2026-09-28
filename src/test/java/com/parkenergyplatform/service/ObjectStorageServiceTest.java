package com.parkenergyplatform.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URL;
import java.nio.charset.StandardCharsets;

import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.config.ParkCosProperties;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.model.PutObjectRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ObjectStorageServiceTest {
    @Test
    void normalizesObjectPrefixAndGeneratesPreviewUrl() throws Exception {
        COSClient client = mock(COSClient.class);
        ObjectProvider<COSClient> provider = provider(client);
        when(client.generatePresignedUrl(any(GeneratePresignedUrlRequest.class)))
                .thenReturn(new URL("https://example.test/signed"));
        ObjectStorageService service = new ObjectStorageService(provider,
                new ParkCosProperties(true, "bucket", "region", "id", "key", "models", 600));

        assertEquals("models/", service.objectPrefix());
        assertEquals("https://example.test/signed", service.previewUrlOrNull("models/1.jpg"));
        verify(client).generatePresignedUrl(any(GeneratePresignedUrlRequest.class));
    }

    @Test
    void returnsNullForBlankPreviewKeyAndFailsClearlyWhenCosIsUnavailable() {
        ObjectStorageService service = new ObjectStorageService(provider(null),
                new ParkCosProperties(true, "bucket", "region", "id", "key", "models/", 600));

        assertNull(service.previewUrlOrNull(""));
        assertThrows(BusinessException.class, () -> service.presignedUrl("models/1.jpg", HttpMethodName.GET));
    }

    @Test
    void uploadsWithMetadataAndDeletesQuietly() throws Exception {
        COSClient client = mock(COSClient.class);
        ObjectStorageService service = new ObjectStorageService(provider(client),
                new ParkCosProperties(true, "bucket", "region", "id", "key", "models/", 600));

        service.put("models/1.jpg", new java.io.ByteArrayInputStream("image".getBytes(StandardCharsets.UTF_8)),
                5, "image/jpeg");
        service.deleteQuietly("models/1.jpg");

        verify(client).putObject(any(PutObjectRequest.class));
        verify(client).deleteObject(any());
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<COSClient> provider(COSClient client) {
        ObjectProvider<COSClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }
}
