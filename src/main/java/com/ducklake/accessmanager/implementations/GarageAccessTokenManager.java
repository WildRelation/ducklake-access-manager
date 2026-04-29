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

/**
 * Implementerar {@link ObjectStoreAccessTokenManager} mot Garages Admin API (port 3903).
 *
 * Används i produktion på cbhcloud. Kommunicerar med den interna Admin API:n
 * via HTTP och autentiserar med en Bearer-token (GARAGE_ADMIN_TOKEN).
 *
 * Implementationsordning:
 *   Steg 1 – createReadOnlyKey:  POST /v1/key → POST /v1/bucket/allow-key (read)
 *   Steg 2 – createReadWriteKey: POST /v1/key → POST /v1/bucket/allow-key (read+write)
 *   Steg 3 – deleteKey:          DELETE /v1/key?id={keyId}
 *   Steg 4 – listKeys:           GET /v1/key?list
 *
 * API-dokumentation: https://garagehq.deuxfleurs.fr/api/garage-admin-v0.html#tag/Key
 */
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

    /**
     * Steg 1: Skapa en read-only nyckel för en specifik bucket.
     *
     * Anrop som ska göras mot Garage Admin API:
     *   1. POST {adminApiUrl}/v1/key
     *      Body: { "name": "{keyName}" }
     *      Svar: { "accessKeyId": "...", "secretAccessKey": "..." }
     *
     *   2. POST {adminApiUrl}/v1/bucket/allow-key
     *      Body: { "bucketId": "...", "accessKeyId": "...", "permissions": { "read": true, "write": false, "owner": false } }
     */
    @Override
    public AccessKey createReadOnlyKey(String bucketName, String keyName) {
        // TODO: implementera enligt steg ovan
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Steg 2: Skapa en read/write-nyckel för en specifik bucket.
     *
     * Samma flöde som createReadOnlyKey men med permissions:
     *   { "read": true, "write": true, "owner": false }
     */
    @Override
    public AccessKey createReadWriteKey(String bucketName, String keyName) {
        // TODO: implementera enligt steg ovan
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Steg 3: Ta bort en nyckel permanent.
     *
     * Anrop: DELETE {adminApiUrl}/v1/key?id={keyId}
     * Autentisering: Bearer-token i Authorization-headern
     */
    @Override
    public void deleteKey(String keyId) {
        // TODO: implementera enligt steg ovan
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Steg 4: Lista alla nycklar.
     *
     * Anrop: GET {adminApiUrl}/v1/key?list
     * Svar: lista av { "id": "...", "name": "..." }
     * Hämta sedan detaljer per nyckel via GET /v1/key?id={keyId}
     */
    @Override
    public List<AccessKey> listKeys() {
        // TODO: implementera enligt steg ovan
        throw new UnsupportedOperationException("Not implemented yet");
    }

    // Skapar HTTP-headers med Bearer-autentisering för alla anrop mot Admin API:n
    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
