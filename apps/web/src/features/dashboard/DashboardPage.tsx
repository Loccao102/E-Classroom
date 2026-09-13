import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api'
import type { RiskStudent, Row, SchoolDashboard, TeacherDashboard } from '../../app/types'
import { AttendanceTrendChart, DistributionBars, DonutMetric, MiniBars, ScoreTrendChart, formatNumber } from '../../components/charts'
import { Badge, Card, DataTable, Empty, SectionHeading, Stat, Status } from '../../components/ui'

type PeriodRow = Row & { id: string; name: string; academic_year_id?: string }
type Dashboard = SchoolDashboard | TeacherDashboard

export function DashboardPage({ schoolId }: { schoolId: string }) {
  const [data, setData] = useState<Dashboard | null>(null)
  const [years, setYears] = useState<PeriodRow[]>([])
  const [semesters, setSemesters] = useState<PeriodRow[]>([])
  const [academicYearId, setAcademicYearId] = useState('')
  const [semesterId, setSemesterId] = useState('')
  const [customRange, setCustomRange] = useState(false)
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    void Promise.all([
      api<PeriodRow[]>(`/api/v1/schools/${schoolId}/academic-years`),
      api<PeriodRow[]>(`/api/v1/schools/${schoolId}/semesters`)
    ]).then(([yearRows, semesterRows]) => { setYears(yearRows); setSemesters(semesterRows) }).catch(() => undefined)
  }, [schoolId])

  const load = async () => {
    setLoading(true); setError('')
    try {
      const search = new URLSearchParams()
      if (customRange && from && to) { search.set('from', from); search.set('to', to) }
      else if (semesterId) search.set('semesterId', semesterId)
      else if (academicYearId) search.set('academicYearId', academicYearId)
      const suffix = search.size ? `?${search}` : ''
      setData(await api<Dashboard>(`/api/v1/schools/${schoolId}/reports/dashboard${suffix}`))
    } catch (err) { setError((err as Error).message); setData(null) }
    finally { setLoading(false) }
  }

  useEffect(() => { void load() }, [schoolId, academicYearId, semesterId, customRange && from, customRange && to])
  const filteredSemesters = useMemo(() => academicYearId ? semesters.filter(item => String(item.academic_year_id) === academicYearId) : semesters, [academicYearId, semesters])

  if (!data && loading) return <DashboardSkeleton />
  if (!data) return <Empty text="Chưa thể tải báo cáo. Hãy kiểm tra quyền truy cập hoặc dữ liệu kỳ học." />

  const filters = <div className="report-filters" aria-label="Bộ lọc báo cáo">
    {!customRange && <>
      <label><span>Năm học</span><select value={academicYearId} onChange={event => { setAcademicYearId(event.target.value); setSemesterId('') }}><option value="">Đang hoạt động</option>{years.map(row => <option value={row.id} key={row.id}>{row.name}</option>)}</select></label>
      <label><span>Học kỳ</span><select value={semesterId} onChange={event => setSemesterId(event.target.value)}><option value="">Cả năm / mặc định</option>{filteredSemesters.map(row => <option value={row.id} key={row.id}>{row.name}</option>)}</select></label>
    </>}
    {customRange && <><label><span>Từ ngày</span><input type="date" value={from} onChange={event => setFrom(event.target.value)} /></label><label><span>Đến ngày</span><input type="date" min={from} value={to} onChange={event => setTo(event.target.value)} /></label></>}
    <button className="quiet-button" onClick={() => { setCustomRange(value => !value); setSemesterId(''); setAcademicYearId('') }}>{customRange ? 'Dùng kỳ học' : 'Chọn khoảng ngày'}</button>
  </div>

  return <section className="workspace reporting-workspace">
    <SectionHeading eyebrow={data.scope === 'SCHOOL' ? 'Bức tranh toàn trường' : 'Không gian giáo viên'} title={data.scope === 'SCHOOL' ? 'Sức khỏe học tập & chuyên cần' : 'Hôm nay cần làm gì?'} description={`${data.range.label} · ${data.range.from} → ${data.range.to}`} actions={filters} />
    <Status tone="error">{error}</Status>
    {data.scope === 'SCHOOL' ? <SchoolView data={data} /> : <TeacherView data={data} />}
  </section>
}

