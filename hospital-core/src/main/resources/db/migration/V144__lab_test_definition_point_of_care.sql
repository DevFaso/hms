-- ============================================================
-- V144: mark which lab tests are performed at the bedside.
-- ============================================================
--
-- WHY THIS EXISTS
--
-- A nurse could create, edit AND release any lab result. Release is the
-- act that makes a result authoritative on the chart, so that put a
-- nurse's entry on the same footing as the laboratory's — for a
-- chemistry panel the nurse never ran.
--
-- The blunt fix is to remove nurses from result entry entirely, and it
-- is wrong. Nurses legitimately perform point-of-care tests at the
-- bedside: glucometer, urine dipstick, rapid malaria, haemoglobin.
-- Blocking those does not make the record safer, it makes the reading
-- go unrecorded, which is worse.
--
-- The distinction that actually matters is not WHO but WHICH TEST, and
-- nothing in the schema expressed it. This column does.
--
-- WHAT THIS DOES NOT DO
--
-- It does not decide which tests are point-of-care. That is a clinical
-- catalogue decision belonging to each hospital's laboratory — it varies
-- by site, by available devices and by local protocol — and inventing a
-- list here would be fabricating clinical reference data. Every test
-- therefore starts as NOT point-of-care.
--
-- ⚠ DEPLOYMENT CONSEQUENCE, DELIBERATE AND VISIBLE:
--    until a hospital marks its point-of-care tests, nurses and midwives
--    cannot enter results. That is the correct default — fail closed on
--    an authority question — but it IS a change in what a ward can do on
--    the day this ships. The portal says why the action is unavailable
--    rather than silently grey-ing the button, so the ward can ask an
--    administrator to mark the test instead of assuming the system is
--    broken.
--
-- Additive and idempotent; safe to re-run.
-- ============================================================

ALTER TABLE lab.lab_test_definitions
    ADD COLUMN IF NOT EXISTS point_of_care BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN lab.lab_test_definitions.point_of_care IS
    'True when this test is performed at the bedside rather than in the '
    'laboratory (glucometer, dipstick, rapid test). Nurses and midwives '
    'may enter results only for these; laboratory staff may enter any. '
    'Set per hospital by the laboratory — never seeded, see V144.';

-- The entry gate reads this per test on every create/edit, so it is a
-- hot-path lookup on an otherwise small table. Partial: only the true
-- rows are ever selected FOR, and they are the minority.
CREATE INDEX IF NOT EXISTS idx_lab_test_definitions_point_of_care
    ON lab.lab_test_definitions (id)
    WHERE point_of_care = TRUE;
