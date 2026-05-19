package com.ducklake.accessmanager.service.impl;

import com.ducklake.accessmanager.model.AccessKey;
import com.ducklake.accessmanager.model.UploadedFile;
import com.ducklake.accessmanager.service.ObjectStoreAccessTokenManager;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Service
public class ParquetUploadService {

    private static final Logger log = LoggerFactory.getLogger(ParquetUploadService.class);
    private static final byte[] PARQUET_MAGIC = {'P', 'A', 'R', '1'};

    private final ObjectStoreAccessTokenManager objectStore;
    private final JdbcTemplate jdbc;
    private final String garageEndpoint;
    private final String garageRegion;

    public ParquetUploadService(
        ObjectStoreAccessTokenManager objectStore,
        JdbcTemplate jdbc,
        @Value("${garage.s3.endpoint}") String garageEndpoint,
        @Value("${garage.s3.region:garage}") String garageRegion
    ) {
        this.objectStore   = objectStore;
        this.jdbc          = jdbc;
        this.garageEndpoint = garageEndpoint;
        this.garageRegion  = garageRegion;
    }

    @PostConstruct
    void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS uploaded_files (
                id            SERIAL PRIMARY KEY,
                bucket_name   VARCHAR(63)  NOT NULL,
                object_key    VARCHAR(500) NOT NULL,
                original_name VARCHAR(255) NOT NULL,
                table_name    VARCHAR(255) NOT NULL,
                file_size     BIGINT,
                uploaded_by   VARCHAR(255),
                uploaded_at   TIMESTAMPTZ DEFAULT now(),
                UNIQUE (bucket_name, object_key)
            )
        """);
    }

    /**
     * Validates the Parquet magic bytes, uploads to Garage via a transient
     * read-write key, then records the file metadata in PostgreSQL.
     */
    public UploadedFile upload(String bucketName, String tableName, MultipartFile file, String uploadedBy)
            throws IOException {
        validateParquet(file);

        String sanitizedName = file.getOriginalFilename() == null ? "file.parquet"
            : file.getOriginalFilename().toLowerCase().replaceAll("[^a-z0-9._-]", "_");
        String objectKey = "data/" + tableName + "/" + sanitizedName;

        withTempKey(bucketName, key -> {
            try (S3Client s3 = buildS3Client(key.keyId(), key.secretKey())) {
                try (InputStream is = file.getInputStream()) {
                    s3.putObject(
                        PutObjectRequest.builder()
                            .bucket(bucketName)
                            .key(objectKey)
                            .contentType("application/octet-stream")
                            .contentLength(file.getSize())
                            .build(),
                        RequestBody.fromInputStream(is, file.getSize())
                    );
                }
            }
        });

        jdbc.update("""
            INSERT INTO uploaded_files (bucket_name, object_key, original_name, table_name, file_size, uploaded_by)
            VALUES (?,?,?,?,?,?)
            ON CONFLICT (bucket_name, object_key) DO UPDATE SET
              original_name = EXCLUDED.original_name,
              table_name    = EXCLUDED.table_name,
              file_size     = EXCLUDED.file_size,
              uploaded_by   = EXCLUDED.uploaded_by,
              uploaded_at   = now()
            """,
            bucketName, objectKey, file.getOriginalFilename(), tableName, file.getSize(), uploadedBy
        );

        return jdbc.queryForObject(
            "SELECT * FROM uploaded_files WHERE bucket_name=? AND object_key=?",
            this::mapRow, bucketName, objectKey
        );
    }

    public List<UploadedFile> listByBucket(String bucketName) {
        return jdbc.query(
            "SELECT * FROM uploaded_files WHERE bucket_name=? ORDER BY uploaded_at DESC",
            this::mapRow, bucketName
        );
    }

    /** Deletes the S3 object and removes the metadata row. */
    public void delete(int id, String bucketName) {
        UploadedFile f = jdbc.queryForObject(
            "SELECT * FROM uploaded_files WHERE id=? AND bucket_name=?",
            this::mapRow, id, bucketName
        );
        if (f == null) return;

        try {
            withTempKey(bucketName, key -> {
                try (S3Client s3 = buildS3Client(key.keyId(), key.secretKey())) {
                    s3.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucketName)
                        .key(f.objectKey())
                        .build());
                }
            });
        } catch (Exception e) {
            log.warn("S3 delete failed for {}/{}, continuing with metadata removal: {}", bucketName, f.objectKey(), e.getMessage());
        }

        jdbc.update("DELETE FROM uploaded_files WHERE id=? AND bucket_name=?", id, bucketName);
    }

    // --- private ---

    /** Creates a transient read-write key, runs the action, then deletes the key. */
    private void withTempKey(String bucketName, S3Action action) throws IOException {
        AccessKey tempKey = objectStore.createReadWriteKey(bucketName, "upload-tmp-" + System.currentTimeMillis());
        try {
            action.run(tempKey);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            try { objectStore.deleteKey(tempKey.keyId()); } catch (Exception ex) {
                log.warn("Failed to delete temp upload key {}: {}", tempKey.keyId(), ex.getMessage());
            }
        }
    }

    @FunctionalInterface
    private interface S3Action {
        void run(AccessKey key) throws Exception;
    }

    private S3Client buildS3Client(String keyId, String secret) {
        String endpoint = garageEndpoint.startsWith("http") ? garageEndpoint : "http://" + garageEndpoint;
        return S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(garageRegion))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(keyId, secret)
            ))
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build())
            .httpClient(UrlConnectionHttpClient.create())
            .build();
    }

    private void validateParquet(MultipartFile file) throws IOException {
        try (InputStream is = file.getInputStream()) {
            byte[] magic = is.readNBytes(4);
            if (magic.length < 4
                || magic[0] != PARQUET_MAGIC[0]
                || magic[1] != PARQUET_MAGIC[1]
                || magic[2] != PARQUET_MAGIC[2]
                || magic[3] != PARQUET_MAGIC[3]) {
                throw new IllegalArgumentException("Not a valid Parquet file (missing PAR1 magic bytes)");
            }
        }
    }

    private UploadedFile mapRow(ResultSet rs, int n) throws SQLException {
        return new UploadedFile(
            rs.getInt("id"),
            rs.getString("bucket_name"),
            rs.getString("object_key"),
            rs.getString("original_name"),
            rs.getString("table_name"),
            rs.getLong("file_size"),
            rs.getString("uploaded_by"),
            rs.getString("uploaded_at")
        );
    }
}
