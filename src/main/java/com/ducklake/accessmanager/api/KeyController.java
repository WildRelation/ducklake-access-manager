package com.ducklake.accessmanager.api;

import com.ducklake.accessmanager.interfaces.DatabaseAccessTokenManager;
import com.ducklake.accessmanager.interfaces.ObjectStoreAccessTokenManager;
import com.ducklake.accessmanager.model.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/keys")
public class KeyController {

    private final ObjectStoreAccessTokenManager objectStore;
    private final DatabaseAccessTokenManager database;

    public KeyController(ObjectStoreAccessTokenManager objectStore, DatabaseAccessTokenManager database) {
        this.objectStore = objectStore;
        this.database = database;
    }

    @PostMapping("/generate")
    public ResponseEntity<GeneratedCredentials> generate(@RequestBody KeyRequest request) {
        // TODO: check user role before allowing readwrite
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

    @GetMapping
    public ResponseEntity<List<AccessKey>> list() {
        return ResponseEntity.ok(objectStore.listKeys());
    }

    @DeleteMapping("/{keyId}")
    public ResponseEntity<Void> delete(@PathVariable String keyId, @RequestParam String pgUsername) {
        objectStore.deleteKey(keyId);
        database.deleteUser(pgUsername);
        return ResponseEntity.noContent().build();
    }

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
