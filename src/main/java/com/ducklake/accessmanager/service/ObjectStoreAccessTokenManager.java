package com.ducklake.accessmanager.service;

import com.ducklake.accessmanager.model.AccessKey;
import com.ducklake.accessmanager.model.Bucket;
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

    /**
     * Creates a bucket in object storage with the given global alias.
     * No-op if the bucket already exists.
     *
     * @param bucketName the global alias / bucket name
     */
    void createBucket(String bucketName);

    /**
     * Lists all buckets in object storage that have at least one global alias.
     *
     * @return buckets sorted by name
     */
    List<Bucket> listBuckets();

    /**
     * Permanently deletes a bucket from object storage by its global alias.
     * The bucket must be empty before deletion.
     *
     * @param bucketName the global alias of the bucket to delete
     */
    void deleteBucket(String bucketName);
}
