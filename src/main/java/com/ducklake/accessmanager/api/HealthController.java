package com.ducklake.accessmanager.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health check endpoint for the cbhcloud deployment.
 * Returns HTTP 200 when the service is running.
 */
@RestController
public class HealthController {

    @GetMapping("/healthz")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("ok");
    }
}
