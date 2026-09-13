import { FormEvent, useEffect, useState } from 'react'
import { api } from '../../api'
import type { Row, Student, StudentReport } from '../../app/types'
import { AttendanceTrendChart, MiniBars, formatNumber } from '../../components/charts'
import { Badge, Card, DataTable, DialogForm, Field, Modal, SectionHeading, Stat, Status } from '../../components/ui'
import { StudentTimeline } from '../timeline/StudentTimeline'

export function ParentPage({ schoolId, onOpenMessages }: { schoolId: string; onOpenMessages?: () => void }) {
  const [children, setChildren] = useState<Student[]>([])
  const [selected, setSelected] = useState('')
  const [report, setReport] = useState<StudentReport | null>(null)
  const [leaveRows, setLeaveRows] = useState<Row[]>([])
  const [leaveOpen, setLeaveOpen] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    void api<Student[]>(`/api/v1/schools/${schoolId}/me/children`).then(rows => {
      setChildren(rows); setSelected(current => current || rows[0]?.id || '')
    }).catch(err => setError((err as Error).message))
  }, [schoolId])

  const loadChild = async () => {
    if (!selected) return
    setLoading(true); setError('')
    try {
      const [reportData, requests] = await Promise.all([
        api<StudentReport>(`/api/v1/schools/${schoolId}/reports/students/${selected}`),
        api<Row[]>(`/api/v1/schools/${schoolId}/students/${selected}/leave-requests`).catch(() => [] as Row[])
      ])
      setReport(reportData); setLeaveRows(requests)
    } catch (err) { setError((err as Error).message); setReport(null) }
    finally { setLoading(false) }
  }
  useEffect(() => { void loadChild() }, [selected, schoolId])

  const child = children.find(item => item.id === selected)
  return <section className="workspace family-workspace">
    <SectionHeading eyebrow="Gia đình" title={child ? `Theo dõi ${child.full_name}` : 'Theo dõi con'} description="Những điều quan trọng về chuyên cần, kết quả học tập và phản hồi từ giáo viên." actions={<><select value={selected} onChange={event => setSelected(event.target.value)}>{children.map(item => <option key={item.id} value={item.id}>{item.full_name} · {item.classroom_name || 'Chưa xếp lớp'}</option>)}</select><button onClick={onOpenMessages}>Nhắn giáo viên</button><button className="primary" disabled={!selected} onClick={() => setLeaveOpen(true)}>Xin nghỉ học</button></>} />
    <Status>{message}</Status><Status tone="error">{error}</Status>
    {loading && !report ? <FamilySkeleton /> : report && <FamilyReport report={report} leaveRows={leaveRows} />}
    {selected && <StudentTimeline schoolId={schoolId} studentId={selected} />}
    <LeaveModal open={leaveOpen} onClose={() => setLeaveOpen(false)} schoolId={schoolId} studentId={selected} onSaved={async () => { setLeaveOpen(false); setMessage('Đã gửi đơn xin nghỉ tới giáo viên chủ nhiệm.'); await loadChild() }} />
  </section>
}

