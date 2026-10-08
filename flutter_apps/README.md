# Tenant Management App (Flutter)

A Flutter implementation of the Tenant Management System SRS (v1.0, Phase 1 MVP).
It runs on Android, iOS, Web and desktop. The UI adapts to the screen: a sidebar on
wide screens and a drawer on phones.

> **Backend:** a Spring Boot API that supports multiple landlords lives in [`server/backend/`](../server/backend/README.md).
> The Flutter app still stores its data locally on the device, and will be switched to this API
> in Milestone 3.
>
> **Data console:** a web app for the vendor to browse every organisation, table and change lives in [`server/web_console/`](../server/web_console/README.md).

## Getting started
Open this folder (`flutter_apps/`) in Android Studio; run the commands below from here.
In Android Studio, pick **Admin app** or **Tenant app** in the run configuration drop-down next to
the Run button.
This folder contains only the Dart sources (`lib/`, `test/`, `pubspec.yaml`).
Generate the platform folders once, then run:

```bash
flutter create . --project-name tenant_management --platforms=android,ios,web,windows
flutter pub get
flutter run            # or: flutter run -d chrome
flutter test
```

### Demo accounts (local test data)

| Role             | Username  | Password      |
| ---------------- | --------- | ------------- |
| Admin            | `admin`   | `Admin@123`   |
| Property Manager | `manager` | `Manager@123` |
| Tenant           | `karim`   | `Tenant@123`  |

The manager is assigned to *Green View Apartment* and *Rose Garden Residency* only.
You can log in with a username, email or mobile number. **Settings → Reset to demo data**
restores the sample dataset.

### Starting with real data

Sign in as `admin` and go to **Settings → Erase all data & start fresh**. Type `ERASE` to
confirm. This deletes every record and every other user, and keeps only your admin login
and the settings (organisation name, currency, alert days). You must then set a new admin
password, because the demo password is public. IDs restart from the beginning (`P-1001`,
`T-10001`, …), and the demo-account hint disappears from the login screen.

## SRS coverage

| SRS module | Where | Notes |
| --- | --- | --- |
| §4 Authentication | `login_screen.dart`, `AppStore.login` | Username/email/mobile login, salted SHA-256 password hashes, account lock after 5 failed attempts, forced change of temporary passwords, "forgot password" sends a reset request to admins |
| §4 RBAC | `home_shell.dart`, `AppStore.visible*` | Menus are filtered by role. Data is scoped too: managers see only their assigned properties, tenants see only their own records. Store methods also check the role |
| §5 Dashboard | `dashboard_screen.dart` | All the KPIs listed in the SRS, plus a collection-progress bar, a 6-month trend, expiring agreements, top overdue and pending maintenance |
| §6 Properties | `properties_screen.dart` | Create, view, update, activate/deactivate and search, with type and status filters |
| §7 Units | `properties_screen.dart` | Units grouped by floor, rent configuration (rent, service, utility, other), deposit and status. Unit status changes automatically on move-in and move-out |
| §8 Tenants | `tenants_screen.dart` | Full profile, search, history tabs (agreements, rent, payments, maintenance), outstanding balance, document register, optional portal login |
| §9 Agreements | `agreements_screen.dart` | Draft or activate, renew, terminate (frees the unit), expiry tracking with a configurable alert window |
| §10 Rent | `rent_screen.dart` | Rent is generated automatically for the current month and can also be generated manually per month without duplicates. Statuses: Pending, Partially Paid, Paid, Overdue, Cancelled |
| §11 Payments | `payments_screen.dart` | Cash, Bank, bKash, Nagad and Other. Partial payments, overpayment guard, reference number required for non-cash, receipt generation, admin void (audited) |
| §12 Dues | `dues_screen.dart` | Filter by property, unit, tenant, month and status. Shows days overdue, has a Collect action and CSV export |
| §13 Maintenance | `maintenance_screen.dart` | Tenants and staff can raise requests. Assignment and Open→Assigned→In Progress→Completed→Closed workflow with history |
| §14 Notifications | `notifications_screen.dart` | In-app notifications for every tenant and staff event listed in the SRS, with duplicates suppressed |
| §15 Reports | `reports_screen.dart` | Rent collection, outstanding, occupancy and vacant units, tenant history, income vs expense. Date and property filters, CSV export (opens in Excel) |
| §16 Users | `users_screen.dart` | Create and update users, assign roles and properties, activate, deactivate or lock, reset password (temporary password) |
| §17 Audit log | `audit_screen.dart` | Logins, tenant, agreement, rent, payment, user and settings changes. Searchable and exportable |
| Expenses | `expenses_screen.dart` | Property expenses that feed the income vs expense report |

## Architecture

```
lib/
  main.dart                 app entry, theme, auth gate
  models/models.dart        entities + enums (JSON serialisable)
  data/app_store.dart       ChangeNotifier store: business rules, RBAC, persistence
  data/seed.dart            demo dataset
  utils/format.dart         money/date formatting, CSV
  widgets/common.dart       shared UI (cards, chips, forms, filters, tables)
  screens/                  one file per module
test/store_test.dart        business-rule tests
```

* **State:** `provider` with a single `AppStore`.
* **Persistence:** the whole dataset is saved as JSON in `shared_preferences`. That makes
  the app fully offline and single-device. To make it multi-user, swap `AppStore`'s
  load/save for calls to the REST API in SRS §21. Screens only use the store's methods.
* **Daily jobs** run at startup and login. They mark agreements Expiring or Expired (and
  free the unit), flag overdue invoices, auto-generate the current month's rent and send
  the related notifications.

## Known MVP limitations

* Documents and photos are recorded as references (file location or link), not uploaded
  as files. Real file storage needs a backend.
* Exports are CSV, copied to the clipboard. PDF export is not included.
* SMS, email and online bKash/Nagad payments are Phase 2 items in the SRS.
