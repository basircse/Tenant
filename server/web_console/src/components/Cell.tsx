import { Link } from 'react-router-dom'
import type { Column, Lookups } from '../api/types'
import { fmtDate, fmtDateTime, fmtMoney, shortId } from '../format'
import { StatusBadge } from './ui'

/** Columns whose values are a small set of codes, shown as coloured badges. */
const BADGE_COLUMNS = new Set(['status', 'op', 'role', 'actor_role', 'app', 'method', 'priority', 'state'])

/** Tables whose rows can be opened by id (others have composite keys). */
export const OPENABLE_SKIP = new Set(['code_counters', 'user_properties'])

export function recordPath(table: string, id: string) {
  return `/tables/${table}/${encodeURIComponent(id)}`
}

/** One value, formatted by column type; foreign keys become links labelled with the linked row. */
export function Cell({ column, value, lookups, table, full = false }: {
  column: Column
  value: unknown
  lookups?: Lookups
  table: string
  full?: boolean
}) {
  if (value === null || value === undefined || value === '') {
    return <span className="text-muted/60">—</span>
  }
  if (column.secret) return <span className="text-muted">•••</span>

  if (column.type === 'id') {
    const id = String(value)
    if (column.ref) {
      const label = lookups?.[column.ref]?.[id]
      return (
        <Link to={recordPath(column.ref, id)} className={`link ${full ? '' : 'whitespace-nowrap'}`} title={id} onClick={(e) => e.stopPropagation()}>
          {label ?? <span className="font-mono text-xs">{shortId(id)}</span>}
        </Link>
      )
    }
    if (column.name === 'id' && !OPENABLE_SKIP.has(table)) {
      return (
        <Link to={recordPath(table, id)} className="link font-mono text-xs" title={id} onClick={(e) => e.stopPropagation()}>
          {full ? id : shortId(id)}
        </Link>
      )
    }
    return <span className="font-mono text-xs" title={id}>{full ? id : shortId(id)}</span>
  }

  switch (column.type) {
    case 'money':
      return <span className="tabular">{fmtMoney(value as number)}</span>
    case 'number':
      return <span className="tabular">{String(value)}</span>
    case 'date':
      return <span className="whitespace-nowrap">{fmtDate(String(value))}</span>
    case 'datetime':
      return <span className="whitespace-nowrap" title={String(value)}>{fmtDateTime(String(value))}</span>
    case 'bool':
      return value ? <span className="text-ok">Yes</span> : <span className="text-muted">No</span>
    case 'json': {
      const text = JSON.stringify(value, null, full ? 2 : undefined)
      return full ? (
        <pre className="max-h-80 overflow-auto rounded bg-surface-2 p-2 font-mono text-xs">{text}</pre>
      ) : (
        <span className="block max-w-80 truncate font-mono text-xs text-muted" title={text}>{text}</span>
      )
    }
  }

  const text = String(value)
  if (BADGE_COLUMNS.has(column.name) && /^[A-Z_]+$/.test(text)) return <StatusBadge value={text} />
  if (full) return <span className="whitespace-pre-wrap break-words">{text}</span>
  return (
    <span className="block max-w-72 truncate" title={text.length > 40 ? text : undefined}>
      {text}
    </span>
  )
}
