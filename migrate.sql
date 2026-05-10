-- Migration: ducklake-access-manager v1 → v2
-- Run this against the ducklake Postgres database BEFORE deploying the new image.
-- All statements are idempotent (IF NOT EXISTS / ON CONFLICT DO NOTHING).

-- 1. Add pg_database column to key_user_mapping (null = legacy row, falls back to shared DB)
ALTER TABLE key_user_mapping ADD COLUMN IF NOT EXISTS pg_database VARCHAR;

-- 2. Groups support
CREATE TABLE IF NOT EXISTS groups (
    name        VARCHAR PRIMARY KEY,
    description VARCHAR,
    created_by  VARCHAR,
    created_at  TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS group_members (
    group_name   VARCHAR NOT NULL REFERENCES groups(name) ON DELETE CASCADE,
    member_email VARCHAR NOT NULL,
    added_at     TIMESTAMP DEFAULT NOW(),
    PRIMARY KEY (group_name, member_email)
);

-- 3. Generalized grants (replaces student_grants)
CREATE TABLE IF NOT EXISTS dataset_grants (
    bucket_name    VARCHAR NOT NULL,
    principal_type VARCHAR NOT NULL CHECK (principal_type IN ('user', 'group', 'everyone')),
    principal_id   VARCHAR NOT NULL,
    granted_at     TIMESTAMP DEFAULT NOW(),
    PRIMARY KEY (bucket_name, principal_type, principal_id)
);

-- 4. Migrate existing student_grants → dataset_grants (type=user)
--    The app also does this automatically on startup, but running it here
--    ensures the data is ready before the first request.
INSERT INTO dataset_grants (bucket_name, principal_type, principal_id, granted_at)
SELECT bucket_name, 'user', student_email, granted_at
FROM student_grants
ON CONFLICT (bucket_name, principal_type, principal_id) DO NOTHING;

-- 5. Dataset metadata table
CREATE TABLE IF NOT EXISTS datasets (
    bucket_name   VARCHAR PRIMARY KEY,
    pg_database   VARCHAR NOT NULL UNIQUE,
    title         VARCHAR NOT NULL,
    description   TEXT,
    owner_email   VARCHAR NOT NULL,
    visibility    VARCHAR NOT NULL DEFAULT 'private'
                  CHECK (visibility IN ('private','public')),
    created_at    TIMESTAMP DEFAULT NOW()
);

-- NOTE: Existing Garage buckets will be auto-registered as datasets on first app startup
-- (via DatasetService.syncOrphanBuckets). Each will get a corresponding Postgres database
-- named dl_<bucket_with_underscores> created automatically. No manual action needed.
