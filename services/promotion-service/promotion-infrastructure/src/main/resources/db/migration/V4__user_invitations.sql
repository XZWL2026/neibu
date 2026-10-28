CREATE TABLE promotion_invitation (
 user_id VARCHAR(128) NOT NULL PRIMARY KEY,
 invitation_code VARCHAR(64) NOT NULL UNIQUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
INSERT INTO promotion_invitation(user_id,invitation_code) SELECT promoter_user_id,invitation_code FROM promoter_account;
CREATE TABLE promotion_referral_guard (id INT NOT NULL PRIMARY KEY);
INSERT INTO promotion_referral_guard(id) VALUES(1);
