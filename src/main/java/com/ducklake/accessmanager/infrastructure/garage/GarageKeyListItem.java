package com.ducklake.accessmanager.infrastructure.garage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Ett element i listan från GET /v2/ListKeys.
 * Innehåller inte secretAccessKey – den returneras bara vid skapandet.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GarageKeyListItem(
    String id,
    String name
) {}
