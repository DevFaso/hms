ALTER TABLE clinical.roi_requests ADD COLUMN IF NOT EXISTS request_kind VARCHAR(20) NOT NULL DEFAULT 'COPY' CHECK (request_kind IN ('COPY','CORRECTION'));
ALTER TABLE clinical.roi_requests ADD COLUMN IF NOT EXISTS due_on DATE;
ALTER TABLE clinical.roi_requests ADD COLUMN IF NOT EXISTS decision_actor_user_id UUID;
