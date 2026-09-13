import { FormEvent, useEffect, useState } from 'react'
import { api } from '../../api'
import type { Row } from '../../app/types'
import { DialogForm, Field, Modal, Status } from '../../components/ui'

export type SetupKind = 'academic-year' | 'semester' | 'classroom' | 'guardian-link' | 'enrollment' | 'assignment' | 'timetable'

type Catalog = {
  students: Row[]; guardians: Row[]; teachers: Row[]; classrooms: Row[]; subjects: Row[];
  years: Row[]; semesters: Row[]; assignments: Row[]
}
const emptyCatalog: Catalog = { students: [], guardians: [], teachers: [], classrooms: [], subjects: [], years: [], semesters: [], assignments: [] }

export function SetupWizard({ schoolId, kind, onClose, onSaved }: { schoolId: string; kind: SetupKind | null; onClose: () => void; onSaved: (message: string) => Promise<void> }) {
  const [catalog, setCatalog] = useState<Catalog>(emptyCatalog)
  const [values, setValues] = useState<Record<string, string>>({})
  const [primary, setPrimary] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const today = new Date().toISOString().slice(0, 10)

  useEffect(() => {
    if (!kind) return
    setValues({ startDate: today, validFrom: today, relationship: 'PARENT', weekday: '2', period: '1' }); setPrimary(false); setError('')
    void Promise.all([
      api<Row[]>(`/api/v1/schools/${schoolId}/students`), api<Row[]>(`/api/v1/schools/${schoolId}/guardians`),
      api<Row[]>(`/api/v1/schools/${schoolId}/teachers`), api<Row[]>(`/api/v1/schools/${schoolId}/classrooms`),
      api<Row[]>(`/api/v1/schools/${schoolId}/subjects`), api<Row[]>(`/api/v1/schools/${schoolId}/academic-years`),
      api<Row[]>(`/api/v1/schools/${schoolId}/semesters`), api<Row[]>(`/api/v1/schools/${schoolId}/teaching-assignments`)
    ]).then(([students, guardians, teachers, classrooms, subjects, years, semesters, assignments]) => setCatalog({ students, guardians, teachers, classrooms, subjects, years, semesters, assignments })).catch(err => setError((err as Error).message))
  }, [kind, schoolId])

  const set = (key: string, value: string) => setValues(current => ({ ...current, [key]: value }))
  const fallback = (key: keyof Catalog) => String(catalog[key][0]?.id || '')
  const value = (key: string, catalogKey?: keyof Catalog) => values[key] ?? (catalogKey ? fallback(catalogKey) : '')

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); if (!kind) return
    setBusy(true); setError('')
    try {
      if (kind === 'academic-year') await api(`/api/v1/schools/${schoolId}/academic-years`, { method: 'POST', body: JSON.stringify({ name: value('name'), startDate: value('startDate'), endDate: value('endDate') }) })
      if (kind === 'semester') await api(`/api/v1/schools/${schoolId}/semesters`, { method: 'POST', body: JSON.stringify({ academicYearId: value('academicYearId', 'years'), name: value('name'), startDate: value('startDate'), endDate: value('endDate') }) })
      if (kind === 'classroom') await api(`/api/v1/schools/${schoolId}/classrooms`, { method: 'POST', body: JSON.stringify({ academicYearId: value('academicYearId', 'years'), gradeLevelId: null, code: value('code'), name: value('name'), homeroomTeacherId: value('teacherId', 'teachers') || null }) })
      if (kind === 'guardian-link') await api(`/api/v1/schools/${schoolId}/student-guardians`, { method: 'POST', body: JSON.stringify({ studentId: value('studentId', 'students'), guardianId: value('guardianId', 'guardians'), relationship: value('relationship') || 'PARENT', primaryContact: primary }) })
      if (kind === 'enrollment') await api(`/api/v1/schools/${schoolId}/enrollments`, { method: 'POST', body: JSON.stringify({ classroomId: value('classroomId', 'classrooms'), studentId: value('studentId', 'students'), startDate: value('startDate') }) })
      if (kind === 'assignment') await api(`/api/v1/schools/${schoolId}/teaching-assignments`, { method: 'POST', body: JSON.stringify({ teacherId: value('teacherId', 'teachers'), classroomId: value('classroomId', 'classrooms'), subjectId: value('subjectId', 'subjects'), semesterId: value('semesterId', 'semesters') || null }) })
      if (kind === 'timetable') await api(`/api/v1/schools/${schoolId}/timetable`, { method: 'POST', body: JSON.stringify({ teachingAssignmentId: value('assignmentId', 'assignments'), weekday: Number(value('weekday')), period: Number(value('period')), room: value('room') || null, validFrom: value('validFrom') || null, validTo: value('validTo') || null }) })
      await onSaved(successMessage(kind))
    } catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }

  return <Modal open={kind !== null} title={kind ? title(kind) : ''} onClose={onClose}><DialogForm onSubmit={submit} onCancel={onClose} busy={busy} submitLabel="Hoàn tất"><Status tone="error">{error}</Status>
    {kind === 'academic-year' && <><Field label="Tên năm học"><input required value={value('name')} onChange={e => set('name', e.target.value)} placeholder="2026–2027" /></Field><DateRange values={values} set={set} /></>}
    {kind === 'semester' && <><SelectField label="Năm học" rows={catalog.years} selected={value('academicYearId', 'years')} onChange={v => set('academicYearId', v)} labelKey="name" /><Field label="Tên học kỳ"><input required value={value('name')} onChange={e => set('name', e.target.value)} placeholder="Học kỳ 1" /></Field><DateRange values={values} set={set} /></>}
    {kind === 'classroom' && <><SelectField label="Năm học" rows={catalog.years} selected={value('academicYearId', 'years')} onChange={v => set('academicYearId', v)} labelKey="name" /><div className="form-grid"><Field label="Mã lớp"><input required value={value('code')} onChange={e => set('code', e.target.value)} placeholder="10A1" /></Field><Field label="Tên lớp"><input required value={value('name')} onChange={e => set('name', e.target.value)} placeholder="Lớp 10A1" /></Field></div><SelectField label="Giáo viên chủ nhiệm" optional rows={catalog.teachers} selected={value('teacherId', 'teachers')} onChange={v => set('teacherId', v)} labelKey="full_name" /></>}
    {kind === 'guardian-link' && <><SelectField label="Học sinh" rows={catalog.students} selected={value('studentId', 'students')} onChange={v => set('studentId', v)} labelKey="full_name" secondaryKey="student_code" /><SelectField label="Phụ huynh" rows={catalog.guardians} selected={value('guardianId', 'guardians')} onChange={v => set('guardianId', v)} labelKey="full_name" secondaryKey="email" /><Field label="Quan hệ"><select value={value('relationship') || 'PARENT'} onChange={e => set('relationship', e.target.value)}><option value="PARENT">Cha/Mẹ</option><option value="GUARDIAN">Người giám hộ</option><option value="OTHER">Khác</option></select></Field><label className="check-row"><input type="checkbox" checked={primary} onChange={e => setPrimary(e.target.checked)} /><span>Liên hệ chính của học sinh</span></label></>}
    {kind === 'enrollment' && <><SelectField label="Học sinh" rows={catalog.students} selected={value('studentId', 'students')} onChange={v => set('studentId', v)} labelKey="full_name" secondaryKey="student_code" /><SelectField label="Lớp" rows={catalog.classrooms} selected={value('classroomId', 'classrooms')} onChange={v => set('classroomId', v)} labelKey="name" secondaryKey="code" /><Field label="Ngày bắt đầu"><input required type="date" value={value('startDate')} onChange={e => set('startDate', e.target.value)} /></Field></>}
    {kind === 'assignment' && <><SelectField label="Giáo viên" rows={catalog.teachers} selected={value('teacherId', 'teachers')} onChange={v => set('teacherId', v)} labelKey="full_name" secondaryKey="teacher_code" /><SelectField label="Lớp" rows={catalog.classrooms} selected={value('classroomId', 'classrooms')} onChange={v => set('classroomId', v)} labelKey="name" /><SelectField label="Môn học" rows={catalog.subjects} selected={value('subjectId', 'subjects')} onChange={v => set('subjectId', v)} labelKey="name" secondaryKey="code" /><SelectField optional label="Học kỳ" rows={catalog.semesters} selected={value('semesterId', 'semesters')} onChange={v => set('semesterId', v)} labelKey="name" /></>}
    {kind === 'timetable' && <><SelectField label="Phân công" rows={catalog.assignments} selected={value('assignmentId', 'assignments')} onChange={v => set('assignmentId', v)} labelKey="classroom_name" combinedKeys={['subject_name','teacher_name']} /><div className="form-grid"><Field label="Thứ"><select value={value('weekday') || '2'} onChange={e => set('weekday', e.target.value)}>{[['1','Thứ hai'],['2','Thứ ba'],['3','Thứ tư'],['4','Thứ năm'],['5','Thứ sáu'],['6','Thứ bảy'],['7','Chủ nhật']].map(([v,l]) => <option key={v} value={v}>{l}</option>)}</select></Field><Field label="Tiết"><input required type="number" min="1" max="20" value={value('period') || '1'} onChange={e => set('period', e.target.value)} /></Field></div><Field label="Phòng học"><input value={value('room')} onChange={e => set('room', e.target.value)} placeholder="Ví dụ: P.204" /></Field><div className="form-grid"><Field label="Hiệu lực từ"><input type="date" value={value('validFrom')} onChange={e => set('validFrom', e.target.value)} /></Field><Field label="Đến ngày"><input type="date" min={value('validFrom')} value={value('validTo')} onChange={e => set('validTo', e.target.value)} /></Field></div></>}
  </DialogForm></Modal>
}

