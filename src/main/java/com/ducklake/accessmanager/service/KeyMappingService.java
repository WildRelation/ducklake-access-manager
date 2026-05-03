package com.ducklake.accessmanager.service;

import java.util.List;

/**
 * Tracks which Keycloak user owns which Garage key.
 *
 * Stored in PostgreSQL table key_user_mapping (created on first use).
 * Used to enforce ownership checks on GET and DELETE, and to filter
 * the key list so regular users only see their own keys.
 */
public interface KeyMappingService {
    void saveMapping(String garageKeyId, String keycloakSub, String displayName);
    String findOwner(String garageKeyId);
    List<String> findKeyIdsForUser(String keycloakUser);
    void deleteMapping(String garageKeyId);
}
