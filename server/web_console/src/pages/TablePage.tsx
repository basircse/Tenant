import { useParams, useSearchParams } from 'react-router-dom'
import { useTables } from '../api/hooks'
import { TableView, type TableState } from '../components/TableView'
import { ErrorBox, PageHeader, Spinner } from '../components/ui'
import { tableTitle } from '../format'

/** Table state in the URL: ?org=&q=&filter=col:val&from=&to=&sort=&desc=1&page= */
function readState(sp: URLSearchParams): TableState {
  return {
    org: sp.get('org') ?? undefined,
    q: sp.get('q') ?? undefined,
    filter: sp.getAll('filter'),
    from: sp.get('from') ?? undefined,
    to: sp.get('to') ?? undefined,
    sort: sp.get('sort') ?? undefined,
    desc: sp.get('desc') === '1',
    page: Number(sp.get('page') ?? 0),
    size: Number(sp.get('size') ?? 50),
  }
}

function writeState(s: TableState): URLSearchParams {
  const sp = new URLSearchParams()
  if (s.org) sp.set('org', s.org)
  if (s.q) sp.set('q', s.q)
  s.filter?.forEach((f) => sp.append('filter', f))
  if (s.from) sp.set('from', s.from)
  if (s.to) sp.set('to', s.to)
  if (s.sort) sp.set('sort', s.sort)
  if (s.desc) sp.set('desc', '1')
  if (s.page) sp.set('page', String(s.page))
  if (s.size !== 50) sp.set('size', String(s.size))
  return sp
}

export function TablePage() {
  const { table = '' } = useParams()
  const [sp, setSp] = useSearchParams()
  const { data: tables, error, isLoading } = useTables()
  const info = tables?.find((t) => t.name === table)
  const state = readState(sp)

  if (error) return <ErrorBox error={error} />
  if (isLoading) return <Spinner />
  if (!info) return <ErrorBox error={`There is no table called “${table}”.`} />

  return (
    <>
      <PageHeader
        title={tableTitle(info.name)}
        subtitle={
          <>
            <span className="font-mono text-xs">{info.name}</span> · {info.columns.length} columns ·{' '}
            {info.rows.toLocaleString()} rows in total
          </>
        }
      />
      <TableView
        key={info.name}
        info={info}
        state={state}
        onChange={(next) => setSp(writeState({ ...state, ...next }), { replace: next.page === undefined })}
      />
    </>
  )
}
