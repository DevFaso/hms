-- V305 (billing-money go-live plan PR 6, scorecard M1): each hospital's payer
-- catalogue ("organismes payeurs") and the link from a patient's insurance to
-- one of its plans.
--
-- Until now a patient's coverage was free text (payer_code, provider_name):
-- no payer table, no coverage rate, no exclusion and no ceiling. This adds the
-- catalogue the hospital admin and the accountant maintain (owner decision
-- D-1, 2026-11-15: never the cashier, never the platform super-admin alone).
-- Nothing applies it to an invoice yet: the coverage calculator is PR 7 (V306).
--
-- billing.payers - one payer of a hospital: its own code, the name as typed
--   (shown untranslated, D-8) and a type. Deactivating keeps the row, with who,
--   when and why; an inactive payer's plans are no longer linked to anyone new.
--
-- billing.payer_plans - a payer's plan ("formule"): the default coverage rate
--   (whole percent, 0-100), an optional ceiling per invoice and per beneficiary
--   per calendar year (whole francs, > 0), and the dates it is offered
--   (effective_from .. effective_to, inclusive; open-ended while null;
--   effective_to = effective_from - 1 marks a plan withdrawn before it started).
--
-- billing.payer_plan_rules - the exceptions to a plan's default, each on
--   EXACTLY ONE target: a price-list category or one price-list item of the same
--   hospital. A rule overrides the rate, excludes the target, and/or caps the
--   payer's share per act. Rules are versioned like prices (V302): insert-only,
--   effective-dated; a new rule for the same target closes the one in force on
--   the day before it starts, and the only later update is effective_to (plus
--   who ended it and why). effective_to = effective_from - 1 marks a rule
--   replaced or withdrawn before it applied (kept in the history).
--
-- clinical.patient_insurances + payer_plan_id - the plan a patient's coverage
--   is billed under (NULL for every existing row: free-text coverage stays as
--   it is and gets no payer share, plan A-9). The service accepts only a plan
--   of the hospital the coverage row belongs to.
--
-- Idempotent (IF NOT EXISTS, CHECKs created with their tables, ADD COLUMN IF
-- NOT EXISTS, DROP CONSTRAINT IF EXISTS before the one ADD). Plain DDL, no DO
-- block. Rollback: none declared (forward-only;
-- the three tables are new and the column is nullable).
-- V301-V319 are reserved for the billing-money plan.

CREATE SCHEMA IF NOT EXISTS billing;

