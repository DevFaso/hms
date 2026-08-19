-- V102 — Hospital scope for announcements.
--
-- Adds nullable hospital_id to public.announcement so Nurse Station and
-- hospital-wide announcement APIs can filter rows by tenant instead of reading
-- every announcement in the database. The column is nullable to preserve legacy
-- rows and platform/global announcements until an operator chooses to backfill
-- or archive them.
--
-- Idempotency: ADD COLUMN IF NOT EXISTS, CREATE INDEX IF NOT EXISTS, and a
-- pg_constraint guard for the FK. No automated rollback is declared; this is a
-- forward-only tenant-isolation hardening migration.

ALTER TABLE public.announcement
    ADD COLUMN IF NOT EXISTS hospital_id UUID;

CREATE INDEX IF NOT EXISTS idx_announcement_hospital_date
    ON public.announcement (hospital_id, date DESC);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'fk_announcement_hospital'
           AND conrelid = 'public.announcement'::regclass
    ) THEN
        ALTER TABLE public.announcement
            ADD CONSTRAINT fk_announcement_hospital
            FOREIGN KEY (hospital_id)
            REFERENCES hospital.hospitals(id);
    END IF;
END $$;
