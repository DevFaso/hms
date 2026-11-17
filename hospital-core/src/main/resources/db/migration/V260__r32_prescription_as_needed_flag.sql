-- V260 (QA round 32, D1378 / D1360, coordinator decision 2026-11-03): an
-- as-needed (PRN) prescription is the prescriber's EXPLICIT choice, a flag
-- on the order, no longer a guess from the free-text frequency (round 32
-- found ~45 more as-needed wordings - SOS, p.r.n., sur demande, si precisa,
-- when necessary ... - that created a PRN order with no minimum interval).
--
-- 1. clinical.prescriptions.as_needed BOOLEAN NOT NULL DEFAULT false.
-- 2. Backfill true ONLY for the rows whose frequency the round-31 shared
--    phrase list (MarDoseSchedule.AS_NEEDED_PHRASES, 31 phrases) already
--    read as as-needed, with that reader's rules: a blank frequency is not,
--    a frequency with STAT is a single dose, accents and case are ignored,
--    every run of non-letters/digits is one space, and a phrase matches where
--    a word starts. No other inference: an older order the list did not read
--    keeps false and the MAR keeps it open, asking a reason for every later
--    dose (the round-31 behaviour) until it is edited.
-- 3. The same for the MEDICATION items of the admission order sets (JSONB):
--    an item the list read gets "asNeeded": true, so a set applied before
--    V260 keeps creating the same as-needed order. Items that already carry
--    the key are left alone.
-- 4. QA D1381 (round 32): an admission is cancelled with its reason, who and
--    when (real hospitals record why any stay is voided). Nullable: the
--    admissions cancelled before V260 have none.
-- PrnAsNeededBackfillMigrationIT runs this file on PostgreSQL against the
-- shared fixture and holds it equal to the Java reader. Plain statements, no
-- DO block, no semicolon inside a literal. Idempotent.

ALTER TABLE clinical.prescriptions
    ADD COLUMN IF NOT EXISTS as_needed BOOLEAN NOT NULL DEFAULT false;

UPDATE clinical.prescriptions
   SET as_needed = true
 WHERE as_needed = false
   AND btrim(coalesce(frequency, '')) <> ''
   AND position('STAT' IN upper(translate(frequency, 'àáâãäåçèéêëìíîïñòóôõöùúûüýÿÀÁÂÃÄÅÇÈÉÊËÌÍÎÏÑÒÓÔÕÖÙÚÛÜÝ', 'aaaaaaceeeeiiiinooooouuuuyyAAAAAACEEEEIIIINOOOOOUUUUY'))) = 0
   AND regexp_replace(upper(translate(frequency, 'àáâãäåçèéêëìíîïñòóôõöùúûüýÿÀÁÂÃÄÅÇÈÉÊËÌÍÎÏÑÒÓÔÕÖÙÚÛÜÝ', 'aaaaaaceeeeiiiinooooouuuuyyAAAAAACEEEEIIIINOOOOOUUUUY')), '[^A-Z0-9]+', ' ', 'g')
       ~ '(^|[^A-Z])(PRN|AS NEEDED|AS REQUIRED|WHEN NEEDED|WHEN REQUIRED|IF NEEDED|IF REQUIRED|IF NECESSARY|ON DEMAND|IF PAIN|IF FEVER|SI BESOIN|AU BESOIN|SELON BESOIN|SELON LES BESOINS|EN CAS DE BESOIN|A LA DEMANDE|SI NECESSAIRE|SI DOULEUR|SI FIEVRE|EN CAS DE DOULEUR|EN CAS DE FIEVRE|SI NECESARIO|SI ES NECESARIO|EN CASO NECESARIO|SEGUN NECESIDAD|A DEMANDA|SI DOLOR|SI FIEBRE|EN CASO DE DOLOR|EN CASO DE FIEBRE)';

