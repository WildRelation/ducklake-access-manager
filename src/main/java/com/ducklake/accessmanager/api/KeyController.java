package com.ducklake.accessmanager.api;

import com.ducklake.accessmanager.model.AccessKey;
import com.ducklake.accessmanager.model.DbCredentials;
import com.ducklake.accessmanager.model.GeneratedCredentials;
import com.ducklake.accessmanager.model.KeyRequest;
import com.ducklake.accessmanager.service.DatabaseAccessTokenManager;
import com.ducklake.accessmanager.service.ObjectStoreAccessTokenManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for key management.
 *
 * Exposes three endpoints:
 *
 *   POST   /api/keys/generate  – Generate new keys (Garage + PostgreSQL) and return a DuckDB script
 *   GET    /api/keys           – List all existing keys
 *   DELETE /api/keys/{keyId}   – Delete a key from both Garage and PostgreSQL
 *
 * The controller delegates all logic to {@link ObjectStoreAccessTokenManager}
 * and {@link DatabaseAccessTokenManager}, keeping itself free from
 * implementation details about Garage or PostgreSQL.
 *
 * TODO before this controller is production-ready:
 *   - Add authentication (who is the caller?)
 *   - Verify that unprivileged users cannot request "readwrite"
 */
@RestController
@RequestMapping("/api/keys")
public class KeyController {

    private final ObjectStoreAccessTokenManager objectStore;
    private final DatabaseAccessTokenManager database;
    private final String postgresPublicHost;

    public KeyController(
        ObjectStoreAccessTokenManager objectStore,
        DatabaseAccessTokenManager database,
        @Value("${ducklake.postgres.public-host}") String postgresPublicHost
    ) {
        this.objectStore = objectStore;
        this.database = database;
        this.postgresPublicHost = postgresPublicHost;
    }

    /**
     * Generates a key pair (S3 + PostgreSQL) and returns a ready-to-use DuckDB script.
     *
     * TODO: this endpoint is unauthenticated — anyone can generate credentials, and "readwrite"
     * is not restricted to privileged users. This will be fixed when authentication is implemented.
     *
     * Request body:
     *   - bucketName: the Garage bucket to grant access to
     *   - permission: "read" (default) or "readwrite" (requires privileged user)
     *
     * Response contains:
     *   - s3Key:         keyId, secretKey, endpoint for Garage
     *   - dbCredentials: username, password, host for PostgreSQL
     *   - duckdbScript:  ready-to-run SQL script for DuckDB
     */
    @PostMapping("/generate")
    public ResponseEntity<GeneratedCredentials> generate(@RequestBody KeyRequest request) {
        if (request.bucketName() == null || !request.bucketName().matches("[a-z0-9][a-z0-9\\-]{1,61}[a-z0-9]")) {
            return ResponseEntity.badRequest().build();
        }
        // TODO: kontrollera användarroll innan "readwrite" tillåts

        // Create PG user first so its username can be embedded in the Garage key name.
        // This allows the server to correlate keys and PG users when listing, without
        // relying on client-side storage.
        DbCredentials dbCreds = switch (request.permission()) {
            case "readwrite" -> database.createReadWriteUser();
            default -> database.createReadOnlyUser();
        };

        String keyName = "key-" + request.bucketName() + "|" + dbCreds.username();
        AccessKey s3Key = switch (request.permission()) {
            case "readwrite" -> objectStore.createReadWriteKey(request.bucketName(), keyName);
            default -> objectStore.createReadOnlyKey(request.bucketName(), keyName);
        };

        String script = buildDuckdbScript(s3Key, dbCreds, request.bucketName(), postgresPublicHost);
        return ResponseEntity.ok(new GeneratedCredentials(s3Key, dbCreds, script));
    }

    /**
     * Returns all keys registered in Garage.
     */
    @GetMapping
    public ResponseEntity<List<AccessKey>> list() {
        return ResponseEntity.ok(objectStore.listKeys());
    }

    /**
     * Deletes a key from both Garage and PostgreSQL.
     *
     * TODO: this endpoint is unauthenticated — any user can delete any key, including ones
     * they did not create. This will be fixed when authentication is implemented.
     *
     * @param keyId      the Garage key ID
     * @param pgUsername the PostgreSQL user to delete alongside the key
     */
    @DeleteMapping("/{keyId}")
    public ResponseEntity<Void> delete(@PathVariable String keyId, @RequestParam(required = false) String pgUsername) {
        objectStore.deleteKey(keyId);
        if (pgUsername != null && !pgUsername.isBlank()) {
            database.deleteUser(pgUsername);
        }
        return ResponseEntity.noContent().build();
    }

    // Builds a ready-to-run DuckDB script with the generated credentials
    private String buildDuckdbScript(AccessKey s3Key, DbCredentials db, String bucketName, String pgHost) {
        return """
            -- Run this script from a deployment on kthcloud.
            -- The hostname '%s' is only reachable within the cbhcloud cluster.

            INSTALL ducklake;
            INSTALL postgres;

            LOAD ducklake;
            LOAD postgres;

            CREATE OR REPLACE SECRET (
                TYPE postgres,
                HOST '%s',
                PORT %d,
                DATABASE %s,
                USER '%s',
                PASSWORD '%s'
            );

            CREATE OR REPLACE SECRET garage_secret (
                TYPE s3,
                PROVIDER config,
                KEY_ID '%s',
                SECRET '%s',
                REGION '%s',
                ENDPOINT '%s',
                URL_STYLE 'path',
                USE_SSL false
            );

            ATTACH 'ducklake:postgres:dbname=%s' AS my_ducklake (
                DATA_PATH 's3://%s/'
            );

            USE my_ducklake;
            """.formatted(
                pgHost,
                pgHost, db.port(), db.database(), db.username(), db.password(),
                s3Key.keyId(), s3Key.secretKey(), s3Key.region(), s3Key.endpoint(),
                db.database(), bucketName
        );
    }
}
