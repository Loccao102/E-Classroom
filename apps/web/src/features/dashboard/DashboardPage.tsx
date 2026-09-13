import { useEffect, useState } from 'react'
import { api } from '../../api'
import type { Row } from '../../app/types'
import { Badge, Card, DataTable, Empty, Stat } from '../../components/ui'

export function DashboardPage({ schoolId }: { schoolId: string }) {
  const [data, setData] = useState<Row | null>(null)
  const [risks, setRisks] = useState<Row[]>([])

  useEffect(() => {
    void api<Row>(`/api/v1/schools/${schoolId}/reports/dashboard`).then(setData).catch(() => setData(null))
    void api<Row[]>(`/api/v1/schools/${schoolId}/reports/risks`).then(setRisks).catch(() => setRisks([]))
  }, [schoolId])

  if (!data) return <Empty text="Dashboard dành cho giáo viên và quản trị viên." />
  const attendance = (data.attendance || {}) as Row
  const attention = risks.filter(row => row.risk_level !== 'LOW').slice(0, 10)

  return <section className="workspace">
    <div className="page-intro"><div><p className="eyebrow">Tổng quan hôm nay</p><h3>Tình hình nhà trường</h3><p>Các chỉ số chính để phát hiện nhanh lớp và học sinh cần chú ý.</p></div></div>
    <div className="stats">
      <Stat label="Học sinh" value={data.students} />
      <Stat label="Giáo viên" value={data.teachers} />
      <Stat label="Lớp học" value={data.classrooms} />
      <Stat label="Điểm trung bình" value={data.averageScore} />
    </div>
    <div className="stats compact-stats">
      <Stat label="Lượt điểm danh" value={attendance.total} />
      <Stat label="Có mặt" value={attendance.present} />
      <Stat label="Vắng" value={attendance.absent} />
      <Stat label="Đi muộn" value={attendance.late} />
    </div>
    <div className="grid2">
      <Card title="Học sinh cần chú ý"><DataTable rows={attention} columns={[
        { key: 'full_name', label: 'Học sinh' },
        { key: 'absence_percent', label: 'Vắng (%)' },
        { key: 'average_score', label: 'Điểm TB' },
        { key: 'risk_level', label: 'Mức', render: value => <RiskBadge value={String(value)} /> }
      ]} /></Card>
      <Card title="Vắng học gần đây"><DataTable rows={(data.recentAbsences || []) as Row[]} columns={[
        { key: 'full_name', label: 'Học sinh' },
        { key: 'attendance_date', label: 'Ngày' },
        { key: 'period', label: 'Tiết' },
        { key: 'subject_name', label: 'Môn' }
      ]} /></Card>
    </div>
  </section>
}

function RiskBadge({ value }: { value: string }) {
  const tone = value === 'HIGH' ? 'danger' : value === 'MEDIUM' ? 'warning' : 'success'
  const label = value === 'HIGH' ? 'Cao' : value === 'MEDIUM' ? 'Trung bình' : 'Thấp'
  return <Badge tone={tone}>{label}</Badge>
}
