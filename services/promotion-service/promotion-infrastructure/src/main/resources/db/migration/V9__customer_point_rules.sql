ALTER TABLE promotion_benefit_policy ADD COLUMN changed_field VARCHAR(32);

CREATE TABLE promotion_customer_point_override (
  policy_version BIGINT NOT NULL,
  customer_user_id VARCHAR(128) NOT NULL,
  point_rate6 DECIMAL(18,6) NOT NULL,
  PRIMARY KEY (policy_version, customer_user_id),
  CONSTRAINT promotion_customer_point_rate CHECK (point_rate6 BETWEEN 0 AND 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
