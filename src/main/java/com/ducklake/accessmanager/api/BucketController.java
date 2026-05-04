package com.ducklake.accessmanager.api;

import com.ducklake.accessmanager.config.SecurityConfig;
import com.ducklake.accessmanager.model.Bucket;
import com.ducklake.accessmanager.service.impl.BucketService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/buckets")
public class BucketController {

    private final BucketService bucketService;

    public BucketController(BucketService bucketService) {
        this.bucketService = bucketService;
    }

    /**
     * Returns buckets visible to the caller:
     * - Admin: all buckets in the catalog
     * - Student: only buckets they have been granted access to
     */
    @GetMapping
    public ResponseEntity<List<Bucket>> list(@AuthenticationPrincipal Jwt jwt) {
        if (SecurityConfig.isAdmin(jwt)) {
            return ResponseEntity.ok(bucketService.listAll());
        }
        String email = jwt.getClaimAsString("email");
        if (email == null) email = jwt.getClaimAsString("preferred_username");
        return ResponseEntity.ok(bucketService.listGrantedFor(email));
    }
}
