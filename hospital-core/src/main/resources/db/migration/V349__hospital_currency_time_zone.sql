-- V349 (QA r65 D2500, D2501; track I, platform and onboarding): what a
-- hospital's money and clock are, per hospital.
--
--   currency_code  ISO 4217 code of the currency the hospital bills in. XOF
--                  for every existing row and by default: nothing changes for
--                  a hospital nobody edits. The application accepts only the
--                  currencies it prints truthfully today, the CFA francs XOF
--                  and XAF (HospitalLocaleRules).
--   time_zone      IANA zone id of the hospital (Africa/Ouagadougou). NULL
--                  means "the organisation's default time zone", which is
--                  what every existing hospital uses today
--                  (HospitalCalendar.zoneOf), so no existing row changes.
--
-- Additive and idempotent: IF NOT EXISTS, no DO block, no row rewritten
-- beyond the column default.

ALTER TABLE hospital.hospitals
    ADD COLUMN IF NOT EXISTS currency_code VARCHAR(3) NOT NULL DEFAULT 'XOF';

ALTER TABLE hospital.hospitals
    ADD COLUMN IF NOT EXISTS time_zone VARCHAR(64);
