import { FormEvent, useEffect, useMemo, useState } from 'react'
import { api } from '../../api'
import type { Assessment, LeaveRequest, Row, Student, TeachingAssignment } from '../../app/types'
import { Badge, Card, DialogForm, Empty, Field, Modal, SectionHeading, Status } from '../../components/ui'

const attendanceStatuses = [
  ['PRESENT', 'Có mặt'], ['ABSENT', 'Vắng'], ['EXCUSED', 'Có phép'], ['LATE', 'Đi muộn'], ['EARLY_LEAVE', 'Về sớm']
] as const

export function TeacherPage({ schoolId }: { schoolId: string }) {
  const [assignments, setAssignments] = useState<TeachingAssignment[]>([])
  const [selected, setSelected] = useState('')
  const [roster, setRoster] = useState<Student[]>([])
  const [status, setStatus] = useState<Record<string, string>>({})
  const [session, setSession] = useState<Row | null>(null)
  const [assessments, setAssessments] = useState<Assessment[]>([])
  const [assessmentId, setAssessmentId] = useState('')
  const [scores, setScores] = useState<Record<string, string>>({})
  const [leaveRequests, setLeaveRequests] = useState<LeaveRequest[]>([])
  const [assessmentOpen, setAssessmentOpen] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [savingAttendance, setSavingAttendance] = useState(false)
  const [savingScores, setSavingScores] = useState(false)

  const active = useMemo(() => assignments.find(item => item.teaching_assignment_id === selected), [assignments, selected])
  const assessment = useMemo(() => assessments.find(item => item.id === assessmentId), [assessments, assessmentId])
  const maxScore = Number(assessment?.max_score || 10)

  useEffect(() => {
    void Promise.all([
      api<TeachingAssignment[]>(`/api/v1/schools/${schoolId}/me/teaching-assignments`),
      api<LeaveRequest[]>(`/api/v1/schools/${schoolId}/leave-requests/pending`).catch(() => [] as LeaveRequest[])
    ]).then(([assignmentRows, leaveRows]) => {
      setAssignments(assignmentRows); setLeaveRequests(leaveRows)
      setSelected(current => current || assignmentRows[0]?.teaching_assignment_id || '')
    })
  }, [schoolId])

  const loadAssignment = async () => {
    if (!active) { setRoster([]); return }
    const [students, assessmentRows] = await Promise.all([
      api<Student[]>(`/api/v1/schools/${schoolId}/classrooms/${active.classroom_id}/roster`),
      api<Assessment[]>(`/api/v1/schools/${schoolId}/teaching-assignments/${selected}/assessments`)
    ])
    setRoster(students)
    setStatus(Object.fromEntries(students.map(student => [student.id, 'PRESENT'])))
    setScores(Object.fromEntries(students.map(student => [student.id, ''])))
    setAssessments(assessmentRows)
    setAssessmentId(current => assessmentRows.some(item => item.id === current) ? current : assessmentRows[0]?.id || '')
    setSession(null)
  }
  useEffect(() => { void loadAssignment() }, [selected, schoolId])

  const createAttendance = async () => {
    if (!selected) return
    setError('')
    try {
      const result = await api<{ id: string }>(`/api/v1/schools/${schoolId}/attendance/sessions`, { method: 'POST', body: JSON.stringify({ teachingAssignmentId: selected, date: new Date().toISOString().slice(0, 10), period: 1 }) })
      setSession(await api<Row>(`/api/v1/attendance/sessions/${result.id}`)); setMessage('Đã mở phiên điểm danh hôm nay.')
    } catch (err) { setError((err as Error).message) }
  }

  const saveAttendance = async () => {
    const current = session?.session as Row | undefined
    if (!current?.id) return
    setSavingAttendance(true); setError('')
    try {
      await api(`/api/v1/attendance/sessions/${String(current.id)}/records`, { method: 'PUT', body: JSON.stringify({ version: Number(current.version || 0), records: roster.map(student => ({ studentId: student.id, status: status[student.id] || 'PRESENT', note: null })) }) })
      setSession(await api<Row>(`/api/v1/attendance/sessions/${String(current.id)}`)); setMessage('Đã lưu điểm danh. Các thay đổi quan trọng sẽ được thông báo cho phụ huynh.')
    } catch (err) { setError((err as Error).message) }
    finally { setSavingAttendance(false) }
  }

  const saveScores = async () => {
    if (!assessmentId) return
    const payload = roster.filter(student => scores[student.id] !== '').map(student => ({ studentId: student.id, score: Number(scores[student.id]) }))
    if (!payload.length) { setError('Hãy nhập ít nhất một điểm trước khi lưu.'); return }
    if (payload.some(item => !Number.isFinite(item.score) || item.score < 0 || item.score > maxScore)) { setError(`Điểm phải nằm trong khoảng 0–${maxScore}.`); return }
    setSavingScores(true); setError('')
    try {
      await api(`/api/v1/assessments/${assessmentId}/scores`, { method: 'PUT', body: JSON.stringify({ scores: payload, reason: 'Nhập điểm từ giao diện giáo viên' }) })
      setMessage(`Đã lưu ${payload.length} điểm nháp.`)
    } catch (err) { setError((err as Error).message) }
    finally { setSavingScores(false) }
  }

  const submitScores = async () => {
    if (!assessmentId) return
    setError('')
    try {
      await api(`/api/v1/assessments/${assessmentId}/submit`, { method: 'POST' })
      setAssessments(await api<Assessment[]>(`/api/v1/schools/${schoolId}/teaching-assignments/${selected}/assessments`))
      setMessage('Đã công bố điểm và gửi thông báo nội bộ tới phụ huynh.')
    } catch (err) { setError((err as Error).message) }
  }

  const reviewLeave = async (id: string, action: 'approve' | 'reject') => {
    setError('')
    try {
      await api(`/api/v1/leave-requests/${id}/${action}`, { method: 'POST' })
      setLeaveRequests(await api<LeaveRequest[]>(`/api/v1/schools/${schoolId}/leave-requests/pending`))
      setMessage(action === 'approve' ? 'Đã duyệt đơn nghỉ.' : 'Đã từ chối đơn nghỉ.')
    } catch (err) { setError((err as Error).message) }
  }

  if (!assignments.length) return <Empty text="Tài khoản chưa có phân công giảng dạy đang hoạt động." />

  return <section className="workspace teacher-workspace">
    <SectionHeading eyebrow="Không gian giáo viên" title="Lớp học của tôi" description="Điểm danh nhanh, nhập điểm và xử lý đơn nghỉ trong cùng một luồng." actions={<select className="assignment-select" value={selected} onChange={event => setSelected(event.target.value)}>{assignments.map(item => <option key={item.teaching_assignment_id} value={item.teaching_assignment_id}>{item.classroom_name} · {item.subject_name}</option>)}</select>} />
    <Status>{message}</Status><Status tone="error">{error}</Status>

    <div className="teacher-two-column">
      <Card title="Điểm danh hôm nay" actions={<div className="inline-actions"><button onClick={() => setStatus(Object.fromEntries(roster.map(student => [student.id, 'PRESENT'])))}>Tất cả có mặt</button>{!session ? <button className="primary" onClick={() => void createAttendance()}>Mở điểm danh</button> : <button className="primary" disabled={savingAttendance} onClick={() => void saveAttendance()}>{savingAttendance ? 'Đang lưu…' : 'Lưu điểm danh'}</button>}</div>} className="attendance-card">
        <div className="rollcall-meta"><span>{active?.classroom_name}</span><b>{roster.length} học sinh</b></div>
        <div className="rollcall-list">{roster.map(student => <div className="rollcall-row" key={student.id}><div className="student-identity"><span className="avatar-dot">{initials(student.full_name)}</span><div><strong>{student.full_name}</strong><small>{student.student_code}</small></div></div><label className="compact-field"><span className="sr-only">Trạng thái của {student.full_name}</span><select value={status[student.id] || 'PRESENT'} onChange={event => setStatus(current => ({ ...current, [student.id]: event.target.value }))}>{attendanceStatuses.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label></div>)}</div>
      </Card>

      <Card title="Đánh giá & nhập điểm" actions={<button onClick={() => setAssessmentOpen(true)}>+ Tạo bài đánh giá</button>} className="grading-card">
        <label className="field"><span>Bài đánh giá</span><select value={assessmentId} onChange={event => setAssessmentId(event.target.value)}><option value="">Chọn bài đánh giá</option>{assessments.map(item => <option key={item.id} value={item.id}>{item.title} · {statusLabel(item.status)}</option>)}</select></label>
        {assessment ? <><div className="assessment-strip"><div><Badge tone={assessment.status === 'DRAFT' ? 'warning' : assessment.status === 'LOCKED' ? 'neutral' : 'success'}>{statusLabel(assessment.status)}</Badge></div><strong>Thang {maxScore}</strong></div><div className="score-sheet"><div className="score-sheet-head"><span>Học sinh</span><span>Điểm</span></div>{roster.map(student => <label className="score-row" key={student.id}><span>{student.full_name}</span><input aria-label={`Điểm ${student.full_name}`} type="number" min="0" max={maxScore} step="0.1" disabled={assessment.status !== 'DRAFT'} value={scores[student.id] || ''} onChange={event => setScores(current => ({ ...current, [student.id]: event.target.value }))} placeholder={`0–${maxScore}`} /></label>)}</div>{assessment.status === 'DRAFT' && <div className="dialog-actions sheet-actions"><button disabled={savingScores} onClick={() => void saveScores()}>{savingScores ? 'Đang lưu…' : 'Lưu nháp'}</button><button className="primary" onClick={() => void submitScores()}>Công bố điểm</button></div>}</> : <Empty text="Chưa có bài đánh giá cho phân công này." />}
      </Card>
    </div>

    <Card title={`Đơn xin nghỉ chờ xử lý · ${leaveRequests.length}`} className="leave-review-card">{leaveRequests.length ? <div className="leave-list">{leaveRequests.map(row => <article className="leave-row" key={row.id}><div><strong>{row.student_name}</strong><span>{formatDate(row.start_date)} → {formatDate(row.end_date)}</span><p>{row.reason}</p></div><div className="inline-actions"><button onClick={() => void reviewLeave(row.id, 'reject')}>Từ chối</button><button className="primary" onClick={() => void reviewLeave(row.id, 'approve')}>Duyệt nghỉ</button></div></article>)}</div> : <p className="positive-empty">Không có đơn nghỉ đang chờ xử lý.</p>}</Card>

    <AssessmentModal open={assessmentOpen} schoolId={schoolId} teachingAssignmentId={selected} onClose={() => setAssessmentOpen(false)} onSaved={async id => { setAssessmentOpen(false); const rows = await api<Assessment[]>(`/api/v1/schools/${schoolId}/teaching-assignments/${selected}/assessments`); setAssessments(rows); setAssessmentId(id); setMessage('Đã tạo bài đánh giá mới.') }} />
  </section>
}

function AssessmentModal({ open, onClose, schoolId, teachingAssignmentId, onSaved }: { open: boolean; onClose: () => void; schoolId: string; teachingAssignmentId: string; onSaved: (id: string) => Promise<void> }) {
  const [title, setTitle] = useState('')
  const [category, setCategory] = useState('QUIZ')
  const [maxScore, setMaxScore] = useState('10')
  const [weight, setWeight] = useState('1')
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setBusy(true); setError('')
    try {
      const result = await api<{ id: string }>(`/api/v1/schools/${schoolId}/assessments`, { method: 'POST', body: JSON.stringify({ teachingAssignmentId, semesterId: null, title: title.trim(), category, maxScore: Number(maxScore), weight: Number(weight), assessmentDate: date }) })
      setTitle(''); await onSaved(result.id)
    } catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }
  return <Modal title="Tạo bài đánh giá" open={open} onClose={onClose}><DialogForm onSubmit={submit} onCancel={onClose} submitLabel="Tạo bài" busy={busy}><Status tone="error">{error}</Status><Field label="Tên bài đánh giá"><input required maxLength={255} value={title} onChange={event => setTitle(event.target.value)} placeholder="Ví dụ: Kiểm tra 15 phút · Chương 2" /></Field><div className="form-grid"><Field label="Loại"><select value={category} onChange={event => setCategory(event.target.value)}><option value="QUIZ">Kiểm tra ngắn</option><option value="MIDTERM">Giữa kỳ</option><option value="FINAL">Cuối kỳ</option><option value="ASSIGNMENT">Bài tập</option><option value="OTHER">Khác</option></select></Field><Field label="Ngày đánh giá"><input required type="date" value={date} onChange={event => setDate(event.target.value)} /></Field><Field label="Thang điểm"><input required type="number" min="1" step="0.5" value={maxScore} onChange={event => setMaxScore(event.target.value)} /></Field><Field label="Trọng số"><input required type="number" min="0.1" step="0.1" value={weight} onChange={event => setWeight(event.target.value)} /></Field></div></DialogForm></Modal>
}

function initials(name: string) { return name.split(/\s+/).filter(Boolean).slice(-2).map(part => part[0]).join('').toUpperCase() }
function statusLabel(value: string) { return value === 'DRAFT' ? 'Nháp' : value === 'SUBMITTED' ? 'Đã công bố' : value === 'LOCKED' ? 'Đã khóa' : value }
function formatDate(value: string) { const date = new Date(value); return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString('vi-VN') }
