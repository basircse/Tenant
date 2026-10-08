import { ActivityFeed } from '../components/ActivityFeed'
import { PageHeader } from '../components/ui'

export function ActivityPage() {
  return (
    <>
      <PageHeader
        title="Activity"
        subtitle="Every create, update and delete in every table, plus audit and licence events, newest first."
      />
      <ActivityFeed />
    </>
  )
}
