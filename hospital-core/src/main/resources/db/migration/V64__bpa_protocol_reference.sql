-- V64: P1 #3b — Best-Practice-Advisory protocol reference data
--
-- Adds clinical.bpa_protocols, the catalog the BpaRule implementations
-- look up to render their cards. Persisting the protocol metadata as
-- data (instead of hard-coding card text in Java) lets future PRs
-- localise summaries and update protocol_url links without a code
-- change, and lets future per-hospital toggles (P1 follow-up) join on
-- protocol_code.
--
-- Three protocols are seeded matching the rule set landed in this PR:
--   * MALARIA_FEVER       — WHO febrile-illness referral
--   * SEPSIS_QSOFA        — Hour-1 sepsis bundle (cultures/lactate/abx)
--   * OB_HEMORRHAGE       — FIGO PPH bundle / EMOTIVE
--
-- Additive only; idempotent (CREATE IF NOT EXISTS / ON CONFLICT).

CREATE TABLE IF NOT EXISTS clinical.bpa_protocols (
    id              UUID PRIMARY KEY,
    protocol_code   VARCHAR(60) NOT NULL,
    name            VARCHAR(200) NOT NULL,
    summary         VARCHAR(500) NOT NULL,
    protocol_url    VARCHAR(500),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_bpa_protocols_code UNIQUE (protocol_code)
);

-- Active-only partial index so rule lookups skip retired protocols
-- without scanning the full table.
CREATE INDEX IF NOT EXISTS idx_bpa_protocols_active_code
    ON clinical.bpa_protocols (protocol_code)
    WHERE is_active = TRUE;

-- Seed the three protocols this PR ships rules for. ON CONFLICT keeps
-- the migration idempotent (rerun on dev shouldn't duplicate seeds);
-- protocol_url + summary are refreshed on conflict so editorial
-- updates land via re-running migrations on dev.
INSERT INTO clinical.bpa_protocols (id, protocol_code, name, summary, protocol_url, is_active)
VALUES
    (
        gen_random_uuid(),
        'MALARIA_FEVER',
        'Malaria — febrile patient workup',
        'Patient has documented fever ≥38.5°C in the last 24h with no active anti-malarial therapy. Order rapid diagnostic test or thick-and-thin smear; treat per WHO guideline if positive.',
        'https://www.who.int/publications/i/item/guidelines-for-malaria',
        TRUE
    ),
    (
        gen_random_uuid(),
        'SEPSIS_QSOFA',
        'Sepsis — Hour-1 bundle (qSOFA ≥2)',
        'Two or more qSOFA criteria met in the last 6h (RR≥22, sBP≤100, altered mental status). Draw cultures + lactate, give broad-spectrum antibiotics within one hour, start fluid resuscitation.',
        'https://www.sccm.org/SurvivingSepsisCampaign/Guidelines',
        TRUE
    ),
    (
        gen_random_uuid(),
        'OB_HEMORRHAGE',
        'Postpartum hemorrhage — bundle activation',
        'Postpartum patient with HR>100 or sBP<90 within 6h of delivery. Activate PPH bundle: uterine massage, uterotonics, IV access ×2, type & cross, escalate to obstetric team.',
        'https://www.figo.org/figo-postpartum-haemorrhage-initiative',
        TRUE
    )
ON CONFLICT (protocol_code) DO UPDATE SET
    name         = EXCLUDED.name,
    summary      = EXCLUDED.summary,
    protocol_url = EXCLUDED.protocol_url,
    is_active    = EXCLUDED.is_active,
    updated_at   = NOW();