function SchoolView({ data }: { data: SchoolDashboard }) {
  const attention = data.attention.filter(item => item.riskLevel !== 'LOW')
  return <>
    <div className="metric-hero-grid">
      <div className="metric-story"><span>Chuyên cần</span><strong>{formatNumber(data.attendance.attendanceRate)}%</strong><p>{formatNumber(data.attendance.absent)} lượt vắng · {formatNumber(data.attendance.excused)} lượt có phép</p></div>
      <DonutMetric value={data.attendance.attendanceRate} label="có mặt" detail={`${formatNumber(data.attendance.total)} lượt điểm danh trong kỳ`} />
      <div className="metric-story"><span>Học tập</span><strong>{formatNumber(data.scores.averageScore, 2)}</strong><p>thang 10 · {formatNumber(data.scores.publishedScores)} điểm đã công bố</p></div>
      <div className="headcount-strip"><Stat label="Học sinh" value={data.headcount.students} /><Stat label="Giáo viên" value={data.headcount.teachers} /><Stat label="Lớp" value={data.headcount.classrooms} /><Stat label="Phụ huynh" value={data.headcount.guardians} /></div>
    </div>

    <div className="report-grid-main">
      <Card title="Xu hướng chuyên cần" className="report-panel"><AttendanceTrendChart points={data.attendanceTrend} /></Card>
      <Card title="Xu hướng điểm trung bình" className="report-panel"><ScoreTrendChart points={data.scoreTrend} /></Card>
    </div>

    <div className="report-grid-asymmetric">
      <Card title="Phân bố điểm" className="report-panel"><DistributionBars rows={data.scoreDistribution} /></Card>
      <Card title="Hiệu suất theo môn" className="report-panel"><MiniBars rows={data.subjectPerformance as unknown as Record<string, unknown>[]} labelKey="subjectName" valueKey="averageScore" max={10} /></Card>
    </div>

    <section className="report-section">
      <SectionHeading eyebrow="Drill-down" title="Lớp học & học sinh cần chú ý" description="Mức cảnh báo luôn đi cùng lý do và ngưỡng, không dùng nhãn mơ hồ." />
      <div className="report-grid-main">
        <Card title="So sánh lớp" className="report-panel"><DataTable rows={data.classPerformance as unknown as Row[]} columns={[
          { key: 'classroomName', label: 'Lớp' },
          { key: 'attendanceRate', label: 'Chuyên cần', render: value => `${formatNumber(value)}%` },
          { key: 'averageScore', label: 'Điểm TB', render: value => formatNumber(value, 2) }
        ]} /></Card>
        <Card title={`Cần chú ý · ${attention.length}`} className="report-panel"><AttentionList rows={attention} /></Card>
      </div>
    </section>

    <div className="operational-strip">
      <OperationalItem label="Đơn nghỉ chờ duyệt" value={data.leave.submitted} tone={data.leave.submitted ? 'warning' : 'neutral'} />
      <OperationalItem label="Thông báo đã đăng" value={data.communication.announcements} />
      <OperationalItem label="Tin nhắn trong kỳ" value={data.communication.messages} />
      <OperationalItem label="Thông báo chưa đọc của tôi" value={data.communication.myUnread} tone={data.communication.myUnread ? 'info' : 'neutral'} />
    </div>

    <Card title="Vắng học gần đây" className="report-panel"><DataTable rows={data.recentAbsences} columns={[
      { key: 'studentName', label: 'Học sinh' }, { key: 'classroomName', label: 'Lớp' }, { key: 'date', label: 'Ngày' },
      { key: 'period', label: 'Tiết' }, { key: 'subjectName', label: 'Môn' }
    ]} /></Card>
  </>
}