function SelectField({ label, rows, selected, onChange, labelKey, secondaryKey, combinedKeys, optional = false }: { label: string; rows: Row[]; selected: string; onChange: (value: string) => void; labelKey: string; secondaryKey?: string; combinedKeys?: string[]; optional?: boolean }) {
  return <Field label={label}><select required={!optional} value={selected} onChange={e => onChange(e.target.value)}>{optional && <option value="">Không chọn</option>}{rows.map((row,index) => <option key={String(row.id || index)} value={String(row.id || '')}>{String(row[labelKey] || '—')}{secondaryKey && row[secondaryKey] ? ` · ${String(row[secondaryKey])}` : ''}{combinedKeys?.map(key => row[key] ? ` · ${String(row[key])}` : '').join('')}</option>)}</select></Field>
}
function DateRange({ values, set }: { values: Record<string,string>; set: (key:string,value:string) => void }) { return <div className="form-grid"><Field label="Bắt đầu"><input required type="date" value={values.startDate || ''} onChange={e => set('startDate',e.target.value)} /></Field><Field label="Kết thúc"><input required type="date" min={values.startDate || undefined} value={values.endDate || ''} onChange={e => set('endDate',e.target.value)} /></Field></div> }
function title(kind: SetupKind) { const map: Record<SetupKind,string> = { 'academic-year':'Tạo năm học','semester':'Tạo học kỳ','classroom':'Tạo lớp học','guardian-link':'Liên kết phụ huynh – học sinh','enrollment':'Xếp học sinh vào lớp','assignment':'Phân công giảng dạy','timetable':'Thêm tiết vào thời khóa biểu' }; return map[kind] }
function successMessage(kind: SetupKind) { const map: Record<SetupKind,string> = { 'academic-year':'Đã tạo năm học.','semester':'Đã tạo học kỳ.','classroom':'Đã tạo lớp học.','guardian-link':'Đã liên kết phụ huynh với học sinh.','enrollment':'Đã xếp học sinh vào lớp.','assignment':'Đã tạo phân công giảng dạy.','timetable':'Đã cập nhật thời khóa biểu.' }; return map[kind] }
