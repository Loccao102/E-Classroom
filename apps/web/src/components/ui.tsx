import { FormEvent, ReactNode } from 'react'
import type { Row } from '../app/types'

export function Card({ title, actions, children }: { title: string; actions?: ReactNode; children: ReactNode }) {
  return <section className="card"><div className="card-heading"><div className="card-title">{title}</div>{actions}</div>{children}</section>
}

export function Empty({ text }: { text: string }) {
  return <div className="empty">{text}</div>
}

export function Status({ children, tone = 'info' }: { children?: ReactNode; tone?: 'info' | 'error' | 'success' }) {
  if (!children) return null
  return <p className={`status-line ${tone}`}>{children}</p>
}

export function Stat({ label, value, hint }: { label: string; value: unknown; hint?: string }) {
  return <div className="stat"><span>{label}</span><strong>{String(value ?? 0)}</strong>{hint && <small>{hint}</small>}</div>
}

export function DataTable({ rows, columns }: { rows: Row[]; columns?: { key: string; label: string; render?: (value: unknown, row: Row) => ReactNode }[] }) {
  if (!rows.length) return <Empty text="Chưa có dữ liệu" />
  const selected = columns || Object.keys(rows[0]).slice(0, 7).map(key => ({ key, label: key.replaceAll('_', ' ') }))
  return <div className="table-wrap"><table><thead><tr>{selected.map(column => <th key={column.key}>{column.label}</th>)}</tr></thead><tbody>{rows.map((row, index) => <tr key={String(row.id ?? index)}>{selected.map(column => <td key={column.key}>{column.render ? column.render(row[column.key], row) : display(row[column.key])}</td>)}</tr>)}</tbody></table></div>
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

function display(value: unknown) {
  if (value === null || value === undefined || value === '') return '—'
  if (typeof value === 'boolean') return value ? 'Có' : 'Không'
  return String(value)
}
