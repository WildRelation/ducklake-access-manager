package com.ducklake.accessmanager.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hälsokontroll-endpoint för cbhcloud deployment.
 * Returnerar HTTP 200 när tjänsten är igång.
 */
@RestController
public class HealthController {

    @GetMapping("/healthz")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("ok");
    }
}
