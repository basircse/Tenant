import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, apiPost, qs } from './client'
import type {
  ActivityPage,
  ConsoleSummary,
  OrgDetail,
  OrgSummary,
  RecordView,
  RowPage,
  TableInfo,
  VendorSummary,
  VendorUser,
} from './types'

export const useTables = () =>
  useQuery({ queryKey: ['tables'], queryFn: () => api<TableInfo[]>('/api/console/tables'), staleTime: 30_000 })

export const useOrgs = (state?: string, q?: string) =>
  useQuery({
    queryKey: ['orgs', state ?? '', q ?? ''],
    queryFn: () => api<OrgSummary[]>(`/api/vendor/organizations${qs({ state, q })}`),
    placeholderData: keepPreviousData,
  })

export const useVendorSummary = () =>
  useQuery({ queryKey: ['vendor-summary'], queryFn: () => api<VendorSummary>('/api/vendor/summary') })

export const useConsoleSummary = (org?: string, days = 30) =>
  useQuery({
    queryKey: ['console-summary', org ?? '', days],
    queryFn: () => api<ConsoleSummary>(`/api/console/summary${qs({ org, days })}`),
  })

export interface RowParams {
  org?: string
  q?: string
  filter?: string[]
  from?: string
  to?: string
  sort?: string
  desc?: boolean
  page?: number
  size?: number
}

export const rowsQuery = (table: string, p: RowParams) =>
  `/api/console/tables/${table}/rows${qs({ ...p, filter: p.filter, desc: p.desc || undefined })}`

export const useRows = (table: string, p: RowParams) =>
  useQuery({
    queryKey: ['rows', table, p],
    queryFn: () => api<RowPage>(rowsQuery(table, p)),
    placeholderData: keepPreviousData,
  })

export const useRecord = (table: string, id: string) =>
  useQuery({
    queryKey: ['record', table, id],
    queryFn: () => api<RecordView>(`/api/console/tables/${table}/rows/${encodeURIComponent(id)}`),
  })

export const useOrgDetail = (id: string) =>
  useQuery({ queryKey: ['org', id], queryFn: () => api<OrgDetail>(`/api/vendor/organizations/${id}`) })

export interface ActivityParams {
  org?: string
  source?: string[]
  table?: string
  rowId?: string
  actor?: string
  op?: string
  from?: string
  to?: string
}

export const useActivity = (p: ActivityParams, limit = 50) =>
  useInfiniteQuery({
    queryKey: ['activity', p, limit],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => api<ActivityPage>(`/api/console/activity${qs({ ...p, before: pageParam, limit })}`),
    getNextPageParam: (last) => last.nextBefore ?? undefined,
  })

/** A licence or device action on /api/vendor; refreshes everything that shows organisations. */
export function useVendorAction() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ path, body, method }: { path: string; body?: unknown; method?: string }) =>
      apiPost<OrgDetail>(`/api/vendor${path}`, body, method),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['org'] })
      void client.invalidateQueries({ queryKey: ['orgs'] })
      void client.invalidateQueries({ queryKey: ['vendor-summary'] })
      void client.invalidateQueries({ queryKey: ['activity'] })
      void client.invalidateQueries({ queryKey: ['rows'] })
    },
  })
}

export const useUsers = (p: { org?: string; q?: string; status?: string }) =>
  useQuery({
    queryKey: ['users', p],
    queryFn: () => api<VendorUser[]>(`/api/vendor/users${qs(p)}`),
    placeholderData: keepPreviousData,
  })

/** Blocks (with a reason) or unblocks any user; refreshes everything that shows users or activity. */
export function useBlockUser() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason?: string }) =>
      apiPost<VendorUser>(`/api/vendor/users/${id}/${reason === undefined ? 'unblock' : 'block'}`,
        reason === undefined ? undefined : { reason }),
    onSuccess: () => {
      for (const key of ['users', 'record', 'rows', 'activity', 'org']) void client.invalidateQueries({ queryKey: [key] })
    },
  })
}
