package com.ducklake.accessmanager.service.impl;

import com.ducklake.accessmanager.model.AccessKey;
import com.ducklake.accessmanager.service.DatabaseAccessTokenManager;
import com.ducklake.accessmanager.service.KeyMappingService;
import com.ducklake.accessmanager.service.ObjectStoreAccessTokenManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Automatic key revocation triggered by grant or group-membership changes.
 *
 * When an admin revokes a grant or removes a group member, all Garage keys and
 * PG users the student generated for the affected bucket(s) are deleted.
 * Errors during individual deletions are logged and swallowed so that the
 * grant operation itself always succeeds — cleanup is best-effort.
 */
@Service
public class KeyCleanupService {

    private static final Logger log = LoggerFactory.getLogger(KeyCleanupService.class);

    private final KeyMappingService keyMapping;
    private final ObjectStoreAccessTokenManager objectStore;
    private final DatabaseAccessTokenManager database;
    private final String legacyDatabase;

    public KeyCleanupService(
        KeyMappingService keyMapping,
        ObjectStoreAccessTokenManager objectStore,
        DatabaseAccessTokenManager database,
        @Value("${ducklake.postgres.dbname}") String legacyDatabase
    ) {
        this.keyMapping = keyMapping;
        this.objectStore = objectStore;
        this.database = database;
        this.legacyDatabase = legacyDatabase;
    }

    /**
     * Deletes all Garage keys (and associated PG users) that the given emails
     * have generated for {@code bucketName}.
     *
     * One Garage list-keys call is made; keys are filtered client-side by bucket
     * and ownership, so this is safe to call even if the email list is large.
     */
    public void revokeKeysForEmailsOnBucket(Collection<String> emails, String bucketName) {
        if (emails.isEmpty()) return;

        List<String> candidateIds = keyMapping.findKeyIdsByDisplayNames(List.copyOf(emails));
        if (candidateIds.isEmpty()) return;

        Set<String> candidateSet = Set.copyOf(candidateIds);

        List<AccessKey> toDelete = objectStore.listKeys().stream()
            .filter(k -> bucketName.equals(k.bucketName()) && candidateSet.contains(k.keyId()))
            .collect(Collectors.toList());

        for (AccessKey key : toDelete) {
            String targetDb = keyMapping.findDatabase(key.keyId());
            if (targetDb == null) targetDb = legacyDatabase;

            log.info("Auto-revoking key {} (pgUser={}, bucket={})", key.keyId(), key.pgUsername(), bucketName);

            try { objectStore.deleteKey(key.keyId()); }
            catch (Exception e) { log.warn("Could not delete Garage key {}: {}", key.keyId(), e.getMessage()); }

            keyMapping.deleteMapping(key.keyId());

            if (key.pgUsername() != null && !key.pgUsername().isBlank()) {
                final String db = targetDb;
                try { database.deleteUser(key.pgUsername(), db); }
                catch (Exception e) { log.warn("Could not drop PG user {}: {}", key.pgUsername(), e.getMessage()); }
            }
        }
    }
}
