import { useState, type FormEvent } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { useConsoleSummary, useOrgDetail, useTables, useVendorAction } from '../api/hooks'
import type { OrgDetail } from '../api/types'
import { ActivityChart } from '../components/ActivityChart'
import { ActivityFeed } from '../components/ActivityFeed'
import { TableView, type TableState } from '../components/TableView'
import { UsersList } from '../components/Users'
import { ErrorBox, Kpi, Modal, PageHeader, Spinner, StatusBadge, Tabs } from '../components/ui'
import { fmtDate, fmtDateTime, tableTitle } from '../format'

type Action = 'approve' | 'reject' | 'extend' | 'limits' | 'suspend' | 'reactivate'

const ACTION_TITLES: Record<Action, string> = {
  approve: 'Approve organisation',
  reject: 'Reject registration',
  extend: 'Extend licence',
  limits: 'Change plan and limits',
  suspend: 'Suspend organisation',
  reactivate: 'Reactivate organisation',
}

function actionsFor(state: string): Action[] {
  switch (state) {
    case 'PENDING': return ['approve', 'reject']
    case 'REJECTED': return ['approve']
    case 'SUSPENDED': return ['reactivate', 'limits']
    default: return ['extend', 'limits', 'suspend']
  }
}

function ActionDialog({ action, org, onClose }: { action: Action; org: OrgDetail; onClose: () => void }) {
  const o = org.organization
  const mutation = useVendorAction()
  const [f, setF] = useState({
    expiresOn: o.expiresOn ?? '',
    maxUnits: o.maxUnits?.toString() ?? '',
    maxDevices: o.maxDevices?.toString() ?? '',
    plan: o.plan ?? '',
    note: '',
    reason: '',
  })
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value })
  const num = (s: string) => (s.trim() ? Number(s) : null)

  function submit(e: FormEvent) {
    e.preventDefault()
    const base = `/organizations/${o.id}`
    const call = {
      approve: { path: `${base}/approve`, body: { expiresOn: f.expiresOn || null, maxUnits: num(f.maxUnits), maxDevices: num(f.maxDevices), plan: f.plan || null, note: f.note || null } },
      reject: { path: `${base}/reject`, body: { reason: f.reason } },
      extend: { path: `${base}/extend`, body: { expiresOn: f.expiresOn, note: f.note || null } },
      limits: { path: `${base}/limits`, method: 'PUT', body: { maxUnits: num(f.maxUnits), maxDevices: num(f.maxDevices), plan: f.plan || null, note: f.note || null } },
      suspend: { path: `${base}/suspend`, body: { reason: f.reason } },
      reactivate: { path: `${base}/reactivate`, body: { note: f.note || null } },
    }[action]
    mutation.mutate(call, { onSuccess: onClose })
  }

  const danger = action === 'reject' || action === 'suspend'
  return (
    <Modal
      title={ACTION_TITLES[action]}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose} type="button">Cancel</button>
          <button className={`btn ${danger ? 'btn-danger' : 'btn-primary'}`} form="action-form" disabled={mutation.isPending}>
            {mutation.isPending ? 'Saving…' : ACTION_TITLES[action].split(' ')[0]}
          </button>
        </>
      }
    >
      <form id="action-form" onSubmit={submit} className="space-y-3">
        <p className="text-sm text-muted">{o.name}</p>
        {(action === 'approve' || action === 'extend') && (
          <div>
            <label className="label" htmlFor="exp">Licence valid until {action === 'approve' && '(empty = no expiry)'}</label>
            <input id="exp" type="date" className="input" value={f.expiresOn} onChange={set('expiresOn')} required={action === 'extend'} />
          </div>
        )}
        {(action === 'approve' || action === 'limits') && (
          <>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="label" htmlFor="mu">Max units (empty = unlimited)</label>
                <input id="mu" type="number" min={1} className="input" value={f.maxUnits} onChange={set('maxUnits')} />
              </div>
              <div>
                <label className="label" htmlFor="md">Max admin devices</label>
                <input id="md" type="number" min={1} className="input" value={f.maxDevices} onChange={set('maxDevices')} />
              </div>
            </div>
            <div>
              <label className="label" htmlFor="plan">Plan</label>
              <input id="plan" className="input" value={f.plan} onChange={set('plan')} placeholder="e.g. Basic" />
            </div>
          </>
        )}
        {(action === 'reject' || action === 'suspend') ? (
          <div>
            <label className="label" htmlFor="reason">Reason (the landlord sees this)</label>
            <textarea id="reason" className="input" rows={3} value={f.reason} onChange={set('reason')} required />
          </div>
        ) : (
          <div>
            <label className="label" htmlFor="note">Note (optional)</label>
            <textarea id="note" className="input" rows={2} value={f.note} onChange={set('note')} />
          </div>
        )}
        {mutation.error && <ErrorBox error={mutation.error} />}
      </form>
    </Modal>
  )
}

