# TMS – Quick User Manual

TMS has two kinds of accounts in the **TMS Admin** app, and they see different screens:

| Account | Who | What they see |
|---|---|---|
| **Vendor** (`vendor` / `Vendor1234` in development) | You, the software provider | Vendor console: *Overview, Landlords, Alerts, Account*. Approves landlords and manages their subscriptions. **Cannot** create properties or tenants. |
| **Landlord admin** | Your customer (a rental business) | Full management app: *Dashboard, Properties, Tenants, Agreements, Rent, Payments…* |

Tenants use the separate **TMS Tenant** app.

> If you signed in as `vendor` and see only counters (Waiting for approval, Active, …), that is the
> vendor console. Properties and tenants are created from a **landlord** account — follow step 1.

---

## 1. Create a landlord account (once per business)

1. In TMS Admin, sign out (vendor: **Account → Sign out**).
2. On the sign-in screen tap **Register your business**.
3. Fill in business name, your name, mobile, username and password (8+ characters, letters and digits) → **Register**.
4. Sign in as **vendor** → **Landlords** → open the new business → **Approve** (set an expiry date or *No expiry*).
5. Sign out, then sign in with the landlord's username and password.

You now see the landlord **Dashboard**. It is empty until you add data.

## 2. Find the menu

On a phone, tap **☰** (top-left) to open the menu. Sections:

- **Manage:** Properties, Tenants, Agreements
- **Finance:** Rent, Payments, Dues, Expenses
- **Operations:** Maintenance, Reports, Notifications
- **Administration** (admin role only): Users, Audit Log, Settings, Subscription

Most list screens have a **+ button** at the bottom-right to add a new item.

## 3. Set up your rentals (in this order)

1. **Settings** – check business details, currency symbol and agreement-expiry alert days → **Save settings**.
2. **Properties → + Property** – add a building / house.
3. Open the property → **+ Unit** – add each flat/shop with its monthly rent.
4. **Tenants → + Tenant** – add the tenant's details.
   Tick **Create tenant portal login** to give them a username and password for the TMS Tenant app.
5. **Agreements → + Agreement** (or open a unit → **Assign tenant**) – choose tenant, property, unit,
   start/end dates, payment due day and monthly rent → **Save & activate (move in)**. The unit becomes *Occupied*.

## 4. Monthly work

1. **Rent → Generate <month>** – creates rent invoices for all active agreements.
2. When a tenant pays: **Payments → Record payment** (or open the invoice → **Record payment**).
   A receipt is created; tap **PDF** to share it.
3. **Dues** – see who is overdue; **Export** to Excel.
4. **Expenses → + Expense** – record repair bills, utilities, etc.
5. **Maintenance** – view tenants' requests and move them through *In progress → Resolved*.
6. **Reports** – income, dues, occupancy, expenses; export as **Excel** or **PDF**.

## 5. Staff

**Users → + User** to add a *Manager* (manages assigned properties) or another *Admin*.
Use **⋮ → Reset password** to give someone a temporary password.

## 6. Tenant app (TMS Tenant)

The tenant signs in with the portal login created in step 3.4 and can see their home,
dues and payments (with receipt PDFs), send maintenance requests with a photo, and read alerts.

## Troubleshooting

- **"Cannot reach the server"** – the backend must be running and the phone must reach it
  (USB development: `adb reverse tcp:8980 tcp:8980`).
- **Locked / "waiting for approval" screen** – the vendor has not approved the business yet, or the
  subscription expired. The vendor can **Approve** or **Extend** it under *Landlords*.
- **"Device waiting for approval"** – a new phone over the device limit; the vendor approves it in
  the landlord's detail page under *Devices*.
