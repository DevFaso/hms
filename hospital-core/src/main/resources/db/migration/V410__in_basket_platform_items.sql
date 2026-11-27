-- QA D4308: an in-basket item addressed to the platform administrators can
-- concern no single hospital (a provider facility to verify, an integration
-- failing repeatedly). Until now every item had to name a hospital, so nothing
-- could be filed for them and their inbox was always empty.
ALTER TABLE clinical.in_basket_items ALTER COLUMN hospital_id DROP NOT NULL;
