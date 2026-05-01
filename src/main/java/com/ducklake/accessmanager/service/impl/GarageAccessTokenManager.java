package com.ducklake.accessmanager.service.impl;

import com.ducklake.accessmanager.infrastructure.garage.GarageBucketResponse;
import com.ducklake.accessmanager.infrastructure.garage.GarageKeyListItem;
import com.ducklake.accessmanager.infrastructure.garage.GarageKeyResponse;
import com.ducklake.accessmanager.service.ObjectStoreAccessTokenManager;
import com.ducklake.accessmanager.model.AccessKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Implements {@link ObjectStoreAccessTokenManager} against the Garage Admin API v2.
 *
 * Used in production on cbhcloud. Communicates with the Admin API via the nginx proxy
 * in ducklake-garage (port 3900), which forwards /v2/* to the internal port 3903.
 * Authenticates with a Bearer token (GARAGE_ADMIN_TOKEN).
 *
 * Key creation flow:
 *   1. POST /v2/CreateKey       → creates the key, returns accessKeyId + secretAccessKey
 *   2. GET  /v2/GetBucketInfo   → resolves the bucket ID from its name (globalAlias)
 *   3. POST /v2/AllowBucketKey  → grants the key access to the bucket with the given permissions
 *
 * API reference: https://garagehq.deuxfleurs.fr/api/garage-admin-v2.html
 */
@Service
public class GarageAccessTokenManager implements ObjectStoreAccessTokenManager {

    private final RestTemplate restTemplate;
    private final String adminApiUrl;
    private final String adminToken;
    private final String garageEndpoint;
    private final String garageRegion;

    public GarageAccessTokenManager(
        @Value("${garage.admin.url}") String adminApiUrl,
        @Value("${garage.admin.token}") String adminToken,
        @Value("${garage.s3.endpoint}") String garageEndpoint,
        @Value("${garage.s3.region}") String garageRegion
    ) {
        this.restTemplate = new RestTemplate();
        this.adminApiUrl = adminApiUrl;
        this.adminToken = adminToken;
        this.garageEndpoint = garageEndpoint;
        this.garageRegion = garageRegion;
    }

    /**
     * Creates a read-only key for a specific bucket.
     * Steps: CreateKey → GetBucketInfo → AllowBucketKey (read: true, write: false)
     */
    @Override
    public AccessKey createReadOnlyKey(String bucketName, String keyName) {
        return createKey(bucketName, keyName, false);
    }

    /**
     * Creates a read/write key for a specific bucket.
     * Steps: CreateKey → GetBucketInfo → AllowBucketKey (read: true, write: true)
     */
    @Override
    public AccessKey createReadWriteKey(String bucketName, String keyName) {
        return createKey(bucketName, keyName, true);
    }

    /**
     * Permanently deletes a key.
     * Uses POST /v2/DeleteKey — the Garage Admin API v2 is RPC-style (POST for all operations).
     */
    @Override
    public void deleteKey(String keyId) {
        restTemplate.postForObject(
            adminApiUrl + "/v2/DeleteKey",
            new HttpEntity<>(Map.of("id", keyId), authHeaders()),
            Void.class
        );
    }

    /**
     * Lists all keys registered in Garage.
     * Uses GET /v2/ListKeys — returns id and name per key (secretAccessKey is not available after creation).
     */
    @Override
    public List<AccessKey> listKeys() {
        ResponseEntity<GarageKeyListItem[]> response = restTemplate.exchange(
            adminApiUrl + "/v2/ListKeys",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders()),
            GarageKeyListItem[].class
        );

        GarageKeyListItem[] body = response.getBody();
        if (body == null) return List.of();
        return Arrays.stream(body)
            .map(this::parseKeyItem)
            .toList();
    }

    // --- Private helpers ---

    // Shared logic for creating a key with optional write permission
    private AccessKey createKey(String bucketName, String keyName, boolean allowWrite) {
        GarageKeyResponse created = postCreateKey(keyName);
        String bucketId = getBucketId(bucketName);
        grantBucketPermission(bucketId, created.accessKeyId(), allowWrite);

        // Extract pgUsername from key name format "key-{bucket}|{pgUsername}"
        String pgUsername = keyName.contains("|") ? keyName.split("\\|", 2)[1] : null;
        String permission = allowWrite ? "readwrite" : "read";
        return new AccessKey(created.accessKeyId(), created.secretAccessKey(), bucketName, permission, garageEndpoint, garageRegion, pgUsername);
    }

    // Parses a ListKeys item, extracting pgUsername from the key name if embedded
    private AccessKey parseKeyItem(GarageKeyListItem item) {
        String name = item.name() != null ? item.name() : "";
        String pgUsername = null;
        if (name.contains("|")) {
            String[] parts = name.split("\\|", 2);
            name = parts[0];
            pgUsername = parts[1];
        }
        return new AccessKey(item.id(), null, name, null, garageEndpoint, garageRegion, pgUsername);
    }

    // Step 1: POST /v2/CreateKey – creates the key and returns accessKeyId + secretAccessKey
    private GarageKeyResponse postCreateKey(String keyName) {
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(
            Map.of("name", keyName),
            authHeaders()
        );
        GarageKeyResponse created = restTemplate.postForObject(adminApiUrl + "/v2/CreateKey", request, GarageKeyResponse.class);
        if (created == null) throw new IllegalStateException("Garage CreateKey returned null");
        return created;
    }

    // Step 2: GET /v2/GetBucketInfo?globalAlias={bucketName} – resolves the bucket ID
    private String getBucketId(String bucketName) {
        ResponseEntity<GarageBucketResponse> response = restTemplate.exchange(
            adminApiUrl + "/v2/GetBucketInfo?globalAlias=" + bucketName,
            HttpMethod.GET,
            new HttpEntity<>(authHeaders()),
            GarageBucketResponse.class
        );
        GarageBucketResponse bucket = response.getBody();
        if (bucket == null) throw new IllegalStateException("Garage GetBucketInfo returned null for bucket: " + bucketName);
        return bucket.id();
    }

    // Step 3: POST /v2/AllowBucketKey – grants the key access to the bucket with the given permissions
    private void grantBucketPermission(String bucketId, String accessKeyId, boolean allowWrite) {
        Map<String, Object> body = Map.of(
            "bucketId", bucketId,
            "accessKeyId", accessKeyId,
            "permissions", Map.of(
                "read", true,
                "write", allowWrite,
                "owner", false
            )
        );
        restTemplate.postForObject(
            adminApiUrl + "/v2/AllowBucketKey",
            new HttpEntity<>(body, authHeaders()),
            Object.class
        );
    }

    // Builds HTTP headers for Admin API calls.
    // Bearer token is only added if GARAGE_ADMIN_TOKEN is configured.
    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        if (adminToken != null && !adminToken.isBlank()) {
            headers.setBearerAuth(adminToken);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
