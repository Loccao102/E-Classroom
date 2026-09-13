import { FormEvent, useEffect, useMemo, useState } from 'react'
import { api } from '../../api'
import type { Meeting, MeetingDetails, MeetingInvitee, MeetingSlot, Row, TeachingAssignment } from '../../app/types'
import { Badge, DialogForm, Empty, Field, Modal, SectionHeading, Status } from '../../components/ui'

type Props = {
  schoolId: string
  isAdmin: boolean
  isTeacher: boolean
  isParent: boolean
  isStudent: boolean
}

type AudienceOption = { id: string; name: string }
type Mutate = (action: () => Promise<unknown>, success: string) => Promise<void>

export function MeetingPage({ schoolId, isAdmin, isTeacher, isParent, isStudent }: Props) {
  const [meetings, setMeetings] = useState<Meeting[]>([])
  const [selected, setSelected] = useState('')
  const [details, setDetails] = useState<MeetingDetails | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [outcomeOpen, setOutcomeOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')

  const canCreate = isAdmin || isTeacher

  const loadMeetings = async (preferred?: string) => {
    try {
      const rows = await api<Meeting[]>(`/api/v1/schools/${schoolId}/meetings`)
      setMeetings(rows)
      setSelected(current => preferred || current || rows[0]?.id || '')
    } catch (err) { setError((err as Error).message) }
  }

  const loadDetails = async () => {
    if (!selected) { setDetails(null); return }
    try { setDetails(await api<MeetingDetails>(`/api/v1/meetings/${selected}`)) }
    catch (err) { setError((err as Error).message) }
  }

  useEffect(() => { setSelected(''); setDetails(null); void loadMeetings() }, [schoolId])
  useEffect(() => { void loadDetails() }, [selected])

  const mutate: Mutate = async (action, success) => {
    setBusy(true); setError(''); setMessage('')
    try {
      await action()
      setMessage(success)
      await loadMeetings(selected)
      await loadDetails()
    } catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }

  const active = details?.meeting
  const upcoming = Boolean(active?.status === 'SCHEDULED' && new Date(active.startsAt).getTime() > Date.now())
  const started = Boolean(active && new Date(active.startsAt).getTime() <= Date.now())
  const ended = Boolean(active && new Date(active.endsAt).getTime() <= Date.now())
  const canRecordOutcome = Boolean(details?.manageable && active && started && active.status !== 'CANCELLED')
  // A multi-role staff+parent account receives the staff detail shape when it manages a meeting.
  // Hide family mutation controls in that shape so it cannot accidentally act on another invitee.
  const showParentActions = Boolean(isParent && details && !details.manageable && details.invitees.length > 0)

  return <section className="workspace meeting-workspace">
    <SectionHeading eyebrow="Nhà trường · Gia đình" title="Lịch họp & trao đổi" description="Lời mời, phản hồi, khung giờ 1-1 và kết quả sau buổi họp ở cùng một nơi." actions={canCreate ? <button className="primary" onClick={() => setCreateOpen(true)}>Tạo lịch họp</button> : undefined} />
    <Status tone="success">{message}</Status><Status tone="error">{error}</Status>

    <div className="meeting-shell">
      <aside className="meeting-list" aria-label="Danh sách lịch họp">
        <div className="meeting-list-head"><strong>{meetings.length} lịch họp</strong><span>Mới nhất trước</span></div>
        {meetings.length ? meetings.map(meeting => <button key={meeting.id} className={meeting.id === selected ? 'selected' : ''} onClick={() => setSelected(meeting.id)}>
          <div className="meeting-list-date"><strong>{day(meeting.startsAt)}</strong><span>{month(meeting.startsAt)}</span></div>
          <div className="meeting-list-copy"><strong>{meeting.title}</strong><span>{formatDateTime(meeting.startsAt)}</span><small>{meeting.location}</small></div>
          <MeetingStatus status={meeting.status} startsAt={meeting.startsAt} />
        </button>) : <Empty text="Chưa có lịch họp nào." />}
      </aside>

      <main className="meeting-detail">
        {!active || !details ? <div className="meeting-empty"><div>◇</div><h3>Chọn một lịch họp</h3><p>Chi tiết, phản hồi và lịch trao đổi sẽ hiển thị tại đây.</p></div> : <>
          <header className="meeting-hero">
            <div><div className="meeting-meta"><MeetingStatus status={active.status} startsAt={active.startsAt} /><span>{scopeLabel(active.scopeType)}</span>{active.includeStudents && <span>Có học sinh tham gia</span>}</div><h3>{active.title}</h3><p>{active.agenda}</p></div>
            <div className="meeting-time-card"><strong>{formatTime(active.startsAt)} – {formatTime(active.endsAt)}</strong><span>{formatDate(active.startsAt)}</span><small>{active.location}</small></div>
          </header>

          {active.note && <div className="meeting-note"><strong>Ghi chú chuẩn bị</strong><p>{active.note}</p></div>}

          {details.manageable && <div className="meeting-actions">
            {upcoming && <button disabled={busy} onClick={() => void mutate(() => api(`/api/v1/meetings/${active.id}/reminders`, { method: 'POST' }), 'Đã gửi nhắc lịch tới các phụ huynh đủ điều kiện.')}>Gửi nhắc lịch</button>}
            {canRecordOutcome && <button onClick={() => setOutcomeOpen(true)}>Ghi kết quả</button>}
            {active.status === 'SCHEDULED' && <button className="danger-ghost" disabled={busy} onClick={() => void mutate(() => api(`/api/v1/meetings/${active.id}/cancel`, { method: 'POST', body: JSON.stringify({ version: active.version }) }), 'Đã huỷ lịch họp.')}>Huỷ lịch</button>}
            {active.status === 'SCHEDULED' && ended && <button className="primary" disabled={busy} onClick={() => void mutate(() => api(`/api/v1/meetings/${active.id}/complete`, { method: 'POST', body: JSON.stringify({ version: active.version }) }), 'Đã hoàn tất buổi họp.')}>Đánh dấu hoàn tất</button>}
          </div>}

          {showParentActions && <ParentResponsePanel meeting={active} invitees={details.invitees} slots={details.slots} busy={busy} mutate={mutate} />}
          {details.manageable && <InviteePanel meeting={active} invitees={details.invitees} started={started} busy={busy} mutate={mutate} />}
          {!details.manageable && !isParent && isStudent && <div className="meeting-note"><strong>Thông tin dành cho học sinh</strong><p>Bạn được đưa vào phạm vi của buổi họp này. Các kết quả được phép chia sẻ sẽ xuất hiện bên dưới.</p></div>}

          <section className="meeting-section"><div className="meeting-section-head"><div><span>Sau buổi họp</span><h4>Kết quả & việc cần theo dõi</h4></div>{canRecordOutcome && <button onClick={() => setOutcomeOpen(true)}>+ Thêm ghi nhận</button>}</div>
            {details.outcomes.length ? <div className="outcome-list">{details.outcomes.map(outcome => <article key={outcome.id}><div><Badge tone={outcome.visibility === 'STAFF_ONLY' ? 'neutral' : outcome.visibility === 'GUARDIAN' ? 'warning' : 'info'}>{visibilityLabel(outcome.visibility)}</Badge>{outcome.studentName && <span>{outcome.studentName}</span>}<time>{formatDateTime(outcome.createdAt)}</time></div><p>{outcome.body}</p><small>{outcome.recordedByName}</small></article>)}</div> : <Empty text={started ? 'Chưa có kết quả hoặc ghi chú theo dõi.' : 'Kết quả sẽ được ghi nhận sau khi buổi họp bắt đầu.'} />}
          </section>
        </>}
      </main>
    </div>

    <CreateMeetingModal open={createOpen} schoolId={schoolId} isAdmin={isAdmin} isTeacher={isTeacher} onClose={() => setCreateOpen(false)} onSaved={async id => { setCreateOpen(false); setMessage('Đã tạo lịch họp và gửi lời mời trong hệ thống.'); await loadMeetings(id) }} />
    {active && canRecordOutcome && <OutcomeModal open={outcomeOpen} meeting={active} invitees={details?.invitees || []} onClose={() => setOutcomeOpen(false)} onSaved={async () => { setOutcomeOpen(false); setMessage('Đã lưu kết quả buổi họp.'); await loadDetails() }} />}
  </section>
}

function ParentResponsePanel({ meeting, invitees, slots, busy, mutate }: { meeting: Meeting; invitees: MeetingInvitee[]; slots: MeetingSlot[]; busy: boolean; mutate: Mutate }) {
  return <section className="meeting-section response-panel"><div className="meeting-section-head"><div><span>Phản hồi của gia đình</span><h4>Xác nhận tham dự</h4></div></div>
    <div className="family-responses">{invitees.map(invitee => <article key={invitee.id}><div><strong>{invitee.studentName}</strong><MeetingResponse value={invitee.response} /></div><div className="response-actions"><button disabled={busy || meeting.status !== 'SCHEDULED' || invitee.response === 'ACCEPTED'} onClick={() => void mutate(() => api(`/api/v1/meetings/${meeting.id}/response`, { method: 'PUT', body: JSON.stringify({ studentId: invitee.studentId, response: 'ACCEPTED', version: invitee.version }) }), 'Đã xác nhận tham dự.')}>Tham dự</button><button disabled={busy || meeting.status !== 'SCHEDULED' || invitee.response === 'DECLINED'} onClick={() => void mutate(() => api(`/api/v1/meetings/${meeting.id}/response`, { method: 'PUT', body: JSON.stringify({ studentId: invitee.studentId, response: 'DECLINED', version: invitee.version }) }), 'Đã gửi phản hồi không tham dự.')}>Không tham dự</button></div>
      {invitee.response === 'ACCEPTED' && slots.length > 0 && <div className="slot-picker"><span>Chọn khung giờ trao đổi 1-1</span><div>{slots.map(slot => <SlotButton key={slot.id} slot={slot} meeting={meeting} studentId={invitee.studentId} busy={busy} mutate={mutate} />)}</div></div>}
    </article>)}</div>
  </section>
}

function SlotButton({ slot, meeting, studentId, busy, mutate }: { slot: MeetingSlot; meeting: Meeting; studentId: string; busy: boolean; mutate: Mutate }) {
  if (slot.mine) return <button className="slot selected" disabled={busy} onClick={() => void mutate(() => api(`/api/v1/meetings/${meeting.id}/slots/${slot.id}/booking?studentId=${studentId}&version=${slot.version}`, { method: 'DELETE' }), 'Đã huỷ khung giờ 1-1.')}>{formatTime(slot.startsAt)} · Đã chọn ×</button>
  return <button className="slot" disabled={busy || !slot.available} onClick={() => void mutate(() => api(`/api/v1/meetings/${meeting.id}/slots/${slot.id}/booking`, { method: 'PUT', body: JSON.stringify({ studentId, version: slot.version }) }), 'Đã đặt khung giờ 1-1.')}>{formatTime(slot.startsAt)}{slot.available ? '' : ' · Đã đặt'}</button>
}

function InviteePanel({ meeting, invitees, started, busy, mutate }: { meeting: Meeting; invitees: MeetingInvitee[]; started: boolean; busy: boolean; mutate: Mutate }) {
  return <section className="meeting-section"><div className="meeting-section-head"><div><span>Danh sách mời</span><h4>{invitees.length} phụ huynh / học sinh</h4></div></div>{invitees.length ? <div className="invitee-table"><div className="invitee-row header"><span>Gia đình</span><span>Phản hồi</span><span>Điểm danh</span><span>Thao tác</span></div>{invitees.map(invitee => <div className="invitee-row" key={invitee.id}><div><strong>{invitee.guardianName}</strong><small>{invitee.studentName}</small></div><MeetingResponse value={invitee.response} /><AttendanceBadge value={invitee.attendance} /><div className="attendance-actions">{started && meeting.status !== 'CANCELLED' ? <><button disabled={busy || invitee.attendance === 'PRESENT'} onClick={() => void mutate(() => api(`/api/v1/meetings/${meeting.id}/invitees/${invitee.id}/attendance`, { method: 'PUT', body: JSON.stringify({ attendance: 'PRESENT', version: invitee.version }) }), 'Đã ghi nhận phụ huynh có mặt.')}>Có mặt</button><button disabled={busy || invitee.attendance === 'ABSENT'} onClick={() => void mutate(() => api(`/api/v1/meetings/${meeting.id}/invitees/${invitee.id}/attendance`, { method: 'PUT', body: JSON.stringify({ attendance: 'ABSENT', version: invitee.version }) }), 'Đã ghi nhận vắng mặt.')}>Vắng</button></> : <span className="muted">{meeting.status === 'CANCELLED' ? 'Đã huỷ' : 'Chưa đến giờ'}</span>}</div></div>)}</div> : <Empty text="Không có phụ huynh có tài khoản đang hoạt động trong phạm vi này." />}</section>
}

function CreateMeetingModal({ open, schoolId, isAdmin, isTeacher, onClose, onSaved }: { open: boolean; schoolId: string; isAdmin: boolean; isTeacher: boolean; onClose: () => void; onSaved: (id: string) => Promise<void> }) {
  const [scopeType, setScopeType] = useState(isAdmin ? 'SCHOOL' : 'CLASSROOM')
  const [scopeId, setScopeId] = useState('')
  const [classrooms, setClassrooms] = useState<AudienceOption[]>([])
  const [students, setStudents] = useState<AudienceOption[]>([])
  const [title, setTitle] = useState('')
  const [agenda, setAgenda] = useState('')
  const [note, setNote] = useState('')
  const [location, setLocation] = useState('')
  const [startsAt, setStartsAt] = useState(defaultLocalDateTime(2))
  const [endsAt, setEndsAt] = useState(defaultLocalDateTime(3))
  const [includeStudents, setIncludeStudents] = useState(false)
  const [slotMinutes, setSlotMinutes] = useState(0)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    if (!open) return
    const load = async () => {
      try {
        if (isAdmin) {
          const [classes, studentRows] = await Promise.all([
            api<Row[]>(`/api/v1/schools/${schoolId}/classrooms`), api<Row[]>(`/api/v1/schools/${schoolId}/students`)
          ])
          setClassrooms(classes.map(row => ({ id: String(row.id || ''), name: String(row.name || row.code || 'Lớp học') })))
          setStudents(studentRows.map(row => ({ id: String(row.id || ''), name: String(row.full_name || row.student_code || 'Học sinh') })))
        } else if (isTeacher) {
          const assignments = await api<TeachingAssignment[]>(`/api/v1/schools/${schoolId}/me/teaching-assignments`)
          const classes = uniqueClassOptions(assignments)
          setClassrooms(classes)
          const rosters = await Promise.all(classes.map(item => api<Row[]>(`/api/v1/schools/${schoolId}/classrooms/${item.id}/roster`).catch(() => [] as Row[])))
          const seen = new Set<string>()
          setStudents(rosters.flat().flatMap(row => {
            const id = String(row.id || '')
            if (!id || seen.has(id)) return []
            seen.add(id)
            return [{ id, name: String(row.full_name || row.student_code || 'Học sinh') }]
          }))
        }
      } catch (err) { setError((err as Error).message) }
    }
    void load()
  }, [open, schoolId, isAdmin, isTeacher])

  const options = scopeType === 'CLASSROOM' ? classrooms : scopeType === 'STUDENT' ? students : []
  useEffect(() => { if (scopeType === 'SCHOOL') setScopeId(''); else if (!options.some(item => item.id === scopeId)) setScopeId(options[0]?.id || '') }, [scopeType, classrooms, students])

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setBusy(true); setError('')
    try {
      const start = new Date(startsAt); const end = new Date(endsAt)
      if (!(end.getTime() > start.getTime())) throw new Error('Giờ kết thúc phải sau giờ bắt đầu.')
      const slots = slotMinutes > 0 ? generateSlots(start, end, slotMinutes) : []
      const result = await api<{ id: string }>(`/api/v1/schools/${schoolId}/meetings`, { method: 'POST', body: JSON.stringify({ scopeType, scopeId: scopeType === 'SCHOOL' ? null : scopeId, title: title.trim(), agenda: agenda.trim(), note: note.trim() || null, location: location.trim(), startsAt: start.toISOString(), endsAt: end.toISOString(), includeStudents, slots }) })
      setTitle(''); setAgenda(''); setNote(''); setLocation(''); setSlotMinutes(0)
      await onSaved(result.id)
    } catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }

  return <Modal title="Tạo lịch họp phụ huynh" open={open} onClose={onClose}><DialogForm onSubmit={submit} onCancel={onClose} submitLabel="Tạo & gửi lời mời" busy={busy}><Status tone="error">{error}</Status>
    <div className="form-grid two"><Field label="Phạm vi"><select value={scopeType} onChange={event => setScopeType(event.target.value)}>{isAdmin && <option value="SCHOOL">Toàn trường</option>}<option value="CLASSROOM">Một lớp</option><option value="STUDENT">Một học sinh</option></select></Field>{scopeType !== 'SCHOOL' && <Field label={scopeType === 'CLASSROOM' ? 'Lớp' : 'Học sinh'}><select required value={scopeId} onChange={event => setScopeId(event.target.value)}><option value="">Chọn…</option>{options.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></Field>}</div>
    <Field label="Tiêu đề"><input required maxLength={255} value={title} onChange={event => setTitle(event.target.value)} placeholder="Ví dụ: Họp phụ huynh giữa học kỳ" /></Field>
    <Field label="Nội dung / chương trình"><textarea required maxLength={10000} rows={4} value={agenda} onChange={event => setAgenda(event.target.value)} placeholder="Những nội dung sẽ trao đổi…" /></Field>
    <div className="form-grid two"><Field label="Bắt đầu"><input required type="datetime-local" value={startsAt} onChange={event => setStartsAt(event.target.value)} /></Field><Field label="Kết thúc"><input required type="datetime-local" value={endsAt} onChange={event => setEndsAt(event.target.value)} /></Field></div>
    <div className="form-grid two"><Field label="Địa điểm"><input required maxLength={255} value={location} onChange={event => setLocation(event.target.value)} placeholder="Phòng 101 / Hội trường A" /></Field><Field label="Khung trao đổi 1-1" hint="Tự chia thời lượng cuộc họp thành các khung có thể đặt."><select value={slotMinutes} onChange={event => setSlotMinutes(Number(event.target.value))}><option value={0}>Không tạo</option><option value={10}>10 phút</option><option value={15}>15 phút</option><option value={20}>20 phút</option><option value={30}>30 phút</option></select></Field></div>
    <Field label="Ghi chú chuẩn bị"><textarea maxLength={10000} rows={2} value={note} onChange={event => setNote(event.target.value)} /></Field>
    <label className="check-row"><input type="checkbox" checked={includeStudents} onChange={event => setIncludeStudents(event.target.checked)} /><span>Cho học sinh trong phạm vi xem lịch họp và các kết quả được chia sẻ</span></label>
  </DialogForm></Modal>
}

