package com.ducklake.accessmanager.infrastructure.garage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Svar från GET /v2/GetBucketInfo.
 * Används för att slå upp bucket-ID:t utifrån ett globalAlias (bucket-namn).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GarageBucketResponse(
    String id,
    List<String> globalAliases
) {}
