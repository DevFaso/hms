-- V240 (round 16, QA D971): the BPA protocol row for the
-- "hypertension in pregnancy" advisory card (PregnancyHypertensionProtocolRule).
--
-- WHAT: one row in clinical.bpa_protocols, code PREGNANCY_HYPERTENSION.
-- WHY: the rule fires without a row (the card is written from the bundle),
-- but an administrator can only switch a protocol off, or edit its text,
-- through its row (review of PR #971). Seeded active.
-- The cutoffs (SBP >= 140 or DBP >= 90 mmHg, severe >= 160/110) are the ones
-- LaborServiceImpl already uses; the summary is the bundle's English text.
-- IDEMPOTENT: ON CONFLICT DO NOTHING, so a re-run never overwrites an
-- administrator's edit or switch-off. Forward-only; no rollback declared.

INSERT INTO clinical.bpa_protocols (id, protocol_code, name, summary, protocol_url, is_active)
VALUES (
    gen_random_uuid(),
    'PREGNANCY_HYPERTENSION',
    'Hypertension in pregnancy',
    'Hypertension in pregnancy (systolic >= 140 or diastolic >= 90 mmHg). Recheck the blood pressure, test for proteinuria and look for warning signs (headache, visual disturbance, epigastric pain); manage per the local pre-eclampsia protocol.',
    NULL,
    TRUE
)
ON CONFLICT (protocol_code) DO NOTHING;
