-- D3904: culture routing is explicit catalogue metadata, not a runtime guess from a free-text label.
ALTER TABLE lab.lab_test_definitions ADD COLUMN culture boolean NOT NULL DEFAULT false;

-- Only reviewed culture codes or unambiguous names are auto-enabled. Category-only matches need catalogue review.
WITH candidates AS (
    SELECT id,
        upper(trim(coalesce(test_code, ''))) AS code_key,
        translate(lower(coalesce(name, '')), 'àáâãäåèéêëìíîïòóôõöùúûüçñ', 'aaaaaaeeeeiiiiooooouuuucn') AS name_key
    FROM lab.lab_test_definitions
)
UPDATE lab.lab_test_definitions AS definition
SET culture = true
FROM candidates
WHERE definition.id = candidates.id
  AND (candidates.code_key IN ('ECBU', 'COPROCULTURE', 'TWP-ECBU')
       OR candidates.name_key ~ '\m(ecbu|hemocultures?|coprocultures?|cultures?|antibiogram(me)?s?)\M'
       OR candidates.name_key ~ '\mprelevement\M.*\mantibiogram(me)?s?\M');
