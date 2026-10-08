import { useOrgs } from '../api/hooks'

/** Choose one organisation, or all of them. */
export function OrgPicker({ value, onChange, className = '' }: {
  value: string | undefined
  onChange: (org: string | undefined) => void
  className?: string
}) {
  const { data } = useOrgs()
  return (
    <select
      className={`input ${className}`}
      value={value ?? ''}
      onChange={(e) => onChange(e.target.value || undefined)}
      aria-label="Organisation"
    >
      <option value="">All organisations</option>
      {data?.map((o) => (
        <option key={o.id} value={o.id}>{o.name}</option>
      ))}
    </select>
  )
}
