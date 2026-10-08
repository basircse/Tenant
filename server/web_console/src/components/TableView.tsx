import { useEffect, useState } from 'react'
import { download, qs } from '../api/client'
import { useRows, type RowParams } from '../api/hooks'
import type { TableInfo } from '../api/types'
import { humanize } from '../format'
import { DataGrid } from './DataGrid'
import { OrgPicker } from './OrgPicker'
import { ErrorBox, Pager, Spinner } from './ui'

export interface TableState extends RowParams {
  page: number
  size: number
}

/** Columns hidden by default: bookkeeping that rarely matters when browsing. */
const QUIET = ['version', 'updated_at']

/**
 * Search, filter, sort and page through one table. The state lives with the caller (the URL on the
 * table page) so links and the back button work. {@code fixedOrg} locks the organisation filter.
 */
export function TableView({ info, state, onChange, fixedOrg }: {
  info: TableInfo
  state: TableState
  onChange: (next: Partial<TableState>) => void
  fixedOrg?: string
}) {
  const org = fixedOrg ?? state.org
  const params: RowParams = { ...state, org: info.orgScoped ? org : undefined }
  const { data, error, isLoading, isFetching } = useRows(info.name, params)
  const [q, setQ] = useState(state.q ?? '')
  const [showAll, setShowAll] = useState(false)
  const [exporting, setExporting] = useState(false)

  useEffect(() => setQ(state.q ?? ''), [state.q])
  // Apply the search after typing stops.
  useEffect(() => {
    if ((state.q ?? '') === q) return
    const t = setTimeout(() => onChange({ q: q || undefined, page: 0 }), 350)
    return () => clearTimeout(t)
  }, [q]) // eslint-disable-line react-hooks/exhaustive-deps

  const hasCreated = info.columns.some((c) => c.name === 'created_at')
  const hidden = showAll ? [] : QUIET.filter((c) => info.columns.length > 6 || c === 'version')

  async function exportCsv() {
    setExporting(true)
    try {
      const { page: _p, size: _s, ...rest } = params
      await download(`/api/console/tables/${info.name}/export${qs({ ...rest, desc: rest.desc || undefined })}`, `${info.name}.csv`)
    } finally {
      setExporting(false)
    }
  }

  return (
    <div className="card overflow-hidden">
      <div className="flex flex-wrap items-end gap-2 border-b border-line p-3">
        <div className="min-w-48 flex-1">
          <label className="label" htmlFor="q">Search</label>
          <input id="q" className="input" placeholder="Any text, code, id…" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        {info.orgScoped && !fixedOrg && (
          <div className="w-full sm:w-56">
            <label className="label">Organisation</label>
            <OrgPicker value={state.org} onChange={(o) => onChange({ org: o, page: 0 })} />
          </div>
        )}
        {hasCreated && (
          <>
            <div>
              <label className="label" htmlFor="from">Created from</label>
              <input id="from" type="date" className="input" value={state.from ?? ''} onChange={(e) => onChange({ from: e.target.value || undefined, page: 0 })} />
            </div>
            <div>
              <label className="label" htmlFor="to">to</label>
              <input id="to" type="date" className="input" value={state.to ?? ''} onChange={(e) => onChange({ to: e.target.value || undefined, page: 0 })} />
            </div>
          </>
        )}
        <div className="flex gap-2">
          <button className="btn" onClick={() => setShowAll((s) => !s)} title="Show version and updated-at columns">
            {showAll ? 'Fewer columns' : 'All columns'}
          </button>
          <button className="btn" onClick={() => void exportCsv()} disabled={exporting || !data?.total}>
            {exporting ? 'Exporting…' : 'Export CSV'}
          </button>
        </div>
      </div>

      {state.filter && state.filter.length > 0 && (
        <div className="flex flex-wrap items-center gap-2 border-b border-line bg-surface-2 px-3 py-2 text-sm">
          <span className="text-muted">Filtered:</span>
          {state.filter.map((f) => {
            const [col, ...rest] = f.split(':')
            const val = rest.join(':')
            const label = data?.lookups[info.columns.find((c) => c.name === col)?.ref ?? '']?.[val] ?? val
            return (
              <button
                key={f}
                className="inline-flex items-center gap-1 rounded bg-surface px-2 py-0.5 text-xs ring-1 ring-line hover:ring-accent"
                onClick={() => onChange({ filter: state.filter!.filter((x) => x !== f), page: 0 })}
                title="Remove filter"
              >
                {humanize(col)} = {label || '∅'} <span aria-hidden>×</span>
              </button>
            )
          })}
        </div>
      )}

      {error ? (
        <div className="p-3"><ErrorBox error={error} /></div>
      ) : isLoading || !data ? (
        <Spinner />
      ) : (
        <div className={isFetching ? 'opacity-60 transition-opacity' : ''}>
          <DataGrid
            table={info.name}
            columns={data.columns}
            rows={data.rows}
            lookups={data.lookups}
            sort={state.sort}
            desc={state.desc}
            hidden={fixedOrg || state.org ? [...hidden, 'org_id'] : hidden}
            onSort={(sort, desc) => onChange({ sort, desc, page: 0 })}
          />
          <div className="border-t border-line">
            <Pager page={data.page} size={data.size} total={data.total} onPage={(page) => onChange({ page })} />
          </div>
        </div>
      )}
    </div>
  )
}
