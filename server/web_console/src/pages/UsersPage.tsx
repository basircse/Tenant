import { PageHeader } from '../components/ui'
import { UsersList } from '../components/Users'

export function UsersPage() {
  return (
    <>
      <PageHeader
        title="Users"
        subtitle="Every login in every organisation. Blocking signs the user out at once; only you can lift your blocks."
      />
      <UsersList />
    </>
  )
}
