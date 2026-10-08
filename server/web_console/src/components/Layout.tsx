import { useEffect, useState } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { currentUser, logout } from '../api/client'
import { useTables } from '../api/hooks'
import { tableTitle } from '../format'

type Theme = 'system' | 'light' | 'dark'

function useTheme(): [Theme, (t: Theme) => void] {
  const [theme, setTheme] = useState<Theme>(() => {
    try {
      return (localStorage.getItem('tms.console.theme') as Theme) || 'system'
    } catch {
      return 'system'
    }
  })
  useEffect(() => {
    if (theme === 'system') document.documentElement.removeAttribute('data-theme')
    else document.documentElement.setAttribute('data-theme', theme)
    try {
      localStorage.setItem('tms.console.theme', theme)
    } catch {
      // ignore
    }
  }, [theme])
  return [theme, setTheme]
}

const navClass = ({ isActive }: { isActive: boolean }) =>
  `flex items-center justify-between rounded-md px-2.5 py-1.5 text-sm transition-colors ${
    isActive ? 'bg-accent-soft font-medium text-accent' : 'text-ink hover:bg-surface-2'
  }`

function Sidebar({ onNavigate }: { onNavigate: () => void }) {
  const { data: tables } = useTables()
  const groups = new Map<string, NonNullable<typeof tables>>()
  tables?.forEach((t) => groups.set(t.group, [...(groups.get(t.group) ?? []), t]))

  return (
    <nav className="flex h-full flex-col gap-4 overflow-y-auto px-3 py-4" onClick={(e) => (e.target as HTMLElement).closest('a') && onNavigate()}>
      <div className="space-y-0.5">
        <NavLink to="/" end className={navClass}>Dashboard</NavLink>
        <NavLink to="/orgs" className={navClass}>Organisations</NavLink>
        <NavLink to="/users" className={navClass}>Users</NavLink>
        <NavLink to="/activity" className={navClass}>Activity</NavLink>
      </div>
      {[...groups.entries()].map(([group, items]) => (
        <div key={group}>
          <div className="mb-1 px-2.5 text-[11px] font-semibold uppercase tracking-wider text-muted">{group}</div>
          <div className="space-y-0.5">
            {items.map((t) => (
              <NavLink key={t.name} to={`/tables/${t.name}`} className={navClass}>
                <span className="truncate">{tableTitle(t.name)}</span>
                <span className="tabular text-xs text-muted">{t.rows.toLocaleString()}</span>
              </NavLink>
            ))}
          </div>
        </div>
      ))}
    </nav>
  )
}

export function Layout() {
  const [theme, setTheme] = useTheme()
  const [menuOpen, setMenuOpen] = useState(false)
  const user = currentUser()
  const location = useLocation()
  useEffect(() => setMenuOpen(false), [location.pathname])

  return (
    <div className="min-h-screen">
      <header className="sticky top-0 z-30 flex h-13 items-center gap-3 border-b border-line bg-surface px-4">
        <button className="btn px-2 py-1 lg:hidden" onClick={() => setMenuOpen((o) => !o)} aria-label="Menu" aria-expanded={menuOpen}>
          ☰
        </button>
        <div className="flex items-center gap-2 font-semibold">
          <span className="grid size-7 place-items-center rounded-md bg-accent text-xs font-bold text-accent-ink">TMS</span>
          <span className="hidden sm:inline">Data Console</span>
        </div>
        <div className="ml-auto flex items-center gap-2">
          <select
            className="input w-auto py-1"
            value={theme}
            onChange={(e) => setTheme(e.target.value as Theme)}
            aria-label="Theme"
          >
            <option value="system">Auto</option>
            <option value="light">Light</option>
            <option value="dark">Dark</option>
          </select>
          <span className="hidden text-sm text-muted md:inline">{user?.name}</span>
          <button className="btn py-1" onClick={() => void logout()}>Sign out</button>
        </div>
      </header>
      <div className="flex">
        <aside className="sticky top-13 hidden h-[calc(100vh-3.25rem)] w-60 shrink-0 border-r border-line bg-surface lg:block">
          <Sidebar onNavigate={() => undefined} />
        </aside>
        {menuOpen && (
          <div className="fixed inset-0 top-13 z-20 bg-black/30 lg:hidden" onClick={() => setMenuOpen(false)}>
            <aside className="h-full w-72 max-w-[85vw] border-r border-line bg-surface" onClick={(e) => e.stopPropagation()}>
              <Sidebar onNavigate={() => setMenuOpen(false)} />
            </aside>
          </div>
        )}
        <main className="min-w-0 flex-1 px-4 py-6 lg:px-8">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