function OutcomeModal({ open, meeting, invitees, onClose, onSaved }: { open: boolean; meeting: Meeting; invitees: MeetingInvitee[]; onClose: () => void; onSaved: () => Promise<void> }) {
  const students = useMemo(() => {
    const seen = new Set<string>()
    return invitees.flatMap(row => {
      if (seen.has(row.studentId)) return []
      seen.add(row.studentId)
      return [{ id: row.studentId, name: row.studentName }]
    })
  }, [invitees])
  const [studentId, setStudentId] = useState('')
  const [visibility, setVisibility] = useState('GUARDIAN')
  const [body, setBody] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => { if (open) setStudentId(students[0]?.id || '') }, [open, students])

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setBusy(true); setError('')
    try {
      await api(`/api/v1/meetings/${meeting.id}/outcomes`, { method: 'POST', body: JSON.stringify({ studentId: visibility === 'STAFF_ONLY' ? studentId || null : studentId, body: body.trim(), visibility }) })
      setBody('')
      await onSaved()
    } catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }

  return <Modal open={open} onClose={onClose} title="Ghi kết quả buổi họp"><DialogForm onSubmit={submit} onCancel={onClose} submitLabel="Lưu kết quả" busy={busy}><Status tone="error">{error}</Status><Field label="Phạm vi chia sẻ"><select value={visibility} onChange={event => setVisibility(event.target.value)}><option value="STAFF_ONLY">Nội bộ nhà trường</option><option value="GUARDIAN">Phụ huynh</option>{meeting.includeStudents && <option value="STUDENT_AND_GUARDIAN">Phụ huynh & học sinh</option>}</select></Field><Field label="Học sinh" hint={visibility === 'STAFF_ONLY' ? 'Có thể bỏ trống cho ghi chú chung.' : 'Bắt buộc với ghi chú gửi gia đình.'}><select required={visibility !== 'STAFF_ONLY'} value={studentId} onChange={event => setStudentId(event.target.value)}><option value="">Ghi chú chung</option>{students.map(student => <option key={student.id} value={student.id}>{student.name}</option>)}</select></Field><Field label="Nội dung"><textarea required maxLength={10000} rows={5} value={body} onChange={event => setBody(event.target.value)} placeholder="Điểm đã thống nhất, việc cần theo dõi…" /></Field></DialogForm></Modal>
}

