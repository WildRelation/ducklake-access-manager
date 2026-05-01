package com.ducklake.accessmanager.service;

import com.ducklake.accessmanager.model.AccessKey;
import java.util.List;

/**
 * Manages generation and deletion of access keys for S3-compatible object storage.
 *
 * Implemented by {@link com.ducklake.accessmanager.service.impl.GarageAccessTokenManager}
 * for production. Can also be implemented for MinIO in local development.
 *
 * Each key is scoped to a specific bucket and granted either read-only or read/write permission.
 */
public interface ObjectStoreAccessTokenManager {

    /**
     * Creates a read-only key (GET) for the given bucket.
     *
     * @param bucketName the bucket the key should have access to
     * @param keyName    a descriptive name for the key
     * @return {@link AccessKey} with keyId, secretKey, and endpoint
     */
    AccessKey createReadOnlyKey(String bucketName, String keyName);

    /**
     * Creates a read/write key (GET, PUT, DELETE) for the given bucket.
     * Should only be called for privileged users.
     *
     * @param bucketName the bucket the key should have access to
     * @param keyName    a descriptive name for the key
     * @return {@link AccessKey} with keyId, secretKey, and endpoint
     */
    AccessKey createReadWriteKey(String bucketName, String keyName);

    /**
     * Permanently deletes a key from object storage.
     *
     * @param keyId the unique ID of the key to delete
     */
    void deleteKey(String keyId);

    /**
     * Lists all keys registered in object storage.
     *
     * @return list of {@link AccessKey}
     */
    List<AccessKey> listKeys();
}
