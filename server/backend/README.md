# Tenant Management API (Spring Boot)

The backend for the admin and tenant apps. It has the domain model, authentication,
per-organisation data isolation and the core REST API (Milestone 1), licensing and the
vendor console (Milestone 2), file uploads, reports with Excel/PDF export and push
notifications (Milestone 4), and the data console API with a change history of every table
(see [Data console](#data-console) and [`web_console/`](../web_console/README.md)).

**Stack:** Java 21 · Spring Boot 4.1 · Spring Security (JWT) · Spring Data JPA / Hibernate 7 ·
MySQL 8.4 · Flyway · springdoc (Swagger UI)

## Run it

You need **JDK 21**. If `JAVA_HOME` points to an older JDK, set it for the session first:

```bash
export JAVA_HOME="/c/Program Files/OpenLogic/jdk-21.0.6.7-hotspot"
```

**Without Docker (simplest).** This starts a private MySQL from an unpacked MySQL 8.4 "no-install"
archive in `~/.tms/mysql-*` (or `MYSQL_HOME`) on 127.0.0.1:3307, and keeps its data in
`backend/.dev-mysql`. It also creates a local vendor account, `vendor` / `Vendor1234`:

```bash
mvn spring-boot:test-run -Dspring-boot.run.mainClass=com.revesoft.tms.LocalDevApplication
```

**With Docker / your own MySQL.** Start the database from the `server/` folder, then run the app
(the compose file creates the user `tms` / `tms`):

```bash
docker compose up -d db
DB_USERNAME=tms DB_PASSWORD=tms mvn spring-boot:run
```

Then open:

- Swagger UI: http://localhost:8980/swagger-ui.html. Log in with `POST /api/auth/login`, click
  **Authorize**, and paste the `accessToken`.
- Health check: http://localhost:8980/actuator/health

**Tests:** `mvn test` runs 41 integration tests against a real MySQL: the private one described
above, or an existing server when `TMS_TEST_DB_URL` (plus `TMS_TEST_DB_USERNAME` /
`TMS_TEST_DB_PASSWORD`) is set. No Docker is needed.

### Configuration (environment variables)

| Variable | Default | Notes |
| --- | --- | --- |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:mysql://localhost:3306/tms?…`, `root`, empty | |
| `JWT_SECRET` | dev value | **Must be set in production.** Use a random string of at least 32 characters |
| `ACCESS_TOKEN_MINUTES` | 15 | Access-token lifetime |
| `REFRESH_TOKEN_DAYS` | 30 | Refresh tokens are rotated on every use |
| `TMS_ZONE` | `Asia/Dhaka` | Business dates (rent month, due date, overdue) |
| `PORT` | 8980 | The apps (`TMS_API_URL` default) and the web console expect this port |
| `TMS_VENDOR_USERNAME` / `TMS_VENDOR_PASSWORD` | (none) | Creates your vendor account at startup if it does not exist. You are asked to change the password at first login |
| `LICENSE_GRACE_DAYS` | 7 | Read-only days after a subscription expires |
| `STORAGE_DIR` | `./data/files` | Uploaded files (tenant documents, maintenance photos). Back this folder up together with the database |
| `FCM_CREDENTIALS_FILE` | (none) | Path to a Firebase service-account JSON. When unset, push notifications are only logged |
| `LOG_LEVEL` | `DEBUG` (`INFO` with the `prod` profile) | Level for the app's own code (`com.revesoft.tms`): `TRACE`, `DEBUG`, `INFO`, `WARN` |
| `LOG_SQL` | `INFO` | `DEBUG` prints every SQL statement (without parameter values) |
| `LOG_WEB` | `INFO` | `DEBUG` shows Spring MVC and Spring Security decisions |
| `LOG_FILE` | (none) | Also write to this file, rolled at 20 MB and kept for 14 days (1 GB at most) |
| `SLOW_REQUEST_MS` | 2000 | Requests slower than this are logged at INFO even when DEBUG is off |

### Logs

Every line carries the **request id**, the **caller** and their **organisation**, so you can follow
one request, user or landlord through the log:

```
15:49:12.363  INFO [076c895e-87f8 Owner/ADMIN 307a4054-…] c.r.tms.audit.AuditService     : Audit: Created Property Property P-1001 by Owner - North Court
15:49:14.831 DEBUG [c108fa97-87f9 Owner/ADMIN d39546be-…] c.r.tms.console.ChangeLogListener : UPDATE units dfd108cf-… {"status":{"old":"AVAILABLE","new":"OCCUPIED"}}
15:49:12.413 DEBUG [076c895e-87f8 Owner/ADMIN 307a4054-…] c.r.tms.common.RequestLoggingFilter : POST /api/properties -> 201 (129 ms)
```

- **Request id.** Every response has an `X-Request-Id` header. When a user reports an error, ask
  for it (or send your own from the app, 8–64 letters, digits or dashes) and search the log for it.
  Nightly jobs use `job-…` ids.
- **What is logged:**
  - DEBUG: one line per request (method, path, status, time), every row change with its masked
    diff, refusals by the licence and account checks, handled errors, notifications and stored
    files.
  - INFO: sign-ins and refused sign-ins, every audit-log action, licence events, slow requests,
    nightly job summaries.
  - WARN: locked accounts, database constraint violations (with the constraint that failed), and
    server errors.
- **Never logged:** passwords, password hashes, tokens, `Authorization` headers, request bodies and
  query strings.
- Log output is UTF-8, so Bangla names are written correctly. Test output shown by Maven on Windows
  is re-encoded with the system code page; that is a Maven display issue, not the app's logs.

## How it works

### Multi-landlord isolation

Every landlord is an **organisation**, and every business table has an `org_id`. Data is kept
apart by three layers:

1. **Hibernate `@TenantId`.** `BaseEntity` puts `org_id` on every record, and
   `OrgTenantResolver` gives Hibernate the caller's organisation from the JWT. Hibernate then
   writes `org_id` on every insert and filters every query and every load by ID. Unauthenticated
   code gets an organisation that matches nothing.
2. **`AccessGuard.owned(...)`** checks ownership again when a service loads a record by ID.
3. **Role and scope rules:** managers see only their assigned properties, and tenants see only
   their own records.

`TenantFilterTest` and `OrgIsolationTest` prove that one organisation cannot read, change or
reference another organisation's data.

Code that runs without a caller (login, sign-up, nightly jobs) chooses its organisation
explicitly with `TxRunner.inOrg(...)` or `TxRunner.asRoot(...)`.

### Authentication

- `POST /api/auth/login` accepts a username, email or mobile number, and returns an access token
  (HS256 JWT, 15 min) plus a refresh token.
- Refresh tokens are opaque, stored only as SHA-256 hashes, rotated on every refresh, and revoked
  on logout or password change.
- Passwords are hashed with BCrypt. They need at least 8 characters, including letters and
  numbers.
- An account locks after 5 failed attempts.
- Usernames are unique across all organisations.

### Licensing (Milestone 2)

```
sign-up ──► PENDING ──(vendor approves: expiry, plan, unit & device limits)──► ACTIVE
                └──(vendor rejects)──► REJECTED            │ reminders 15 / 7 / 1 days before expiry
                                                           ▼
             SUSPENDED ◄──(vendor)── ACTIVE ──(expires)──► GRACE (read-only, 7 days) ──► EXPIRED (locked)
                 └──(reactivate)──► ACTIVE ◄──────────(vendor extends)──────────────────────┘
```

- **Every request is checked.** `LicenseFilter` runs on every authenticated API call, for staff and
  tenants alike. When it refuses a call, it returns HTTP 403 with a machine-readable `code`:
  `LICENSE_PENDING`, `LICENSE_REJECTED`, `LICENSE_SUSPENDED`, `LICENSE_EXPIRED`, `LICENSE_READ_ONLY`
  (writes during the grace period) or `DEVICE_BLOCKED`.
- **Some endpoints always work.** Sign-in, `GET /api/license` and renewal requests stay reachable,
  so the apps can show why access is blocked.
- **Admin-app devices are limited.** Staff must send a `deviceKey` with every login: a random ID
  the app generates once per installation. Over the limit, the new device is saved as PENDING and
  the login fails with `DEVICE_PENDING`. The landlord's admin can remove an unused device
  (`DELETE /api/devices/{id}`), or the vendor can approve the device anyway.
- **Blocking a device signs it out.** Its refresh tokens are revoked and its access token stops
  working immediately.
- **Unit limit.** Every unit counts except inactive ones. The limit is checked when a unit is
  created or re-activated, and a refusal returns `UNIT_LIMIT`.
- **Tenant devices are not limited.** But a tenant's access stops whenever their landlord's
  licence is blocked.
- **License history.** Every licensing action is recorded in `license_events`. Your vendor users
  are notified of new sign-ups, renewal requests, device approvals and licences that are about
  to expire.

### Business rules

These are ported from the Flutter app:

- **Move-in and move-out:** activating an agreement sets the unit to OCCUPIED and the tenant to
  ACTIVE. Terminating or expiring it sets the unit to AVAILABLE and the tenant to PREVIOUS. The
  database also enforces at most one current agreement per unit and per tenant.
- **Rent generation:** at most one invoice per agreement per month (also enforced by a database
  index). The due date comes from the agreement's due day.
- **Payments:** partial payments are allowed and overpayments are rejected. Non-cash payments need
  a reference number. Receipt numbers count up separately for each organisation. Voiding a payment
  (admin only) recalculates the invoice.
- **Nightly job (`DailyJobs`)** for each organisation:
  - marks agreements EXPIRING or EXPIRED and releases the units of expired ones;
  - marks unpaid invoices OVERDUE;
  - generates the current month's rent automatically;
  - sends notifications, each one only once: rent due within 3 days and overdue rent go to the
    tenant and to the property's staff.
- **Audit log:** logins, and changes to tenants, agreements, rent, payments, users and settings.

### Files (Milestone 4)

- Uploads are `multipart/form-data`, at most **10 MB**. Only PDF, JPEG, PNG and WebP are accepted:
  the declared content type must match the file's first bytes, otherwise the answer is 400 with
  `code: FILE_TYPE` (`FILE_EMPTY` for an empty file, 413 `FILE_TOO_LARGE` for a big one).
- Files are stored as `<STORAGE_DIR>/<organisation id>/<random id>`; the client's file name is kept
  only as metadata and used (cleaned) in the download's `Content-Disposition`.
- Downloads go through the same access checks as the record they belong to, so another
  organisation gets 404. Deleting a document deletes its file.

### Reports (Milestone 4)

`GET /api/reports/{type}?format=json|xlsx|pdf&from=&to=&month=&propertyId=` with the types
`rent-roll`, `collections`, `dues`, `expenses`, `profit-loss`, `occupancy` and `tenants`.
`collections`, `expenses` and `profit-loss` cover a period (`month=2026-10`, or `from`/`to`;
default: the current month); the others are a snapshot as of today. Managers only get their
properties. The JSON is one generic table that the apps can render for any report:

```json
{ "title": "Collections", "subtitle": "Oct 2026",
  "columns": [ {"key": "date", "label": "Date", "type": "date"},
               {"key": "amount", "label": "Amount", "type": "money"} ],
  "rows":    [ {"date": "2026-10-06", "amount": 10000.00} ],
  "totals":  {"date": "Total (1 payments)", "amount": 10000.00},
  "summary": [ {"label": "Cash", "value": 10000.00, "type": "money"} ] }
```

Column types are `text`, `money`, `date`, `number` and `percent` (0-100). Empty values are left
out of a row. `xlsx` and `pdf` render the same table (with a file name such as
`collections-2026-10.xlsx`). PDFs use the built-in Helvetica font, so non-Latin text (e.g. Bangla
names) is not shown correctly and the taka sign is printed as "Tk".

### Push notifications (Milestone 4)

Every in-app notification is also pushed to the user's registered phones through Firebase Cloud
Messaging. Pushes are sent in the background after the database change is committed, never fail
the request, and are skipped for organisations whose licence is pending, rejected, suspended or
expired. Tokens that Firebase reports as unregistered are deleted.

The apps call `POST /api/push/token` `{"token": "...", "platform": "android"}` after sign-in and
whenever Firebase gives them a new token, and send `pushToken` with `POST /api/auth/logout`. The
push carries the notification's title and body, and the data `{"type": "notification",
"notificationId": "..."}`.

To enable it:

1. Create a project in the [Firebase console](https://console.firebase.google.com/) and add the
   Android apps (admin and tenant package names). Put each app's `google-services.json` into its
   `android/app/` folder (iOS: `GoogleService-Info.plist` and an APNs key).
2. In *Project settings → Service accounts*, generate a new private key (a JSON file). Keep it
   secret and outside the repository.
3. Start the API with `FCM_CREDENTIALS_FILE=/path/to/service-account.json`.

### Blocking users

An organisation's **admin** can block any user of their own organisation (staff or tenant
logins) with `POST /api/users/{id}/block {"reason": "…"}`. The **vendor** can block any user of
any organisation with `POST /api/vendor/users/{id}/block`. The status becomes `BLOCKED`, and the
user record keeps the reason, who blocked them and when.

- **It takes effect at once.** `AccountStatusFilter` re-reads the caller's status on every API
  request, so an access token that is still valid stops working immediately. The user's refresh
  tokens are revoked as well. Deactivating or locking a user also signs them out at once.
- **What the user sees.** API calls and sign-in return 403 `ACCOUNT_BLOCKED`, with a message that
  says who blocked them and why. The reason is only shown after the correct password is entered.
  Wrong passwords on a blocked account don't count towards the automatic lock.
- **Scope.** Admins only ever see their own organisation's users: another organisation's user
  gives 404. Managers and tenants cannot block anyone. Nobody can block themselves, and admins
  cannot block the vendor.
- **The vendor's block wins.** Only the vendor can lift a block they placed (`blocked_by_vendor`),
  and an admin cannot overwrite it by blocking again. The vendor can lift any block.
- `/{id}/status` only handles active, inactive and locked. Use block / unblock for `BLOCKED`.
- Every block and unblock is in the organisation's audit log and in the change log.

### Data console

`/api/console/**` gives the vendor read-only access to every table across all organisations, and
one activity feed. The [`web_console/`](../web_console/README.md) app is its user interface.

- **Change history.** `ChangeLogListener` (a Hibernate post-insert/update/delete listener) writes
  a `change_log` row for every entity change, in the same transaction: the table, row id,
  operation, the changed columns as `{"column": {"old": …, "new": …}}`, who did it (or `System`
  for jobs), and the Hibernate session id, which groups the rows changed by one action. Password
  hashes and tokens are written as `***`. `refresh_tokens`, `audit_logs` and `license_events` are
  not recorded (the last two are already a history).
- **Not recorded:** bulk `@Modifying` queries (revoking refresh tokens, "mark all notifications
  read", moving push tokens), changes to a manager's property list on its own, and anything
  written to the database outside the API. History starts when V2 is deployed; older rows have
  only their `created_at` / `updated_at`.
- **Table browser.** Tables and columns are read from `information_schema` (`DbSchema`), so new
  tables appear without code changes. Identifiers in the generated SQL only ever come from that
  schema, and every value is bound as a parameter. Rows come back with `lookups`: a label
  (code / name / title …) for every foreign key on the page.

| Endpoint | What it returns |
| --- | --- |
| `GET /api/console/summary?org=&days=30` | Row count per table (for one organisation, or all) and changes per day |
| `GET /api/console/tables` | Every table with its columns, types, foreign keys and row count |
| `GET /api/console/tables/{table}/rows?org=&q=&filter=col:value&from=&to=&sort=&desc=&page=&size=` | One page of rows (at most 200) with link labels. `filter` repeats; `col:` matches NULL; `from`/`to` limit `created_at` |
| `GET /api/console/tables/{table}/export?…` | The same rows as CSV (at most 50,000) |
| `GET /api/console/tables/{table}/rows/{id}` | One row, the rows in other tables that point to it (counts) and its history |
| `GET /api/console/activity?org=&source=change\|audit\|license&table=&rowId=&actor=&op=&from=&to=&before=&limit=` | Row changes, audit entries and licence events, newest first. Fetch the next page with `before=<nextBefore>` |

## API overview

| Area | Endpoints | Who |
| --- | --- | --- |
| Auth | `POST /api/auth/login, /refresh, /logout, /signup, /forgot-password, /change-password` · `GET /api/auth/me` | public / any |
| Organisation | `GET, PUT /api/org` (settings) | admin (PUT) |
| Users | `/api/users` CRUD, `/{id}/status`, `/{id}/block` `{reason}`, `/{id}/unblock`, `/{id}/reset-password` | admin |
| Properties & units | `/api/properties`, `/{id}/status`, `/{id}/units`, `/api/units/{id}` | admin, manager |
| Tenants | `/api/tenants`, `/{id}/documents` | admin, manager |
| Tenant documents (files) | `POST /api/tenants/{id}/documents/upload` (multipart `file`, `name`, `docType`, optional `reference`) · `GET /api/tenants/{id}/documents/{documentId}/file` | admin, manager |
| Agreements | `/api/agreements`, `/{id}/activate`, `/terminate`, `/renew` | admin, manager |
| Rent & dues | `/api/rents`, `/api/rents/generate`, `/{id}/cancel`, `/api/dues` | admin, manager |
| Payments | `/api/payments`, `/{id}/receipt`, `/{id}/receipt.pdf`, `/{id}/void` (admin) | admin, manager |
| Maintenance | `/api/maintenance`, `/{id}/assign`, `/{id}/status`, `POST, GET /{id}/attachment` (multipart `file`) | admin, manager |
| Reports | `GET /api/reports` (list) · `GET /api/reports/{type}?format=json\|xlsx\|pdf&from=&to=&month=&propertyId=` | admin, manager |
| Push | `POST /api/push/token` `{token, platform}` · `DELETE /api/push/token` `{token}` | everyone (own) |
| Expenses | `/api/expenses`, `/api/expenses/categories` | admin, manager |
| Dashboard | `GET /api/dashboard` | admin, manager |
| Notifications | `GET /api/notifications`, `/{id}/read`, `/read-all` | everyone (own) |
| Audit | `GET /api/audit` | admin |
| Licence (landlord) | `GET /api/license` · `POST /api/license/renewal-request` · `GET /api/devices`, `DELETE /api/devices/{id}` | admin (status: all) |
| **Vendor console** | `GET /api/vendor/summary, /organizations?state=, /organizations/{id}` · `POST /organizations/{id}/approve, /reject, /extend, /suspend, /reactivate` · `PUT /organizations/{id}/limits` · `POST /api/vendor/devices/{id}/approve, /block` | vendor |
| **Vendor: users** | `GET /api/vendor/users?org=&q=&status=` · `POST /api/vendor/users/{id}/block` `{reason}`, `/{id}/unblock` | vendor |
| **Data console** | `GET /api/console/summary, /tables, /tables/{table}/rows, /tables/{table}/export, /tables/{table}/rows/{id}, /activity` | vendor |
| **Tenant portal** | `GET /api/me/home, /profile, /agreements, /invoices, /payments, /payments/{id}/receipt, /payments/{id}/receipt.pdf, /documents, /documents/{id}/file, /maintenance, /maintenance/{id}/attachment` · `POST /api/me/maintenance, /maintenance/{id}/attachment` (multipart `file`) | tenant |

## Planned

- **Milestone 5:** deployment (Docker image, HTTPS, backups).
