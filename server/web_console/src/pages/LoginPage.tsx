import { useState, type FormEvent } from 'react'
import { changePassword, currentUser, login } from '../api/client'
import { ErrorBox } from '../components/ui'

function Shell({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="grid min-h-screen place-items-center px-4">
      <div className="w-full max-w-sm">
        <div className="mb-6 flex items-center gap-2">
          <span className="grid size-9 place-items-center rounded-lg bg-accent text-sm font-bold text-accent-ink">TMS</span>
          <div>
            <div className="font-semibold">Data Console</div>
            <div className="text-xs text-muted">Every organisation, every table</div>
          </div>
        </div>
        <div className="card p-5">
          <h1 className="mb-4 text-lg font-semibold">{title}</h1>
          {children}
        </div>
      </div>
    </div>
  )
}

export function LoginPage() {
  const [identifier, setIdentifier] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await login(identifier.trim(), password)
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Shell title="Vendor sign in">
      <form onSubmit={submit} className="space-y-3">
        <div>
          <label className="label" htmlFor="identifier">Username, email or mobile</label>
          <input id="identifier" className="input" autoComplete="username" value={identifier} onChange={(e) => setIdentifier(e.target.value)} required autoFocus />
        </div>
        <div>
          <label className="label" htmlFor="password">Password</label>
          <input id="password" type="password" className="input" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </div>
        {error !== null && <ErrorBox error={error} />}
        <button className="btn btn-primary w-full" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
      </form>
    </Shell>
  )
}

/** Shown when the vendor account still has its initial password. */
export function ChangePasswordPage() {
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [repeat, setRepeat] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (next !== repeat) {
      setError(new Error('The new passwords do not match.'))
      return
    }
    setBusy(true)
    setError(null)
    try {
      await changePassword(current, next)
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Shell title="Choose a new password">
      <p className="mb-4 text-sm text-muted">
        {currentUser()?.name}, your account still uses its initial password. Choose a new one (at least 8 characters, with letters and numbers).
      </p>
      <form onSubmit={submit} className="space-y-3">
        <div>
          <label className="label" htmlFor="cur">Current password</label>
          <input id="cur" type="password" className="input" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} required />
        </div>
        <div>
          <label className="label" htmlFor="new">New password</label>
          <input id="new" type="password" className="input" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} required minLength={8} />
        </div>
        <div>
          <label className="label" htmlFor="rep">Repeat new password</label>
          <input id="rep" type="password" className="input" autoComplete="new-password" value={repeat} onChange={(e) => setRepeat(e.target.value)} required />
        </div>
        {error !== null && <ErrorBox error={error} />}
        <button className="btn btn-primary w-full" disabled={busy}>{busy ? 'Saving…' : 'Save and continue'}</button>
      </form>
    </Shell>
  )
}
