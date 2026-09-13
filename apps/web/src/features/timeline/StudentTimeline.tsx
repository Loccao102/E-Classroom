import { FormEvent, useEffect, useMemo, useState } from 'react'
import { api } from '../../api'
import { Badge, Card, DialogForm, Field, Modal, Status } from '../../components/ui'

type TimelineItem = {
  id: string
  type: string
  occurredAt: string
  title: string
  body?: string | null
  state?: string | null
  visibility: string
  actorName?: string | null
  contextLabel?: string | null
  category?: string | null
  severity?: string | null
  version: number
  value?: string | null
}
type TimelineCursor = { beforeOccurredAt: string; beforeId: string }
type TimelinePage = { items: TimelineItem[]; nextCursor?: TimelineCursor | null }

const timelineTypes = [
  ['ALL', 'Tất cả'], ['ATTENDANCE', 'Điểm danh'], ['LEAVE', 'Xin nghỉ'], ['SCORE', 'Điểm số'],
  ['COMMENT', 'Nhận xét'], ['CONDUCT', 'Rèn luyện'], ['ANNOUNCEMENT', 'Thông báo']
] as const

export function StudentTimeline({ schoolId, studentId, canManageConduct = false }: { schoolId: string; studentId: string; canManageConduct?: boolean }) {
  const [items, setItems] = useState<TimelineItem[]>([])
  const [cursor, setCursor] = useState<TimelineCursor | null>(null)
  const [type, setType] = useState('ALL')
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [conductOpen, setConductOpen] = useState(false)

  const load = async (reset = true) => {
    if (!studentId) return
    setLoading(true); setError('')
    try {
      const params = new URLSearchParams({ limit: '20' })
      if (type !== 'ALL') params.set('types', type)
      if (fromDate) params.set('fromDate', fromDate)
      if (toDate) params.set('toDate', toDate)
      if (!reset && cursor) { params.set('beforeOccurredAt', cursor.beforeOccurredAt); params.set('beforeId', cursor.beforeId) }
      const page = await api<TimelinePage>(`/api/v1/schools/${schoolId}/students/${studentId}/timeline?${params}`)
      setItems(current => reset ? page.items : [...current, ...page.items])
      setCursor(page.nextCursor || null)
    } catch (err) { setError((err as Error).message); if (reset) setItems([]) }
    finally { setLoading(false) }
  }

  useEffect(() => { setCursor(null); void load(true) }, [schoolId, studentId, type, fromDate, toDate])
  const grouped = useMemo(() => groupByDay(items), [items])

  return <Card title="Dòng thời gian học sinh" actions={canManageConduct ? <button className="primary" onClick={() => setConductOpen(true)}>+ Ghi nhận rèn luyện</button> : undefined} className="timeline-card">
    <div className="timeline-toolbar">
      <label><span>Loại hoạt động</span><select value={type} onChange={event => setType(event.target.value)}>{timelineTypes.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      <label><span>Từ ngày</span><input type="date" value={fromDate} onChange={event => setFromDate(event.target.value)} /></label>
      <label><span>Đến ngày</span><input type="date" min={fromDate || undefined} value={toDate} onChange={event => setToDate(event.target.value)} /></label>
      {(fromDate || toDate || type !== 'ALL') && <button onClick={() => { setType('ALL'); setFromDate(''); setToDate('') }}>Xóa lọc</button>}
    </div>
    <Status tone="error">{error}</Status>
    {loading && !items.length ? <div className="timeline-skeleton"><div /><div /><div /></div> : !items.length ? <p className="positive-empty">Chưa có hoạt động phù hợp với bộ lọc.</p> : <div className="timeline-stream">{grouped.map(group => <section key={group.day} className="timeline-day"><div className="timeline-day-label"><time>{group.label}</time><span>{group.items.length} hoạt động</span></div><div className="timeline-day-items">{group.items.map(item => <TimelineEntry key={`${item.type}-${item.id}`} item={item} />)}</div></section>)}</div>}
    {cursor && <div className="timeline-more"><button disabled={loading} onClick={() => void load(false)}>{loading ? 'Đang tải…' : 'Xem thêm hoạt động'}</button></div>}
    <ConductModal open={conductOpen} schoolId={schoolId} studentId={studentId} onClose={() => setConductOpen(false)} onSaved={async () => { setConductOpen(false); await load(true) }} />
  </Card>
}

function TimelineEntry({ item }: { item: TimelineItem }) {
  return <article className={`timeline-entry type-${item.type.toLowerCase()}`}>
    <div className="timeline-rail"><span>{typeMark(item.type)}</span></div>
    <div className="timeline-entry-body">
      <div className="timeline-entry-head"><div><Badge tone={typeTone(item)}>{typeLabel(item.type)}</Badge>{item.severity && <Badge tone={severityTone(item.severity)}>{severityLabel(item.severity)}</Badge>}</div><time>{formatTime(item.occurredAt)}</time></div>
      <h4>{item.title}</h4>
      {item.body && <p>{item.body}</p>}
      <div className="timeline-meta">{item.value && <strong>{valueLabel(item)}</strong>}{item.contextLabel && <span>{item.contextLabel}</span>}{item.actorName && <span>Ghi bởi {item.actorName}</span>}{item.category && item.type === 'CONDUCT' && <span>{conductCategory(item.category)}</span>}</div>
    </div>
  </article>
}

function ConductModal({ open, onClose, schoolId, studentId, onSaved }: { open: boolean; onClose: () => void; schoolId: string; studentId: string; onSaved: () => Promise<void> }) {
  const [category, setCategory] = useState('POSITIVE_RECOGNITION')
  const [severity, setSeverity] = useState('INFO')
  const [title, setTitle] = useState('')
  const [body, setBody] = useState('')
  const [visibility, setVisibility] = useState('STUDENT_AND_GUARDIAN')
  const [occurredAt, setOccurredAt] = useState(localDateTime())
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setBusy(true); setError('')
    try {
      await api(`/api/v1/schools/${schoolId}/students/${studentId}/conduct`, { method: 'POST', body: JSON.stringify({ category, severity: severity || null, title: title.trim(), body: body.trim(), visibility, classroomId: null, subjectId: null, occurredAt: new Date(occurredAt).toISOString() }) })
      setTitle(''); setBody(''); setOccurredAt(localDateTime()); await onSaved()
    } catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }
  return <Modal open={open} title="Ghi nhận rèn luyện" onClose={onClose}><DialogForm onSubmit={submit} onCancel={onClose} busy={busy} submitLabel="Lưu ghi nhận"><Status tone="error">{error}</Status>
    <div className="form-grid"><Field label="Loại ghi nhận"><select value={category} onChange={event => setCategory(event.target.value)}><option value="POSITIVE_RECOGNITION">Tuyên dương</option><option value="ACHIEVEMENT">Thành tích</option><option value="REMINDER">Nhắc nhở</option><option value="VIOLATION">Vi phạm</option><option value="GENERAL">Ghi chú chung</option></select></Field><Field label="Mức độ"><select value={severity} onChange={event => setSeverity(event.target.value)}><option value="INFO">Thông tin</option><option value="LOW">Nhẹ</option><option value="MEDIUM">Cần chú ý</option><option value="HIGH">Quan trọng</option></select></Field></div>
    <Field label="Tiêu đề"><input required maxLength={255} value={title} onChange={event => setTitle(event.target.value)} placeholder="Ví dụ: Tích cực hỗ trợ nhóm" /></Field>
    <Field label="Nội dung"><textarea required maxLength={5000} rows={5} value={body} onChange={event => setBody(event.target.value)} /></Field>
    <div className="form-grid"><Field label="Thời điểm"><input required type="datetime-local" value={occurredAt} onChange={event => setOccurredAt(event.target.value)} /></Field><Field label="Ai được xem"><select value={visibility} onChange={event => setVisibility(event.target.value)}><option value="STUDENT_AND_GUARDIAN">Học sinh & phụ huynh</option><option value="GUARDIAN">Chỉ phụ huynh</option><option value="STUDENT">Chỉ học sinh</option><option value="STAFF_ONLY">Chỉ nhân sự nhà trường</option></select></Field></div>
  </DialogForm></Modal>
}

