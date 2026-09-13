import { FormEvent, ReactNode } from 'react'
import type { Row } from '../app/types'

export type TableColumn = {
  key: string
  label: string
  render?: (value: unknown, row: Row) => ReactNode
}

export function Card({ title, actions, children, className = '' }: { title: string; actions?: ReactNode; children: ReactNode; className?: string }) {
  return <section className={`card ${className}`.trim()}><div className="card-heading"><div className="card-title">{title}</div>{actions}</div>{children}</section>
}

export function Empty({ text }: { text: string }) {
  return <div className="empty">{text}</div>
}

export function Status({ children, tone = 'info' }: { children?: ReactNode; tone?: 'info' | 'error' | 'success' }) {
  if (!children) return null
  return <p className={`status-line ${tone}`} role={tone === 'error' ? 'alert' : 'status'}>{children}</p>
}

export function Stat({ label, value, hint, emphasis = false }: { label: string; value: unknown; hint?: string; emphasis?: boolean }) {
  return <div className={`stat ${emphasis ? 'emphasis' : ''}`}><span>{label}</span><strong>{String(value ?? 0)}</strong>{hint && <small>{hint}</small>}</div>
}

export function DataTable({ rows, columns, labelledBy }: { rows: Row[]; columns?: TableColumn[]; labelledBy?: string }) {
  if (!rows.length) return <Empty text="Chưa có dữ liệu" />
  const selected: TableColumn[] = columns ?? Object.keys(rows[0]).slice(0, 7).map(key => ({ key, label: key.replaceAll('_', ' ') }))
  return <div className="table-wrap" role="region" aria-labelledby={labelledBy} tabIndex={0}><table><thead><tr>{selected.map(column => <th scope="col" key={column.key}>{column.label}</th>)}</tr></thead><tbody>{rows.map((row, index) => <tr key={String(row.id ?? row.studentId ?? index)}>{selected.map(column => <td key={column.key}>{column.render ? column.render(row[column.key], row) : display(row[column.key])}</td>)}</tr>)}</tbody></table></div>
}

export function Modal({ title, open, onClose, children }: { title: string; open: boolean; onClose: () => void; children: ReactNode }) {
  if (!open) return null
  return <div className="modal-backdrop" role="presentation" onMouseDown={event => { if (event.target === event.currentTarget) onClose() }}><div className="modal" role="dialog" aria-modal="true" aria-label={title}><div className="modal-head"><h3>{title}</h3><button type="button" className="icon-button" onClick={onClose} aria-label="Đóng">×</button></div>{children}</div></div>
}

export function DialogForm({ onSubmit, children, submitLabel = 'Lưu', cancelLabel = 'Hủy', onCancel, busy }: { onSubmit: (event: FormEvent<HTMLFormElement>) => void; children: ReactNode; submitLabel?: string; cancelLabel?: string; onCancel: () => void; busy?: boolean }) {
  return <form className="dialog-form" onSubmit={onSubmit}>{children}<div className="dialog-actions"><button type="button" onClick={onCancel}>{cancelLabel}</button><button type="submit" className="primary" disabled={busy}>{busy ? 'Đang lưu…' : submitLabel}</button></div></form>
}

export function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return <label className="field"><span>{label}</span>{children}{hint && <small>{hint}</small>}</label>
}

export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: 'neutral' | 'success' | 'warning' | 'danger' | 'info' }) {
  return <span className={`badge ${tone}`}>{children}</span>
}

export function SectionHeading({ eyebrow, title, description, actions }: { eyebrow?: string; title: string; description?: string; actions?: ReactNode }) {
  return <div className="section-heading"><div>{eyebrow && <p className="eyebrow">{eyebrow}</p>}<h3>{title}</h3>{description && <p>{description}</p>}</div>{actions && <div className="page-actions">{actions}</div>}</div>
}

function display(value: unknown) {
  if (value === null || value === undefined || value === '') return '—'
  if (typeof value === 'boolean') return value ? 'Có' : 'Không'
  return String(value)
}
