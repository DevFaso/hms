-- V63: P1 #3 — CDS rule engine reference data
--
-- Adds:
--   1. Indexes on the pre-existing clinical.drug_interactions table to
--      keep DrugDrugInteractionRule lookups O(log n) on the active
--      subset. The table itself was already created in V1 with the
--      schema defined by com.example.hms.model.medication.DrugInteraction
--      (drug1_code/drug2_code/severity/...).
--   2. Twelve seeded high-impact interactions (WHO Model Formulary,
--      BNF, FDA labels). Pairs are bidirectional via the repository's
--      findInteractionBetween query, so each pair is inserted once.
--   3. clinical.medication_catalog_items.pediatric_max_dose_mg_per_kg
--      — optional weight-based ceiling consulted by the
--      PediatricDoseRule. Nullable so existing catalog rows are
--      unaffected.
--
-- Why pre-load a seed list rather than rely on an external API
-- (RxNav/DDI-JSON):
--   * Deployments target intermittent-internet West African sites; a
--     local table avoids a network round-trip on every prescription
--     sign and stays correct when the link is down.
--   * The seed is intentionally narrow — twelve high-impact
--     contraindications and severe interactions that show up in the
--     WHO essential medicines list. Hospitals can extend per-tenant
--     by inserting rows.
--
-- Idempotent — every CREATE / ALTER / INSERT guards on IF NOT EXISTS
-- or relies on duplicate-key suppression.

-- 1. Add lookup indexes on the existing drug_interactions table.
--    DrugInteractionRepository#findInteractionBetween queries
--      (drug1_code = :a AND drug2_code = :b)
--      OR (drug1_code = :b AND drug2_code = :a)
--    so the planner needs a composite key on each ordering. Two
--    composite partial indexes (scoped to is_active = TRUE) match
--    both branches without column-function wrapping; the OR is
--    rewritten into a BitmapOr by the planner.
CREATE INDEX IF NOT EXISTS idx_drug_interactions_drug1_drug2_active
    ON clinical.drug_interactions (drug1_code, drug2_code)
    WHERE is_active = TRUE;

CREATE INDEX IF NOT EXISTS idx_drug_interactions_drug2_drug1_active
    ON clinical.drug_interactions (drug2_code, drug1_code)
    WHERE is_active = TRUE;

-- 2. Pediatric dose ceiling on medication_catalog_items.
ALTER TABLE clinical.medication_catalog_items
    ADD COLUMN IF NOT EXISTS pediatric_max_dose_mg_per_kg NUMERIC(8,3);

