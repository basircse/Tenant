import { Link } from 'react-router-dom'
import { useActivity, useConsoleSummary, useVendorSummary } from '../api/hooks'
import { ActivityChart } from '../components/ActivityChart'
import { ActivityList } from '../components/ActivityList'
import { ErrorBox, Kpi, PageHeader, Spinner } from '../components/ui'
import { tableTitle } from '../format'

const HEADLINE_TABLES = ['organizations', 'properties', 'units', 'tenants', 'agreements', 'rent_invoices', 'payments', 'maintenance_requests']

export function DashboardPage() {
  const vendor = useVendorSummary()
  const summary = useConsoleSummary()
  const recent = useActivity({}, 12)

  const v = vendor.data
  const counts = new Map(summary.data?.tables.map((t) => [t.name, t.rows]))

  return (
    <>
      <PageHeader title="Dashboard" subtitle="All organisations at a glance." />

      {vendor.error ? <ErrorBox error={vendor.error} /> : (
        <div className="mb-5 grid grid-cols-2 gap-3 md:grid-cols-4 xl:grid-cols-6">
          <Link to="/orgs?state=ACTIVE"><Kpi label="Active organisations" value={v?.active ?? '–'} tone="ok" /></Link>
          <Link to="/orgs?state=PENDING"><Kpi label="Waiting for approval" value={v?.pending ?? '–'} tone={v?.pending ? 'warn' : undefined} /></Link>
          <Kpi label="Expiring in 30 days" value={v?.expiringIn30Days ?? '–'} tone={v?.expiringIn30Days ? 'warn' : undefined} />
          <Link to="/orgs?state=GRACE"><Kpi label="In grace period" value={v?.inGrace ?? '–'} tone={v?.inGrace ? 'warn' : undefined} /></Link>
          <Link to="/orgs?state=EXPIRED"><Kpi label="Expired / suspended" value={v ? v.expired + v.suspended : '–'} tone={v && v.expired + v.suspended ? 'bad' : undefined} /></Link>
          <Kpi label="Devices to approve" value={v?.pendingDevices ?? '–'} tone={v?.pendingDevices ? 'warn' : undefined} />
        </div>
      )}

      <div className="grid gap-5 xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <div className="space-y-5">
          <section className="card p-4">
            <div className="mb-3 flex items-baseline justify-between">
              <h2 className="text-sm font-semibold">Row changes, last 30 days</h2>
              <span className="text-xs text-muted">{summary.data ? `${summary.data.changesToday} today` : ''}</span>
            </div>
            {summary.error ? <ErrorBox error={summary.error} /> : summary.data ? <ActivityChart days={summary.data.activityByDay} /> : <Spinner />}
          </section>

          <section className="card overflow-hidden">
            <h2 className="border-b border-line px-4 py-2.5 text-sm font-semibold">Records</h2>
            <div className="grid grid-cols-2 sm:grid-cols-4">
              {HEADLINE_TABLES.map((t) => (
                <Link key={t} to={`/tables/${t}`} className="border-b border-r border-line px-4 py-3 hover:bg-surface-2">
                  <div className="text-xs text-muted">{tableTitle(t)}</div>
                  <div className="tabular mt-0.5 text-xl font-semibold">{counts.get(t)?.toLocaleString() ?? '–'}</div>
                </Link>
              ))}
            </div>
          </section>
        </div>

        <section className="card overflow-hidden">
          <div className="flex items-center justify-between border-b border-line px-4 py-2.5">
            <h2 className="text-sm font-semibold">Latest activity</h2>
            <Link to="/activity" className="link text-sm">View all</Link>
          </div>
          {recent.error ? <div className="p-3"><ErrorBox error={recent.error} /></div>
            : recent.isLoading ? <Spinner />
            : <ActivityList items={recent.data?.pages[0]?.items ?? []} />}
        </section>
      </div>
    </>
  )
}
