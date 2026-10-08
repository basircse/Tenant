import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useBlockUser, useUsers } from '../api/hooks'
import type { VendorUser } from '../api/types'
import { fmtDateTime } from '../format'
import { OrgPicker } from './OrgPicker'
import { Empty, ErrorBox, Modal, Spinner, StatusBadge } from './ui'

/** Block (with a reason the user sees) or unblock one user. */
export function BlockButton({ id, name, blocked, small = false }: {
  id: string
  name: string
  blocked: boolean
  small?: boolean
}) {
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  const mutation = useBlockUser()
  const size = small ? 'px-2 py-0.5 text-xs' : ''

  if (blocked) {
    return (
      <>
        <button
          className={`btn ${size}`}
          disabled={mutation.isPending}
          onClick={() => confirm(`Unblock ${name}? They can sign in again.`) && mutation.mutate({ id })}
        >
          Unblock
        </button>
        {mutation.error && !open && <span className="text-xs text-bad">{(mutation.error as Error).message}</span>}
      </>
    )
  }
  return (
    <>
      <button className={`btn text-bad ${size}`} onClick={() => setOpen(true)}>Block</button>
      {open && (
        <Modal
          title={`Block ${name}`}
          onClose={() => setOpen(false)}
          footer={
            <>
              <button className="btn" type="button" onClick={() => setOpen(false)}>Cancel</button>
              <button className="btn btn-danger" form="block-form" disabled={mutation.isPending || !reason.trim()}>
                {mutation.isPending ? 'Blocking…' : 'Block and sign out'}
              </button>
            </>
          }
        >
          <form
            id="block-form"
            className="space-y-3"
            onSubmit={(e) => {
              e.preventDefault()
              mutation.mutate({ id, reason: reason.trim() }, { onSuccess: () => setOpen(false) })
            }}
          >
            <p className="text-sm text-muted">
              They are signed out of every device at once and cannot sign in until you unblock them. Their
              organisation's admins cannot lift this block.
            </p>
            <div>
              <label className="label" htmlFor="block-reason">Reason (shown to them when they try to sign in)</label>
              <textarea id="block-reason" className="input" rows={3} maxLength={500} value={reason}
                onChange={(e) => setReason(e.target.value)} required autoFocus />
            </div>
            {mutation.error && <ErrorBox error={mutation.error} />}
          </form>
        </Modal>
      )}
    </>
  )
}

/** "Blocked by Owner on 7 Oct: reason" */
export function BlockedNote({ by, byVendor, at, reason }: {
  by: string | null
  byVendor: boolean
  at: string | null
  reason: string | null
}) {
  return (
    <div className="text-xs text-bad">
      Blocked by {byVendor ? 'you (service provider)' : by ?? 'an admin'}
      {at && ` on ${fmtDateTime(at)}`}
      {reason && `: ${reason}`}
    </div>
  )
}

function Row({ v, showOrg }: { v: VendorUser; showOrg: boolean }) {
  const u = v.user
  const blocked = u.status === 'BLOCKED'
  return (
    <tr className="border-b border-line last:border-0 align-top">
      <td className="px-3 py-2">
        <Link to={`/tables/users/${u.id}`} className="font-medium hover:text-accent">{u.name}</Link>
        <div className="text-xs text-muted">{[u.username, u.email, u.mobile].filter(Boolean).join(' · ')}</div>
        {blocked && <BlockedNote by={u.blockedBy} byVendor={u.blockedByVendor} at={u.blockedAt} reason={u.blockedReason} />}
      </td>
      {showOrg && (
        <td className="px-3 py-2">
          <Link to={`/orgs/${v.orgId}`} className="hover:text-accent">{v.orgName ?? '—'}</Link>
        </td>
      )}
      <td className="px-3 py-2"><StatusBadge value={u.role} /></td>
      <td className="px-3 py-2"><StatusBadge value={u.status} /></td>
      <td className="px-3 py-2 text-right">
        {u.role !== 'VENDOR' && <BlockButton id={u.id} name={u.name} blocked={blocked} small />}
      </td>
    </tr>
  )
}

const STATUSES = ['ACTIVE', 'BLOCKED', 'INACTIVE', 'LOCKED']

/** Users with search, status filter and block / unblock; {@code fixedOrg} limits to one organisation. */
export function UsersList({ fixedOrg }: { fixedOrg?: string }) {
  const [org, setOrg] = useState<string | undefined>()
  const [q, setQ] = useState('')
  const [status, setStatus] = useState<string | undefined>()
  const { data, error, isLoading, isFetching } = useUsers({ org: fixedOrg ?? org, q: q.trim() || undefined, status })
  const showOrg = !fixedOrg && !org

  return (
    <div className="card overflow-hidden">
      <div className="flex flex-wrap items-end gap-2 border-b border-line p-3">
        <div className="min-w-48 flex-1">
          <label className="label" htmlFor="user-q">Search</label>
          <input id="user-q" className="input" placeholder="Name, username, email, mobile" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        {!fixedOrg && (
          <div className="w-full sm:w-56">
            <label className="label">Organisation</label>
            <OrgPicker value={org} onChange={setOrg} />
          </div>
        )}
        <div className="flex flex-wrap gap-1.5 pb-1">
          <button onClick={() => setStatus(undefined)} aria-pressed={!status}
            className={`rounded-full px-2.5 py-0.5 text-xs font-medium ring-1 ${!status ? 'bg-accent-soft text-accent ring-accent/40' : 'ring-line hover:ring-accent/40'}`}>
            All
          </button>
          {STATUSES.map((s) => (
            <button key={s} onClick={() => setStatus(s)} aria-pressed={status === s}
              className={`rounded-full px-2.5 py-0.5 text-xs font-medium ring-1 ${status === s ? 'bg-accent-soft text-accent ring-accent/40' : 'ring-line hover:ring-accent/40'}`}>
              {s.charAt(0) + s.slice(1).toLowerCase()}
            </button>
          ))}
        </div>
      </div>
      {error ? <div className="p-3"><ErrorBox error={error} /></div> : isLoading ? <Spinner /> : !data?.length ? <Empty>No users match.</Empty> : (
        <div className={`overflow-x-auto ${isFetching ? 'opacity-60' : ''}`}>
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line bg-surface-2 text-left text-xs text-muted">
                <th className="px-3 py-2 font-semibold">User</th>
                {showOrg && <th className="px-3 py-2 font-semibold">Organisation</th>}
                <th className="px-3 py-2 font-semibold">Role</th>
                <th className="px-3 py-2 font-semibold">Status</th>
                <th className="px-3 py-2"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {data.map((v) => <Row key={v.user.id} v={v} showOrg={showOrg} />)}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
