-- V158: E8 #51 — the sensitive-category tag, so those categories can be
-- withheld from cross-hospital reads before #49 widens the read filter.
--
-- WHY A COLUMN, NOT A GUESS. PatientServiceImpl already decides "is this
-- sensitive?" at READ time by substring-matching English literals
-- (SENSITIVE_KEYWORDS = {"mental health", "psychiatry", "hiv", ...}) against
-- free-text notes, medication names and department names. In a French
-- deployment « psychiatrie », « VIH » and « toxicomanie » match none of them,
-- and the same row reads as sensitive or not depending on incidental wording.
-- The decision record rules that out in as many words: "a category tag on the
-- clinical row, NOT a guess at read time". That heuristic keeps driving the
-- existing intra-hospital doctor-chart toggle for now and is removed in #49,
-- once this tag is populated — decided by the product owner, 2026-09-08.
--
-- WHERE THE TAG LIVES. On the rows that can independently carry a category:
--   * clinical.encounters, public.admissions  — they also carry a department,
--     which supplies the default;
--   * clinical.consultations                  — the specialist-referral surface;
--   * clinical.patient_problems               — a diagnosis is a category all
--     by itself (an HIV problem row);
--   * clinical.nursing_notes                  — ward notes carry the category
--     of the ward, and link to neither an encounter nor a department.
--
-- Rows that hang off an encounter (prescriptions, lab orders, imaging orders)
-- deliberately get NO column: #49 resolves their category by joining the
-- encounter. Denormalising it onto every child row would go stale the moment a
-- clinician re-tags the encounter, and a stale "not sensitive" is a disclosure.
--
-- EFFECTIVE CATEGORY = COALESCE(row.sensitivity_category,
--                               department.default_sensitivity_category)
-- for rows that have a department, and the row's own value otherwise. The
-- column is therefore the explicit override, and the department default does
-- the bulk of the work: a psychiatry department tags its encounters without
-- anyone remembering to. Nothing is written at row-creation time, so the
-- default cannot drift out of step with the department it came from.
--
-- NULLABLE ON PURPOSE. NULL means ordinary clinical information, which travels
-- normally. Treating every untagged legacy row as sensitive would leave
-- hospital B seeing almost nothing and defeat the change this belongs to.
-- No backfill: no department carries a default yet, so there is nothing to
-- derive, and inventing categories for existing rows would be fabricating
-- clinical classification.

ALTER TABLE hospital.departments
    ADD COLUMN IF NOT EXISTS default_sensitivity_category VARCHAR(32);

ALTER TABLE clinical.encounters
    ADD COLUMN IF NOT EXISTS sensitivity_category VARCHAR(32);

ALTER TABLE public.admissions
    ADD COLUMN IF NOT EXISTS sensitivity_category VARCHAR(32);

ALTER TABLE clinical.consultations
    ADD COLUMN IF NOT EXISTS sensitivity_category VARCHAR(32);

ALTER TABLE clinical.patient_problems
    ADD COLUMN IF NOT EXISTS sensitivity_category VARCHAR(32);

ALTER TABLE clinical.nursing_notes
    ADD COLUMN IF NOT EXISTS sensitivity_category VARCHAR(32);

-- #49 filters "which of these foreign rows may travel?" — a partial index on
-- the tagged minority answers it without carrying the untagged majority.
CREATE INDEX IF NOT EXISTS idx_encounters_sensitivity
    ON clinical.encounters (sensitivity_category)
    WHERE sensitivity_category IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_patient_problems_sensitivity
    ON clinical.patient_problems (sensitivity_category)
    WHERE sensitivity_category IS NOT NULL;