CREATE TABLE IF NOT EXISTS billing.payers (
    id                  UUID          NOT NULL DEFAULT gen_random_uuid(),
    hospital_id         UUID          NOT NULL,
    code                VARCHAR(40)   NOT NULL,
    name                VARCHAR(150)  NOT NULL,
    payer_type          VARCHAR(30)   NOT NULL,
    active              BOOLEAN       NOT NULL DEFAULT TRUE,
    deactivated_at      TIMESTAMP,
    deactivated_by      UUID,
    deactivation_reason VARCHAR(500),
    created_by          UUID,
    updated_by          UUID,
    created_at          TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_payers                PRIMARY KEY (id),
    CONSTRAINT fk_payer_hospital        FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id),
    CONSTRAINT uq_payer_code            UNIQUE (hospital_id, code),
    -- Target of the plans' composite FK: a plan always carries its payer's hospital.
    CONSTRAINT uq_payer_id_hospital     UNIQUE (id, hospital_id),
    CONSTRAINT ck_payer_code            CHECK (code = upper(btrim(code)) AND length(code) > 0),
    CONSTRAINT ck_payer_name            CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_payer_type            CHECK (payer_type IN ('AMU', 'MUTUELLE', 'ASSUREUR_PRIVE', 'EMPLOYEUR',
        'ONG_PROGRAMME')),
    CONSTRAINT ck_payer_deactivated     CHECK (active
        OR (deactivated_at IS NOT NULL AND deactivation_reason IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS billing.payer_plans (
    id                             UUID           NOT NULL DEFAULT gen_random_uuid(),
    hospital_id                    UUID           NOT NULL,
    payer_id                       UUID           NOT NULL,
    code                           VARCHAR(40)    NOT NULL,
    name                           VARCHAR(150)   NOT NULL,
    default_coverage_percent       INTEGER        NOT NULL,
    per_invoice_ceiling            NUMERIC(12, 2),
    annual_ceiling_per_beneficiary NUMERIC(12, 2),
    effective_from                 DATE           NOT NULL,
    effective_to                   DATE,
    created_by                     UUID,
    updated_by                     UUID,
    created_at                     TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at                     TIMESTAMP      NOT NULL DEFAULT now(),

    CONSTRAINT pk_payer_plans               PRIMARY KEY (id),
    CONSTRAINT fk_payer_plan_hospital       FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id),
    CONSTRAINT fk_payer_plan_payer          FOREIGN KEY (payer_id, hospital_id)
        REFERENCES billing.payers (id, hospital_id),
    CONSTRAINT uq_payer_plan_code           UNIQUE (payer_id, code),
    -- Target of the rules' composite FK: a rule always carries its plan's hospital.
    CONSTRAINT uq_payer_plan_id_hospital    UNIQUE (id, hospital_id),
    CONSTRAINT ck_payer_plan_code           CHECK (code = upper(btrim(code)) AND length(code) > 0),
    CONSTRAINT ck_payer_plan_name           CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_payer_plan_percent        CHECK (default_coverage_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_payer_plan_invoice_cap    CHECK (per_invoice_ceiling IS NULL
        OR (per_invoice_ceiling > 0 AND per_invoice_ceiling = trunc(per_invoice_ceiling))),
    CONSTRAINT ck_payer_plan_annual_cap     CHECK (annual_ceiling_per_beneficiary IS NULL
        OR (annual_ceiling_per_beneficiary > 0
            AND annual_ceiling_per_beneficiary = trunc(annual_ceiling_per_beneficiary))),
    -- effective_to = effective_from - 1 marks a plan withdrawn before it started.
    CONSTRAINT ck_payer_plan_range          CHECK (effective_to IS NULL OR effective_to >= effective_from - 1)
);

CREATE INDEX IF NOT EXISTS idx_payer_plan_hospital
    ON billing.payer_plans (hospital_id);

CREATE TABLE IF NOT EXISTS billing.payer_plan_rules (
    id                  UUID           NOT NULL DEFAULT gen_random_uuid(),
    hospital_id         UUID           NOT NULL,
    plan_id             UUID           NOT NULL,
    category            VARCHAR(30),
    price_list_item_id  UUID,
    coverage_percent    INTEGER,
    excluded            BOOLEAN        NOT NULL DEFAULT FALSE,
    per_act_ceiling     NUMERIC(12, 2),
    effective_from      DATE           NOT NULL,
    effective_to        DATE,
    created_by          UUID,
    ended_at            TIMESTAMP,
    ended_by            UUID,
    end_reason          VARCHAR(500),
    created_at          TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP      NOT NULL DEFAULT now(),

    CONSTRAINT pk_payer_plan_rules          PRIMARY KEY (id),
    CONSTRAINT fk_payer_rule_hospital       FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id),
    CONSTRAINT fk_payer_rule_plan           FOREIGN KEY (plan_id, hospital_id)
        REFERENCES billing.payer_plans (id, hospital_id),
    -- An item rule names an item of the same hospital (MATCH SIMPLE: a category rule has no item to check).
    CONSTRAINT fk_payer_rule_item           FOREIGN KEY (price_list_item_id, hospital_id)
        REFERENCES billing.price_list_items (id, hospital_id),
    -- Exactly one target: a category, or one price-list item.
    CONSTRAINT ck_payer_rule_target         CHECK ((category IS NULL) <> (price_list_item_id IS NULL)),
    CONSTRAINT ck_payer_rule_category       CHECK (category IS NULL OR category IN ('CONSULTATION', 'LAB_TEST',
        'IMAGING', 'PROCEDURE', 'BED_DAY', 'TRANSFUSION', 'DELIVERY', 'OTHER_SERVICE')),
    CONSTRAINT ck_payer_rule_percent        CHECK (coverage_percent IS NULL OR coverage_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_payer_rule_act_cap        CHECK (per_act_ceiling IS NULL
        OR (per_act_ceiling > 0 AND per_act_ceiling = trunc(per_act_ceiling))),
    -- An exclusion carries no rate and no ceiling; any other rule changes at least one of them.
    CONSTRAINT ck_payer_rule_terms          CHECK (
        (excluded AND coverage_percent IS NULL AND per_act_ceiling IS NULL)
        OR (NOT excluded AND (coverage_percent IS NOT NULL OR per_act_ceiling IS NOT NULL))),
    -- effective_to = effective_from - 1 marks a rule replaced or withdrawn before it applied.
    CONSTRAINT ck_payer_rule_range          CHECK (effective_to IS NULL OR effective_to >= effective_from - 1)
);

CREATE INDEX IF NOT EXISTS idx_payer_rule_plan
    ON billing.payer_plan_rules (plan_id, effective_from);
CREATE INDEX IF NOT EXISTS idx_payer_rule_hospital
    ON billing.payer_plan_rules (hospital_id);

-- One live rule per target and start day, and at most one open-ended rule per target.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payer_rule_category_from
    ON billing.payer_plan_rules (plan_id, category, effective_from)
    WHERE category IS NOT NULL AND (effective_to IS NULL OR effective_to >= effective_from);
CREATE UNIQUE INDEX IF NOT EXISTS uq_payer_rule_item_from
    ON billing.payer_plan_rules (plan_id, price_list_item_id, effective_from)
    WHERE price_list_item_id IS NOT NULL AND (effective_to IS NULL OR effective_to >= effective_from);
CREATE UNIQUE INDEX IF NOT EXISTS uq_payer_rule_category_open
    ON billing.payer_plan_rules (plan_id, category)
    WHERE category IS NOT NULL AND effective_to IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_payer_rule_item_open
    ON billing.payer_plan_rules (plan_id, price_list_item_id)
    WHERE price_list_item_id IS NOT NULL AND effective_to IS NULL;

ALTER TABLE clinical.patient_insurances
    ADD COLUMN IF NOT EXISTS payer_plan_id UUID;

ALTER TABLE clinical.patient_insurances DROP CONSTRAINT IF EXISTS fk_pi_payer_plan;
ALTER TABLE clinical.patient_insurances ADD CONSTRAINT fk_pi_payer_plan
    FOREIGN KEY (payer_plan_id) REFERENCES billing.payer_plans (id);

CREATE INDEX IF NOT EXISTS idx_pi_payer_plan
    ON clinical.patient_insurances (payer_plan_id);
