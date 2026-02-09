-- Migration to add profile_image_url column to users table
-- This allows users to upload and store profile images

ALTER TABLE security.users
ADD COLUMN profile_image_url VARCHAR2(500) NULL;

-- Add comment for documentation
COMMENT ON COLUMN security.users.profile_image_url IS 'URL path to user profile image file';