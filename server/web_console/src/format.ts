// Display helpers. Dates are shown in the browser's time zone.

const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })
const dateOnly = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' })
const money = new Intl.NumberFormat(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const number = new Intl.NumberFormat()

export const fmtNumber = (n: number) => number.format(n)
export const fmtMoney = (n: number | string) => money.format(Number(n))

export function fmtDateTime(iso: string | null | undefined): string {
  return iso ? dateTime.format(new Date(iso)) : ''
}

/** A calendar date (yyyy-mm-dd) without shifting it through time zones. */
export function fmtDate(iso: string | null | undefined): string {
  if (!iso) return ''
  const [y, m, d] = iso.slice(0, 10).split('-').map(Number)
  return dateOnly.format(new Date(y, m - 1, d))
}

export function fmtAgo(iso: string): string {
  const s = Math.round((Date.now() - new Date(iso).getTime()) / 1000)
  if (s < 45) return 'just now'
  const m = Math.round(s / 60)
  if (m < 60) return `${m} min ago`
  const h = Math.round(m / 60)
  if (h < 24) return `${h} h ago`
  const d = Math.round(h / 24)
  if (d < 30) return `${d} d ago`
  return fmtDate(iso)
}

const TITLES: Record<string, string> = {
  organizations: 'Organisation records',
  rent_invoices: 'Rent invoices',
  maintenance_requests: 'Maintenance requests',
  maintenance_history: 'Maintenance history',
  user_properties: 'Manager properties',
  code_counters: 'Code counters',
  tenant_documents: 'Tenant documents',
  refresh_tokens: 'Sign-in sessions',
  push_tokens: 'Push tokens',
  audit_logs: 'Audit log',
  license_events: 'Licence events',
  change_log: 'Change log',
}

/** "rent_invoices" → "Rent invoices". */
export function tableTitle(name: string): string {
  return TITLES[name] ?? humanize(name)
}

/** "monthly_rent" → "Monthly rent", "org_id" → "Organisation". */
export function humanize(name: string): string {
  if (name === 'org_id') return 'Organisation'
  if (name === 'id') return 'ID'
  const s = name.replace(/_id$/, '').replace(/_/g, ' ')
  return s.charAt(0).toUpperCase() + s.slice(1)
}

export function shortId(id: string): string {
  return id.length > 12 ? id.slice(0, 8) : id
}

/** yyyy-mm-dd for date inputs, n days back from today. */
export function daysAgo(n: number): string {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return d.toISOString().slice(0, 10)
}
