-- Blocking a user (status BLOCKED): who did it, when and why. An organisation admin can block the
-- users of their organisation; the vendor can block anyone, and only the vendor can lift a block
-- they placed (blocked_by_vendor).
ALTER TABLE users
    ADD COLUMN blocked_reason     TEXT,
    ADD COLUMN blocked_by         VARCHAR(150),
    ADD COLUMN blocked_at         DATETIME(6),
    ADD COLUMN blocked_by_vendor  BOOLEAN NOT NULL DEFAULT FALSE;
