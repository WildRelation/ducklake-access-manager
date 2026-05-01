package com.ducklake.accessmanager.infrastructure.garage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Response from GET /v2/GetBucketInfo.
 * Used to resolve the bucket ID from a globalAlias (bucket name).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GarageBucketResponse(
    String id,
    List<String> globalAliases
) {}
