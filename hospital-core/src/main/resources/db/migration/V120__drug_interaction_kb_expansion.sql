-- =============================================================
-- V120: expand the drug-interaction knowledge base.
--
-- WHY
--   The checking pipeline is real and wired at three layers —
--   prescribe, dispense and CDS-Hooks — but the knowledge base
--   behind it was a 12-pair seed from V63. A checker with almost no
--   data to check against is worse than no checker, because the
--   green result reads as "no interaction" when it means "not in
--   our twelve rows".
--
--   This adds the interactions a WHO-Essential-Medicines formulary
--   most needs: the ones that are common, severe, and involve drugs
--   an LMIC district hospital actually stocks. Each row cites the
--   reference it came from, in the same shape V63 established.
--
-- ⚠ REVIEW STATUS
--   Every row here is transcribed from a standard reference (BNF 86,
--   WHO Model Formulary 2024, NICE guidance) and each is
--   textbook-level established — but a drug-interaction knowledge
--   base is clinical content, and clinical content belongs to a
--   pharmacist, not to whoever wrote the migration.
--
--   Rows are therefore seeded with source_database naming the
--   reference so a reviewer can check each one against it, and this
--   PR ships an admin CRUD API so the pharmacy team can correct,
--   extend or deactivate entries WITHOUT another migration. That
--   API is the durable half of this change; the seed is a starting
--   point, not an authority.
--
-- IDEMPOTENT and order-insensitive: an interaction already present
-- in either drug order is skipped, exactly as V63 does.
-- =============================================================

DO $$
DECLARE
    seed   RECORD;
    v_id   UUID;
    v_now  TIMESTAMP := NOW();
