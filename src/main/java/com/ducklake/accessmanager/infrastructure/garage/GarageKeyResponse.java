package com.ducklake.accessmanager.infrastructure.garage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Svar från Garage Admin API vid skapande av en nyckel (POST /v2/CreateKey).
 * Fältet secretAccessKey returneras endast vid skapandet – sparas inte av Garage efteråt.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GarageKeyResponse(
    String accessKeyId,
    String secretAccessKey,
    String name
) {}
