-- Labour transfer between hospitals (user decision 2026-11-12, provisional,
-- pending clinical approval).
--
-- A labour episode active at hospital B may be handed to hospital A:
--   * with a handover: B's clinician transfers it; B's episode closes as
--     TRANSFERRED_OUT and A gets a new ACTIVE episode linked to it;
--   * without a handover: the woman arrives at A; A's clinician records it,
--     B's episode closes as LEFT_WITHOUT_HANDOVER and A opens its own.
--
-- status widens to 30 characters (LEFT_WITHOUT_HANDOVER is 21). The closing
-- row carries when, why (encrypted at rest) and where to; the new row carries
-- the episode it continues.
ALTER TABLE clinical.labor_episodes ALTER COLUMN status TYPE VARCHAR(30);

ALTER TABLE clinical.labor_episodes ADD COLUMN IF NOT EXISTS transferred_at TIMESTAMP;
ALTER TABLE clinical.labor_episodes ADD COLUMN IF NOT EXISTS transfer_reason TEXT;
ALTER TABLE clinical.labor_episodes ADD COLUMN IF NOT EXISTS transfer_hospital_id UUID;
ALTER TABLE clinical.labor_episodes ADD COLUMN IF NOT EXISTS transferred_by_user_id UUID;
ALTER TABLE clinical.labor_episodes ADD COLUMN IF NOT EXISTS transferred_from_episode_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_labor_episode_transfer_hospital') THEN
        ALTER TABLE clinical.labor_episodes
            ADD CONSTRAINT fk_labor_episode_transfer_hospital
            FOREIGN KEY (transfer_hospital_id) REFERENCES hospital.hospitals(id) ON DELETE NO ACTION;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_labor_episode_transferred_by') THEN
        ALTER TABLE clinical.labor_episodes
            ADD CONSTRAINT fk_labor_episode_transferred_by
            FOREIGN KEY (transferred_by_user_id) REFERENCES security.users(id) ON DELETE NO ACTION;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_labor_episode_transferred_from') THEN
        ALTER TABLE clinical.labor_episodes
            ADD CONSTRAINT fk_labor_episode_transferred_from
            FOREIGN KEY (transferred_from_episode_id) REFERENCES clinical.labor_episodes(id) ON DELETE NO ACTION;
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_labor_episode_transferred_from
    ON clinical.labor_episodes (transferred_from_episode_id);

-- One woman, one active labour (QA D1631). LaborServiceImpl holds the rule
-- under the patient row lock on every start and transfer path; this partial
-- unique index is the database's second line. A database that already holds
-- two active episodes for one patient (opened before D1631) is not altered:
-- the index is skipped with a warning and the duplicates are left for the
-- clinicians to close, since this migration never changes clinical status.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM clinical.labor_episodes
         WHERE status = 'ACTIVE'
         GROUP BY patient_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE WARNING 'V290: clinical.labor_episodes holds several active episodes for one patient; '
            'uq_labor_episode_patient_active not created. Close the duplicates, then create the index.';
    ELSE
        CREATE UNIQUE INDEX IF NOT EXISTS uq_labor_episode_patient_active
            ON clinical.labor_episodes (patient_id)
            WHERE status = 'ACTIVE';
    END IF;
END
$$;