function MeetingStatus({ status, startsAt }: { status: string; startsAt: string }) { if (status === 'CANCELLED') return <Badge tone="danger">Đã huỷ</Badge>; if (status === 'COMPLETED') return <Badge tone="success">Hoàn tất</Badge>; return new Date(startsAt).getTime() > Date.now() ? <Badge tone="info">Sắp tới</Badge> : <Badge tone="warning">Đang/đã diễn ra</Badge> }
function MeetingResponse({ value }: { value: string }) { return <Badge tone={value === 'ACCEPTED' ? 'success' : value === 'DECLINED' ? 'danger' : 'warning'}>{value === 'ACCEPTED' ? 'Tham dự' : value === 'DECLINED' ? 'Không tham dự' : 'Chờ phản hồi'}</Badge> }
function AttendanceBadge({ value }: { value: string }) { return <Badge tone={value === 'PRESENT' ? 'success' : value === 'ABSENT' ? 'danger' : 'neutral'}>{value === 'PRESENT' ? 'Có mặt' : value === 'ABSENT' ? 'Vắng' : 'Chưa ghi nhận'}</Badge> }
function scopeLabel(value: string) { return value === 'SCHOOL' ? 'Toàn trường' : value === 'CLASSROOM' ? 'Theo lớp' : value === 'STUDENT' ? 'Theo học sinh' : value }
function visibilityLabel(value: string) { return value === 'STAFF_ONLY' ? 'Nội bộ' : value === 'GUARDIAN' ? 'Phụ huynh' : value === 'STUDENT_AND_GUARDIAN' ? 'Gia đình & học sinh' : value }
function formatDateTime(value: string) { const date = new Date(value); return Number.isNaN(date.getTime()) ? value : date.toLocaleString('vi-VN', { dateStyle: 'medium', timeStyle: 'short' }) }
function formatDate(value: string) { const date = new Date(value); return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString('vi-VN', { weekday: 'long', day: '2-digit', month: '2-digit', year: 'numeric' }) }
function formatTime(value: string) { const date = new Date(value); return Number.isNaN(date.getTime()) ? value : date.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' }) }
function day(value: string) { return new Date(value).toLocaleDateString('vi-VN', { day: '2-digit' }) }
function month(value: string) { return new Date(value).toLocaleDateString('vi-VN', { month: 'short' }).replace('thg ', 'T') }
function defaultLocalDateTime(hoursAhead: number) { const date = new Date(Date.now() + hoursAhead * 3600000); date.setMinutes(Math.ceil(date.getMinutes() / 15) * 15, 0, 0); const local = new Date(date.getTime() - date.getTimezoneOffset() * 60000); return local.toISOString().slice(0, 16) }
function generateSlots(start: Date, end: Date, minutes: number) { const result: { startsAt: string; endsAt: string }[] = []; const step = minutes * 60000; for (let cursor = start.getTime(); cursor + step <= end.getTime() && result.length < 100; cursor += step) result.push({ startsAt: new Date(cursor).toISOString(), endsAt: new Date(cursor + step).toISOString() }); return result }
function uniqueClassOptions(assignments: TeachingAssignment[]) { const seen = new Set<string>(); return assignments.flatMap(item => { if (seen.has(item.classroom_id)) return []; seen.add(item.classroom_id); return [{ id: item.classroom_id, name: item.classroom_name }] }) }
