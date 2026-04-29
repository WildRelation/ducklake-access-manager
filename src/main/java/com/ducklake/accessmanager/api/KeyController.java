package com.ducklake.accessmanager.api;

import com.ducklake.accessmanager.interfaces.DatabaseAccessTokenManager;
import com.ducklake.accessmanager.interfaces.ObjectStoreAccessTokenManager;
import com.ducklake.accessmanager.model.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST-kontroller för nyckelhantering.
 *
 * Exponerar tre endpoints:
 *
 *   POST   /api/keys/generate  – Generera nya nycklar (Garage + PostgreSQL) och returnera DuckDB-script
 *   GET    /api/keys           – Lista alla befintliga nycklar
 *   DELETE /api/keys/{keyId}   – Ta bort en nyckel från både Garage och PostgreSQL
 *
 * Kontrollern delegerar all logik till {@link ObjectStoreAccessTokenManager}
 * och {@link DatabaseAccessTokenManager}, och håller sig själv fri från
 * implementationsdetaljer om Garage eller PostgreSQL.
 *
 * TODO innan denna kontroller fungerar i produktion:
 *   - Lägg till autentisering (vem är inloggad?)
 *   - Verifiera att oprivilegierade användare inte kan begära "readwrite"
 */
@RestController
@RequestMapping("/api/keys")
public class KeyController {

    private final ObjectStoreAccessTokenManager objectStore;
    private final DatabaseAccessTokenManager database;

    public KeyController(ObjectStoreAccessTokenManager objectStore, DatabaseAccessTokenManager database) {
        this.objectStore = objectStore;
        this.database = database;
    }

    /**
     * Genererar ett par nycklar (S3 + PostgreSQL) och returnerar ett färdigt DuckDB-script.
     *
     * Begäran innehåller:
     *   - bucketName: vilken Garage-bucket åtkomst ska ges till
     *   - permission: "read" (standard) eller "readwrite" (kräver privilegierad användare)
     *
     * Svar innehåller:
     *   - s3Key:          keyId, secretKey, endpoint för Garage
     *   - dbCredentials:  username, password, host för PostgreSQL
     *   - duckdbScript:   färdigt SQL-script att klistra in i DuckDB
     */
    @PostMapping("/generate")
    public ResponseEntity<GeneratedCredentials> generate(@RequestBody KeyRequest request) {
        // TODO: kontrollera användarroll innan "readwrite" tillåts
        AccessKey s3Key = switch (request.permission()) {
            case "readwrite" -> objectStore.createReadWriteKey(request.bucketName(), "key-" + request.bucketName());
            default -> objectStore.createReadOnlyKey(request.bucketName(), "key-" + request.bucketName());
        };

        DbCredentials dbCreds = switch (request.permission()) {
            case "readwrite" -> database.createReadWriteUser(request.bucketName());
            default -> database.createReadOnlyUser(request.bucketName());
        };

        String script = buildDuckdbScript(s3Key, dbCreds, request.bucketName());
        return ResponseEntity.ok(new GeneratedCredentials(s3Key, dbCreds, script));
    }

    /**
     * Returnerar alla nycklar som finns registrerade i Garage.
     */
    @GetMapping
    public ResponseEntity<List<AccessKey>> list() {
        return ResponseEntity.ok(objectStore.listKeys());
    }

    /**
     * Tar bort en nyckel från både Garage och PostgreSQL.
     *
     * @param keyId      Garage-nyckelns ID
     * @param pgUsername PostgreSQL-användaren som ska tas bort samtidigt
     */
    @DeleteMapping("/{keyId}")
    public ResponseEntity<Void> delete(@PathVariable String keyId, @RequestParam String pgUsername) {
        objectStore.deleteKey(keyId);
        database.deleteUser(pgUsername);
        return ResponseEntity.noContent().build();
    }

    // Bygger ett färdigt DuckDB-script med de genererade nycklarna
    private String buildDuckdbScript(AccessKey s3Key, DbCredentials db, String bucketName) {
        return """
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
                REGION 'local',
                ENDPOINT '%s',
                URL_STYLE 'path',
                USE_SSL false
            );

            ATTACH 'ducklake:postgres:dbname=%s' AS my_ducklake (
                DATA_PATH 's3://%s/'
            );

            USE my_ducklake;
            """.formatted(
                db.host(), db.port(), db.database(), db.username(), db.password(),
                s3Key.keyId(), s3Key.secretKey(), s3Key.endpoint(),
                db.database(), bucketName
        );
    }
}
