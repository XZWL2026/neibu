ALTER TABLE payment_order ADD COLUMN refunded_at VARCHAR(40) NULL;
-- Only existing confirmed refund events can establish historical completion time.
UPDATE payment_order o
JOIN payment_promotion_delivery d ON d.order_id = o.id AND d.version = 2
SET o.refunded_at = d.occurred_at
WHERE o.status = 'REFUNDED';
