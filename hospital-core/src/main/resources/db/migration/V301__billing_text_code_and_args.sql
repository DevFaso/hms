-- V301 (QA D1901, billing-money go-live plan PR 1): billing text the server
-- writes is stored as a message code plus its arguments, so each reader sees
-- it in their own language. Until now it was written once, in French (staff
-- locale) or in the recorder's request language, and shown to every reader
-- in that language.
--
-- Adds (nullable, additive):
--   billing.invoice_items.text_code / text_args              (line description)
--   billing.billing_invoices.notes_code / notes_args          (invoice notes)
--   billing.billing_invoices.cancel_reason_code / _args       (cancel reason)
-- *_args is a JSON array of one-key objects: {"value": "..."} shown as is,
-- {"code": "..."} resolved in the reader's language, {"date": "YYYY-MM-DD"}
-- written the reader's way. The existing text column keeps its copy (French
-- for new rows), so search, older clients and NOT NULL keep working.
--
-- Backfill: a code is set ONLY where the stored text equals a template the
-- server wrote, EXACTLY (anchored regular expressions); everything a person
-- typed differently stays as it is, with no code, and is shown as typed.
--   * notes 'Visite <code> clôturée : brouillon à vérifier, tarifer et émettre
--     par la caisse.' (checkout, always French)            -> billing.checkout.draftNote
--   * line 'Consultation' / 'Hospitalisation (journées)', SERVICE, no priced
--     service, on a visit invoice                           -> billing.checkout.consultation / bedDays
--   * line 'Transfusion : <French product label>'          -> billing.checkout.transfusion
--   * copay cancel reason in FR, EN or ES (request locale)  -> billing.invoice.copayCreditedReason
--   * delivery notes and line labels in FR, EN or ES (the recorder's locale;
--     the birth day written ISO or in that language's medium style)
--                                                          -> maternity.delivery.charge.*
-- The stored text itself is never rewritten. Only counts are raised as
-- NOTICEs, never content (PHI rule).
--
-- Idempotent: columns use IF NOT EXISTS and every UPDATE skips rows that
-- already have a code. One DO block, registered with splitStatements="false".
-- Rollback: none declared (forward-only; dropping the columns loses nothing
-- but the per-reader rendering).

ALTER TABLE billing.invoice_items
    ADD COLUMN IF NOT EXISTS text_code VARCHAR(120),
    ADD COLUMN IF NOT EXISTS text_args TEXT;

ALTER TABLE billing.billing_invoices
    ADD COLUMN IF NOT EXISTS notes_code VARCHAR(120),
    ADD COLUMN IF NOT EXISTS notes_args TEXT,
    ADD COLUMN IF NOT EXISTS cancel_reason_code VARCHAR(120),
    ADD COLUMN IF NOT EXISTS cancel_reason_args TEXT;

DO $$
DECLARE
    affected INTEGER;
BEGIN
    -- 1. The checkout draft note (always French: NotificationLocales.STAFF).
    UPDATE billing.billing_invoices
       SET notes_code = 'billing.checkout.draftNote',
           notes_args = json_build_array(json_build_object('value',
               (regexp_match(notes, '^Visite (\S+) clôturée : brouillon à vérifier, tarifer et émettre par la caisse\.$'))[1]))::text
     WHERE notes_code IS NULL
       AND notes ~ '^Visite \S+ clôturée : brouillon à vérifier, tarifer et émettre par la caisse\.$';
    GET DIAGNOSTICS affected = ROW_COUNT;
    RAISE NOTICE 'V301: % checkout draft note(s) coded', affected;

    -- 2. The unpriced consultation and bed-day lines of a checked-out visit.
    UPDATE billing.invoice_items ii
       SET text_code = CASE ii.item_description
                           WHEN 'Consultation' THEN 'billing.checkout.consultation'
                           ELSE 'billing.checkout.bedDays' END
      FROM billing.billing_invoices bi
     WHERE bi.id = ii.billing_invoice_id
       AND bi.encounter_id IS NOT NULL
       AND ii.text_code IS NULL
       AND ii.related_service_id IS NULL
       AND ii.item_category = 'SERVICE'
       AND ii.item_description IN ('Consultation', 'Hospitalisation (journées)');
    GET DIAGNOSTICS affected = ROW_COUNT;
    RAISE NOTICE 'V301: % consultation/bed-day line(s) coded', affected;

    -- 3. Transfusion lines, the French product label mapped back to its type.
    UPDATE billing.invoice_items ii
       SET text_code = 'billing.checkout.transfusion',
           text_args = json_build_array(json_build_object('code', 'transfusion.productType.' || p.product_type))::text
      FROM (VALUES ('sang total', 'WHOLE_BLOOD'),
                   ('concentré de globules rouges', 'PACKED_RED_CELLS'),
                   ('plasma frais congelé', 'FRESH_FROZEN_PLASMA'),
                   ('plaquettes', 'PLATELETS'),
                   ('cryoprécipité', 'CRYOPRECIPITATE')) AS p(label, product_type)
     WHERE ii.text_code IS NULL
       AND ii.item_category = 'SERVICE'
       AND ii.item_description = 'Transfusion : ' || p.label;
    GET DIAGNOSTICS affected = ROW_COUNT;
    RAISE NOTICE 'V301: % transfusion line(s) coded', affected;

    -- 4. The reason a check-in copay was cancelled when carried onto its
    --    visit invoice (written in the request's language: FR, EN or ES).
    UPDATE billing.billing_invoices
       SET cancel_reason_code = 'billing.invoice.copayCreditedReason',
           cancel_reason_args = json_build_array(json_build_object('value', COALESCE(
               (regexp_match(cancel_reason, '^Ticket modérateur reporté sur la facture (\S+)\.$'))[1],
               (regexp_match(cancel_reason, '^Copay carried onto invoice (\S+)\.$'))[1],
               (regexp_match(cancel_reason, '^Copago trasladado a la factura (\S+)\.$'))[1])))::text
     WHERE cancel_reason_code IS NULL
       AND check_in_copay = TRUE
       AND (cancel_reason ~ '^Ticket modérateur reporté sur la facture \S+\.$'
         OR cancel_reason ~ '^Copay carried onto invoice \S+\.$'
         OR cancel_reason ~ '^Copago trasladado a la factura \S+\.$');
    GET DIAGNOSTICS affected = ROW_COUNT;
    RAISE NOTICE 'V301: % copay cancel reason(s) coded', affected;

    -- 5. The delivery charge line (the recorder's language).
    UPDATE billing.invoice_items ii
       SET text_code = m.code
      FROM (VALUES ('Accouchement (voie basse)', 'maternity.delivery.charge.vaginal'),
                   ('Delivery (vaginal birth)', 'maternity.delivery.charge.vaginal'),
                   ('Parto (vía vaginal)', 'maternity.delivery.charge.vaginal'),
                   ('Accouchement (césarienne)', 'maternity.delivery.charge.caesarean'),
                   ('Delivery (caesarean section)', 'maternity.delivery.charge.caesarean'),
                   ('Parto (cesárea)', 'maternity.delivery.charge.caesarean')) AS m(label, code)
     WHERE ii.text_code IS NULL
       AND ii.item_category = 'SERVICE'
       AND ii.item_description = m.label;
    GET DIAGNOSTICS affected = ROW_COUNT;
    RAISE NOTICE 'V301: % delivery line(s) coded', affected;

    -- 6. The delivery charge note: mode label and birth day, in the recorder's
    --    language. The day was written ISO (before QA D1710) or in that
    --    language's medium style ("5 nov. 2026", "Nov 5, 2026", "5 nov 2026");
    --    a day that cannot be read back, or that does not exist on the
--    calendar, leaves the note as it is, with no code (never an error).
    WITH delivery_notes AS (
        SELECT bi.id, t.lang, t.parts
          FROM billing.billing_invoices bi
          CROSS JOIN LATERAL (VALUES
              ('fr', regexp_match(bi.notes, '^Frais de naissance : (Accouchement \(voie basse\)|Accouchement \(césarienne\)), né\(e\) le (.+)\. À tarifer si l''établissement n''a pas de prestation d''accouchement tarifée\.$')),
              ('en', regexp_match(bi.notes, '^Birth charge: (Delivery \(vaginal birth\)|Delivery \(caesarean section\)), born (.+)\. Price it if the hospital has no priced delivery service\.$')),
              ('es', regexp_match(bi.notes, '^Cargo de nacimiento: (Parto \(vía vaginal\)|Parto \(cesárea\)), nacido el (.+)\. Tarifíquelo si el establecimiento no tiene un servicio de parto con precio\.$'))
          ) AS t(lang, parts)
         WHERE bi.notes_code IS NULL
           AND t.parts IS NOT NULL
    ), months(lang, token, month) AS (
        VALUES ('fr', 'janv.', 1), ('fr', 'févr.', 2), ('fr', 'mars', 3), ('fr', 'avr.', 4),
               ('fr', 'mai', 5), ('fr', 'juin', 6), ('fr', 'juil.', 7), ('fr', 'août', 8),
               ('fr', 'sept.', 9), ('fr', 'oct.', 10), ('fr', 'nov.', 11), ('fr', 'déc.', 12),
               ('en', 'Jan', 1), ('en', 'Feb', 2), ('en', 'Mar', 3), ('en', 'Apr', 4),
               ('en', 'May', 5), ('en', 'Jun', 6), ('en', 'Jul', 7), ('en', 'Aug', 8),
               ('en', 'Sep', 9), ('en', 'Oct', 10), ('en', 'Nov', 11), ('en', 'Dec', 12),
               ('es', 'ene', 1), ('es', 'feb', 2), ('es', 'mar', 3), ('es', 'abr', 4),
               ('es', 'may', 5), ('es', 'jun', 6), ('es', 'jul', 7), ('es', 'ago', 8),
               ('es', 'sept', 9), ('es', 'oct', 10), ('es', 'nov', 11), ('es', 'dic', 12)
    ), parsed AS (
        -- The day's parts as integers, never yet a date. Every cast is on a
        -- regex capture of 1 to 4 digits, so it cannot throw.
        SELECT dn.id,
               CASE WHEN dn.parts[1] IN ('Accouchement (césarienne)', 'Delivery (caesarean section)', 'Parto (cesárea)')
                    THEN 'maternity.delivery.charge.caesarean'
                    ELSE 'maternity.delivery.charge.vaginal' END AS mode_code,
               COALESCE(iso.y, loc.y) AS y,
               COALESCE(iso.m, loc.m) AS m,
               COALESCE(iso.d, loc.d) AS d
          FROM delivery_notes dn
          LEFT JOIN LATERAL (
              SELECT x[1]::int AS y, x[2]::int AS m, x[3]::int AS d
                FROM regexp_match(dn.parts[2], '^(\d{4})-(\d{2})-(\d{2})$') AS x
               WHERE x IS NOT NULL) iso ON TRUE
          LEFT JOIN LATERAL (
              SELECT x[3]::int AS y, mo.month AS m, x[2]::int AS d
                FROM regexp_match(dn.parts[2], '^(\S+) (\d{1,2}), (\d{4})$') AS x
                JOIN months mo ON mo.lang = 'en' AND mo.token = x[1]
               WHERE dn.lang = 'en'
              UNION ALL
              SELECT x[3]::int, mo.month, x[1]::int
                FROM regexp_match(dn.parts[2], '^(\d{1,2}) (\S+) (\d{4})$') AS x
                JOIN months mo ON mo.lang = dn.lang AND mo.token = x[2]
               WHERE dn.lang <> 'en') loc ON TRUE
    ), dated AS (
        -- A day is kept only when it exists on the calendar (31 Feb, day 0,
        -- month 13, year 0, 29 Feb of a common year are not), checked with
        -- integer arithmetic and written with lpad: no make_date, no ::date,
        -- nothing here can raise, so stored text can never stop the deploy.
        -- A day that does not exist leaves the note with no code, as typed.
        SELECT p.id, p.mode_code,
               CASE WHEN p.y BETWEEN 1 AND 9999 AND p.m BETWEEN 1 AND 12
                         AND p.d BETWEEN 1 AND CASE
                             WHEN p.m IN (4, 6, 9, 11) THEN 30
                             WHEN p.m = 2 AND (p.y % 4 = 0 AND (p.y % 100 <> 0 OR p.y % 400 = 0)) THEN 29
                             WHEN p.m = 2 THEN 28
                             ELSE 31 END
                    THEN lpad(p.y::text, 4, '0') || '-' || lpad(p.m::text, 2, '0') || '-' || lpad(p.d::text, 2, '0')
               END AS birth_day
          FROM parsed p
    )
    UPDATE billing.billing_invoices bi
       SET notes_code = 'maternity.delivery.charge.note',
           notes_args = json_build_array(json_build_object('code', d.mode_code),
                                         json_build_object('date', d.birth_day))::text
      FROM dated d
     WHERE bi.id = d.id
       AND d.birth_day IS NOT NULL;
    GET DIAGNOSTICS affected = ROW_COUNT;
    RAISE NOTICE 'V301: % delivery charge note(s) coded', affected;
END
$$;