function FamilyReport({ report, leaveRows }: { report: StudentReport; leaveRows: Row[] }) {
  const risk = report.risk
  const student = report.student
  return <>
    <div className="family-hero">
      <div className="family-profile"><div className="avatar-large">{initials(String(student.full_name || 'HS'))}</div><div><span className="eyebrow">{String(student.classroom_name || 'Học sinh')}</span><h3>{String(student.full_name || '—')}</h3><p>{String(student.student_code || '')} · {report.range.label}</p></div></div>
      <div className="family-kpis"><Stat label="Chuyên cần" value={`${formatNumber(report.attendance.attendanceRate)}%`} hint={`${formatNumber(report.attendance.absent)} lượt vắng`} emphasis /><Stat label="Đi muộn" value={report.attendance.late} /><Stat label="Điểm công bố" value={report.subjectAverages.reduce((sum, subject) => sum + Number(subject.publishedScores || 0), 0)} /><div className="family-risk"><span>Cần chú ý</span><RiskBadge level={risk.riskLevel} /></div></div>
    </div>

    {risk.factors.length > 0 && <div className="attention-banner" role="status"><div><strong>Vì sao đang cần chú ý?</strong><p>Hệ thống dùng quy tắc minh bạch, không phải đánh giá AI mơ hồ.</p></div><div className="factor-pills">{risk.factors.map(factor => <span key={factor.code}>{factor.label}: <b>{formatNumber(factor.value, 1)}{factor.code === 'SCORE' ? '' : '%'}</b> · ngưỡng {formatNumber(factor.threshold, 1)}{factor.code === 'SCORE' ? '' : '%'}</span>)}</div></div>}

    <div className="report-grid-main"><Card title="Chuyên cần theo thời gian" className="report-panel"><AttendanceTrendChart points={report.attendanceTrend} /></Card><Card title="Điểm trung bình theo môn" className="report-panel"><MiniBars rows={report.subjectAverages as unknown as Record<string, unknown>[]} labelKey="subjectName" valueKey="weightedAverage" max={10} /></Card></div>

    <section className="report-section"><SectionHeading eyebrow="Gần đây" title="Hoạt động học tập" description="Điểm và điểm danh mới nhất, theo thứ tự thời gian." /><div className="report-grid-main"><Card title="Điểm mới công bố"><DataTable rows={report.recentScores} columns={[{ key: 'subjectName', label: 'Môn' }, { key: 'title', label: 'Bài đánh giá' }, { key: 'normalizedScore', label: 'Quy về /10', render: value => <strong>{formatNumber(value, 2)}</strong> }, { key: 'date', label: 'Ngày', render: value => formatDate(String(value ?? '')) }]} /></Card><Card title="Điểm danh gần đây"><DataTable rows={report.recentAttendance} columns={[{ key: 'date', label: 'Ngày', render: value => formatDate(String(value ?? '')) }, { key: 'subjectName', label: 'Môn' }, { key: 'period', label: 'Tiết' }, { key: 'status', label: 'Trạng thái', render: value => <AttendanceBadge value={String(value)} /> }]} /></Card></div></section>

    <div className="report-grid-main"><Card title="Nhận xét giáo viên"><div className="comment-stream">{report.recentComments.length ? report.recentComments.map((row, index) => <article key={String(row.id || index)}><div className="comment-meta"><strong>{String(row.teacherName || 'Giáo viên')}</strong><time>{formatDateTime(String(row.createdAt || ''))}</time></div><p>{String(row.body || '')}</p></article>) : <p className="positive-empty">Chưa có nhận xét mới.</p>}</div></Card><Card title="Đơn xin nghỉ"><DataTable rows={leaveRows} columns={[{ key: 'start_date', label: 'Từ ngày', render: value => formatDate(String(value ?? '')) }, { key: 'end_date', label: 'Đến ngày', render: value => formatDate(String(value ?? '')) }, { key: 'reason', label: 'Lý do' }, { key: 'status', label: 'Trạng thái', render: value => <LeaveBadge value={String(value)} /> }]} /></Card></div>
  </>
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
    try { await api(`/api/v1/schools/${schoolId}/students/${studentId}/leave-requests`, { method: 'POST', body: JSON.stringify({ startDate, endDate, reason: reason.trim() }) }); setReason(''); await onSaved() }
    catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }
  return <Modal title="Xin phép nghỉ học" open={open} onClose={onClose}><DialogForm onSubmit={submit} onCancel={onClose} submitLabel="Gửi đơn" busy={busy}><Status tone="error">{error}</Status><div className="form-grid"><Field label="Từ ngày"><input required type="date" value={startDate} onChange={event => setStartDate(event.target.value)} /></Field><Field label="Đến ngày"><input required type="date" min={startDate} value={endDate} onChange={event => setEndDate(event.target.value)} /></Field></div><Field label="Lý do"><textarea required maxLength={1000} rows={4} value={reason} onChange={event => setReason(event.target.value)} placeholder="Ví dụ: Con bị sốt, gia đình xin phép nghỉ một ngày." /></Field></DialogForm></Modal>
}

function RiskBadge({ level }: { level: string }) { return <Badge tone={level === 'HIGH' ? 'danger' : level === 'MEDIUM' ? 'warning' : 'success'}>{level === 'HIGH' ? 'Cao' : level === 'MEDIUM' ? 'Theo dõi' : 'Ổn định'}</Badge> }
function AttendanceBadge({ value }: { value: string }) { const labels: Record<string, string> = { PRESENT: 'Có mặt', ABSENT: 'Vắng', EXCUSED: 'Có phép', LATE: 'Đi muộn', EARLY_LEAVE: 'Về sớm' }; const tone = value === 'ABSENT' ? 'danger' : value === 'EXCUSED' || value === 'LATE' ? 'warning' : 'success'; return <Badge tone={tone}>{labels[value] || value}</Badge> }
function LeaveBadge({ value }: { value: string }) { return <Badge tone={value === 'APPROVED' ? 'success' : value === 'REJECTED' ? 'danger' : 'warning'}>{value === 'APPROVED' ? 'Đã duyệt' : value === 'REJECTED' ? 'Từ chối' : 'Chờ duyệt'}</Badge> }
function initials(name: string) { return name.split(/\s+/).filter(Boolean).slice(-2).map(part => part[0]).join('').toUpperCase() }
function formatDate(value: string) { const date = new Date(value); return Number.isNaN(date.getTime()) ? value || '—' : date.toLocaleDateString('vi-VN') }
function formatDateTime(value: string) { const date = new Date(value); return Number.isNaN(date.getTime()) ? value || '—' : date.toLocaleString('vi-VN', { dateStyle: 'short', timeStyle: 'short' }) }
function FamilySkeleton() { return <div className="family-skeleton"><div className="skeleton-block" /><div className="report-grid-main"><div className="skeleton-chart" /><div className="skeleton-chart" /></div></div> }
