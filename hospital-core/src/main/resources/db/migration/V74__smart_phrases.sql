-- V74: SmartPhrase / dot-phrase macro library (P1 #12 follow-up #4 — items 4/5/6)
--
-- Pairs with the per-section EncounterNote form (item 5). Triggers like
-- ".normexam" or ".htn-followup" expand to a multi-line block when typed in
-- any note section.
--
-- Scope precedence USER > HOSPITAL > GLOBAL is enforced in the service; the
-- table just stores the rows. Three partial unique indexes prevent collisions
-- per scope while still letting the same trigger exist at multiple tiers
-- (e.g. a global ".normros" plus a user-private ".normros" that overrides it).
--
-- Additive only; pure DDL.

CREATE TABLE IF NOT EXISTS clinical.smart_phrases (
    id              UUID            PRIMARY KEY,
    created_at      TIMESTAMP       NOT NULL,
    updated_at      TIMESTAMP       NOT NULL,
    phrase_trigger  VARCHAR(64)     NOT NULL,
    title           VARCHAR(200)    NOT NULL,
    expansion       TEXT            NOT NULL,
    scope           VARCHAR(12)     NOT NULL,
    hospital_id     UUID,
    owner_user_id   UUID,
    specialty       VARCHAR(64),
    usage_count     BIGINT          NOT NULL DEFAULT 0,
    last_used_at    TIMESTAMP,
    version         BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_smartphrase_hospital FOREIGN KEY (hospital_id)   REFERENCES hospital.hospitals (id),
    CONSTRAINT fk_smartphrase_owner    FOREIGN KEY (owner_user_id) REFERENCES security.users (id),
    CONSTRAINT chk_smartphrase_scope_owner CHECK (
        (scope = 'GLOBAL'   AND hospital_id IS NULL  AND owner_user_id IS NULL)
     OR (scope = 'HOSPITAL' AND hospital_id IS NOT NULL)
     OR (scope = 'USER'     AND owner_user_id IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_smartphrase_trigger
    ON clinical.smart_phrases (phrase_trigger);

CREATE INDEX IF NOT EXISTS idx_smartphrase_scope_hospital
    ON clinical.smart_phrases (scope, hospital_id);

CREATE INDEX IF NOT EXISTS idx_smartphrase_owner
    ON clinical.smart_phrases (owner_user_id);

-- Per-scope uniqueness — one global trigger, one per (hospital, trigger),
-- and one per (owner, hospital, trigger). Partial indexes let the same
-- trigger live across tiers so a user can shadow a hospital macro.
CREATE UNIQUE INDEX IF NOT EXISTS uq_smartphrase_global
    ON clinical.smart_phrases (LOWER(phrase_trigger))
    WHERE scope = 'GLOBAL';

CREATE UNIQUE INDEX IF NOT EXISTS uq_smartphrase_hospital
    ON clinical.smart_phrases (hospital_id, LOWER(phrase_trigger))
    WHERE scope = 'HOSPITAL';

CREATE UNIQUE INDEX IF NOT EXISTS uq_smartphrase_user
    ON clinical.smart_phrases (owner_user_id, COALESCE(hospital_id, '00000000-0000-0000-0000-000000000000'::uuid), LOWER(phrase_trigger))
    WHERE scope = 'USER';

-- ──────────────────────────────────────────────────────────────────────────
-- Seed the GLOBAL library with a small starter pack. Idempotent: skip rows
-- whose trigger already exists at GLOBAL scope so re-runs / replays are safe.
-- These triggers are the most common district-hospital templates; tenants
-- can shadow them via a HOSPITAL or USER macro of the same name.
-- ──────────────────────────────────────────────────────────────────────────
INSERT INTO clinical.smart_phrases
    (id, created_at, updated_at, phrase_trigger, title, expansion, scope, usage_count, version)
SELECT gen_random_uuid(), now(), now(), phrase_trigger, title, expansion, 'GLOBAL', 0, 0
FROM (VALUES
    ('.normexam',
     'Normal physical exam — adult',
     E'General: alert, oriented x3, in no acute distress.\nHEENT: normocephalic, atraumatic; PERRL; mucous membranes moist.\nNeck: supple, no JVD, no lymphadenopathy.\nCV: regular rate and rhythm, no murmurs/gallops/rubs.\nRespiratory: clear to auscultation bilaterally.\nAbdomen: soft, non-tender, non-distended, normoactive bowel sounds.\nExtremities: no edema, no cyanosis.\nSkin: warm and dry, no rashes.\nNeuro: grossly intact, no focal deficits.'),
    ('.normros',
     'Normal review of systems — adult',
     E'Constitutional: denies fever, chills, weight loss.\nHEENT: denies headache, vision/hearing changes.\nCardiac: denies chest pain, palpitations.\nRespiratory: denies cough, dyspnea.\nGI: denies nausea, vomiting, diarrhea, constipation.\nGU: denies dysuria or change in urinary habits.\nMusculoskeletal: denies joint pain or swelling.\nSkin: denies rashes or new lesions.\nNeurologic: denies weakness, numbness, syncope.\nPsychiatric: denies depression or anxiety.'),
    ('.htn-followup',
     'Hypertension follow-up plan',
     E'1. Continue current antihypertensive regimen.\n2. Reinforce DASH-style diet, salt restriction, regular aerobic activity.\n3. Home BP log; recheck in 4 weeks.\n4. Repeat basic metabolic panel and urine ACR if not done in last 12 months.\n5. Patient education on stroke / chest-pain warning signs — return precautions reviewed.'),
    ('.dm-followup',
     'Type-2 diabetes follow-up plan',
     E'1. Continue oral hypoglycaemics; review adherence and side effects.\n2. HbA1c, lipid panel, urine ACR if not done in last 6 months.\n3. Foot exam reviewed today.\n4. Reinforce dietary counselling and physical activity.\n5. Reschedule retinal screening if overdue.\n6. Hypoglycaemia precautions and return-precaution counselling provided.'),
    ('.malaria-tx',
     'Uncomplicated malaria — treatment plan',
     E'1. Confirm species and parasitaemia on RDT / smear.\n2. First-line ACT (artemether-lumefantrine 80/480 mg) by weight band, 6-dose schedule.\n3. Antipyretic and oral hydration.\n4. Counsel on completion of full course and red-flag symptoms (vomiting, altered consciousness, jaundice).\n5. Re-evaluate in 48–72 h or sooner if not improving.'),
    ('.anc-routine',
     'Routine antenatal visit — assessment',
     E'Gestational age confirmed by LMP / early USG.\nMaternal weight, BP, fundal height and fetal heart tones documented.\nUrine dipstick reviewed.\nIron / folic acid adherence reviewed.\nDanger signs (severe headache, blurred vision, vaginal bleeding, decreased fetal movement) reviewed; patient instructed to present immediately if any occur.\nNext ANC visit scheduled per WHO 2016 8-contact model.')
) AS seed(phrase_trigger, title, expansion)
WHERE NOT EXISTS (
    SELECT 1 FROM clinical.smart_phrases sp
     WHERE sp.scope = 'GLOBAL'
       AND LOWER(sp.phrase_trigger) = LOWER(seed.phrase_trigger)
);

-- Rollback (manual only):
--   DROP INDEX IF EXISTS clinical.uq_smartphrase_user;
--   DROP INDEX IF EXISTS clinical.uq_smartphrase_hospital;
--   DROP INDEX IF EXISTS clinical.uq_smartphrase_global;
--   DROP INDEX IF EXISTS clinical.idx_smartphrase_owner;
--   DROP INDEX IF EXISTS clinical.idx_smartphrase_scope_hospital;
--   DROP INDEX IF EXISTS clinical.idx_smartphrase_trigger;
--   DROP TABLE IF EXISTS clinical.smart_phrases;
