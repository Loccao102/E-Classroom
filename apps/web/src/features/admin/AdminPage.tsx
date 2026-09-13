import { FormEvent, useEffect, useMemo, useState } from 'react'
import { api } from '../../api'
import type { Row } from '../../app/types'
import { Card, DataTable, DialogForm, Field, Modal, SectionHeading, Status } from '../../components/ui'
import { AccountManagementPanel } from './AccountManagementPanel'
import { SetupWizard, type SetupKind } from './SetupWizard'

type CreateKind = 'student' | 'teacher' | 'guardian' | 'subject'

const resources = [
  ['students', 'Học sinh'], ['teachers', 'Giáo viên'], ['guardians', 'Phụ huynh'], ['classrooms', 'Lớp học'],
  ['subjects', 'Môn học'], ['academic-years', 'Năm học'], ['semesters', 'Học kỳ'],
  ['teaching-assignments', 'Phân công giảng dạy'], ['timetable', 'Thời khóa biểu']
] as const

const setupSteps: { kind: SetupKind; step: string; title: string; copy: string }[] = [
  { kind: 'academic-year', step: '01', title: 'Năm học', copy: 'Mốc thời gian nền cho lớp và báo cáo.' },
  { kind: 'semester', step: '02', title: 'Học kỳ', copy: 'Chia phạm vi đánh giá và phân công.' },
  { kind: 'classroom', step: '03', title: 'Lớp học', copy: 'Tạo lớp và gán giáo viên chủ nhiệm.' },
  { kind: 'guardian-link', step: '04', title: 'Liên kết gia đình', copy: 'Ghép phụ huynh với đúng học sinh.' },
  { kind: 'enrollment', step: '05', title: 'Xếp lớp', copy: 'Đưa học sinh vào lớp đang hoạt động.' },
  { kind: 'assignment', step: '06', title: 'Phân công', copy: 'Giáo viên × lớp × môn × học kỳ.' },
  { kind: 'timetable', step: '07', title: 'Thời khóa biểu', copy: 'Đặt thứ, tiết, phòng và hiệu lực.' }
]

export function AdminPage({ schoolId }: { schoolId: string }) {
  const [resource, setResource] = useState('students')
  const [rows, setRows] = useState<Row[]>([])
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [createKind, setCreateKind] = useState<CreateKind | null>(null)
  const [setupKind, setSetupKind] = useState<SetupKind | null>(null)

  const load = async () => {
    setLoading(true); setError('')
    try { setRows(await api<Row[]>(`/api/v1/schools/${schoolId}/${resource}`)) }
    catch (err) { setError((err as Error).message) }
    finally { setLoading(false) }
  }
  useEffect(() => { void load() }, [schoolId, resource])
  const resourceLabel = useMemo(() => resources.find(([value]) => value === resource)?.[1] || resource, [resource])

  return <section className="workspace admin-workspace">
    <SectionHeading eyebrow="Quản trị nhà trường" title="Thiết lập học vụ không cần UUID" description="Tạo người dùng, lớp, quan hệ gia đình và lịch học theo một chuỗi nghiệp vụ rõ ràng." actions={<div className="inline-actions"><button onClick={() => setCreateKind('student')}>+ Học sinh</button><button onClick={() => setCreateKind('teacher')}>+ Giáo viên</button><button onClick={() => setCreateKind('guardian')}>+ Phụ huynh</button><button className="primary" onClick={() => setCreateKind('subject')}>+ Môn học</button></div>} />
    <Status>{message}</Status><Status tone="error">{error}</Status>

    <section className="setup-journey" aria-labelledby="setup-title"><div className="setup-copy"><p className="eyebrow">Guided setup</p><h3 id="setup-title">Từ dữ liệu rời rạc thành một lớp học hoạt động</h3><p>Đi theo thứ tự gợi ý khi khởi tạo trường mới, hoặc mở bất kỳ bước nào để bổ sung dữ liệu.</p></div><div className="setup-steps">{setupSteps.map(item => <button key={item.kind} onClick={() => setSetupKind(item.kind)}><span>{item.step}</span><div><strong>{item.title}</strong><small>{item.copy}</small></div><em>→</em></button>)}</div></section>

    <AccountManagementPanel schoolId={schoolId} />

    <section className="resource-browser"><div className="resource-toolbar"><div><span className="eyebrow">Dữ liệu hiện có</span><h3>{resourceLabel}</h3></div><div className="inline-actions"><select value={resource} onChange={event => setResource(event.target.value)}>{resources.map(([value,label]) => <option key={value} value={value}>{label}</option>)}</select><button onClick={() => void load()}>{loading ? 'Đang tải…' : 'Làm mới'}</button></div></div>
      <Card title={`${rows.length} bản ghi`}>{loading && !rows.length ? <div className="skeleton-block" /> : <DataTable rows={rows} />}</Card>
    </section>

    <CreateResourceModal schoolId={schoolId} kind={createKind} onClose={() => setCreateKind(null)} onSaved={async text => { setCreateKind(null); setMessage(text); await load() }} />
    <SetupWizard schoolId={schoolId} kind={setupKind} onClose={() => setSetupKind(null)} onSaved={async text => { setSetupKind(null); setMessage(text); await load() }} />
  </section>
}

