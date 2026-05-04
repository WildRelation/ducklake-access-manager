package com.ducklake.accessmanager.api;

import com.ducklake.accessmanager.config.SecurityConfig;
import com.ducklake.accessmanager.model.Bucket;
import com.ducklake.accessmanager.model.BucketGrant;
import com.ducklake.accessmanager.service.impl.BucketService;
import com.ducklake.accessmanager.service.impl.GrantService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final BucketService buckets;
    private final GrantService grants;

    public AdminController(BucketService buckets, GrantService grants) {
        this.buckets = buckets;
        this.grants  = grants;
    }

    // ── Buckets ──────────────────────────────────────────────────────────

    @GetMapping("/buckets")
    public ResponseEntity<List<Bucket>> listBuckets(@AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        return ResponseEntity.ok(buckets.listAll());
    }

    @PostMapping("/buckets")
    public ResponseEntity<Bucket> addBucket(
        @RequestBody Map<String, String> body,
        @AuthenticationPrincipal Jwt jwt
    ) {
        requireAdmin(jwt);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(buckets.add(body.get("name"), body.get("description")));
    }

    @DeleteMapping("/buckets/{id}")
    public ResponseEntity<Void> deleteBucket(
        @PathVariable UUID id,
        @AuthenticationPrincipal Jwt jwt
    ) {
        requireAdmin(jwt);
        buckets.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ── Grants ───────────────────────────────────────────────────────────

    @GetMapping("/grants")
    public ResponseEntity<List<BucketGrant>> listGrants(
        @RequestParam(required = false) String email,
        @AuthenticationPrincipal Jwt jwt
    ) {
        requireAdmin(jwt);
        List<BucketGrant> result = (email != null && !email.isBlank())
            ? grants.listForStudent(email)
            : grants.listAll();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/grants")
    public ResponseEntity<Void> addGrant(
        @RequestBody Map<String, String> body,
        @AuthenticationPrincipal Jwt jwt
    ) {
        requireAdmin(jwt);
        String studentEmail = body.get("studentEmail");
        if (studentEmail == null || studentEmail.isBlank()) return ResponseEntity.badRequest().build();
        grants.grant(studentEmail, UUID.fromString(body.get("bucketId")));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/grants")
    public ResponseEntity<Void> revokeGrant(
        @RequestBody Map<String, String> body,
        @AuthenticationPrincipal Jwt jwt
    ) {
        requireAdmin(jwt);
        grants.revoke(body.get("studentEmail"), UUID.fromString(body.get("bucketId")));
        return ResponseEntity.noContent().build();
    }

    private void requireAdmin(Jwt jwt) {
        if (!SecurityConfig.isAdmin(jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
        }
    }
}
