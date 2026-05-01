package com.ducklake.accessmanager.infrastructure.garage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Response from the Garage Admin API when creating a key (POST /v2/CreateKey).
 * The secretAccessKey is only returned at creation time — Garage does not store it afterwards.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GarageKeyResponse(
    String accessKeyId,
    String secretAccessKey,
    String name
) {}
