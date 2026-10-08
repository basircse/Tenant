import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useOrgs } from '../api/hooks'
import { Empty, ErrorBox, PageHeader, Spinner, StatusBadge } from '../components/ui'
import { fmtDate } from '../format'

const STATES = ['PENDING', 'ACTIVE', 'GRACE', 'EXPIRED', 'SUSPENDED', 'REJECTED']

function usage(used: number, max: number | null) {
  return max ? `${used} / ${max}` : `${used}`
}

export function OrganizationsPage() {
  const [sp, setSp] = useSearchParams()
  const state = sp.get('state') ?? undefined
  const [q, setQ] = useState(sp.get('q') ?? '')
  const [debounced, setDebounced] = useState(q)
  const { data, error, isLoading } = useOrgs(state, debounced)
  const navigate = useNavigate()

  useEffect(() => {
    const t = setTimeout(() => setDebounced(q), 300)
    return () => clearTimeout(t)
  }, [q])

  const setState = (s?: string) => {
    const next = new URLSearchParams(sp)
    if (s) next.set('state', s)
    else next.delete('state')
    setSp(next, { replace: true })
  }

  return (
    <>
      <PageHeader title="Organisations" subtitle="Every landlord account, its licence and its usage." />
      <div className="card overflow-hidden">
        <div className="flex flex-wrap items-center gap-2 border-b border-line p-3">
          <input className="input sm:max-w-xs" placeholder="Search name, contact, phone, email" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search organisations" />
          <div className="flex flex-wrap gap-1.5">
            <button onClick={() => setState()} className={`rounded-full px-2.5 py-0.5 text-xs font-medium ring-1 ${!state ? 'bg-accent-soft text-accent ring-accent/40' : 'ring-line hover:ring-accent/40'}`}>All</button>
            {STATES.map((s) => (
              <button key={s} onClick={() => setState(s)} aria-pressed={state === s}
                className={`rounded-full px-2.5 py-0.5 text-xs font-medium ring-1 ${state === s ? 'bg-accent-soft text-accent ring-accent/40' : 'ring-line hover:ring-accent/40'}`}>
                {s.charAt(0) + s.slice(1).toLowerCase()}
              </button>
            ))}
          </div>
        </div>
        {error ? <div className="p-3"><ErrorBox error={error} /></div> : isLoading ? <Spinner /> : !data?.length ? <Empty>No organisations match.</Empty> : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line bg-surface-2 text-left text-xs text-muted">
                  <th className="px-3 py-2 font-semibold">Organisation</th>
                  <th className="px-3 py-2 font-semibold">Licence</th>
                  <th className="px-3 py-2 font-semibold">Plan</th>
                  <th className="px-3 py-2 font-semibold">Expires</th>
                  <th className="px-3 py-2 text-right font-semibold">Units</th>
                  <th className="px-3 py-2 text-right font-semibold">Devices</th>
                  <th className="px-3 py-2 font-semibold">Registered</th>
                </tr>
              </thead>
              <tbody>
                {data.map((o) => (
                  <tr key={o.id} className="cursor-pointer border-b border-line last:border-0 hover:bg-surface-2" onClick={() => navigate(`/orgs/${o.id}`)}>
                    <td className="px-3 py-2">
                      <Link to={`/orgs/${o.id}`} className="font-medium hover:text-accent" onClick={(e) => e.stopPropagation()}>{o.name}</Link>
                      <div className="text-xs text-muted">{[o.contactName, o.phone, o.email].filter(Boolean).join(' · ')}</div>
                    </td>
                    <td className="px-3 py-2"><StatusBadge value={o.state} /></td>
                    <td className="px-3 py-2">{o.plan ?? <span className="text-muted">—</span>}</td>
                    <td className="whitespace-nowrap px-3 py-2">
                      {o.expiresOn ? fmtDate(o.expiresOn) : <span className="text-muted">{o.state === 'ACTIVE' ? 'No expiry' : '—'}</span>}
                      {o.daysLeft !== null && o.daysLeft <= 30 && <div className={`text-xs ${o.daysLeft < 0 ? 'text-bad' : 'text-warn'}`}>{o.daysLeft < 0 ? `${-o.daysLeft} days ago` : `${o.daysLeft} days left`}</div>}
                    </td>
                    <td className="tabular whitespace-nowrap px-3 py-2 text-right">{usage(o.unitsUsed, o.maxUnits)}</td>
                    <td className="tabular whitespace-nowrap px-3 py-2 text-right">
                      {usage(o.devicesUsed, o.maxDevices)}
                      {o.pendingDevices > 0 && <div className="text-xs text-warn">{o.pendingDevices} waiting</div>}
                    </td>
                    <td className="whitespace-nowrap px-3 py-2 text-muted">{fmtDate(o.registeredAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </>
  )
}