function Overview({ org }: { org: OrgDetail }) {
  const o = org.organization
  const summary = useConsoleSummary(o.id)
  const device = useVendorAction()

  return (
    <div className="space-y-5">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Kpi label="Licence" value={<StatusBadge value={o.state} />} hint={o.plan ?? 'No plan'} />
        <Kpi
          label="Valid until"
          value={<span className="text-lg">{o.expiresOn ? fmtDate(o.expiresOn) : 'No expiry'}</span>}
          hint={o.daysLeft !== null ? (o.daysLeft < 0 ? `${-o.daysLeft} days ago` : `${o.daysLeft} days left`) : undefined}
          tone={o.daysLeft !== null && o.daysLeft <= 30 ? (o.daysLeft < 0 ? 'bad' : 'warn') : undefined}
        />
        <Kpi label="Units used" value={o.maxUnits ? `${o.unitsUsed} / ${o.maxUnits}` : o.unitsUsed} />
        <Kpi label="Admin devices" value={o.maxDevices ? `${o.devicesUsed} / ${o.maxDevices}` : o.devicesUsed}
          hint={o.pendingDevices ? `${o.pendingDevices} waiting for approval` : undefined} tone={o.pendingDevices ? 'warn' : undefined} />
      </div>

      <div className="grid gap-5 xl:grid-cols-2">
        <section className="card overflow-hidden">
          <h2 className="border-b border-line px-4 py-2.5 text-sm font-semibold">Records</h2>
          {summary.error ? <div className="p-3"><ErrorBox error={summary.error} /></div> : !summary.data ? <Spinner /> : (
            <div className="grid grid-cols-2 sm:grid-cols-3">
              {summary.data.tables.filter((t) => !['change_log', 'audit_logs', 'license_events', 'code_counters', 'organizations'].includes(t.name)).map((t) => (
                <Link key={t.name} to={`/tables/${t.name}?org=${o.id}`} className="border-b border-r border-line px-4 py-2.5 hover:bg-surface-2">
                  <div className="truncate text-xs text-muted">{tableTitle(t.name)}</div>
                  <div className={`tabular text-lg font-semibold ${t.rows === 0 ? 'text-muted' : ''}`}>{t.rows.toLocaleString()}</div>
                </Link>
              ))}
            </div>
          )}
        </section>

        <div className="space-y-5">
          <section className="card p-4">
            <h2 className="mb-3 text-sm font-semibold">Row changes, last 30 days</h2>
            {summary.data ? <ActivityChart days={summary.data.activityByDay} /> : <Spinner />}
          </section>
          <section className="card overflow-hidden">
            <h2 className="border-b border-line px-4 py-2.5 text-sm font-semibold">Contact</h2>
            <dl className="grid grid-cols-[8rem_1fr] gap-x-3 gap-y-1.5 px-4 py-3 text-sm">
              <dt className="text-muted">Contact</dt><dd>{o.contactName ?? '—'}</dd>
              <dt className="text-muted">Phone</dt><dd>{o.phone ?? '—'}</dd>
              <dt className="text-muted">Email</dt><dd className="break-all">{o.email ?? '—'}</dd>
              <dt className="text-muted">Address</dt><dd>{org.address ?? '—'}</dd>
              <dt className="text-muted">Registered</dt><dd>{fmtDateTime(o.registeredAt)}</dd>
              <dt className="text-muted">Approved</dt><dd>{o.approvedAt ? fmtDateTime(o.approvedAt) : '—'}</dd>
              {org.licenseNote && (<><dt className="text-muted">Licence note</dt><dd>{org.licenseNote}</dd></>)}
            </dl>
          </section>
        </div>
      </div>

      <div className="grid gap-5 xl:grid-cols-2">
        <section className="card overflow-hidden">
          <h2 className="border-b border-line px-4 py-2.5 text-sm font-semibold">Administrators</h2>
          <ul className="divide-y divide-line">
            {org.admins.map((a) => (
              <li key={a.id} className="flex items-center justify-between gap-3 px-4 py-2 text-sm">
                <div className="min-w-0">
                  <Link to={`/tables/users/${a.id}`} className="font-medium hover:text-accent">{a.name}</Link>
                  <div className="truncate text-xs text-muted">{[a.username, a.email, a.mobile].filter(Boolean).join(' · ')}</div>
                </div>
                <StatusBadge value={a.status} />
              </li>
            ))}
          </ul>
        </section>

        <section className="card overflow-hidden">
          <h2 className="border-b border-line px-4 py-2.5 text-sm font-semibold">Devices</h2>
          {device.error && <div className="p-3"><ErrorBox error={device.error} /></div>}
          {org.devices.length === 0 ? <div className="px-4 py-4 text-sm text-muted">No devices yet.</div> : (
            <ul className="divide-y divide-line">
              {org.devices.map((d) => (
                <li key={d.id} className="flex flex-wrap items-center justify-between gap-2 px-4 py-2 text-sm">
                  <div className="min-w-0">
                    <div className="font-medium">{d.name ?? 'Unnamed device'} <span className="text-xs font-normal text-muted">{d.app.toLowerCase()} app · {d.platform ?? '?'}</span></div>
                    <div className="text-xs text-muted">Last seen {d.lastSeenAt ? fmtDateTime(d.lastSeenAt) : 'never'}</div>
                  </div>
                  <div className="flex items-center gap-2">
                    <StatusBadge value={d.status} />
                    {d.status !== 'APPROVED' && (
                      <button className="btn px-2 py-0.5 text-xs" disabled={device.isPending} onClick={() => device.mutate({ path: `/devices/${d.id}/approve` })}>Approve</button>
                    )}
                    {d.status !== 'BLOCKED' && (
                      <button className="btn px-2 py-0.5 text-xs text-bad" disabled={device.isPending}
                        onClick={() => confirm(`Block “${d.name ?? 'this device'}”? It is signed out immediately.`) && device.mutate({ path: `/devices/${d.id}/block` })}>
                        Block
                      </button>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>

      <section className="card overflow-hidden">
        <h2 className="border-b border-line px-4 py-2.5 text-sm font-semibold">Licence history</h2>
        <ul className="divide-y divide-line">
          {org.history.map((h, i) => (
            <li key={i} className="flex flex-wrap justify-between gap-2 px-4 py-2 text-sm">
              <div>
                <span className="font-medium">{h.action}</span>
                {h.details && <span className="text-muted"> — {h.details}</span>}
                {(h.expiresBefore || h.expiresAfter) && (
                  <div className="text-xs text-muted">Expiry {h.expiresBefore ? fmtDate(h.expiresBefore) : 'none'} → {h.expiresAfter ? fmtDate(h.expiresAfter) : 'none'}</div>
                )}
              </div>
              <div className="text-right text-xs text-muted">{h.actorName}<br />{fmtDateTime(h.at)}</div>
            </li>
          ))}
        </ul>
      </section>
    </div>
  )
}

function Data({ orgId }: { orgId: string }) {
  const { data: tables } = useTables()
  const scoped = tables?.filter((t) => t.orgScoped && t.name !== 'organizations') ?? []
  const [table, setTable] = useState('properties')
  const [state, setState] = useState<TableState>({ page: 0, size: 50 })
  const info = scoped.find((t) => t.name === table)
  if (!tables) return <Spinner />
  return (
    <div className="space-y-3">
      <select className="input w-full sm:w-64" value={table} aria-label="Table"
        onChange={(e) => { setTable(e.target.value); setState({ page: 0, size: 50 }) }}>
        {scoped.map((t) => <option key={t.name} value={t.name}>{tableTitle(t.name)}</option>)}
      </select>
      {info && <TableView key={table} info={info} state={state} fixedOrg={orgId} onChange={(n) => setState({ ...state, ...n })} />}
    </div>
  )
}

type Tab = 'overview' | 'users' | 'data' | 'activity'

export function OrganizationPage() {
  const { id = '' } = useParams()
  const [sp, setSp] = useSearchParams()
  const tab = (sp.get('tab') as Tab) ?? 'overview'
  const { data, error, isLoading } = useOrgDetail(id)
  const [action, setAction] = useState<Action | null>(null)

  if (error) return <ErrorBox error={error} />
  if (isLoading || !data) return <Spinner />
  const o = data.organization

  return (
    <>
      <div className="mb-2 text-sm text-muted"><Link to="/orgs" className="hover:text-ink">Organisations</Link> /</div>
      <PageHeader
        title={<span className="flex items-center gap-2">{o.name} <StatusBadge value={o.state} /></span>}
        subtitle={<>Registered {fmtDate(o.registeredAt)} · <Link to={`/tables/organizations/${o.id}`} className="link">raw record</Link></>}
        actions={actionsFor(o.state).map((a) => (
          <button key={a} className={`btn ${a === 'approve' || a === 'reactivate' ? 'btn-primary' : ''} ${a === 'suspend' || a === 'reject' ? 'text-bad' : ''}`} onClick={() => setAction(a)}>
            {ACTION_TITLES[a].replace(' organisation', '').replace(' registration', '')}
          </button>
        ))}
      />
      <Tabs<Tab>
        value={tab}
        onChange={(t) => setSp(t === 'overview' ? {} : { tab: t }, { replace: true })}
        tabs={[{ value: 'overview', label: 'Overview' }, { value: 'users', label: 'Users' }, { value: 'data', label: 'Data' }, { value: 'activity', label: 'Activity' }]}
      />
      {tab === 'overview' && <Overview org={data} />}
      {tab === 'users' && <UsersList fixedOrg={o.id} />}
      {tab === 'data' && <Data orgId={o.id} />}
      {tab === 'activity' && <ActivityFeed fixedOrg={o.id} />}
      {action && <ActionDialog action={action} org={data} onClose={() => setAction(null)} />}
    </>
  )
}
