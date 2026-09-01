
CREATE SCHEMA IF NOT EXISTS ysmdm_admin;

CREATE TABLE IF NOT EXISTS ysmdm_admin.tbl_sp_sebal_subcategory_tags (
  id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
  sp_id                       INT NOT NULL,
  sub_category_id             INT NOT NULL,
  first_job_users_count       BIGINT NOT NULL DEFAULT 0,
  rebooked_90d_users_count    BIGINT NOT NULL DEFAULT 0,
  rebooking_rate              DECIMAL(5,2),
  national_rank               INT,
  tier                        VARCHAR(20),
  last_run_id                 VARCHAR(50),
  created_at                  TIMESTAMP NOT NULL,
  updated_at                  TIMESTAMP NOT NULL,
  CONSTRAINT uq_sp_subcategory UNIQUE (sp_id, sub_category_id)
);

CREATE TABLE IF NOT EXISTS ysmdm_admin.tbl_sp_rose_order_eligibilities (
  id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  sp_id    INT NOT NULL,
  eligible BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS ysmdm_admin.tbl_sp_sebal_subcategory_tag_ledgers (
  id                                      BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id                                  VARCHAR(50) NOT NULL,
  run_date                                TIMESTAMP NOT NULL,
  run_mode                                VARCHAR(20) NOT NULL,
  row_type                                VARCHAR(20) NOT NULL,
  sp_id                                   INT NOT NULL,
  sub_category_id                         INT NOT NULL,
  delta_first_job_users_count             BIGINT NOT NULL DEFAULT 0,
  delta_rebooked_90d_users_count          BIGINT NOT NULL DEFAULT 0,
  cumulative_first_job_users_count        BIGINT NOT NULL DEFAULT 0,
  cumulative_rebooked_90d_users_count     BIGINT NOT NULL DEFAULT 0,
  rebooking_rate                          DECIMAL(5,2),
  national_rank                           INT,
  tier                                     VARCHAR(20),
  created_at                              TIMESTAMP NOT NULL
);
