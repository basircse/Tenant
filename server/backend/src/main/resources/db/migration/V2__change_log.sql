-- Row-level change history for every entity table, written by ChangeLogListener in the same
-- transaction as the change itself. Feeds the vendor data console's activity views.
--
-- No foreign keys on purpose: the history must outlive the rows it describes.
CREATE TABLE change_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    org_id      CHAR(36)     NOT NULL,
    table_name  VARCHAR(64)  NOT NULL,
    row_id      VARCHAR(64)  NOT NULL,
    op          VARCHAR(10)  NOT NULL,           -- INSERT | UPDATE | DELETE
    -- {"column": {"old": ..., "new": ...}}; secrets are written as "***".
    changes     JSON         NOT NULL,
    actor_id    CHAR(36),
    actor_name  VARCHAR(150) NOT NULL,
    actor_role  VARCHAR(20),
    -- Hibernate session (one per transaction): groups the rows touched by one action.
    session_id  CHAR(36),
    created_at  DATETIME(6)  NOT NULL,
    KEY idx_change_log_time (created_at DESC),
    KEY idx_change_log_org_time (org_id, created_at DESC),
    KEY idx_change_log_row (table_name, row_id, created_at DESC)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
