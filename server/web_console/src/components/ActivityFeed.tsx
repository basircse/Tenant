import { useEffect, useState } from 'react'
import { useActivity, useTables, type ActivityParams } from '../api/hooks'
import { tableTitle } from '../format'
import { ActivityList } from './ActivityList'
import { OrgPicker } from './OrgPicker'
import { ErrorBox, Spinner } from './ui'

const SOURCES = [
  { value: 'change', label: 'Row changes' },
  { value: 'audit', label: 'Audit log' },
  { value: 'license', label: 'Licence events' },
]

/** Activity across all tables with filters; {@code fixedOrg} limits it to one organisation. */
export function ActivityFeed({ fixedOrg, initial = {} }: { fixedOrg?: string; initial?: ActivityParams }) {
  const [p, setP] = useState<ActivityParams>(initial)
  const [actor, setActor] = useState(initial.actor ?? '')
  const { data: tables } = useTables()
  const params = { ...p, org: fixedOrg ?? p.org }
  const { data, error, isLoading, fetchNextPage, hasNextPage, isFetchingNextPage } = useActivity(params)

  useEffect(() => {
    const t = setTimeout(() => setP((x) => ({ ...x, actor: actor || undefined })), 350)
    return () => clearTimeout(t)
  }, [actor])

  const sources = p.source ?? []
  const toggleSource = (s: string) =>
    setP({ ...p, source: sources.includes(s) ? sources.filter((x) => x !== s) : [...sources, s] })
  const items = data?.pages.flatMap((pg) => pg.items) ?? []

  return (
    <div className="card overflow-hidden">
      <div className="flex flex-wrap items-end gap-2 border-b border-line p-3">
        {!fixedOrg && (
          <div className="w-full sm:w-56">
            <label className="label">Organisation</label>
            <OrgPicker value={p.org} onChange={(org) => setP({ ...p, org })} />
          </div>
        )}
        <div className="w-full sm:w-48">
          <label className="label" htmlFor="act-table">Table</label>
          <select id="act-table" className="input" value={p.table ?? ''} onChange={(e) => setP({ ...p, table: e.target.value || undefined })}>
            <option value="">All tables</option>
            {tables?.filter((t) => !['change_log', 'audit_logs', 'license_events'].includes(t.name)).map((t) => (
              <option key={t.name} value={t.name}>{tableTitle(t.name)}</option>
            ))}
          </select>
        </div>
        <div className="w-[calc(50%-0.25rem)] sm:w-32">
          <label className="label" htmlFor="act-op">Operation</label>
          <select id="act-op" className="input" value={p.op ?? ''} onChange={(e) => setP({ ...p, op: e.target.value || undefined })}>
            <option value="">Any</option>
            <option value="INSERT">Created</option>
            <option value="UPDATE">Updated</option>
            <option value="DELETE">Deleted</option>
          </select>
        </div>
        <div className="w-[calc(50%-0.25rem)] sm:w-40">
          <label className="label" htmlFor="act-actor">By</label>
          <input id="act-actor" className="input" placeholder="Name" value={actor} onChange={(e) => setActor(e.target.value)} />
        </div>
        <div>
          <label className="label" htmlFor="act-from">From</label>
          <input id="act-from" type="date" className="input" value={p.from ?? ''} onChange={(e) => setP({ ...p, from: e.target.value || undefined })} />
        </div>
        <div>
          <label className="label" htmlFor="act-to">To</label>
          <input id="act-to" type="date" className="input" value={p.to ?? ''} onChange={(e) => setP({ ...p, to: e.target.value || undefined })} />
        </div>
      </div>
      <div className="flex flex-wrap gap-1.5 border-b border-line px-3 py-2">
        {SOURCES.map((s) => {
          const on = sources.length === 0 || sources.includes(s.value)
          return (
            <button
              key={s.value}
              onClick={() => toggleSource(s.value)}
              aria-pressed={sources.includes(s.value)}
              className={`rounded-full px-2.5 py-0.5 text-xs font-medium ring-1 transition-colors ${
                sources.includes(s.value) ? 'bg-accent-soft text-accent ring-accent/40' : on ? 'text-ink ring-line hover:ring-accent/40' : 'text-muted ring-line'
              }`}
            >
              {s.label}
            </button>
          )
        })}
        {(p.table || p.op) && <span className="self-center text-xs text-muted">Table and operation filters show row changes only.</span>}
      </div>
      {error ? (
        <div className="p-3"><ErrorBox error={error} /></div>
      ) : isLoading ? (
        <Spinner />
      ) : (
        <>
          <ActivityList items={items} showOrg={!fixedOrg} />
          {hasNextPage && (
            <div className="border-t border-line p-3 text-center">
              <button className="btn" onClick={() => void fetchNextPage()} disabled={isFetchingNextPage}>
                {isFetchingNextPage ? 'Loading…' : 'Load older'}
              </button>
            </div>
          )}
        </>
      )}
    </div>
  )
}
