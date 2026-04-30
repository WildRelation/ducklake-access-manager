package com.ducklake.accessmanager.service.impl;

import com.ducklake.accessmanager.infrastructure.garage.GarageBucketResponse;
import com.ducklake.accessmanager.infrastructure.garage.GarageKeyListItem;
import com.ducklake.accessmanager.infrastructure.garage.GarageKeyResponse;
import com.ducklake.accessmanager.service.ObjectStoreAccessTokenManager;
import com.ducklake.accessmanager.model.AccessKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Implementerar {@link ObjectStoreAccessTokenManager} mot Garages Admin API v2 (port 3903).
 *
 * Används i produktion på cbhcloud. Kommunicerar med den interna Admin API:n
 * via HTTP och autentiserar med en Bearer-token (GARAGE_ADMIN_TOKEN).
 *
 * Flöde för att skapa en nyckel:
 *   1. POST /v2/CreateKey          → skapar nyckeln, returnerar accessKeyId + secretAccessKey
 *   2. GET  /v2/GetBucketInfo      → hämtar bucket-ID utifrån bucket-namn (globalAlias)
 *   3. POST /v2/AllowBucketKey     → kopplar nyckeln till bucketen med rätt behörighet
 *
 * API-dokumentation: https://garagehq.deuxfleurs.fr/api/garage-admin-v2.html
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
     * Skapar en read-only nyckel för en specifik bucket.
     * Steg: CreateKey → GetBucketInfo → AllowBucketKey (read: true, write: false)
     */
    @Override
    public AccessKey createReadOnlyKey(String bucketName, String keyName) {
        return createKey(bucketName, keyName, false);
    }

    /**
     * Skapar en read/write-nyckel för en specifik bucket.
     * Steg: CreateKey → GetBucketInfo → AllowBucketKey (read: true, write: true)
     */
    @Override
    public AccessKey createReadWriteKey(String bucketName, String keyName) {
        return createKey(bucketName, keyName, true);
    }

    /**
     * Tar bort en nyckel permanent.
     * Anrop: DELETE /v2/DeleteKey?id={keyId}
     */
    @Override
    public void deleteKey(String keyId) {
        restTemplate.exchange(
            adminApiUrl + "/v2/DeleteKey?id=" + keyId,
            HttpMethod.DELETE,
            new HttpEntity<>(authHeaders()),
            Void.class
        );
    }

    /**
     * Listar alla nycklar registrerade i Garage.
     * Anrop: GET /v2/ListKeys
     * Returnerar id + namn per nyckel (secretAccessKey är inte tillgänglig efter skapandet).
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
            .map(item -> new AccessKey(item.id(), null, item.name(), null, garageEndpoint, garageRegion))
            .toList();
    }

    // --- Privata hjälpmetoder ---

    // Gemensam logik för att skapa en nyckel med valfri write-behörighet
    private AccessKey createKey(String bucketName, String keyName, boolean allowWrite) {
        GarageKeyResponse created = postCreateKey(keyName);
        String bucketId = getBucketId(bucketName);
        grantBucketPermission(bucketId, created.accessKeyId(), allowWrite);

        String permission = allowWrite ? "readwrite" : "read";
        return new AccessKey(created.accessKeyId(), created.secretAccessKey(), bucketName, permission, garageEndpoint, garageRegion);
    }

    // Steg 1: POST /v2/CreateKey – skapar nyckeln och returnerar accessKeyId + secretAccessKey
    private GarageKeyResponse postCreateKey(String keyName) {
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(
            Map.of("name", keyName),
            authHeaders()
        );
        GarageKeyResponse created = restTemplate.postForObject(adminApiUrl + "/v2/CreateKey", request, GarageKeyResponse.class);
        if (created == null) throw new IllegalStateException("Garage CreateKey returned null");
        return created;
    }

    // Steg 2: GET /v2/GetBucketInfo?globalAlias={bucketName} – hämtar bucket-ID
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

    // Steg 3: POST /v2/AllowBucketKey – kopplar nyckeln till bucketen med behörigheter
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

    // Skapar HTTP-headers för Admin API-anrop.
    // Bearer-token inkluderas bara om GARAGE_ADMIN_TOKEN är konfigurerat.
    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        if (adminToken != null && !adminToken.isBlank()) {
            headers.setBearerAuth(adminToken);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
