-- V387 (scorecard K4): the in-app notification body is stored encrypted.
--
-- security.notifications.message is written through EncryptedStringConverter from now on, like the
-- clinical text columns: a body can name a patient, a test and a value. The stored form
-- (gcm1:<Base64(iv || ciphertext + tag)>) is longer than the text it holds, so the column is widened
-- from VARCHAR(255) to TEXT. A widening only: no row is rewritten here. Existing rows keep their text
-- until PhiTextEncryptionBackfill encrypts them at the next start, and are read as they are meanwhile.
--
-- message_args stays JSONB: its arguments are sealed inside the JSON document by the application
-- (SealedJsonListConverter), so its type does not change.
--
-- Plain statement, no DO block.

ALTER TABLE security.notifications
    ALTER COLUMN message TYPE TEXT;
