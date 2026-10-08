# TMS Data Console (web)

A web app for the vendor. It shows every organisation and every table in the `tms` database,
and every change made to them. You can:

- **Dashboard:** see licence states, record totals, row changes per day and the latest activity.
- **Organisations:** list every landlord with its licence, plan, expiry and unit/device usage.
  Each one has four tabs:
  - **Overview:** record counts per table, contact details, admins, devices (approve or block)
    and licence history.
  - **Users:** its logins, with block / unblock.
  - **Data:** any of the organisation's tables.
  - **Activity:** everything that happened in that organisation.

  You can approve, reject, extend, change limits, suspend or reactivate from here.
- **Users:** every login in every organisation, filtered by organisation and status. You can
  **block** a user (with a reason they see when signing in) or **unblock** them. A block signs
  them out of every device at once, and the organisation's admins cannot lift it. Each
  organisation also has a Users tab, and a user's record page has the same button.
- **Table explorer:** open any table from the sidebar. You can search it, filter it by
  organisation and creation date, sort it, page through it and export it to CSV. Foreign keys
  show as readable links (for example `P-1001 · Green View`).
- **Record view:** see every field of a row, the rows that point to it (for example a property's
  units and agreements) and its full history, with old → new values.
- **Activity:** every create, update and delete in every table, together with audit entries and
  licence events. You can filter by organisation, table, operation, user and date.

Everything is **read-only** except the licence, device and user-blocking actions. Those call the existing
`/api/vendor` endpoints, so the business rules stay in the API. Password hashes and tokens are
never shown. Only the `VENDOR` account can sign in.

Built with React 19, TypeScript, Vite, TanStack Query, React Router and Tailwind CSS 4.
It supports light and dark themes and works on phones.

## Run it (development)

Start the API first (see [`backend/README.md`](../backend/README.md)), then:

```bash
cd server/web_console
npm install
npm run dev
```

Open http://localhost:5173/console/ and sign in with the vendor account. Requests to `/api` are proxied to
`http://localhost:8980`. To use another address, set `API_URL`, for example:

```bash
API_URL=http://localhost:8099 npm run dev
```

## Build for production

```bash
npm run build
```

This writes static files to `dist/`. Serve them from the same origin as the API, and send `/api/*`
to the API: for example, with Caddy, `handle /api/* { reverse_proxy api:8980 }` plus
`handle { root * /srv/console; try_files {path} /index.html; file_server }`. The app uses
client-side routes, so unknown paths must fall back to `index.html`.

## How "all activity" works

The API records a `change_log` row for every insert, update and delete made through it (see
*Data console* in the backend README). History starts when that migration (V2) runs. Rows that
existed before then show only their `created_at` / `updated_at`. Changes made directly in SQL,
and a few bulk updates, are not recorded.
