import { StrictMode, useSyncExternalStore } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { ApiError, currentUser, onSessionChange } from './api/client'
import { Layout } from './components/Layout'
import { ActivityPage } from './pages/ActivityPage'
import { DashboardPage } from './pages/DashboardPage'
import { ChangePasswordPage, LoginPage } from './pages/LoginPage'
import { OrganizationPage } from './pages/OrganizationPage'
import { OrganizationsPage } from './pages/OrganizationsPage'
import { RecordPage } from './pages/RecordPage'
import { TablePage } from './pages/TablePage'
import { UsersPage } from './pages/UsersPage'
import './index.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      refetchOnWindowFocus: true,
      retry: (count, error) => !(error instanceof ApiError && error.status < 500) && count < 2,
    },
  },
})

// Another account must never see the previous one's cached data.
onSessionChange(() => {
  if (!currentUser()) queryClient.clear()
})

function App() {
  const user = useSyncExternalStore(onSessionChange, currentUser)
  if (!user) return <LoginPage />
  if (user.mustChangePassword) return <ChangePasswordPage />
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<DashboardPage />} />
        <Route path="orgs" element={<OrganizationsPage />} />
        <Route path="orgs/:id" element={<OrganizationPage />} />
        <Route path="activity" element={<ActivityPage />} />
        <Route path="users" element={<UsersPage />} />
        <Route path="tables/:table" element={<TablePage />} />
        <Route path="tables/:table/:id" element={<RecordPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      {/* The production build is served under /console/ (vite.config.ts base). */}
      <BrowserRouter basename={import.meta.env.BASE_URL}>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