function CreateResourceModal({ schoolId, kind, onClose, onSaved }: { schoolId: string; kind: CreateKind | null; onClose: () => void; onSaved: (message: string) => Promise<void> }) {
  const [code, setCode] = useState('')
  const [fullName, setFullName] = useState('')
  const [email, setEmail] = useState('')
  const [phone, setPhone] = useState('')
  const [password, setPassword] = useState('')
  const [dateOfBirth, setDateOfBirth] = useState('')
  const [gender, setGender] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => { setCode(''); setFullName(''); setEmail(''); setPhone(''); setPassword(''); setDateOfBirth(''); setGender(''); setError('') }, [kind])
  const title = kind === 'student' ? 'Thêm học sinh' : kind === 'teacher' ? 'Thêm giáo viên' : kind === 'guardian' ? 'Thêm phụ huynh' : 'Thêm môn học'

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); if (!kind) return
    setBusy(true); setError('')
    try {
      if (kind === 'subject') await api(`/api/v1/schools/${schoolId}/subjects`, { method: 'POST', body: JSON.stringify({ code: code.trim(), name: fullName.trim() }) })
      else if (kind === 'student') await api(`/api/v1/schools/${schoolId}/students`, { method: 'POST', body: JSON.stringify({ code: code.trim(), fullName: fullName.trim(), dateOfBirth: dateOfBirth || null, gender: gender || null, email: email.trim() || null, password: email.trim() ? password || null : null }) })
      else if (kind === 'teacher') await api(`/api/v1/schools/${schoolId}/teachers`, { method: 'POST', body: JSON.stringify({ code: code.trim(), fullName: fullName.trim(), email: email.trim() || null, phone: phone.trim() || null, password: email.trim() ? password || null : null }) })
      else await api(`/api/v1/schools/${schoolId}/guardians`, { method: 'POST', body: JSON.stringify({ fullName: fullName.trim(), email: email.trim() || null, phone: phone.trim() || null, password: email.trim() ? password || null : null }) })
      await onSaved('Đã tạo dữ liệu thành công.')
    } catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }

  return <Modal open={kind !== null} title={title} onClose={onClose}><DialogForm onSubmit={submit} onCancel={onClose} busy={busy} submitLabel="Tạo mới"><Status tone="error">{error}</Status>
    {(kind === 'student' || kind === 'teacher' || kind === 'subject') && <Field label={kind === 'subject' ? 'Mã môn học' : 'Mã hồ sơ'}><input required value={code} onChange={event => setCode(event.target.value)} /></Field>}
    <Field label={kind === 'subject' ? 'Tên môn học' : 'Họ và tên'}><input required value={fullName} onChange={event => setFullName(event.target.value)} /></Field>
    {kind === 'student' && <div className="form-grid"><Field label="Ngày sinh"><input type="date" value={dateOfBirth} onChange={event => setDateOfBirth(event.target.value)} /></Field><Field label="Giới tính"><select value={gender} onChange={event => setGender(event.target.value)}><option value="">Chưa chọn</option><option value="MALE">Nam</option><option value="FEMALE">Nữ</option><option value="OTHER">Khác</option></select></Field></div>}
    {kind !== 'subject' && <><div className="form-grid"><Field label="Email đăng nhập" hint="Có thể để trống nếu chưa cấp tài khoản."><input type="email" value={email} onChange={event => setEmail(event.target.value)} /></Field>{kind !== 'student' && <Field label="Số điện thoại"><input value={phone} onChange={event => setPhone(event.target.value)} /></Field>}</div>{email && <Field label="Mật khẩu ban đầu" hint="Tối thiểu 10 ký tự, có chữ hoa, chữ thường và số."><input required type="password" minLength={10} maxLength={128} value={password} onChange={event => setPassword(event.target.value)} /></Field>}</>}
  </DialogForm></Modal>
}
