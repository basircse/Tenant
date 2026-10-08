import { useNavigate } from 'react-router-dom'
import type { Column, Lookups, Row } from '../api/types'
import { humanize } from '../format'
import { Cell, OPENABLE_SKIP, recordPath } from './Cell'
import { Empty } from './ui'

/** A sortable grid of rows. Clicking a row opens the record. */
export function DataGrid({ table, columns, rows, lookups, sort, desc, onSort, hidden = [] }: {
  table: string
  columns: Column[]
  rows: Row[]
  lookups: Lookups
  sort?: string
  desc?: boolean
  onSort?: (column: string, desc: boolean) => void
  hidden?: string[]
}) {
  const navigate = useNavigate()
  const visible = columns.filter((c) => !hidden.includes(c.name))
  const openable = columns.some((c) => c.name === 'id') && !OPENABLE_SKIP.has(table)

  if (rows.length === 0) return <Empty>No rows match.</Empty>

  return (
    <div className="overflow-x-auto">
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr className="border-b border-line bg-surface-2 text-left">
            {visible.map((c) => {
              const active = sort === c.name
              return (
                <th key={c.name} scope="col" className="whitespace-nowrap px-3 py-2 text-xs font-semibold text-muted">
                  {onSort && !c.secret ? (
                    <button
                      className={`inline-flex items-center gap-1 hover:text-ink ${active ? 'text-ink' : ''}`}
                      onClick={() => onSort(c.name, active ? !desc : c.type === 'datetime' || c.type === 'date')}
                      title={c.name}
                    >
                      {humanize(c.name)}
                      <span aria-hidden className="w-2">{active ? (desc ? '↓' : '↑') : ''}</span>
                    </button>
                  ) : (
                    humanize(c.name)
                  )}
                </th>
              )
            })}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) => (
            <tr
              key={openable ? String(row.id) : i}
              className={`border-b border-line last:border-0 ${openable ? 'cursor-pointer hover:bg-surface-2' : ''}`}
              onClick={openable ? () => navigate(recordPath(table, String(row.id))) : undefined}
            >
              {visible.map((c) => (
                <td key={c.name} className="px-3 py-2 align-top">
                  <Cell column={c} value={row[c.name]} lookups={lookups} table={table} />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
