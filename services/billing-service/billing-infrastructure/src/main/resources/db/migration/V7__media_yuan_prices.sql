-- Preserve existing point prices; edited/new prices can be denominated in yuan.
ALTER TABLE media_price MODIFY COLUMN points_per_unit DECIMAL(16,4) NULL;
ALTER TABLE media_price ADD COLUMN yuan_per_unit DECIMAL(16,4) NULL;
ALTER TABLE media_price ADD CONSTRAINT chk_media_price_currency CHECK (
  (points_per_unit IS NOT NULL AND yuan_per_unit IS NULL)
  OR (points_per_unit IS NULL AND yuan_per_unit IS NOT NULL AND yuan_per_unit >= 0)
);
