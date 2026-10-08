// Talks to the Spring Boot API. Tokens are kept in localStorage so a reload keeps you signed in;
// the short-lived access token is refreshed automatically when the API answers 401.

export interface Me {
  userId: string
  name: string
  username: string
  role: string
  orgName: string
  mustChangePassword: boolean
}

interface Session {
  accessToken: string
  refreshToken: string
  user: Me
}

export class ApiError extends Error {
  status: number
  code?: string
  constructor(status: number, message: string, code?: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

const SESSION_KEY = 'tms.console.session'
const DEVICE_KEY = 'tms.console.device'

function storageGet(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

function storageSet(key: string, value: string | null) {
  try {
    if (value === null) localStorage.removeItem(key)
    else localStorage.setItem(key, value)
  } catch {
    // Private mode: the session lasts until the tab closes.
  }
}

let session: Session | null = (() => {
  const raw = storageGet(SESSION_KEY)
  try {
    return raw ? (JSON.parse(raw) as Session) : null
  } catch {
    return null
  }
})()

const listeners = new Set<() => void>()

function setSession(s: Session | null) {
  session = s
  storageSet(SESSION_KEY, s ? JSON.stringify(s) : null)
  listeners.forEach((l) => l())
}

export function currentUser(): Me | null {
  return session?.user ?? null
}

export function onSessionChange(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function deviceKey(): string {
  let key = storageGet(DEVICE_KEY)
  if (!key) {
    key = crypto.randomUUID()
    storageSet(DEVICE_KEY, key)
  }
  return key
}

async function problem(res: Response): Promise<ApiError> {
  let message = `${res.status} ${res.statusText}`
  let code: string | undefined
  try {
    const body = await res.json()
    message = body.detail ?? body.message ?? message
    code = body.code
    if (body.errors) message += ': ' + Object.entries(body.errors).map(([k, v]) => `${k} ${v}`).join(', ')
  } catch {
    // not JSON
  }
  return new ApiError(res.status, message, code)
}

export async function login(identifier: string, password: string): Promise<Me> {
  const res = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      identifier,
      password,
      deviceKey: deviceKey(),
      deviceName: 'Web console',
      platform: 'web',
    }),
  })
  if (!res.ok) throw await problem(res)
  const body = await res.json()
  if (body.user.role !== 'VENDOR') {
    // Sign the session out again: this console is for the vendor only.
    void fetch('/api/auth/logout', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: body.refreshToken }),
    })
    throw new ApiError(403, 'This console is only for the vendor account.')
  }
  setSession({ accessToken: body.accessToken, refreshToken: body.refreshToken, user: body.user })
  return body.user
}

export async function logout() {
  const s = session
  setSession(null)
  if (s) {
    await fetch('/api/auth/logout', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: s.refreshToken }),
    }).catch(() => undefined)
  }
}

let refreshing: Promise<boolean> | null = null

async function refresh(): Promise<boolean> {
  if (!session) return false
  refreshing ??= (async () => {
    try {
      const res = await fetch('/api/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: session!.refreshToken }),
      })
      if (!res.ok) {
        setSession(null)
        return false
      }
      const body = await res.json()
      setSession({ accessToken: body.accessToken, refreshToken: body.refreshToken, user: body.user })
      return true
    } finally {
      refreshing = null
    }
  })()
  return refreshing
}

async function send(method: string, path: string, body?: unknown, retry = true): Promise<Response> {
  const res = await fetch(path, {
    method,
    headers: {
      ...(session ? { Authorization: `Bearer ${session.accessToken}` } : {}),
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  if (res.status === 401 && retry && (await refresh())) {
    return send(method, path, body, false)
  }
  if (res.status === 401) setSession(null)
  if (!res.ok) throw await problem(res)
  return res
}

export async function api<T>(path: string): Promise<T> {
  return (await send('GET', path)).json()
}

export async function apiPost<T>(path: string, body?: unknown, method = 'POST'): Promise<T> {
  const res = await send(method, path, body ?? {})
  return res.status === 204 ? (undefined as T) : res.json()
}

export async function changePassword(currentPassword: string, newPassword: string) {
  await apiPost('/api/auth/change-password', { currentPassword, newPassword })
  // Changing the password signs out every session; sign in again with the new one.
  const username = session?.user.username
  setSession(null)
  if (username) await login(username, newPassword)
}

/** Downloads a file from the API (with the auth header, so a plain link cannot be used). */
export async function download(path: string, fallbackName: string) {
  const res = await send('GET', path)
  const disposition = res.headers.get('Content-Disposition') ?? ''
  const name = /filename="([^"]+)"/.exec(disposition)?.[1] ?? fallbackName
  const url = URL.createObjectURL(await res.blob())
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

/** Builds a query string, leaving out empty values; arrays become repeated parameters. */
export function qs(params: Record<string, string | number | boolean | string[] | undefined | null>): string {
  const sp = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue
    if (Array.isArray(v)) v.forEach((x) => sp.append(k, x))
    else sp.set(k, String(v))
  }
  const s = sp.toString()
  return s ? `?${s}` : ''
}
