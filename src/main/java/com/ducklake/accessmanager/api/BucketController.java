package com.ducklake.accessmanager.api;

import com.ducklake.accessmanager.config.SecurityConfig;
import com.ducklake.accessmanager.model.Bucket;
import com.ducklake.accessmanager.service.ObjectStoreAccessTokenManager;
import com.ducklake.accessmanager.service.impl.GrantService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/buckets")
public class BucketController {

    private final ObjectStoreAccessTokenManager objectStore;
    private final GrantService grantService;

    public BucketController(ObjectStoreAccessTokenManager objectStore, GrantService grantService) {
        this.objectStore = objectStore;
        this.grantService = grantService;
    }

    /**
     * Returns buckets visible to the caller:
     * - Admin: all Garage buckets
     * - Student: only buckets they have been granted access to
     */
    @GetMapping
    public ResponseEntity<List<Bucket>> list(@AuthenticationPrincipal Jwt jwt) {
        if (SecurityConfig.isAdmin(jwt)) {
            return ResponseEntity.ok(objectStore.listBuckets());
        }
        String email = jwt.getClaimAsString("email");
        if (email == null) email = jwt.getClaimAsString("preferred_username");
        Set<String> granted = Set.copyOf(grantService.grantedBucketNames(email));
        List<Bucket> visible = objectStore.listBuckets().stream()
            .filter(b -> granted.contains(b.name()))
            .toList();
        return ResponseEntity.ok(visible);
    }
}
