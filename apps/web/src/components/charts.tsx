import type { AttendanceTrendPoint, ScoreBand, ScoreTrendPoint } from '../app/types'

type Point = { label: string; value: number; secondary?: number }

export function TrendChart({ points, label, valueSuffix = '', min = 0, max }: { points: Point[]; label: string; valueSuffix?: string; min?: number; max?: number }) {
  if (!points.length) return <div className="chart-empty">Chưa đủ dữ liệu để vẽ xu hướng.</div>
  const width = 720, height = 240, px = 28, py = 24
  const values = points.map(point => Number(point.value) || 0)
  const high = max ?? Math.max(...values, min + 1)
  const low = Math.min(min, ...values)
  const span = Math.max(1, high - low)
  const x = (index: number) => points.length === 1 ? width / 2 : px + index * ((width - px * 2) / (points.length - 1))
  const y = (value: number) => height - py - ((value - low) / span) * (height - py * 2)
  const path = points.map((point, index) => `${index ? 'L' : 'M'} ${x(index)} ${y(Number(point.value) || 0)}`).join(' ')
  const area = `${path} L ${x(points.length - 1)} ${height - py} L ${x(0)} ${height - py} Z`
  const labelIndexes = new Set([0, Math.floor((points.length - 1) / 2), points.length - 1])

  return <figure className="chart" aria-label={label}>
    <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label={label}>
      <line className="chart-grid" x1={px} y1={py} x2={px} y2={height - py} />
      <line className="chart-grid" x1={px} y1={height - py} x2={width - px} y2={height - py} />
      <path className="chart-area" d={area} />
      <path className="chart-line" d={path} />
      {points.map((point, index) => <g key={`${point.label}-${index}`}>
        <circle className="chart-dot" cx={x(index)} cy={y(Number(point.value) || 0)} r="4" />
        {labelIndexes.has(index) && <text className="chart-axis-label" x={x(index)} y={height - 5} textAnchor={index === 0 ? 'start' : index === points.length - 1 ? 'end' : 'middle'}>{shortDate(point.label)}</text>}
      </g>)}
    </svg>
    <figcaption><strong>{formatNumber(points.at(-1)?.value)}{valueSuffix}</strong><span>{label}</span></figcaption>
    <div className="sr-only">{points.map(point => `${point.label}: ${formatNumber(point.value)}${valueSuffix}`).join('; ')}</div>
  </figure>
}

export function AttendanceTrendChart({ points }: { points: AttendanceTrendPoint[] }) {
  return <TrendChart label="Tỷ lệ có mặt theo thời gian" valueSuffix="%" min={0} max={100} points={points.map(point => ({ label: point.date, value: Number(point.attendanceRate) }))} />
}

export function ScoreTrendChart({ points }: { points: ScoreTrendPoint[] }) {
  return <TrendChart label="Điểm trung bình theo thời gian" min={0} max={10} points={points.map(point => ({ label: point.date, value: Number(point.averageScore) }))} />
}

export function DistributionBars({ rows }: { rows: ScoreBand[] }) {
  const high = Math.max(1, ...rows.map(row => Number(row.count) || 0))
  return <div className="distribution" role="img" aria-label="Phân bố điểm theo các khoảng">
    {rows.map(row => <div className="distribution-row" key={row.band}>
      <span className="distribution-label">{row.band}</span>
      <div className="distribution-track"><span className="distribution-fill" style={{ width: `${Math.max(2, (Number(row.count) / high) * 100)}%` }} /></div>
      <strong>{formatNumber(row.count)}</strong>
    </div>)}
  </div>
}

export function MiniBars({ rows, valueKey, labelKey, max = 10, suffix = '' }: { rows: Record<string, unknown>[]; valueKey: string; labelKey: string; max?: number; suffix?: string }) {
  if (!rows.length) return <div className="chart-empty">Chưa có dữ liệu.</div>
  return <div className="mini-bars">
    {rows.map((row, index) => {
      const value = Number(row[valueKey] ?? 0)
      return <div className="mini-bar" key={String(row[labelKey] ?? index)}><div className="mini-bar-head"><span>{String(row[labelKey] ?? '—')}</span><strong>{formatNumber(value)}{suffix}</strong></div><div className="distribution-track"><span className="distribution-fill" style={{ width: `${Math.min(100, Math.max(2, value / max * 100))}%` }} /></div></div>
    })}
  </div>
}

export function DonutMetric({ value, label, detail }: { value: number; label: string; detail?: string }) {
  const bounded = Math.max(0, Math.min(100, Number(value) || 0))
  return <div className="donut-metric"><div className="donut" style={{ '--metric': `${bounded * 3.6}deg` } as React.CSSProperties}><div><strong>{formatNumber(bounded)}%</strong><span>{label}</span></div></div>{detail && <p>{detail}</p>}</div>
}

function shortDate(value: string) {
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleDateString('vi-VN', { day: '2-digit', month: '2-digit' })
}

export function formatNumber(value: unknown, maximumFractionDigits = 1) {
  const numeric = Number(value ?? 0)
  if (!Number.isFinite(numeric)) return '0'
  return new Intl.NumberFormat('vi-VN', { maximumFractionDigits }).format(numeric)
}
