-- V450 (QA D4221, round 81): a visit is billed the consultation tariff of its kind, not always the default one.
--
-- The price list now knows more system links than « CONSULTATION_DEFAULT » and « BED_DAY » (emergency by day and
-- by night, follow-up, antenatal, postnatal, four specialties, a stay in observation). A hospital links its own
-- items to them in the price list screen. The items below come from the platform's starter price list, where they
-- were delivered unlinked: they are linked here so that a hospital that kept the starter codes is billed correctly
-- without touching its list.
--
-- Only an ACTIVE item that has NO link is touched, only when its code and its category are the starter's, and only
-- when no other active item of the same hospital already holds that link (uq_price_list_item_active_link).
-- No price, no label, no invoice and no invoice line is changed: a line already posted keeps what it copied.

UPDATE billing.price_list_items item
   SET link_type = 'SYSTEM',
       link_key = starter.link_key,
       updated_at = now()
  FROM (VALUES
        ('CS-URG',      'CONSULTATION', 'CONSULTATION_EMERGENCY'),
        ('CS-URG-NUIT', 'CONSULTATION', 'CONSULTATION_EMERGENCY_NIGHT'),
        ('CS-CTRL',     'CONSULTATION', 'CONSULTATION_FOLLOW_UP'),
        ('CS-CPN',      'CONSULTATION', 'CONSULTATION_PRENATAL'),
        ('CS-CPON',     'CONSULTATION', 'CONSULTATION_POSTNATAL'),
        ('CS-PED',      'CONSULTATION', 'CONSULTATION_PAEDIATRICS'),
        ('CS-GYN',      'CONSULTATION', 'CONSULTATION_GYNAECOLOGY'),
        ('CS-CHIR',     'CONSULTATION', 'CONSULTATION_SURGERY'),
        ('CS-MED',      'CONSULTATION', 'CONSULTATION_INTERNAL_MEDICINE'),
        ('HOSP-OBS',    'BED_DAY',      'BED_DAY_OBSERVATION')
       ) AS starter (code, category, link_key)
 WHERE item.code = starter.code
   AND item.category = starter.category
   AND item.active
   AND item.link_type IS NULL
   AND item.link_key IS NULL
   AND NOT EXISTS (
        SELECT 1
          FROM billing.price_list_items taken
         WHERE taken.hospital_id = item.hospital_id
           AND taken.active
           AND taken.link_type = 'SYSTEM'
           AND taken.link_key = starter.link_key);
