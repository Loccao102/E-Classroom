import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api'
import type { Student, TeachingAssignment } from '../../app/types'
import { Empty, SectionHeading, Status } from '../../components/ui'
import { StudentTimeline } from './StudentTimeline'

export function StudentRecordsPage({ schoolId, isAdmin, isTeacher }: { schoolId: string; isAdmin: boolean; isTeacher: boolean }) {
  const [students, setStudents] = useState<Student[]>([])
  const [selected, setSelected] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    setLoading(true); setError('')
    const load = async () => {
      if (isAdmin) return api<Student[]>(`/api/v1/schools/${schoolId}/students`)
      if (!isTeacher) return [] as Student[]
      const assignments = await api<TeachingAssignment[]>(`/api/v1/schools/${schoolId}/me/teaching-assignments`)
      const classroomIds = [...new Set(assignments.map(item => item.classroom_id))]
      const rosters = await Promise.all(classroomIds.map(id => api<Student[]>(`/api/v1/schools/${schoolId}/classrooms/${id}/roster`)))
      const unique = new Map<string, Student>()
      rosters.flat().forEach(student => unique.set(student.id, student))
      return [...unique.values()].sort((a, b) => a.full_name.localeCompare(b.full_name, 'vi'))
    }
    void load().then(rows => { setStudents(rows); setSelected(current => rows.some(row => row.id === current) ? current : rows[0]?.id || '') })
      .catch(err => { setStudents([]); setSelected(''); setError((err as Error).message) }).finally(() => setLoading(false))
  }, [schoolId, isAdmin, isTeacher])

  const student = useMemo(() => students.find(item => item.id === selected), [students, selected])
  if (loading) return <section className="workspace"><div className="skeleton-line wide" /><div className="skeleton-block" /></section>
  if (!students.length) return <Empty text={error || 'Chưa có học sinh nào trong phạm vi bạn được phân quyền.'} />

  return <section className="workspace student-records-workspace">
    <SectionHeading eyebrow="Hồ sơ học sinh" title={student?.full_name || 'Dòng thời gian học sinh'} description="Một dòng thời gian thống nhất cho chuyên cần, điểm số, nhận xét, rèn luyện và thông báo quan trọng." actions={<select className="student-record-select" value={selected} onChange={event => setSelected(event.target.value)}>{students.map(row => <option key={row.id} value={row.id}>{row.full_name} · {row.student_code}{row.classroom_name ? ` · ${row.classroom_name}` : ''}</option>)}</select>} />
    <Status tone="error">{error}</Status>
    {student && <div className="student-record-header"><div className="avatar-large">{initials(student.full_name)}</div><div><span className="eyebrow">{student.classroom_name || 'Học sinh'}</span><h3>{student.full_name}</h3><p>{student.student_code} · Những thay đổi nhạy cảm đều có lịch sử chỉnh sửa và người thực hiện.</p></div></div>}
    {selected && <StudentTimeline schoolId={schoolId} studentId={selected} canManageConduct />}
  </section>
}

function initials(name: string) { return name.split(/\s+/).filter(Boolean).slice(-2).map(part => part[0]).join('').toUpperCase() }
