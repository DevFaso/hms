-- V12: Add force_username_change column to users table
ALTER TABLE "security".users
    ADD COLUMN IF NOT EXISTS force_username_change BOOLEAN NOT NULL DEFAULT FALSE;
