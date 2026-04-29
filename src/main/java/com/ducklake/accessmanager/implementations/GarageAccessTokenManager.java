package com.ducklake.accessmanager.implementations;

import com.ducklake.accessmanager.interfaces.ObjectStoreAccessTokenManager;
import com.ducklake.accessmanager.model.AccessKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GarageAccessTokenManager implements ObjectStoreAccessTokenManager {

    private final RestTemplate restTemplate;
    private final String adminApiUrl;
    private final String adminToken;
    private final String garageEndpoint;

    public GarageAccessTokenManager(
        @Value("${garage.admin.url}") String adminApiUrl,
        @Value("${garage.admin.token}") String adminToken,
        @Value("${garage.s3.endpoint}") String garageEndpoint
    ) {
        this.restTemplate = new RestTemplate();
        this.adminApiUrl = adminApiUrl;
        this.adminToken = adminToken;
        this.garageEndpoint = garageEndpoint;
    }

    @Override
    public AccessKey createReadOnlyKey(String bucketName, String keyName) {
        // TODO: POST /v1/key to create key, then POST /v1/bucket/allow-key to grant read-only
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public AccessKey createReadWriteKey(String bucketName, String keyName) {
        // TODO: POST /v1/key to create key, then POST /v1/bucket/allow-key to grant read+write
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public void deleteKey(String keyId) {
        // TODO: DELETE /v1/key?id={keyId}
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public List<AccessKey> listKeys() {
        // TODO: GET /v1/key?list
        throw new UnsupportedOperationException("Not implemented yet");
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
