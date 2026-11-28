-- D3905 (blood-group half, r81 D4144): a blood-group order is closed by the grouping the blood bank records.
-- Which definitions are blood-group tests is explicit catalogue metadata, as the culture flag is (V394).
ALTER TABLE lab.lab_test_definitions ADD COLUMN IF NOT EXISTS blood_grouping boolean NOT NULL DEFAULT false;

-- Only reviewed codes, the ABO and Rh LOINC code or unambiguous names are auto-enabled. A screen alone (RAI)
-- and a crossmatch are not a grouping; anything else is set in the catalogue through its approval.
WITH candidates AS (
    SELECT id,
        upper(trim(coalesce(test_code, ''))) AS code_key,
        upper(trim(coalesce(loinc_code, ''))) AS loinc_key,
        translate(lower(coalesce(name, '')), 'àáâãäåèéêëìíîïòóôõöùúûüçñ', 'aaaaaaeeeeiiiiooooouuuucn') AS name_key
    FROM lab.lab_test_definitions
)
UPDATE lab.lab_test_definitions AS definition
SET blood_grouping = true
FROM candidates
WHERE definition.id = candidates.id
  AND definition.culture = false
  AND (candidates.code_key IN ('TWP-GS-RH', 'GS-RH', 'GSRH', 'ABO-RH', 'ABORH', 'GROUPAGE')
       OR candidates.loinc_key IN ('882-1')
       OR candidates.name_key ~ '\mgroupe sanguin\M'
       OR candidates.name_key ~ '\mgroupage\M'
       OR candidates.name_key ~ '\mblood (group|type)(ing)?\M');
