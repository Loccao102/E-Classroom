import { FormEvent, useEffect, useState } from 'react'
import { api } from '../../api'
import type { Row, Student } from '../../app/types'
import { Badge, Card, DataTable, DialogForm, Field, Modal, Stat, Status } from '../../components/ui'

export function ParentPage({ schoolId }: { schoolId: string }) {
  const [children, setChildren] = useState<Student[]>([])
  const [selected, setSelected] = useState('')
  const [report, setReport] = useState<Row | null>(null)
  const [attendance, setAttendance] = useState<Row[]>([])
  const [scores, setScores] = useState<Row[]>([])
  const [comments, setComments] = useState<Row[]>([])
  const [leaveRows, setLeaveRows] = useState<Row[]>([])
  const [leaveOpen, setLeaveOpen] = useState(false)
  const [message, setMessage] = useState('')

  useEffect(() => {
    void api<Student[]>(`/api/v1/schools/${schoolId}/me/children`).then(rows => {
      setChildren(rows)
      if (rows[0]) setSelected(current => current || rows[0].id)
    })
  }, [schoolId])

  const loadChild = async () => {
    if (!selected) return
    const [reportData, attendanceRows, scoreRows, commentRows, requests] = await Promise.all([
      api<Row>(`/api/v1/schools/${schoolId}/reports/students/${selected}`),
      api<Row[]>(`/api/v1/schools/${schoolId}/students/${selected}/attendance`),
      api<Row[]>(`/api/v1/schools/${schoolId}/students/${selected}/scores`),
      api<Row[]>(`/api/v1/schools/${schoolId}/students/${selected}/comments`).catch(() => [] as Row[]),
      api<Row[]>(`/api/v1/schools/${schoolId}/students/${selected}/leave-requests`).catch(() => [] as Row[])
    ])
    setReport(reportData); setAttendance(attendanceRows); setScores(scoreRows); setComments(commentRows); setLeaveRows(requests)
  }
  useEffect(() => { void loadChild() }, [selected, schoolId])

  const attendanceSummary = (report?.attendanceSummary || []) as Row[]
  const count = (status: string) => attendanceSummary.find(row => row.status === status)?.count ?? 0
  const risk = (report?.risk || {}) as Row
  const student = (report?.student || {}) as Row

  return <section className="workspace">
    <div className="page-intro"><div><p className="eyebrow">Phụ huynh</p><h3>Theo dõi con</h3><p>Điểm danh, điểm số, nhận xét và xin phép nghỉ học trong một nơi.</p></div><div className="page-actions"><select value={selected} onChange={event => setSelected(event.target.value)}>{children.map(child => <option key={child.id} value={child.id}>{child.full_name} · {child.classroom_name || 'Chưa xếp lớp'}</option>)}</select><button className="primary" disabled={!selected} onClick={() => setLeaveOpen(true)}>Xin phép nghỉ học</button></div></div>
    <Status>{message}</Status>
    {selected && <>
      <div className="stats"><Stat label="Có mặt" value={count('PRESENT')} /><Stat label="Vắng" value={count('ABSENT')} /><Stat label="Có phép" value={count('EXCUSED')} /><Stat label="Mức cần chú ý" value={risk.level || 'LOW'} /></div>
      <div className="grid2">
        <Card title="Thông tin học sinh"><dl className="detail-list"><div><dt>Họ tên</dt><dd>{String(student.full_name || '—')}</dd></div><div><dt>Mã học sinh</dt><dd>{String(student.student_code || '—')}</dd></div><div><dt>Ngày sinh</dt><dd>{String(student.date_of_birth || '—')}</dd></div><div><dt>Trạng thái</dt><dd><Badge tone="success">{String(student.status || 'ACTIVE')}</Badge></dd></div></dl></Card>
        <Card title="Điểm trung bình theo môn"><DataTable rows={(report?.subjectAverages || []) as Row[]} columns={[{ key: 'subject_name', label: 'Môn học' }, { key: 'weighted_average', label: 'Điểm TB' }]} /></Card>
      </div>
      <Card title="Điểm đã công bố"><DataTable rows={scores} columns={[{ key: 'subject_name', label: 'Môn' }, { key: 'title', label: 'Bài đánh giá' }, { key: 'score', label: 'Điểm' }, { key: 'max_score', label: 'Thang điểm' }, { key: 'assessment_date', label: 'Ngày' }]} /></Card>
      <div className="grid2"><Card title="Lịch sử điểm danh"><DataTable rows={attendance} columns={[{ key: 'attendance_date', label: 'Ngày' }, { key: 'period', label: 'Tiết' }, { key: 'subject_name', label: 'Môn' }, { key: 'status', label: 'Trạng thái', render: value => <Badge tone={String(value) === 'ABSENT' ? 'danger' : String(value) === 'EXCUSED' ? 'info' : 'success'}>{String(value)}</Badge> }]} /></Card>
      <Card title="Nhận xét giáo viên"><DataTable rows={comments} columns={[{ key: 'created_at', label: 'Thời gian' }, { key: 'teacher_name', label: 'Giáo viên' }, { key: 'body', label: 'Nhận xét' }]} /></Card></div>
      <Card title="Đơn xin nghỉ"><DataTable rows={leaveRows} columns={[{ key: 'start_date', label: 'Từ ngày' }, { key: 'end_date', label: 'Đến ngày' }, { key: 'reason', label: 'Lý do' }, { key: 'status', label: 'Trạng thái', render: value => <Badge tone={String(value) === 'APPROVED' ? 'success' : String(value) === 'REJECTED' ? 'danger' : 'warning'}>{String(value)}</Badge> }]} /></Card>
    </>}
    <LeaveModal open={leaveOpen} onClose={() => setLeaveOpen(false)} schoolId={schoolId} studentId={selected} onSaved={async () => { setLeaveOpen(false); setMessage('Đã gửi đơn xin nghỉ tới giáo viên chủ nhiệm.'); await loadChild() }} />
  </section>
}

function LeaveModal({ open, onClose, schoolId, studentId, onSaved }: { open: boolean; onClose: () => void; schoolId: string; studentId: string; onSaved: () => Promise<void> }) {
  const today = new Date().toISOString().slice(0, 10)
  const [startDate, setStartDate] = useState(today)
  const [endDate, setEndDate] = useState(today)
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); if (!studentId) return
    setBusy(true); setError('')
    try {
      await api(`/api/v1/schools/${schoolId}/students/${studentId}/leave-requests`, { method: 'POST', body: JSON.stringify({ startDate, endDate, reason: reason.trim() }) })
      setReason(''); await onSaved()
    } catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }

  return <Modal title="Xin phép nghỉ học" open={open} onClose={onClose}><DialogForm onSubmit={submit} onCancel={onClose} submitLabel="Gửi đơn" busy={busy}><Status tone="error">{error}</Status><div className="form-grid"><Field label="Từ ngày"><input required type="date" value={startDate} onChange={event => setStartDate(event.target.value)} /></Field><Field label="Đến ngày"><input required type="date" min={startDate} value={endDate} onChange={event => setEndDate(event.target.value)} /></Field></div><Field label="Lý do"><textarea required maxLength={1000} rows={4} value={reason} onChange={event => setReason(event.target.value)} placeholder="Ví dụ: Con bị sốt, gia đình xin phép nghỉ một ngày." /></Field></DialogForm></Modal>
}
