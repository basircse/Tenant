import { useState } from 'react'
import type { ConsoleSummary } from '../api/types'
import { fmtDate } from '../format'

type Day = ConsoleSummary['activityByDay'][number]

const H = 140

/** Row changes per day as one bar series; hover shows the created / updated / deleted split. */
export function ActivityChart({ days }: { days: Day[] }) {
  const [hover, setHover] = useState<number | null>(null)
  const totals = days.map((d) => d.inserts + d.updates + d.deletes)
  const max = Math.max(1, ...totals)
  // Round the axis top to a friendly number.
  const step = 10 ** Math.floor(Math.log10(max))
  const top = Math.ceil(max / step) * step
  const n = days.length
  const w = 100 / n

  const d = hover !== null ? days[hover] : null
  return (
    <div className="relative">
      <div className="flex gap-2">
        <div className="tabular flex h-[140px] flex-col justify-between py-0 text-right text-[11px] text-muted" aria-hidden>
          <span>{top}</span>
          <span>{top / 2}</span>
          <span>0</span>
        </div>
        <div className="relative flex-1" onMouseLeave={() => setHover(null)}>
          <svg viewBox={`0 0 100 ${H}`} preserveAspectRatio="none" className="block h-[140px] w-full overflow-visible" role="img"
            aria-label={`Row changes per day over the last ${n} days, peak ${max}`}>
            {[0, 0.5, 1].map((f) => (
              <line key={f} x1="0" x2="100" y1={H * f} y2={H * f} stroke="var(--line)" strokeWidth="1" vectorEffect="non-scaling-stroke" />
            ))}
            {totals.map((t, i) => {
              const h = (t / top) * H
              return (
                <g key={days[i].day}>
                  {t > 0 && (
                    <rect
                      x={i * w + w * 0.15}
                      width={w * 0.7}
                      y={H - h}
                      height={h}
                      rx={0.6}
                      fill="var(--chart-1)"
                      opacity={hover === null || hover === i ? 1 : 0.45}
                    />
                  )}
                  {/* Hit target: the full column, larger than the bar. */}
                  <rect x={i * w} width={w} y={0} height={H} fill="transparent" onMouseEnter={() => setHover(i)} />
                </g>
              )
            })}
          </svg>
          <div className="mt-1 flex justify-between text-[11px] text-muted" aria-hidden>
            <span>{fmtDate(days[0]?.day)}</span>
            <span>Today</span>
          </div>
          {d && hover !== null && (
            <div
              className="pointer-events-none absolute top-0 z-10 w-40 -translate-x-1/2 rounded-md border border-line bg-surface px-2.5 py-2 text-xs shadow-lg"
              style={{ left: `clamp(5rem, ${(hover + 0.5) * w}%, calc(100% - 5rem))` }}
            >
              <div className="mb-1 font-medium">{fmtDate(d.day)}</div>
              <div className="tabular flex justify-between"><span className="text-muted">Created</span>{d.inserts}</div>
              <div className="tabular flex justify-between"><span className="text-muted">Updated</span>{d.updates}</div>
              <div className="tabular flex justify-between"><span className="text-muted">Deleted</span>{d.deletes}</div>
              <div className="tabular mt-1 flex justify-between border-t border-line pt-1 font-medium"><span>Total</span>{totals[hover]}</div>
            </div>
          )}
        </div>
      </div>
      {/* Table view for screen readers. */}
      <table className="sr-only">
        <caption>Row changes per day</caption>
        <thead><tr><th>Day</th><th>Created</th><th>Updated</th><th>Deleted</th></tr></thead>
        <tbody>
          {days.map((x) => (
            <tr key={x.day}><td>{x.day}</td><td>{x.inserts}</td><td>{x.updates}</td><td>{x.deletes}</td></tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
