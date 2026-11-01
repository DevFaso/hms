-- V257 (QA D1169, round 21).
-- The triage and ward vitals wrote the pain score (and the triage the fundal
-- height) into the free-text notes, as a sentence in the nurse's language
-- ("Douleur : 8/10"), so a Spanish or English reader read French and nothing
-- could compare the score. They are numbers now, rendered in the reader's
-- language when read. Existing rows: the score and the height composed by
-- the two writers (the exact sentences of triage.vitals.painScale and
-- triage.vitals.fundalHeight in en, fr and es) are moved out of the notes
-- into the new columns; what the nurse typed stays in the notes.
-- Plain statements, no DO block. Idempotent: a row whose sentence was moved
-- no longer matches.

ALTER TABLE clinical.patient_vital_signs
    ADD COLUMN IF NOT EXISTS pain_score INTEGER;

ALTER TABLE clinical.patient_vital_signs
    ADD COLUMN IF NOT EXISTS fundal_height_cm INTEGER;

UPDATE clinical.patient_vital_signs
SET pain_score = CAST(substring(notes FROM '(?:Douleur : |Pain: |Dolor: )([0-9]{1,2})/10') AS INTEGER)
WHERE pain_score IS NULL
  AND notes ~ '(Douleur : |Pain: |Dolor: )[0-9]{1,2}/10';

UPDATE clinical.patient_vital_signs
SET fundal_height_cm = CAST(substring(notes FROM '(?:Hauteur utérine : |Fundal height: |Altura uterina: )([0-9]{1,3}) cm') AS INTEGER)
WHERE fundal_height_cm IS NULL
  AND notes ~ '(Hauteur utérine : |Fundal height: |Altura uterina: )[0-9]{1,3} cm';

UPDATE clinical.patient_vital_signs
SET notes = NULLIF(
        regexp_replace(
            regexp_replace(
                regexp_replace(notes,
                    '(Douleur : |Pain: |Dolor: )[0-9]{1,2}/10|(Hauteur utérine : |Fundal height: |Altura uterina: )[0-9]{1,3} cm',
                    '', 'g'),
                '( · )+', ' · ', 'g'),
            '^ · | · $', '', 'g'),
        '')
WHERE notes ~ '(Douleur : |Pain: |Dolor: )[0-9]{1,2}/10|(Hauteur utérine : |Fundal height: |Altura uterina: )[0-9]{1,3} cm';
