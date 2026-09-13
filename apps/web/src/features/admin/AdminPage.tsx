import { FormEvent, useEffect, useMemo, useState } from 'react'
import { api } from '../../api'
import type { Row } from '../../app/types'
import { Card, DataTable, DialogForm, Field, Modal, Status } from '../../components/ui'

type CreateKind = 'student' | 'teacher' | 'guardian' | 'subject'

const resources = [
  ['students', 'Học sinh'], ['teachers', 'Giáo viên'], ['guardians', 'Phụ huynh'], ['classrooms', 'Lớp học'],
  ['subjects', 'Môn học'], ['academic-years', 'Năm học'], ['semesters', 'Học kỳ'],
  ['teaching-assignments', 'Phân công giảng dạy'], ['timetable', 'Thời khóa biểu']
] as const

export function AdminPage({ schoolId }: { schoolId: string }) {
  const [resource, setResource] = useState('students')
  const [rows, setRows] = useState<Row[]>([])
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [createKind, setCreateKind] = useState<CreateKind | null>(null)

  const load = async () => {
    setLoading(true); setError('')
    try { setRows(await api<Row[]>(`/api/v1/schools/${schoolId}/${resource}`)) }
    catch (err) { setError((err as Error).message) }
    finally { setLoading(false) }
  }

  useEffect(() => { void load() }, [schoolId, resource])
  const resourceLabel = useMemo(() => resources.find(([value]) => value === resource)?.[1] || resource, [resource])

  return <section className="workspace">
    <div className="page-intro"><div><p className="eyebrow">Quản trị nhà trường</p><h3>Dữ liệu học vụ</h3><p>Quản lý dữ liệu nền mà không cần nhập UUID hoặc dùng công cụ kỹ thuật.</p></div></div>
    <div className="toolbar admin-toolbar">
      <select value={resource} onChange={event => setResource(event.target.value)}>{resources.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select>
      <button onClick={() => setCreateKind('student')}>+ Học sinh</button>
      <button onClick={() => setCreateKind('teacher')}>+ Giáo viên</button>
      <button onClick={() => setCreateKind('guardian')}>+ Phụ huynh</button>
      <button onClick={() => setCreateKind('subject')}>+ Môn học</button>
    </div>
    <Status>{message}</Status><Status tone="error">{error}</Status>
    <Card title={resourceLabel} actions={<button onClick={() => void load()}>{loading ? 'Đang tải…' : 'Làm mới'}</button>}>
      {loading && !rows.length ? <div className="skeleton-block" /> : <DataTable rows={rows} />}
    </Card>
    <p className="hint">Luồng liên kết phụ huynh, xếp lớp, phân công giáo viên và thời khóa biểu sẽ được chuyển thành wizard ở bước tiếp theo của frontend productization.</p>
    <CreateResourceModal schoolId={schoolId} kind={createKind} onClose={() => setCreateKind(null)} onSaved={async text => { setCreateKind(null); setMessage(text); await load() }} />
  </section>
}

function CreateResourceModal({ schoolId, kind, onClose, onSaved }: { schoolId: string; kind: CreateKind | null; onClose: () => void; onSaved: (message: string) => Promise<void> }) {
  const [code, setCode] = useState('')
  const [fullName, setFullName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => { setCode(''); setFullName(''); setEmail(''); setPassword(''); setError('') }, [kind])
  const title = kind === 'student' ? 'Thêm học sinh' : kind === 'teacher' ? 'Thêm giáo viên' : kind === 'guardian' ? 'Thêm phụ huynh' : 'Thêm môn học'

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); if (!kind) return
    setBusy(true); setError('')
    try {
      if (kind === 'subject') {
        await api(`/api/v1/schools/${schoolId}/subjects`, { method: 'POST', body: JSON.stringify({ code: code.trim(), name: fullName.trim() }) })
      } else if (kind === 'student') {
        await api(`/api/v1/schools/${schoolId}/students`, { method: 'POST', body: JSON.stringify({ code: code.trim(), fullName: fullName.trim(), email: email.trim() || null, password: email.trim() ? password || null : null }) })
      } else if (kind === 'teacher') {
        await api(`/api/v1/schools/${schoolId}/teachers`, { method: 'POST', body: JSON.stringify({ code: code.trim(), fullName: fullName.trim(), email: email.trim() || null, password: email.trim() ? password || null : null }) })
      } else {
        await api(`/api/v1/schools/${schoolId}/guardians`, { method: 'POST', body: JSON.stringify({ fullName: fullName.trim(), email: email.trim() || null, password: email.trim() ? password || null : null }) })
      }
      await onSaved('Đã tạo dữ liệu thành công.')
    } catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }

  return <Modal open={kind !== null} title={title} onClose={onClose}><DialogForm onSubmit={submit} onCancel={onClose} busy={busy} submitLabel="Tạo mới">
    <Status tone="error">{error}</Status>
    {(kind === 'student' || kind === 'teacher' || kind === 'subject') && <Field label={kind === 'subject' ? 'Mã môn học' : 'Mã hồ sơ'}><input required value={code} onChange={event => setCode(event.target.value)} /></Field>}
    <Field label={kind === 'subject' ? 'Tên môn học' : 'Họ và tên'}><input required value={fullName} onChange={event => setFullName(event.target.value)} /></Field>
    {kind !== 'subject' && <><Field label="Email đăng nhập" hint="Có thể để trống nếu chưa cấp tài khoản."><input type="email" value={email} onChange={event => setEmail(event.target.value)} /></Field>{email && <Field label="Mật khẩu ban đầu"><input type="password" minLength={8} value={password} onChange={event => setPassword(event.target.value)} /></Field>}</>}
  </DialogForm></Modal>
}