function TeacherView({ data }: { data: TeacherDashboard }) {
  const queue = data.workQueue
  return <>
    <div className="teacher-command-grid">
      <Card title="Lịch dạy hôm nay" className="schedule-card"><div className="schedule-list">{data.todayClasses.length ? data.todayClasses.map((row, index) => <article className="schedule-item" key={`${String(row.assignmentId)}-${index}`}><time>Tiết {String(row.period)}</time><div><strong>{String(row.classroomName)}</strong><span>{String(row.subjectName)}{row.room ? ` · Phòng ${String(row.room)}` : ''}</span></div></article>) : <p className="muted">Không có tiết dạy theo thời khóa biểu hôm nay.</p>}</div></Card>
      <div className="queue-stack">
        <QueueItem value={queue.missingAttendance} label="Tiết chưa điểm danh" action="Ưu tiên xử lý" danger={queue.missingAttendance > 0} />
        <QueueItem value={queue.pendingLeave} label="Đơn nghỉ chờ duyệt" action="Cần phản hồi" danger={queue.pendingLeave > 0} />
        <QueueItem value={queue.draftAssessments} label="Bài đánh giá nháp" action="Tiếp tục hoàn thiện" />
      </div>
    </div>

    <div className="metric-hero-grid teacher-metrics"><div className="metric-story"><span>Học sinh đang phụ trách</span><strong>{formatNumber(data.headcount.students)}</strong><p>{formatNumber(data.headcount.classrooms)} lớp · {formatNumber(data.headcount.assignments)} phân công</p></div><DonutMetric value={data.attendance.attendanceRate} label="chuyên cần" /><div className="metric-story"><span>Điểm trung bình</span><strong>{formatNumber(data.scores.averageScore, 2)}</strong><p>{formatNumber(data.scores.publishedScores)} điểm đã công bố</p></div></div>

    <div className="report-grid-main"><Card title="Chuyên cần lớp phụ trách" className="report-panel"><AttendanceTrendChart points={data.attendanceTrend} /></Card><Card title="Tiến độ học tập" className="report-panel"><ScoreTrendChart points={data.scoreTrend} /></Card></div>
    <div className="report-grid-asymmetric"><Card title="Theo môn đang dạy" className="report-panel"><MiniBars rows={data.subjectPerformance as unknown as Record<string, unknown>[]} labelKey="subjectName" valueKey="averageScore" max={10} /></Card><Card title={`Học sinh cần chú ý · ${data.attention.filter(item => item.riskLevel !== 'LOW').length}`} className="report-panel"><AttentionList rows={data.attention.filter(item => item.riskLevel !== 'LOW')} /></Card></div>
    {data.missingAttendance.length > 0 && <Card title="Tiết chưa điểm danh" className="report-panel"><DataTable rows={data.missingAttendance} columns={[{ key: 'period', label: 'Tiết' }, { key: 'classroomName', label: 'Lớp' }, { key: 'subjectName', label: 'Môn' }]} /></Card>}
  </>
}

function AttentionList({ rows }: { rows: RiskStudent[] }) {
  if (!rows.length) return <p className="positive-empty">Không có học sinh vượt ngưỡng cảnh báo trong khoảng đang xem.</p>
  return <div className="attention-list">{rows.slice(0, 10).map(student => <article className="attention-row" key={student.studentId}><div className="avatar-dot" aria-hidden="true">{initials(student.fullName)}</div><div className="attention-copy"><div><strong>{student.fullName}</strong><span>{student.classroomName || '—'}</span></div><div className="factor-list">{student.factors.map(factor => <span key={`${student.studentId}-${factor.code}`}><i aria-hidden="true" />{factor.label}: {formatNumber(factor.value, 1)}{factor.code === 'SCORE' ? '' : '%'} · ngưỡng {formatNumber(factor.threshold, 1)}{factor.code === 'SCORE' ? '' : '%'}</span>)}</div></div><RiskBadge value={student.riskLevel} /></article>)}</div>
}

function RiskBadge({ value }: { value: string }) {
  const tone = value === 'HIGH' ? 'danger' : value === 'MEDIUM' ? 'warning' : 'success'
  return <Badge tone={tone}>{value === 'HIGH' ? 'Cao' : value === 'MEDIUM' ? 'Theo dõi' : 'Ổn'}</Badge>
}

function OperationalItem({ label, value, tone = 'neutral' }: { label: string; value: number; tone?: 'neutral' | 'warning' | 'info' }) {
  return <div className={`operational-item ${tone}`}><strong>{formatNumber(value)}</strong><span>{label}</span></div>
}

function QueueItem({ value, label, action, danger = false }: { value: number; label: string; action: string; danger?: boolean }) {
  return <article className={`queue-item ${danger ? 'needs-action' : ''}`}><strong>{value}</strong><div><b>{label}</b><span>{value ? action : 'Đã xử lý hết'}</span></div></article>
}

function DashboardSkeleton() {
  return <section className="workspace"><div className="skeleton-line wide" /><div className="metric-hero-grid"><div className="skeleton-block" /><div className="skeleton-block" /><div className="skeleton-block" /></div><div className="report-grid-main"><div className="skeleton-chart" /><div className="skeleton-chart" /></div></section>
}

function initials(name: string) {
  return name.split(/\s+/).filter(Boolean).slice(-2).map(part => part[0]).join('').toUpperCase()
}