function groupByDay(items: TimelineItem[]) {
  const groups = new Map<string, TimelineItem[]>()
  for (const item of items) { const day = new Date(item.occurredAt).toLocaleDateString('sv-SE'); groups.set(day, [...(groups.get(day) || []), item]) }
  return [...groups.entries()].map(([day, values]) => ({ day, label: new Date(`${day}T00:00:00`).toLocaleDateString('vi-VN', { weekday: 'long', day: '2-digit', month: '2-digit', year: 'numeric' }), items: values }))
}
function typeLabel(value: string) { const labels: Record<string, string> = { ATTENDANCE: 'Điểm danh', LEAVE: 'Xin nghỉ', SCORE: 'Điểm số', COMMENT: 'Nhận xét', CONDUCT: 'Rèn luyện', ANNOUNCEMENT: 'Thông báo' }; return labels[value] || value }
function typeMark(value: string) { const marks: Record<string, string> = { ATTENDANCE: '✓', LEAVE: 'L', SCORE: '10', COMMENT: '“', CONDUCT: '★', ANNOUNCEMENT: '!' }; return marks[value] || '•' }
function typeTone(item: TimelineItem): 'neutral' | 'success' | 'warning' | 'danger' { if (item.type === 'SCORE' || item.type === 'CONDUCT') return 'success'; if (item.type === 'LEAVE' || item.type === 'ATTENDANCE') return item.state === 'ABSENT' || item.state === 'REJECTED' ? 'danger' : 'warning'; return 'neutral' }
function severityTone(value: string): 'neutral' | 'success' | 'warning' | 'danger' { return value === 'HIGH' ? 'danger' : value === 'MEDIUM' ? 'warning' : value === 'INFO' ? 'success' : 'neutral' }
function severityLabel(value: string) { return value === 'HIGH' ? 'Quan trọng' : value === 'MEDIUM' ? 'Cần chú ý' : value === 'LOW' ? 'Nhẹ' : 'Thông tin' }
function conductCategory(value: string) { const labels: Record<string, string> = { POSITIVE_RECOGNITION: 'Tuyên dương', ACHIEVEMENT: 'Thành tích', REMINDER: 'Nhắc nhở', VIOLATION: 'Vi phạm', GENERAL: 'Ghi chú chung' }; return labels[value] || value }
function valueLabel(item: TimelineItem) { if (item.type === 'SCORE') return `Điểm ${item.value}`; const labels: Record<string, string> = { PRESENT: 'Có mặt', ABSENT: 'Vắng', EXCUSED: 'Có phép', LATE: 'Đi muộn', EARLY_LEAVE: 'Về sớm', APPROVED: 'Đã duyệt', REJECTED: 'Từ chối', SUBMITTED: 'Chờ duyệt' }; return labels[item.value || ''] || item.value }
function formatTime(value: string) { const date = new Date(value); return Number.isNaN(date.getTime()) ? value : date.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' }) }
function localDateTime() { const now = new Date(); const local = new Date(now.getTime() - now.getTimezoneOffset() * 60000); return local.toISOString().slice(0, 16) }
