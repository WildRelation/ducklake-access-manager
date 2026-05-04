package com.ducklake.accessmanager.model;

import java.time.LocalDateTime;
import java.util.UUID;

public record Bucket(
    UUID id,
    String name,
    String description,
    LocalDateTime createdAt
) {}
