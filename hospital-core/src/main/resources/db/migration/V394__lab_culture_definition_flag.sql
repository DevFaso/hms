-- D3904: culture routing is explicit catalogue metadata, not a runtime guess from a free-text label.
ALTER TABLE lab.lab_test_definitions ADD COLUMN culture boolean NOT NULL DEFAULT false;

WITH candidates AS (
    SELECT id,
        translate(lower(trim(coalesce(category, ''))), 'àáâãäåèéêëìíîïòóôõöùúûüçñ', 'aaaaaaeeeeiiiiooooouuuucn') AS category_key,
        translate(lower(concat_ws(' ', test_code, name)), 'àáâãäåèéêëìíîïòóôõöùúûüçñ', 'aaaaaaeeeeiiiiooooouuuucn') AS test_key
    FROM lab.lab_test_definitions
)
UPDATE lab.lab_test_definitions AS definition
SET culture = true
FROM candidates
WHERE definition.id = candidates.id
  AND (candidates.category_key IN ('microbiologie', 'microbiology', 'bacteriologie')
       OR candidates.test_key ~ '\m(ecbu|hemocultures?|coprocultures?|cultures?|antibiogrammes?)\M'
       OR candidates.test_key ~ '\mprelevement\M.*\mantibiogramme');