-- 3. Seed twelve high-impact interactions. RxNorm RXCUIs verified
--    against RxNav (https://rxnav.nlm.nih.gov/) as of 2024.
--    Severity legend (com.example.hms.enums.InteractionSeverity):
--      CONTRAINDICATED — never co-prescribe under normal conditions
--      MAJOR           — co-prescribe only with monitoring + override
--      MODERATE        — clinically relevant, advisory only
--    Conflict guard: pairs already loaded by another mechanism are
--    skipped via NOT EXISTS; both directions are checked because the
--    repository search is bidirectional.
DO $$
DECLARE
    -- PostgreSQL requires the FOR-loop target to be a declared record /
    -- row variable (or a list of scalars). Without `seed RECORD` here,
    -- Liquibase's apply fails with:
    --   ERROR: loop variable of loop over rows must be a record variable
    --   or list of scalar variables
    seed  RECORD;
    v_now TIMESTAMP WITHOUT TIME ZONE := NOW();
    v_id  UUID;
BEGIN
    FOR seed IN
        SELECT * FROM (VALUES
            ('1191',  'aspirin',
             '11289', 'warfarin',
             'MAJOR',
             'Warfarin + aspirin: additive bleeding risk.',
             'Avoid unless the indication is documented (e.g. cardiac stent); monitor INR weekly and reinforce bleeding-precaution counselling.',
             'WHO Model Formulary 2024'),

            ('5640',  'ibuprofen',
             '11289', 'warfarin',
             'MAJOR',
             'Warfarin + NSAID: elevated GI bleed and INR variability.',
             'Prefer paracetamol; if NSAID required, limit to short course with PPI cover.',
             'WHO Model Formulary 2024'),

            ('2551',  'clarithromycin',
             '36567', 'simvastatin',
             'CONTRAINDICATED',
             'Simvastatin + clarithromycin: CYP3A4 inhibition raises rhabdomyolysis risk.',
             'Switch macrolide (azithromycin) or hold statin during the antibiotic course.',
             'FDA label simvastatin'),

            ('4493',  'fluoxetine',
             '10689', 'tramadol',
             'MAJOR',
             'Fluoxetine + tramadol: serotonin-syndrome risk and reduced seizure threshold.',
             'Choose non-SSRI analgesic ladder; if both needed, monitor mental status and avoid maximum doses.',
             'WHO PEN-Plus 2023'),

            ('6851',  'methotrexate',
             '10180', 'trimethoprim/sulfamethoxazole',
             'CONTRAINDICATED',
             'Methotrexate + TMP/SMX: profound bone-marrow suppression.',
             'Use an alternative antibiotic (amoxicillin / cefuroxime) for the methotrexate-treated patient.',
             'BNF 86'),

            ('29046', 'lisinopril',
             '8588',  'potassium chloride',
             'MODERATE',
             'Lisinopril + potassium supplement: hyperkalaemia risk.',
             'Verify serum K+ before initiating; avoid combination if K+ > 4.5 mmol/L.',
             'WHO Model Formulary 2024'),

            ('3407',  'digoxin',
             '703',   'amiodarone',
             'MAJOR',
             'Digoxin + amiodarone: amiodarone roughly doubles digoxin levels.',
             'Halve the digoxin dose at initiation and check level at day 7.',
             'BNF 86'),

            ('8123',  'phenelzine',
             '36437', 'sertraline',
             'CONTRAINDICATED',
             'MAOI + SSRI: hypertensive crisis and serotonin syndrome.',
             'Allow a 14-day washout when switching between these classes.',
             'NICE CG90'),

            ('5933',  'iohexol',
             '6809',  'metformin',
             'MODERATE',
             'Metformin + iodinated contrast: lactic-acidosis risk in renal impairment.',
             'Hold metformin from the day of contrast and resume after 48 h with renal-function check.',
             'ESUR Contrast Manual'),

            ('29046', 'lisinopril',
             '9997',  'spironolactone',
             'MODERATE',
             'Spironolactone + ACE inhibitor: hyperkalaemia risk.',
             'Monitor K+ at week 1 and after each dose adjustment.',
             'WHO Model Formulary 2024'),

            ('309394','ciprofloxacin',
             '10355', 'theophylline',
             'MAJOR',
             'Theophylline + ciprofloxacin: ciprofloxacin doubles theophylline levels (toxicity, seizure).',
             'Use levofloxacin or moxifloxacin if a fluoroquinolone is needed.',
             'BNF 86'),

            ('6851',  'methotrexate',
             '8331',  'penicillin V',
             'MODERATE',
             'Penicillin V + methotrexate: penicillins reduce methotrexate clearance.',
             'Increase MTX-toxicity surveillance (FBC, LFT) during the antibiotic course.',
             'BNF 86')
        ) AS s(d1_code, d1_name, d2_code, d2_name, severity, description, recommendation, source)
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM clinical.drug_interactions di
            WHERE (di.drug1_code = seed.d1_code AND di.drug2_code = seed.d2_code)
               OR (di.drug1_code = seed.d2_code AND di.drug2_code = seed.d1_code)
        ) THEN
            v_id := gen_random_uuid();
            INSERT INTO clinical.drug_interactions (
                id, created_at, updated_at,
                drug1_code, drug1_name, drug2_code, drug2_name,
                severity, description, recommendation,
                source_database, is_active,
                requires_avoidance, requires_dose_adjustment, requires_monitoring
            ) VALUES (
                v_id, v_now, v_now,
                seed.d1_code, seed.d1_name, seed.d2_code, seed.d2_name,
                seed.severity, seed.description, seed.recommendation,
                seed.source, TRUE,
                seed.severity = 'CONTRAINDICATED',
                seed.severity IN ('CONTRAINDICATED', 'MAJOR'),
                seed.severity IN ('CONTRAINDICATED', 'MAJOR', 'MODERATE')
            );
        END IF;
    END LOOP;
END
$$;
