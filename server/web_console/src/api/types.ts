// Shapes returned by /api/console and /api/vendor.

export type ColumnType = 'id' | 'text' | 'number' | 'money' | 'date' | 'datetime' | 'bool' | 'json'

export interface Column {
  name: string
  type: ColumnType
  nullable: boolean
  ref: string | null
  secret: boolean
}

export interface TableInfo {
  name: string
  group: string
  columns: Column[]
  primaryKey: string[]
  openable: boolean
  orgScoped: boolean
  rows: number
}

export type Row = Record<string, unknown>
export type Lookups = Record<string, Record<string, string>>

export interface RowPage {
  table: string
  columns: Column[]
  rows: Row[]
  total: number
  page: number
  size: number
  lookups: Lookups
}

export interface Activity {
  source: 'change' | 'audit' | 'license'
  key: string
  at: string
  orgId: string | null
  orgName: string | null
  actor: string
  actorRole: string | null
  action: string
  table: string | null
  rowId: string | null
  details: string | null
  changes: Record<string, { old?: unknown; new?: unknown }> | null
}

export interface ActivityPage {
  items: Activity[]
  nextBefore: string | null
}

export interface RecordView {
  table: string
  columns: Column[]
  row: Row
  label: string | null
  lookups: Lookups
  referencedBy: { table: string; column: string; count: number }[]
  history: Activity[]
}

export interface ConsoleSummary {
  tables: { name: string; group: string; rows: number }[]
  activityByDay: { day: string; inserts: number; updates: number; deletes: number }[]
  changesToday: number
}

export type LicenseState = 'PENDING' | 'REJECTED' | 'SUSPENDED' | 'ACTIVE' | 'GRACE' | 'EXPIRED'

export interface OrgSummary {
  id: string
  name: string
  contactName: string | null
  phone: string | null
  email: string | null
  status: string
  state: LicenseState
  plan: string | null
  expiresOn: string | null
  daysLeft: number | null
  maxUnits: number | null
  unitsUsed: number
  maxDevices: number | null
  devicesUsed: number
  pendingDevices: number
  registeredAt: string
  approvedAt: string | null
}

export interface DeviceView {
  id: string
  app: 'ADMIN' | 'TENANT'
  name: string | null
  platform: string | null
  status: 'APPROVED' | 'PENDING' | 'BLOCKED'
  lastSeenAt: string | null
  registeredAt: string
}

export interface OrgDetail {
  organization: OrgSummary
  address: string | null
  licenseNote: string | null
  admins: { id: string; name: string; username: string; email: string | null; mobile: string | null; status: string }[]
  devices: DeviceView[]
  history: {
    action: string
    details: string | null
    expiresBefore: string | null
    expiresAfter: string | null
    actorName: string
    at: string
  }[]
}

export interface VendorSummary {
  pending: number
  active: number
  expiringIn30Days: number
  inGrace: number
  expired: number
  suspended: number
  rejected: number
  pendingDevices: number
}

export interface UserView {
  id: string
  name: string
  username: string
  email: string | null
  mobile: string | null
  role: 'VENDOR' | 'ADMIN' | 'MANAGER' | 'TENANT'
  status: 'ACTIVE' | 'INACTIVE' | 'LOCKED' | 'BLOCKED'
  tenantId: string | null
  propertyIds: string[]
  mustChangePassword: boolean
  createdAt: string
  blockedReason: string | null
  blockedBy: string | null
  blockedAt: string | null
  blockedByVendor: boolean
}

export interface VendorUser {
  user: UserView
  orgId: string
  orgName: string | null
}
