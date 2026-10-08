import { Link, useParams } from 'react-router-dom'
import { useRecord } from '../api/hooks'
import { ActivityList } from '../components/ActivityList'
import { Cell } from '../components/Cell'
import { BlockButton, BlockedNote } from '../components/Users'
import { ErrorBox, PageHeader, Spinner } from '../components/ui'
import { humanize, shortId, tableTitle } from '../format'

export function RecordPage() {
  const { table = '', id = '' } = useParams()
  const { data, error, isLoading } = useRecord(table, id)

  if (error) return <ErrorBox error={error} />
  if (isLoading || !data) return <Spinner />

  const orgId = table === 'organizations' ? id : (data.row.org_id as string | undefined)

  return (
    <>
      <div className="mb-2 text-sm text-muted">
        <Link to={`/tables/${table}`} className="hover:text-ink">{tableTitle(table)}</Link> /
      </div>
      <PageHeader
        title={data.label ?? shortId(id)}
        subtitle={<span className="font-mono text-xs">{id}</span>}
        actions={
          <>
            {table === 'users' && data.row.role !== 'VENDOR' && (
              <BlockButton id={id} name={String(data.row.name ?? '')} blocked={data.row.status === 'BLOCKED'} />
            )}
            {orgId && (
              <Link to={`/orgs/${orgId}`} className="btn">
                {table === 'organizations' ? 'Open organisation' : 'Go to organisation'}
              </Link>
            )}
          </>
        }
      />
      {table === 'users' && data.row.status === 'BLOCKED' && (
        <div className="mb-4 rounded-md border border-bad/30 bg-bad-soft px-3 py-2">
          <BlockedNote by={data.row.blocked_by as string | null} byVendor={data.row.blocked_by_vendor === true}
            at={data.row.blocked_at as string | null} reason={data.row.blocked_reason as string | null} />
        </div>
      )}

      <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
        <section className="card overflow-hidden">
          <h2 className="border-b border-line px-4 py-2.5 text-sm font-semibold">Fields</h2>
          <dl className="divide-y divide-line">
            {data.columns.map((c) => (
              <div key={c.name} className="grid grid-cols-[minmax(0,10rem)_minmax(0,1fr)] gap-3 px-4 py-2">
                <dt className="truncate text-sm text-muted" title={c.name}>{humanize(c.name)}</dt>
                <dd className="min-w-0 text-sm">
                  <Cell column={c} value={data.row[c.name]} lookups={data.lookups} table={table} full />
                </dd>
              </div>
            ))}
          </dl>
        </section>

        <div className="space-y-5">
          <section className="card overflow-hidden">
            <h2 className="border-b border-line px-4 py-2.5 text-sm font-semibold">Linked records</h2>
            {data.referencedBy.length === 0 ? (
              <div className="px-4 py-4 text-sm text-muted">No other rows point to this one.</div>
            ) : (
              <ul className="divide-y divide-line">
                {data.referencedBy.map((r) => (
                  <li key={`${r.table}.${r.column}`}>
                    <Link
                      to={`/tables/${r.table}?filter=${encodeURIComponent(`${r.column}:${id}`)}`}
                      className="flex items-center justify-between px-4 py-2 text-sm hover:bg-surface-2"
                    >
                      <span>
                        {tableTitle(r.table)}
                        <span className="ml-1.5 text-xs text-muted">by {humanize(r.column).toLowerCase()}</span>
                      </span>
                      <span className="tabular font-medium text-accent">{r.count.toLocaleString()} ›</span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="card overflow-hidden">
            <h2 className="border-b border-line px-4 py-2.5 text-sm font-semibold">History</h2>
            <ActivityList items={data.history} showOrg={false} />
          </section>
        </div>
      </div>
    </>
  )
}
