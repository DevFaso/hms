-- V246 (QA D1053, the D665 class): an in-basket item's title and body were
-- resolved once, in the staff language (French), and stored as prose, so a
-- clinician reading in Spanish or English saw "Correction : ... Ancienne
-- valeur ..., nouvelle valeur ...". The row now also keeps the message keys and
-- their arguments, and the text is rendered in the reader's language when it
-- is read. The French text stays in title/body for old rows and old clients.
-- Rows written before this have no key and keep showing their stored text.
-- Plain statements, no DO block. Idempotent.

ALTER TABLE clinical.in_basket_items
    ADD COLUMN IF NOT EXISTS title_key VARCHAR(150);
ALTER TABLE clinical.in_basket_items
    ADD COLUMN IF NOT EXISTS title_args JSONB;
ALTER TABLE clinical.in_basket_items
    ADD COLUMN IF NOT EXISTS body_key VARCHAR(150);
ALTER TABLE clinical.in_basket_items
    ADD COLUMN IF NOT EXISTS body_args JSONB;
