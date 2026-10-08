-- Tenant Management System - schema for MySQL 8.0+ (8.4 LTS recommended).
--
-- Every business table carries org_id: each landlord organisation is an isolated tenant, and
-- Hibernate's @TenantId adds "org_id = :currentOrg" to every query automatically.
--
-- Conventions:
--  * ids are UUIDs stored as CHAR(36) (readable in any SQL client);
--  * instants are DATETIME(6) in UTC (hibernate.jdbc.time_zone = UTC);
--  * text uses utf8mb4 with a case-insensitive collation, so unique usernames, unit numbers etc.
--    ignore letter case without functional indexes;
--  * MySQL has no partial indexes: "at most one current agreement per unit" and similar rules use
--    STORED generated columns that are NULL for rows the rule does not cover (NULLs never clash
--    in a unique index).

CREATE TABLE organizations (
    id                  CHAR(36)     NOT NULL PRIMARY KEY,
    name                VARCHAR(150) NOT NULL,
    contact_name        VARCHAR(150),
    phone               VARCHAR(30),
    email               VARCHAR(150),
    address             TEXT,
    status              VARCHAR(20)  NOT NULL,          -- PENDING | ACTIVE | SUSPENDED | REJECTED
    license_expires_at  DATETIME(6),
    max_units           INT,
    max_devices         INT,
    currency            VARCHAR(10)  NOT NULL DEFAULT '৳',
    expiry_alert_days   INT          NOT NULL DEFAULT 30,
    auto_generate_rent  BOOLEAN      NOT NULL DEFAULT TRUE,
    is_vendor           BOOLEAN      NOT NULL DEFAULT FALSE, -- the software vendor's own organisation
    plan                VARCHAR(30),
    license_note        TEXT,
    approved_at         DATETIME(6),
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Per-organisation sequences for human readable codes (P-1001, T-10001, INV-1 ...).
CREATE TABLE code_counters (
    org_id      CHAR(36)    NOT NULL,
    prefix      VARCHAR(10) NOT NULL,
    counter_value BIGINT      NOT NULL,
    PRIMARY KEY (org_id, prefix),
    CONSTRAINT fk_code_counters_org FOREIGN KEY (org_id) REFERENCES organizations (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE properties (
    id              CHAR(36)     NOT NULL PRIMARY KEY,
    org_id          CHAR(36)     NOT NULL,
    code            VARCHAR(20)  NOT NULL,
    name            VARCHAR(150) NOT NULL,
    type            VARCHAR(20)  NOT NULL,
    address         TEXT         NOT NULL,
    city            VARCHAR(100) NOT NULL,
    owner_name      VARCHAR(150),
    contact_number  VARCHAR(30),
    floors          INT          NOT NULL,
    description     TEXT,
    status          VARCHAR(20)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    UNIQUE KEY uq_properties_code (org_id, code),
    CONSTRAINT fk_properties_org FOREIGN KEY (org_id) REFERENCES organizations (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE units (
    id                CHAR(36)       NOT NULL PRIMARY KEY,
    org_id            CHAR(36)       NOT NULL,
    property_id       CHAR(36)       NOT NULL,
    code              VARCHAR(20)    NOT NULL,
    floor             VARCHAR(20)    NOT NULL,
    unit_no           VARCHAR(30)    NOT NULL,
    unit_type         VARCHAR(50),
    size              VARCHAR(50),
    monthly_rent      DECIMAL(14, 2) NOT NULL,
    service_charge    DECIMAL(14, 2) NOT NULL DEFAULT 0,
    utility_charge    DECIMAL(14, 2) NOT NULL DEFAULT 0,
    other_charge      DECIMAL(14, 2) NOT NULL DEFAULT 0,
    security_deposit  DECIMAL(14, 2) NOT NULL DEFAULT 0,
    status            VARCHAR(20)    NOT NULL,
    created_at        DATETIME(6)    NOT NULL,
    updated_at        DATETIME(6)    NOT NULL,
    version           BIGINT         NOT NULL DEFAULT 0,
    UNIQUE KEY uq_units_code (org_id, code),
    UNIQUE KEY uq_units_property_unit_no (property_id, unit_no),   -- case-insensitive collation
    CONSTRAINT fk_units_org FOREIGN KEY (org_id) REFERENCES organizations (id),
    CONSTRAINT fk_units_property FOREIGN KEY (property_id) REFERENCES properties (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE tenants (
    id                 CHAR(36)     NOT NULL PRIMARY KEY,
    org_id             CHAR(36)     NOT NULL,
    code               VARCHAR(20)  NOT NULL,
    name               VARCHAR(150) NOT NULL,
    mobile             VARCHAR(30)  NOT NULL,
    email              VARCHAR(150),
    nid                VARCHAR(50)  NOT NULL,
    date_of_birth      DATE,
    present_address    TEXT,
    emergency_contact  VARCHAR(200),
    occupation         VARCHAR(100),
    status             VARCHAR(20)  NOT NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    version            BIGINT       NOT NULL DEFAULT 0,
    UNIQUE KEY uq_tenants_code (org_id, code),
    UNIQUE KEY uq_tenants_mobile (org_id, mobile),
    UNIQUE KEY uq_tenants_nid (org_id, nid),
    CONSTRAINT fk_tenants_org FOREIGN KEY (org_id) REFERENCES organizations (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE tenant_documents (
    id             CHAR(36)     NOT NULL PRIMARY KEY,
    org_id         CHAR(36)     NOT NULL,
    tenant_id      CHAR(36)     NOT NULL,
    name           VARCHAR(150) NOT NULL,
    doc_type       VARCHAR(30)  NOT NULL,
    reference      TEXT         NOT NULL,
    -- Optional uploaded file, stored on disk under <storage dir>/<org id>/<file_key>.
    file_key       VARCHAR(100),
    content_type   VARCHAR(100),
    size_bytes     BIGINT,
    original_name  VARCHAR(255),
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    KEY idx_tenant_documents_tenant (tenant_id),
    CONSTRAINT fk_tenant_documents_org FOREIGN KEY (org_id) REFERENCES organizations (id),
    CONSTRAINT fk_tenant_documents_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE users (
    id                    CHAR(36)     NOT NULL PRIMARY KEY,
    org_id                CHAR(36)     NOT NULL,
    name                  VARCHAR(150) NOT NULL,
    username              VARCHAR(60)  NOT NULL,
    email                 VARCHAR(150),
    mobile                VARCHAR(30),
    password_hash         VARCHAR(100) NOT NULL,
    role                  VARCHAR(20)  NOT NULL,       -- VENDOR | ADMIN | MANAGER | TENANT
    status                VARCHAR(20)  NOT NULL,       -- ACTIVE | INACTIVE | LOCKED
    tenant_id             CHAR(36),
    failed_attempts       INT          NOT NULL DEFAULT 0,
    must_change_password  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    version               BIGINT       NOT NULL DEFAULT 0,
    -- Usernames are global because login happens before the organisation is known.
    UNIQUE KEY uq_users_username (username),
    UNIQUE KEY uq_users_tenant (tenant_id),
    KEY idx_users_email (email),
    KEY idx_users_mobile (mobile),
    CONSTRAINT fk_users_org FOREIGN KEY (org_id) REFERENCES organizations (id),
    CONSTRAINT fk_users_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Properties a manager is allowed to work with.
CREATE TABLE user_properties (
    user_id      CHAR(36) NOT NULL,
    property_id  CHAR(36) NOT NULL,
    PRIMARY KEY (user_id, property_id),
    CONSTRAINT fk_user_properties_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_properties_property FOREIGN KEY (property_id) REFERENCES properties (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Devices that use the admin app (limited per organisation) or the tenant app (not limited).
CREATE TABLE devices (
    id            CHAR(36)     NOT NULL PRIMARY KEY,
    org_id        CHAR(36)     NOT NULL,
    device_key    VARCHAR(100) NOT NULL,           -- random install id generated by the app
    app           VARCHAR(10)  NOT NULL,           -- ADMIN | TENANT
    name          VARCHAR(150),
    platform      VARCHAR(30),
    status        VARCHAR(10)  NOT NULL,           -- APPROVED | PENDING | BLOCKED
    last_user_id  CHAR(36),
    last_seen_at  DATETIME(6),
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    UNIQUE KEY uq_devices_key (org_id, app, device_key),
    KEY idx_devices_org_status (org_id, app, status),
    CONSTRAINT fk_devices_org FOREIGN KEY (org_id) REFERENCES organizations (id),
    CONSTRAINT fk_devices_last_user FOREIGN KEY (last_user_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- A refresh token belongs to the device it was issued to; removing the device signs it out.
CREATE TABLE refresh_tokens (
    id           CHAR(36)    NOT NULL PRIMARY KEY,
    user_id      CHAR(36)    NOT NULL,
    device_id    CHAR(36),
    token_hash   VARCHAR(64) NOT NULL,
    expires_at   DATETIME(6) NOT NULL,
    revoked_at   DATETIME(6),
    created_at   DATETIME(6) NOT NULL,
    UNIQUE KEY uq_refresh_tokens_hash (token_hash),
    KEY idx_refresh_tokens_user (user_id),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_refresh_tokens_device FOREIGN KEY (device_id) REFERENCES devices (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE agreements (
    id                  CHAR(36)       NOT NULL PRIMARY KEY,
    org_id              CHAR(36)       NOT NULL,
    code                VARCHAR(20)    NOT NULL,
    tenant_id           CHAR(36)       NOT NULL,
    unit_id             CHAR(36)       NOT NULL,
    property_id         CHAR(36)       NOT NULL,
    start_date          DATE           NOT NULL,
    end_date            DATE           NOT NULL,
    monthly_rent        DECIMAL(14, 2) NOT NULL,
    service_charge      DECIMAL(14, 2) NOT NULL DEFAULT 0,
    utility_charge      DECIMAL(14, 2) NOT NULL DEFAULT 0,
    other_charge        DECIMAL(14, 2) NOT NULL DEFAULT 0,
    security_deposit    DECIMAL(14, 2) NOT NULL DEFAULT 0,
    advance_amount      DECIMAL(14, 2) NOT NULL DEFAULT 0,
    due_day             INT            NOT NULL,
    document_ref        TEXT,
    terms               TEXT,
    status              VARCHAR(20)    NOT NULL,   -- DRAFT | ACTIVE | EXPIRING | EXPIRED | TERMINATED
    renewed_from_id     CHAR(36),
    terminated_on       DATE,
    termination_reason  TEXT,
    created_at          DATETIME(6)    NOT NULL,
    updated_at          DATETIME(6)    NOT NULL,
    version             BIGINT         NOT NULL DEFAULT 0,
    -- Set only while the agreement is current, so the unique keys below allow at most one
    -- current (ACTIVE/EXPIRING) agreement per unit and per tenant.
    current_unit_id     CHAR(36) AS (CASE WHEN status IN ('ACTIVE', 'EXPIRING') THEN unit_id END) STORED,
    current_tenant_id   CHAR(36) AS (CASE WHEN status IN ('ACTIVE', 'EXPIRING') THEN tenant_id END) STORED,
    UNIQUE KEY uq_agreements_code (org_id, code),
    UNIQUE KEY uq_agreements_current_unit (current_unit_id),
    UNIQUE KEY uq_agreements_current_tenant (current_tenant_id),
    KEY idx_agreements_org_status (org_id, status),
    CONSTRAINT chk_agreements_dates CHECK (end_date > start_date),
    CONSTRAINT chk_agreements_due_day CHECK (due_day BETWEEN 1 AND 28),
    CONSTRAINT fk_agreements_org FOREIGN KEY (org_id) REFERENCES organizations (id),
    CONSTRAINT fk_agreements_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_agreements_unit FOREIGN KEY (unit_id) REFERENCES units (id),
    CONSTRAINT fk_agreements_property FOREIGN KEY (property_id) REFERENCES properties (id),
    CONSTRAINT fk_agreements_renewed_from FOREIGN KEY (renewed_from_id) REFERENCES agreements (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE rent_invoices (
    id              CHAR(36)       NOT NULL PRIMARY KEY,
    org_id          CHAR(36)       NOT NULL,
    code            VARCHAR(20)    NOT NULL,
    tenant_id       CHAR(36)       NOT NULL,
    agreement_id    CHAR(36)       NOT NULL,
    unit_id         CHAR(36)       NOT NULL,
    property_id     CHAR(36)       NOT NULL,
    billing_month   DATE           NOT NULL,   -- first day of month
    due_date        DATE           NOT NULL,
    rent_amount     DECIMAL(14, 2) NOT NULL,
    service_charge  DECIMAL(14, 2) NOT NULL DEFAULT 0,
    utility_charge  DECIMAL(14, 2) NOT NULL DEFAULT 0,
    other_charge    DECIMAL(14, 2) NOT NULL DEFAULT 0,
    paid_amount     DECIMAL(14, 2) NOT NULL DEFAULT 0,
    status          VARCHAR(20)    NOT NULL,   -- PENDING | PARTIALLY_PAID | PAID | OVERDUE | CANCELLED
    cancel_reason   TEXT,
    created_at      DATETIME(6)    NOT NULL,
    updated_at      DATETIME(6)    NOT NULL,
    version         BIGINT         NOT NULL DEFAULT 0,
    -- NULL for cancelled invoices: one live invoice per agreement and month.
    live_agreement_id CHAR(36) AS (CASE WHEN status <> 'CANCELLED' THEN agreement_id END) STORED,
    UNIQUE KEY uq_rent_invoices_code (org_id, code),
    UNIQUE KEY uq_rent_invoices_agreement_month (live_agreement_id, billing_month),
    KEY idx_rent_invoices_org_month (org_id, billing_month),
    KEY idx_rent_invoices_tenant (tenant_id),
    CONSTRAINT fk_rent_invoices_org FOREIGN KEY (org_id) REFERENCES organizations (id),
    CONSTRAINT fk_rent_invoices_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_rent_invoices_agreement FOREIGN KEY (agreement_id) REFERENCES agreements (id),
    CONSTRAINT fk_rent_invoices_unit FOREIGN KEY (unit_id) REFERENCES units (id),
    CONSTRAINT fk_rent_invoices_property FOREIGN KEY (property_id) REFERENCES properties (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE payments (
    id              CHAR(36)       NOT NULL PRIMARY KEY,
    org_id          CHAR(36)       NOT NULL,
    code            VARCHAR(20)    NOT NULL,
    receipt_no      VARCHAR(20)    NOT NULL,
    invoice_id      CHAR(36)       NOT NULL,
    tenant_id       CHAR(36)       NOT NULL,
    unit_id         CHAR(36)       NOT NULL,
    property_id     CHAR(36)       NOT NULL,
    payment_date    DATE           NOT NULL,
    amount          DECIMAL(14, 2) NOT NULL,
    method          VARCHAR(20)    NOT NULL,   -- CASH | BANK_TRANSFER | BKASH | NAGAD | OTHER
    reference_no    VARCHAR(100),
    remarks         TEXT,
    recorded_by     VARCHAR(150)   NOT NULL,
    voided          BOOLEAN        NOT NULL DEFAULT FALSE,
    void_reason     TEXT,
    voided_at       DATETIME(6),
    created_at      DATETIME(6)    NOT NULL,
    updated_at      DATETIME(6)    NOT NULL,
    version         BIGINT         NOT NULL DEFAULT 0,
    UNIQUE KEY uq_payments_code (org_id, code),
    UNIQUE KEY uq_payments_receipt (org_id, receipt_no),
    KEY idx_payments_invoice (invoice_id),
    KEY idx_payments_org_date (org_id, payment_date),
    CONSTRAINT chk_payments_amount CHECK (amount > 0),
    CONSTRAINT fk_payments_org FOREIGN KEY (org_id) REFERENCES organizations (id),
    CONSTRAINT fk_payments_invoice FOREIGN KEY (invoice_id) REFERENCES rent_invoices (id),
    CONSTRAINT fk_payments_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_payments_unit FOREIGN KEY (unit_id) REFERENCES units (id),
    CONSTRAINT fk_payments_property FOREIGN KEY (property_id) REFERENCES properties (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE maintenance_requests (
    id               CHAR(36)     NOT NULL PRIMARY KEY,
    org_id           CHAR(36)     NOT NULL,
    code             VARCHAR(20)  NOT NULL,
    tenant_id        CHAR(36)     NOT NULL,
    unit_id          CHAR(36)     NOT NULL,
    property_id      CHAR(36)     NOT NULL,
    title            VARCHAR(200) NOT NULL,
    type             VARCHAR(20)  NOT NULL,
    description      TEXT         NOT NULL,
    priority         VARCHAR(20)  NOT NULL,
    attachment       TEXT,
    -- One uploaded photo / file per request.
    attachment_key   VARCHAR(100),
    attachment_type  VARCHAR(100),
    attachment_size  BIGINT,
    attachment_name  VARCHAR(255),
    assigned_to      VARCHAR(150),
    status           VARCHAR(20)  NOT NULL,   -- OPEN | ASSIGNED | IN_PROGRESS | COMPLETED | CLOSED
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    UNIQUE KEY uq_maintenance_code (org_id, code),
    KEY idx_maintenance_org_status (org_id, status),
    CONSTRAINT fk_maintenance_org FOREIGN KEY (org_id) REFERENCES organizations (id),
    CONSTRAINT fk_maintenance_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_maintenance_unit FOREIGN KEY (unit_id) REFERENCES units (id),
    CONSTRAINT fk_maintenance_property FOREIGN KEY (property_id) REFERENCES properties (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE maintenance_history (
    id          CHAR(36)     NOT NULL PRIMARY KEY,
    org_id      CHAR(36)     NOT NULL,
    request_id  CHAR(36)     NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    changed_by  VARCHAR(150) NOT NULL,
    note        TEXT,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    KEY idx_maintenance_history_request (request_id),
    CONSTRAINT fk_maintenance_history_org FOREIGN KEY (org_id) REFERENCES organizations (id),
    CONSTRAINT fk_maintenance_history_request FOREIGN KEY (request_id) REFERENCES maintenance_requests (id)
        ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE expenses (
    id            CHAR(36)       NOT NULL PRIMARY KEY,
    org_id        CHAR(36)       NOT NULL,
    property_id   CHAR(36),
    expense_date  DATE           NOT NULL,
    category      VARCHAR(50)    NOT NULL,
    amount        DECIMAL(14, 2) NOT NULL,
    description   TEXT,
    created_at    DATETIME(6)    NOT NULL,
    updated_at    DATETIME(6)    NOT NULL,
    version       BIGINT         NOT NULL DEFAULT 0,
    KEY idx_expenses_org_date (org_id, expense_date),
    CONSTRAINT chk_expenses_amount CHECK (amount > 0),
    CONSTRAINT fk_expenses_org FOREIGN KEY (org_id) REFERENCES organizations (id),
    CONSTRAINT fk_expenses_property FOREIGN KEY (property_id) REFERENCES properties (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE notifications (
    id          CHAR(36)     NOT NULL PRIMARY KEY,
    org_id      CHAR(36)     NOT NULL,
    user_id     CHAR(36)     NOT NULL,
    title       VARCHAR(200) NOT NULL,
    body        TEXT         NOT NULL,
    dedup_key   VARCHAR(100),               -- NULL = no de-duplication
    is_read     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    KEY idx_notifications_user (user_id, created_at DESC),
    UNIQUE KEY uq_notifications_dedup (user_id, dedup_key),
    CONSTRAINT fk_notifications_org FOREIGN KEY (org_id) REFERENCES organizations (id),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE audit_logs (
    id           CHAR(36)     NOT NULL PRIMARY KEY,
    org_id       CHAR(36)     NOT NULL,
    user_id      CHAR(36),
    user_name    VARCHAR(150) NOT NULL,
    action       VARCHAR(100) NOT NULL,
    entity_type  VARCHAR(50),
    entity_id    VARCHAR(50),
    details      TEXT,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    version      BIGINT       NOT NULL DEFAULT 0,
    KEY idx_audit_logs_org_time (org_id, created_at DESC),
    CONSTRAINT fk_audit_logs_org FOREIGN KEY (org_id) REFERENCES organizations (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Every licensing action (sign-up, approval, renewal, suspension, limit change, device decision).
CREATE TABLE license_events (
    id              CHAR(36)     NOT NULL PRIMARY KEY,
    org_id          CHAR(36)     NOT NULL,
    action          VARCHAR(50)  NOT NULL,
    details         TEXT,
    expires_before  DATETIME(6),
    expires_after   DATETIME(6),
    actor_name      VARCHAR(150) NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    KEY idx_license_events_org (org_id, created_at DESC),
    CONSTRAINT fk_license_events_org FOREIGN KEY (org_id) REFERENCES organizations (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Firebase Cloud Messaging registration tokens. A token belongs to one user at a time; registering
-- it again (e.g. another user signs in on the same phone) moves it to the new user.
CREATE TABLE push_tokens (
    id          CHAR(36)     NOT NULL PRIMARY KEY,
    org_id      CHAR(36)     NOT NULL,
    user_id     CHAR(36)     NOT NULL,
    device_id   CHAR(36),
    token       VARCHAR(512) NOT NULL,
    app         VARCHAR(10)  NOT NULL,           -- ADMIN | TENANT
    platform    VARCHAR(30),
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    UNIQUE KEY uq_push_tokens_token (token),
    KEY idx_push_tokens_user (org_id, user_id),
    CONSTRAINT fk_push_tokens_org FOREIGN KEY (org_id) REFERENCES organizations (id),
    CONSTRAINT fk_push_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_push_tokens_device FOREIGN KEY (device_id) REFERENCES devices (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