BEGIN
    FOR seed IN
        SELECT * FROM (VALUES
            -- ── Anticoagulation: the commonest cause of avoidable admission ──
            ('11289', 'warfarin',
             '1191',  'aspirin',
             'MAJOR',
             'Warfarin + aspirin: additive bleeding risk, no INR change to warn you.',
             'Avoid unless a specific indication exists; if combined, use gastroprotection and tighten INR checks.',
             'BNF 86'),

            ('11289', 'warfarin',
             '5640',  'ibuprofen',
             'MAJOR',
             'Warfarin + NSAID: GI bleeding risk plus displacement raising free warfarin.',
             'Use paracetamol instead. If an NSAID is unavoidable, add a PPI and recheck INR at 3-5 days.',
             'BNF 86'),

            ('11289', 'warfarin',
             '4450',  'fluconazole',
             'MAJOR',
             'Fluconazole inhibits CYP2C9: warfarin effect rises sharply, often within 2-3 days.',
             'Recheck INR at 3 days and anticipate a warfarin dose reduction.',
             'BNF 86'),

            ('11289', 'warfarin',
             '10829', 'trimethoprim/sulfamethoxazole',
             'MAJOR',
             'Co-trimoxazole + warfarin: CYP2C9 inhibition plus gut-flora vitamin K loss; large INR rises reported.',
             'Prefer an alternative antibiotic. If used, recheck INR within 3 days.',
             'WHO Model Formulary 2024'),

            -- ── QT prolongation: common combinations in TB / HIV / malaria care ──
            ('6902',  'methadone',
             '2551',  'clarithromycin',
             'MAJOR',
             'Additive QT prolongation with CYP3A4 inhibition raising methadone levels.',
             'ECG before and during; consider azithromycin, which carries less CYP3A4 effect.',
             'BNF 86'),

            ('733',   'amiodarone',
             '2551',  'clarithromycin',
             'MAJOR',
             'Two QT-prolonging agents together: torsades de pointes risk.',
             'Avoid the combination; if unavoidable, ECG and electrolyte monitoring.',
             'BNF 86'),

            ('733',   'amiodarone',
             '11289', 'warfarin',
             'MAJOR',
             'Amiodarone inhibits warfarin metabolism; the effect builds over weeks and persists after stopping.',
             'Reduce the warfarin dose by roughly a third and monitor INR weekly for 6 weeks.',
             'BNF 86'),

            -- ── TB therapy: rifampicin is the great enzyme inducer ──
            ('9384',  'rifampicin',
             '3355',  'ethinylestradiol',
             'CONTRAINDICATED',
             'Rifampicin induces CYP3A4 and abolishes hormonal contraceptive efficacy.',
             'Use a non-hormonal method (copper IUD) during treatment and for 28 days after.',
             'WHO Model Formulary 2024'),

            ('9384',  'rifampicin',
             '11289', 'warfarin',
             'MAJOR',
             'Rifampicin induction can halve warfarin effect; INR falls, thrombosis risk rises.',
             'Expect a substantial dose increase and monitor INR weekly through and after treatment.',
             'BNF 86'),

            ('9384',  'rifampicin',
             '10582', 'levothyroxine',
             'MODERATE',
             'Rifampicin accelerates levothyroxine clearance; hypothyroid symptoms return.',
             'Recheck TSH 4-6 weeks after starting or stopping rifampicin.',
             'BNF 86'),

            -- ── HIV therapy ──
            ('35617', 'nevirapine',
             '9384',  'rifampicin',
             'MAJOR',
             'Rifampicin lowers nevirapine concentrations, risking virological failure and resistance.',
             'Use efavirenz-based ART during rifampicin-containing TB treatment.',
             'WHO Model Formulary 2024'),

            -- ── Diabetes and renal risk ──
            ('6809',  'metformin',
             '29046', 'lisinopril',
             'MODERATE',
             'ACE inhibitor may enhance hypoglycaemic effect and, in acute kidney injury, raises lactic-acidosis risk.',
             'Hold metformin during intercurrent illness with dehydration; check renal function.',
             'BNF 86'),

            -- ── Serotonergic combinations ──
            ('36437', 'sertraline',
             '10689', 'tramadol',
             'MAJOR',
             'SSRI + tramadol: serotonin syndrome, and tramadol lowers the seizure threshold.',
             'Prefer a non-serotonergic analgesic; counsel on serotonin-syndrome symptoms if combined.',
             'BNF 86'),

            ('36437', 'sertraline',
             '1191',  'aspirin',
             'MODERATE',
             'SSRIs impair platelet aggregation; combined with aspirin the GI-bleeding risk roughly doubles.',
             'Consider gastroprotection, particularly over 65 or with prior GI bleed.',
             'NICE CG184'),

            -- ── Electrolyte and renal ──
            ('9997',  'spironolactone',
             '8591',  'potassium chloride',
             'MAJOR',
             'Potassium-sparing diuretic plus potassium supplement: dangerous hyperkalaemia.',
             'Avoid routine supplementation; if both are essential, check K+ within a week.',
             'BNF 86'),

            ('6835',  'digoxin',
             '4109',  'furosemide',
             'MODERATE',
             'Loop-diuretic hypokalaemia potentiates digoxin toxicity at normal digoxin levels.',
             'Monitor K+ and Mg2+; replace before attributing symptoms to digoxin dose.',
             'BNF 86'),

            ('6835',  'digoxin',
             '733',   'amiodarone',
             'MAJOR',
             'Amiodarone roughly doubles digoxin concentration.',
             'Halve the digoxin dose when starting amiodarone and check levels.',
             'BNF 86'),

            -- ── Statin myopathy ──
            ('36567', 'simvastatin',
             '2551',  'clarithromycin',
             'CONTRAINDICATED',
             'CYP3A4 inhibition raises simvastatin exposure sharply: rhabdomyolysis risk.',
             'Suspend simvastatin for the antibiotic course, or use azithromycin.',
             'BNF 86'),

            ('36567', 'simvastatin',
             '4450',  'fluconazole',
             'MAJOR',
             'Azole inhibition of CYP3A4 raises statin levels and myopathy risk.',
             'Suspend the statin during short antifungal courses.',
             'BNF 86'),

            -- ── Antimalarials ──
            ('6058',  'quinine',
             '6835',  'digoxin',
             'MAJOR',
             'Quinine raises plasma digoxin concentration.',
             'Reduce digoxin dose and monitor for toxicity during quinine treatment.',
             'WHO Model Formulary 2024')
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
                TRUE
            );
        END IF;
    END LOOP;

    RAISE NOTICE 'V120: drug-interaction knowledge base now holds % pair(s).',
        (SELECT COUNT(*) FROM clinical.drug_interactions);
END $$;
