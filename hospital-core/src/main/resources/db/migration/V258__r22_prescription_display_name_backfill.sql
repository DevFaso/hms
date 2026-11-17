-- V258 (QA D1147, round 22).
-- Until the round-21 fix (#1001) an edit of a prescription rewrote its
-- medication_name but kept medication_display_name (written only while blank)
-- and kept the old drug's medication_code when the edit sent none. Rows edited
-- before the fix still name the FIRST drug in the list, the pharmacist's view
-- and the record PDF: dev row c251ffc9 reads medication_name 'Morphine' with
-- display 'Amoxicilline'. New edits are right, this repairs the old rows.
--
-- The display name is a copy of the drug's name: the mapper writes
-- display = name on create and, since #1001, on every edit, and nothing else
-- writes the column. So a row whose display differs from its name is a
-- pre-fix edit (or a row written with no display). In order:
--   1. a pre-fix edit linked to a catalogue item takes that item's code (the
--      item is the edit's own pick: the mapper has copied it on every edit),
--   2. a pre-fix edit with no item, whose code is the code of a catalogue
--      item of its hospital that names the OLD drug (the stale display) and
--      not the current one, loses that code, as the mapper now does when the
--      drug changes, a code no catalogue item explains is left as it is,
--   3. the display name becomes the drug's name.
-- Steps 1 and 2 read the stale display, so they run before step 3.
-- Only clinical.prescriptions is touched: dispenses and administrations keep
-- the name they were recorded under (history).
-- Plain statements, no DO block. Idempotent: after step 3 no row has a
-- display that differs from its name, so a second run changes nothing.

UPDATE clinical.prescriptions p
SET medication_code = c.code
FROM clinical.medication_catalog_items c
WHERE p.medication_catalog_item_id = c.id
  AND c.code IS NOT NULL
  AND p.medication_display_name IS NOT NULL
  AND lower(btrim(p.medication_display_name)) <> lower(btrim(p.medication_name))
  AND p.medication_code IS DISTINCT FROM c.code;

UPDATE clinical.prescriptions p
SET medication_code = NULL
WHERE p.medication_catalog_item_id IS NULL
  AND p.medication_code IS NOT NULL
  AND p.medication_display_name IS NOT NULL
  AND lower(btrim(p.medication_display_name)) <> lower(btrim(p.medication_name))
  AND EXISTS (
      SELECT 1 FROM clinical.medication_catalog_items c
      WHERE c.hospital_id = p.hospital_id
        AND c.code = p.medication_code
        AND lower(btrim(p.medication_display_name)) IN (
            lower(btrim(c.name_fr)), lower(btrim(coalesce(c.generic_name, ''))),
            lower(btrim(coalesce(c.brand_name, ''))))
        AND lower(btrim(p.medication_name)) NOT IN (
            lower(btrim(c.name_fr)), lower(btrim(coalesce(c.generic_name, ''))),
            lower(btrim(coalesce(c.brand_name, '')))));

UPDATE clinical.prescriptions
SET medication_display_name = medication_name
WHERE medication_display_name IS DISTINCT FROM medication_name;
