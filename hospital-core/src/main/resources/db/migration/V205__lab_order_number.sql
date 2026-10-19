-- QA D291 (D177 / D227 not fixed): a lab order had no code of its own, so
-- every screen showed its UUID (or the UUID's first 8 characters). Each
-- order now gets a readable number, LAB-000001, from a sequence, and existing
-- orders are numbered in order-date order. Plain statements, no DO block.
CREATE SEQUENCE IF NOT EXISTS lab.lab_order_number_seq;

ALTER TABLE lab.lab_orders ADD COLUMN IF NOT EXISTS order_number VARCHAR(20);

UPDATE lab.lab_orders o
   SET order_number = 'LAB-' || lpad(n.rn::text, 6, '0')
  FROM (SELECT id, row_number() OVER (ORDER BY order_datetime, id) AS rn
          FROM lab.lab_orders) n
 WHERE o.id = n.id
   AND o.order_number IS NULL;

SELECT setval('lab.lab_order_number_seq', GREATEST((SELECT count(*) FROM lab.lab_orders), 1));

ALTER TABLE lab.lab_orders
    ALTER COLUMN order_number SET DEFAULT ('LAB-' || lpad(nextval('lab.lab_order_number_seq')::text, 6, '0'));

CREATE UNIQUE INDEX IF NOT EXISTS ux_lab_orders_order_number ON lab.lab_orders (order_number);
