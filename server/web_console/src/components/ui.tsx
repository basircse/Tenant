import { useEffect, type ReactNode } from 'react'

type Tone = 'ok' | 'warn' | 'bad' | 'info' | 'accent' | 'neutral'

const TONES: Record<Tone, string> = {
  ok: 'bg-ok-soft text-ok',
  warn: 'bg-warn-soft text-warn',
  bad: 'bg-bad-soft text-bad',
  info: 'bg-info-soft text-info',
  accent: 'bg-accent-soft text-accent',
  neutral: 'bg-surface-2 text-muted',
}

const VALUE_TONES: Record<string, Tone> = {
  ACTIVE: 'ok', APPROVED: 'ok', PAID: 'ok', COMPLETED: 'ok', CLOSED: 'neutral', AVAILABLE: 'ok', INSERT: 'ok',
  PENDING: 'warn', PARTIALLY_PAID: 'warn', EXPIRING: 'warn', GRACE: 'warn', ASSIGNED: 'warn',
  IN_PROGRESS: 'warn', RESERVED: 'warn', MAINTENANCE: 'warn', DRAFT: 'neutral', UPDATE: 'info',
  OVERDUE: 'bad', BLOCKED: 'bad', SUSPENDED: 'bad', EXPIRED: 'bad', REJECTED: 'bad', LOCKED: 'bad',
  TERMINATED: 'bad', CANCELLED: 'neutral', DELETE: 'bad', INACTIVE: 'neutral', PREVIOUS: 'neutral',
  OCCUPIED: 'info', OPEN: 'info', HIGH: 'bad', URGENT: 'bad', MEDIUM: 'warn', LOW: 'neutral',
  VENDOR: 'accent', ADMIN: 'info', MANAGER: 'info', TENANT: 'neutral',
}

export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: Tone }) {
  return (
    <span className={`inline-flex items-center whitespace-nowrap rounded px-1.5 py-0.5 text-xs font-medium ${TONES[tone]}`}>
      {children}
    </span>
  )
}

/** An enum-like value (status, operation, role) with a colour that matches its meaning. */
export function StatusBadge({ value }: { value: string }) {
  return <Badge tone={VALUE_TONES[value] ?? 'neutral'}>{value.replace(/_/g, ' ').toLowerCase()}</Badge>
}

export function Spinner({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="flex items-center gap-2 p-6 text-sm text-muted" role="status">
      <span className="size-4 animate-spin rounded-full border-2 border-line border-t-accent" />
      {label}
    </div>
  )
}

export function ErrorBox({ error }: { error: unknown }) {
  const message = error instanceof Error ? error.message : String(error)
  return <div className="rounded-md border border-bad/30 bg-bad-soft px-3 py-2 text-sm text-bad">{message}</div>
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="px-4 py-10 text-center text-sm text-muted">{children}</div>
}

export function PageHeader({ title, subtitle, actions }: { title: ReactNode; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
      <div className="min-w-0">
        <h1 className="truncate text-xl font-semibold tracking-tight">{title}</h1>
        {subtitle && <div className="mt-0.5 text-sm text-muted">{subtitle}</div>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  )
}

export function Kpi({ label, value, tone, hint }: { label: string; value: ReactNode; tone?: Tone; hint?: ReactNode }) {
  const color = tone === 'bad' ? 'text-bad' : tone === 'warn' ? 'text-warn' : tone === 'ok' ? 'text-ok' : 'text-ink'
  return (
    <div className="card px-4 py-3">
      <div className="text-xs font-medium text-muted">{label}</div>
      <div className={`tabular mt-1 text-2xl font-semibold ${color}`}>{value}</div>
      {hint && <div className="mt-0.5 text-xs text-muted">{hint}</div>}
    </div>
  )
}

export function Modal({ title, onClose, children, footer }: {
  title: string
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 p-0 sm:items-center sm:p-4" onClick={onClose}>
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="card max-h-[90vh] w-full overflow-auto text-left shadow-xl sm:max-w-md"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="border-b border-line px-4 py-3 font-semibold">{title}</div>
        <div className="space-y-3 px-4 py-4">{children}</div>
        {footer && <div className="flex justify-end gap-2 border-t border-line px-4 py-3">{footer}</div>}
      </div>
    </div>
  )
}

export function Pager({ page, size, total, onPage }: {
  page: number
  size: number
  total: number
  onPage: (page: number) => void
}) {
  const pages = Math.max(1, Math.ceil(total / size))
  const first = total === 0 ? 0 : page * size + 1
  const last = Math.min(total, (page + 1) * size)
  return (
    <div className="flex flex-wrap items-center justify-between gap-2 px-3 py-2 text-sm text-muted">
      <span className="tabular">
        {first}–{last} of {total.toLocaleString()}
      </span>
      <div className="flex items-center gap-1">
        <button className="btn px-2 py-1" disabled={page === 0} onClick={() => onPage(page - 1)} aria-label="Previous page">
          ‹ Prev
        </button>
        <span className="tabular px-2">
          {page + 1} / {pages}
        </span>
        <button className="btn px-2 py-1" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)} aria-label="Next page">
          Next ›
        </button>
      </div>
    </div>
  )
}

export function Tabs<T extends string>({ value, onChange, tabs }: {
  value: T
  onChange: (v: T) => void
  tabs: { value: T; label: ReactNode }[]
}) {
  return (
    <div className="mb-4 flex gap-1 overflow-x-auto overflow-y-hidden border-b border-line" role="tablist">
      {tabs.map((t) => (
        <button
          key={t.value}
          role="tab"
          aria-selected={value === t.value}
          onClick={() => onChange(t.value)}
          className={`-mb-px whitespace-nowrap border-b-2 px-3 py-2 text-sm font-medium transition-colors ${
            value === t.value ? 'border-accent text-ink' : 'border-transparent text-muted hover:text-ink'
          }`}
        >
          {t.label}
        </button>
      ))}
    </div>
  )
}
