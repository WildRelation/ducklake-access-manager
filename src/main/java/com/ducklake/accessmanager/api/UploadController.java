package com.ducklake.accessmanager.api;

import com.ducklake.accessmanager.config.SecurityConfig;
import com.ducklake.accessmanager.model.UploadedFile;
import com.ducklake.accessmanager.service.impl.ParquetUploadService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;

/**
 * Admin-only endpoints for uploading Parquet files to a dataset's Garage bucket.
 *
 * POST   /api/admin/datasets/{bucket}/upload  — upload a Parquet file
 * GET    /api/admin/datasets/{bucket}/files   — list files for a dataset
 * DELETE /api/admin/datasets/{bucket}/files/{id} — delete file from S3 + metadata
 */
@RestController
@RequestMapping("/api/admin/datasets/{bucket}")
public class UploadController {

    private final ParquetUploadService uploadService;

    public UploadController(ParquetUploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping("/upload")
    public ResponseEntity<UploadedFile> upload(
        @PathVariable String bucket,
        @RequestParam String tableName,
        @RequestParam MultipartFile file,
        @AuthenticationPrincipal Jwt jwt
    ) {
        requireAdmin(jwt);

        if (!tableName.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid table name. Use lowercase letters, digits, underscores; start with letter or underscore.");
        }
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty.");
        }

        String uploadedBy = jwt.getClaimAsString("email");
        if (uploadedBy == null) uploadedBy = jwt.getClaimAsString("preferred_username");

        try {
            UploadedFile result = uploadService.upload(bucket, tableName, file, uploadedBy);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Upload failed: " + e.getMessage());
        }
    }

    @GetMapping("/files")
    public ResponseEntity<List<UploadedFile>> list(
        @PathVariable String bucket,
        @AuthenticationPrincipal Jwt jwt
    ) {
        requireAdmin(jwt);
        return ResponseEntity.ok(uploadService.listByBucket(bucket));
    }

    @DeleteMapping("/files/{id}")
    public ResponseEntity<Void> delete(
        @PathVariable String bucket,
        @PathVariable int id,
        @AuthenticationPrincipal Jwt jwt
    ) {
        requireAdmin(jwt);
        uploadService.delete(id, bucket);
        return ResponseEntity.noContent().build();
    }

    private void requireAdmin(Jwt jwt) {
        if (!SecurityConfig.isAdmin(jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin only.");
        }
    }
}
