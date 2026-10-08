import { useState } from 'react'
import { Link } from 'react-router-dom'
import type { Activity } from '../api/types'
import { fmtAgo, fmtDateTime, humanize, shortId, tableTitle } from '../format'
import { recordPath } from './Cell'
import { Badge, Empty, StatusBadge } from './ui'

const VERBS: Record<string, string> = { INSERT: 'created', UPDATE: 'updated', DELETE: 'deleted' }

function show(v: unknown): string {
  if (v === null || v === undefined || v === '') return '∅'
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}

function Changes({ item }: { item: Activity }) {
  const [open, setOpen] = useState(item.action === 'UPDATE')
  const entries = Object.entries(item.changes ?? {})
  if (entries.length === 0) return null
  if (!open) {
    return (
      <button className="mt-1 text-xs text-muted hover:text-ink" onClick={() => setOpen(true)}>
        Show {entries.length} field{entries.length === 1 ? '' : 's'} ▸
      </button>
    )
  }
  return (
    <div className="mt-1.5 overflow-x-auto rounded-md bg-surface-2 px-2.5 py-1.5">
      <table className="text-xs">
        <tbody>
          {entries.map(([field, change]) => (
            <tr key={field} className="align-top">
              <td className="whitespace-nowrap py-0.5 pr-3 text-muted">{humanize(field)}</td>
              <td className="py-0.5 font-mono break-all">
                {item.action === 'UPDATE' ? (
                  <>
                    <span className="text-bad line-through decoration-bad/50">{show(change.old)}</span>
                    <span className="px-1.5 text-muted">→</span>
                    <span className="text-ok">{show(change.new)}</span>
                  </>
                ) : (
                  <span>{show(item.action === 'DELETE' ? change.old : change.new)}</span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {item.action !== 'UPDATE' && (
        <button className="mt-1 text-xs text-muted hover:text-ink" onClick={() => setOpen(false)}>
          Hide ▴
        </button>
      )}
    </div>
  )
}

function Target({ item }: { item: Activity }) {
  if (item.source === 'change' && item.table) {
    const title = tableTitle(item.table)
    if (item.rowId && item.action !== 'DELETE' && !item.rowId.includes(',')) {
      return (
        <Link to={recordPath(item.table, item.rowId)} className="link">
          {title} <span className="font-mono text-xs">{shortId(item.rowId)}</span>
        </Link>
      )
    }
    return (
      <span>
        {title} {item.rowId && <span className="font-mono text-xs text-muted">{shortId(item.rowId)}</span>}
      </span>
    )
  }
  if (item.source === 'audit' && item.table) {
    return (
      <span className="text-muted">
        {item.table} {item.rowId && <span className="font-mono text-xs">{item.rowId.length > 20 ? shortId(item.rowId) : item.rowId}</span>}
      </span>
    )
  }
  return null
}

/** Row changes, audit entries and licence events in one list. */
export function ActivityList({ items, showOrg = true }: { items: Activity[]; showOrg?: boolean }) {
  if (items.length === 0) return <Empty>No activity yet.</Empty>
  return (
    <ol className="divide-y divide-line">
      {items.map((item) => (
        <li key={item.key} className="flex gap-3 px-4 py-3">
          <div className="w-16 shrink-0 pt-0.5">
            {item.source === 'change' ? (
              <StatusBadge value={item.action} />
            ) : (
              <Badge tone={item.source === 'license' ? 'accent' : 'neutral'}>{item.source === 'license' ? 'licence' : 'audit'}</Badge>
            )}
          </div>
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-baseline gap-x-1.5 text-sm">
              <span className="font-medium">{item.actor}</span>
              {item.actorRole && <span className="text-xs text-muted">({item.actorRole.toLowerCase()})</span>}
              <span className="text-muted">{item.source === 'change' ? VERBS[item.action] ?? item.action : item.action}</span>
              <Target item={item} />
            </div>
            {item.details && <div className="mt-0.5 text-sm text-muted">{item.details}</div>}
            <Changes item={item} />
            <div className="mt-1 text-xs text-muted sm:hidden">
              {fmtAgo(item.at)}
              {showOrg && item.orgName && ` · ${item.orgName}`}
            </div>
          </div>
          <div className="hidden shrink-0 text-right text-xs text-muted sm:block">
            <div title={fmtDateTime(item.at)}>{fmtAgo(item.at)}</div>
            {showOrg && item.orgId && (
              <Link to={`/orgs/${item.orgId}`} className="mt-0.5 block max-w-40 truncate hover:text-ink">
                {item.orgName ?? shortId(item.orgId)}
              </Link>
            )}
          </div>
        </li>
      ))}
    </ol>
  )
}
