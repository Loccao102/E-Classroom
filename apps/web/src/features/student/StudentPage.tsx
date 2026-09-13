import { useEffect, useState } from 'react'
import { api } from '../../api'
import type { Row, StudentReport } from '../../app/types'
import { AttendanceTrendChart, MiniBars, formatNumber } from '../../components/charts'
import { Badge, Card, DataTable, Empty, SectionHeading, Stat, Status } from '../../components/ui'
import { StudentTimeline } from '../timeline/StudentTimeline'

export function StudentPage({ schoolId }: { schoolId: string }) {
  const [report, setReport] = useState<StudentReport | null>(null)
  const [studentId, setStudentId] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    setLoading(true); setError(''); setStudentId('')
    void api<Row>(`/api/v1/schools/${schoolId}/me/student`).then(profile => {
      const id = String(profile.id || '')
      if (!id) throw new Error('Tài khoản chưa liên kết hồ sơ học sinh.')
      setStudentId(id)
      return api<StudentReport>(`/api/v1/schools/${schoolId}/reports/students/${id}`)
    }).then(setReport).catch(err => setError((err as Error).message)).finally(() => setLoading(false))
  }, [schoolId])

  if (loading) return <StudentSkeleton />
  if (!report) return <Empty text={error || 'Tài khoản chưa liên kết hồ sơ học sinh.'} />
  const student = report.student

  return <section className="workspace student-workspace">
    <SectionHeading eyebrow="Không gian học sinh" title={`Chào ${firstName(String(student.full_name || 'bạn'))}`} description={`${String(student.classroom_name || 'Học sinh')} · ${report.range.label}`} />
    <Status tone="error">{error}</Status>

    <div className="student-hero">
      <div><p className="eyebrow">Tóm tắt học tập</p><h3>{String(student.full_name || '—')}</h3><p>Thay vì chỉ nhìn một con số, hãy theo dõi xu hướng để biết mình đang tiến bộ ở đâu.</p></div>
      <div className="student-kpis"><Stat label="Chuyên cần" value={`${formatNumber(report.attendance.attendanceRate)}%`} /><Stat label="Vắng" value={report.attendance.absent} /><Stat label="Đi muộn" value={report.attendance.late} /><div><span>Mức cần chú ý</span><RiskBadge level={report.risk.riskLevel} /></div></div>
    </div>

    {report.risk.factors.length > 0 && <div className="student-guidance"><strong>Điều nên cải thiện trước</strong><div>{report.risk.factors.map(factor => <span key={factor.code}>{factor.label} · hiện tại <b>{formatNumber(factor.value, 1)}{factor.code === 'SCORE' ? '' : '%'}</b></span>)}</div></div>}

    <div className="report-grid-main"><Card title="Nhịp chuyên cần"><AttendanceTrendChart points={report.attendanceTrend} /></Card><Card title="Điểm trung bình theo môn"><MiniBars rows={report.subjectAverages as unknown as Record<string, unknown>[]} labelKey="subjectName" valueKey="weightedAverage" max={10} /></Card></div>

    <section className="report-section"><SectionHeading eyebrow="Mới nhất" title="Kết quả & hoạt động" description="Chỉ hiển thị điểm đã được giáo viên công bố." /><div className="report-grid-main"><Card title="Điểm mới"><DataTable rows={report.recentScores} columns={[{ key: 'subjectName', label: 'Môn' }, { key: 'title', label: 'Bài đánh giá' }, { key: 'normalizedScore', label: 'Điểm /10', render: value => <strong>{formatNumber(value, 2)}</strong> }, { key: 'date', label: 'Ngày', render: value => formatDate(String(value ?? '')) }]} /></Card><Card title="Điểm danh gần đây"><DataTable rows={report.recentAttendance} columns={[{ key: 'date', label: 'Ngày', render: value => formatDate(String(value ?? '')) }, { key: 'subjectName', label: 'Môn' }, { key: 'period', label: 'Tiết' }, { key: 'status', label: 'Trạng thái', render: value => <AttendanceBadge value={String(value)} /> }]} /></Card></div></section>

    <Card title="Nhận xét từ giáo viên"><div className="comment-stream">{report.recentComments.length ? report.recentComments.map((row, index) => <article key={String(row.id || index)}><div className="comment-meta"><strong>{String(row.teacherName || 'Giáo viên')}</strong><time>{formatDateTime(String(row.createdAt || ''))}</time></div><p>{String(row.body || '')}</p></article>) : <p className="positive-empty">Chưa có nhận xét mới.</p>}</div></Card>
    {studentId && <StudentTimeline schoolId={schoolId} studentId={studentId} />}
  </section>
}

function RiskBadge({ level }: { level: string }) { return <Badge tone={level === 'HIGH' ? 'danger' : level === 'MEDIUM' ? 'warning' : 'success'}>{level === 'HIGH' ? 'Cần ưu tiên' : level === 'MEDIUM' ? 'Nên theo dõi' : 'Ổn định'}</Badge> }
function AttendanceBadge({ value }: { value: string }) { const labels: Record<string, string> = { PRESENT: 'Có mặt', ABSENT: 'Vắng', EXCUSED: 'Có phép', LATE: 'Đi muộn', EARLY_LEAVE: 'Về sớm' }; return <Badge tone={value === 'ABSENT' ? 'danger' : value === 'PRESENT' ? 'success' : 'warning'}>{labels[value] || value}</Badge> }
function firstName(value: string) { return value.trim().split(/\s+/).at(-1) || value }
function formatDate(value: string) { const date = new Date(value); return Number.isNaN(date.getTime()) ? value || '—' : date.toLocaleDateString('vi-VN') }
function formatDateTime(value: string) { const date = new Date(value); return Number.isNaN(date.getTime()) ? value || '—' : date.toLocaleString('vi-VN', { dateStyle: 'short', timeStyle: 'short' }) }
function StudentSkeleton() { return <section className="workspace"><div className="skeleton-line wide" /><div className="student-hero skeleton-block" /><div className="report-grid-main"><div className="skeleton-chart" /><div className="skeleton-chart" /></div></section> }