UPDATE admission_order_sets s
   SET order_items = (
       SELECT jsonb_agg(
                  CASE
                      WHEN jsonb_typeof(t.item) = 'object'
                       AND upper(coalesce(t.item ->> 'orderType', '')) = 'MEDICATION'
                       AND t.item -> 'asNeeded' IS NULL
                       AND btrim(coalesce(t.item ->> 'frequency', '')) <> ''
                       AND position('STAT' IN upper(translate(t.item ->> 'frequency', 'àáâãäåçèéêëìíîïñòóôõöùúûüýÿÀÁÂÃÄÅÇÈÉÊËÌÍÎÏÑÒÓÔÕÖÙÚÛÜÝ', 'aaaaaaceeeeiiiinooooouuuuyyAAAAAACEEEEIIIINOOOOOUUUUY'))) = 0
                       AND regexp_replace(upper(translate(t.item ->> 'frequency', 'àáâãäåçèéêëìíîïñòóôõöùúûüýÿÀÁÂÃÄÅÇÈÉÊËÌÍÎÏÑÒÓÔÕÖÙÚÛÜÝ', 'aaaaaaceeeeiiiinooooouuuuyyAAAAAACEEEEIIIINOOOOOUUUUY')), '[^A-Z0-9]+', ' ', 'g')
                           ~ '(^|[^A-Z])(PRN|AS NEEDED|AS REQUIRED|WHEN NEEDED|WHEN REQUIRED|IF NEEDED|IF REQUIRED|IF NECESSARY|ON DEMAND|IF PAIN|IF FEVER|SI BESOIN|AU BESOIN|SELON BESOIN|SELON LES BESOINS|EN CAS DE BESOIN|A LA DEMANDE|SI NECESSAIRE|SI DOULEUR|SI FIEVRE|EN CAS DE DOULEUR|EN CAS DE FIEVRE|SI NECESARIO|SI ES NECESARIO|EN CASO NECESARIO|SEGUN NECESIDAD|A DEMANDA|SI DOLOR|SI FIEBRE|EN CASO DE DOLOR|EN CASO DE FIEBRE)'
                      THEN t.item || jsonb_build_object('asNeeded', true)
                      ELSE t.item
                  END
                  ORDER BY t.ord)
         FROM jsonb_array_elements(s.order_items) WITH ORDINALITY AS t(item, ord))
 WHERE jsonb_typeof(s.order_items) = 'array'
   AND EXISTS (
       SELECT 1
         FROM jsonb_array_elements(s.order_items) AS e(item)
        WHERE jsonb_typeof(e.item) = 'object'
          AND upper(coalesce(e.item ->> 'orderType', '')) = 'MEDICATION'
          AND e.item -> 'asNeeded' IS NULL
          AND btrim(coalesce(e.item ->> 'frequency', '')) <> ''
          AND position('STAT' IN upper(translate(e.item ->> 'frequency', 'àáâãäåçèéêëìíîïñòóôõöùúûüýÿÀÁÂÃÄÅÇÈÉÊËÌÍÎÏÑÒÓÔÕÖÙÚÛÜÝ', 'aaaaaaceeeeiiiinooooouuuuyyAAAAAACEEEEIIIINOOOOOUUUUY'))) = 0
          AND regexp_replace(upper(translate(e.item ->> 'frequency', 'àáâãäåçèéêëìíîïñòóôõöùúûüýÿÀÁÂÃÄÅÇÈÉÊËÌÍÎÏÑÒÓÔÕÖÙÚÛÜÝ', 'aaaaaaceeeeiiiinooooouuuuyyAAAAAACEEEEIIIINOOOOOUUUUY')), '[^A-Z0-9]+', ' ', 'g')
              ~ '(^|[^A-Z])(PRN|AS NEEDED|AS REQUIRED|WHEN NEEDED|WHEN REQUIRED|IF NEEDED|IF REQUIRED|IF NECESSARY|ON DEMAND|IF PAIN|IF FEVER|SI BESOIN|AU BESOIN|SELON BESOIN|SELON LES BESOINS|EN CAS DE BESOIN|A LA DEMANDE|SI NECESSAIRE|SI DOULEUR|SI FIEVRE|EN CAS DE DOULEUR|EN CAS DE FIEVRE|SI NECESARIO|SI ES NECESARIO|EN CASO NECESARIO|SEGUN NECESIDAD|A DEMANDA|SI DOLOR|SI FIEBRE|EN CASO DE DOLOR|EN CASO DE FIEBRE)');

ALTER TABLE admissions
    ADD COLUMN IF NOT EXISTS cancellation_reason VARCHAR(1000);

ALTER TABLE admissions
    ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMP WITHOUT TIME ZONE;

ALTER TABLE admissions
    ADD COLUMN IF NOT EXISTS cancelled_by_user_id UUID;
