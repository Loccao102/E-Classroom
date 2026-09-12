import { FormEvent, ReactNode, useEffect, useMemo, useState } from 'react'
import { api, login, tokens } from './api'

type Membership = { school_id: string; school_code: string; school_name: string; role: string }
type Me = { user: { id: string; email: string; full_name: string; platform_role: string }; memberships: Membership[] }
type Row = Record<string, any>

function Login({ onDone }: { onDone: () => void }) {
  const [email, setEmail] = useState('admin@eclassroom.local')
  const [password, setPassword] = useState('Admin123!')
  const [error, setError] = useState('')
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setError('')
    try { await login(email, password); onDone() } catch (err) { setError((err as Error).message) }
  }
  return <div className="login-page"><form className="login-card" onSubmit={submit}>
    <div className="brand-mark">EC</div><h1>E-Classroom</h1><p>School communication & student performance platform</p>
    <label>Email<input value={email} onChange={e => setEmail(e.target.value)} /></label>
    <label>Password<input type="password" value={password} onChange={e => setPassword(e.target.value)} /></label>
    {error && <div className="error">{error}</div>}<button className="primary">Sign in</button>
    <small>Local demo: admin@eclassroom.local / Admin123!</small>
  </form></div>
}

export default function App() {
  const [me, setMe] = useState<Me | null>(null)
  const [loading, setLoading] = useState(true)
  const [schoolId, setSchoolId] = useState('')
  const [tab, setTab] = useState('dashboard')
  const [notifications, setNotifications] = useState<Row[]>([])
  const [toast, setToast] = useState('')

  const loadMe = async () => {
    setLoading(true)
    try {
      const data = await api<Me>('/api/v1/me')
      setMe(data)
      if (!schoolId && data.memberships[0]) setSchoolId(String(data.memberships[0].school_id))
    } catch { tokens.clear(); setMe(null) } finally { setLoading(false) }
  }

  useEffect(() => { if (tokens.access()) void loadMe(); else setLoading(false) }, [])
  useEffect(() => { if (me) void api<Row[]>('/api/v1/notifications?limit=30').then(setNotifications).catch(() => undefined) }, [me, schoolId])
  useEffect(() => {
    const token = tokens.access(); if (!token || !me) return
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
    const ws = new WebSocket(`${scheme}://${location.host}/realtime/v1/ws?access_token=${encodeURIComponent(token)}`)
    ws.onmessage = event => {
      try {
        const message = JSON.parse(event.data)
        setToast(message.data?.title || message.eventType || 'New update')
        void api<Row[]>('/api/v1/notifications?limit=30').then(setNotifications)
      } catch { /* ignore malformed pushed message */ }
    }
    return () => ws.close()
  }, [me])

  if (loading) return <div className="center">Loading…</div>
  if (!me) return <Login onDone={() => void loadMe()} />
  const memberships = me.memberships.filter(m => String(m.school_id) === schoolId)
  const roles = memberships.map(m => m.role)
  const isAdmin = roles.includes('SCHOOL_ADMIN') || me.user.platform_role === 'SUPER_ADMIN'
  const isTeacher = roles.includes('TEACHER')
  const isParent = roles.includes('PARENT')
  const isStudent = roles.includes('STUDENT')
  const school = me.memberships.find(m => String(m.school_id) === schoolId)
  const tabs = ['dashboard', ...(isAdmin ? ['admin'] : []), ...(isTeacher || isAdmin ? ['teacher'] : []), ...(isParent ? ['parent'] : []), ...(isStudent ? ['student'] : []), 'communication', 'notifications']

  return <div className="app-shell">
    {toast && <div className="toast" onClick={() => setToast('')}>{toast}</div>}
    <aside><div className="logo"><span>EC</span><div><b>E-Classroom</b><small>{school?.school_name || 'Select school'}</small></div></div>
      <nav>{tabs.map(item => <button key={item} className={tab === item ? 'active' : ''} onClick={() => setTab(item)}>{item[0].toUpperCase() + item.slice(1)}</button>)}</nav>
      <div className="profile"><b>{me.user.full_name}</b><span>{roles.join(' · ') || me.user.platform_role}</span><button onClick={() => { tokens.clear(); setMe(null) }}>Sign out</button></div>
    </aside>
    <main><header><div><h2>{tab[0].toUpperCase() + tab.slice(1)}</h2><p>{school?.school_name}</p></div>
      <select value={schoolId} onChange={e => setSchoolId(e.target.value)}>{me.memberships.filter((m, i, all) => all.findIndex(x => x.school_id === m.school_id) === i).map(m => <option key={m.school_id} value={m.school_id}>{m.school_name}</option>)}</select>
    </header>
      {!schoolId ? <Empty text="No school membership" /> : <>
        {tab === 'dashboard' && <Dashboard schoolId={schoolId} />}
        {tab === 'admin' && <Admin schoolId={schoolId} />}
        {tab === 'teacher' && <Teacher schoolId={schoolId} />}
        {tab === 'parent' && <Parent schoolId={schoolId} />}
        {tab === 'student' && <Student schoolId={schoolId} />}
        {tab === 'communication' && <Communication schoolId={schoolId} canPublish={isAdmin || isTeacher} />}
        {tab === 'notifications' && <Notifications rows={notifications} reload={() => { void api<Row[]>('/api/v1/notifications?limit=30').then(setNotifications) }} />}
      </>}
    </main>
  </div>
}

function Dashboard({ schoolId }: { schoolId: string }) {
  const [data, setData] = useState<Row | null>(null); const [risks, setRisks] = useState<Row[]>([])
  useEffect(() => { void api<Row>(`/api/v1/schools/${schoolId}/reports/dashboard`).then(setData).catch(() => setData(null)); void api<Row[]>(`/api/v1/schools/${schoolId}/reports/risks`).then(setRisks).catch(() => setRisks([])) }, [schoolId])
  if (!data) return <Empty text="Dashboard is available to school staff." />
  return <section><div className="stats"><Stat label="Students" value={data.students} /><Stat label="Teachers" value={data.teachers} /><Stat label="Classes" value={data.classrooms} /><Stat label="Average score" value={data.averageScore} /></div>
    <div className="grid2"><Card title="Attendance"><pre>{JSON.stringify(data.attendance, null, 2)}</pre></Card><Card title="Students requiring attention"><Table rows={risks.filter(r => r.risk_level !== 'LOW').slice(0, 10)} /></Card></div>
    <Card title="Recent absences"><Table rows={data.recentAbsences || []} /></Card></section>
}

function Admin({ schoolId }: { schoolId: string }) {
  const [resource, setResource] = useState('students'); const [rows, setRows] = useState<Row[]>([]); const [message, setMessage] = useState('')
  const load = () => api<Row[]>(`/api/v1/schools/${schoolId}/${resource}`).then(setRows).catch(e => setMessage(e.message))
  useEffect(() => { void load() }, [schoolId, resource])
  const create = async (kind: 'student' | 'teacher' | 'guardian' | 'subject') => {
    try {
      if (kind === 'subject') { const code = prompt('Subject code?'); const name = prompt('Subject name?'); if (code && name) await api(`/api/v1/schools/${schoolId}/subjects`, { method: 'POST', body: JSON.stringify({ code, name }) }) }
      if (kind === 'student') { const code = prompt('Student code?'); const fullName = prompt('Full name?'); const email = prompt('Login email (optional)?') || null; const password = email ? prompt('Initial password?') : null; if (code && fullName) await api(`/api/v1/schools/${schoolId}/students`, { method: 'POST', body: JSON.stringify({ code, fullName, email, password }) }) }
      if (kind === 'teacher') { const code = prompt('Teacher code?'); const fullName = prompt('Full name?'); const email = prompt('Login email (optional)?') || null; const password = email ? prompt('Initial password?') : null; if (code && fullName) await api(`/api/v1/schools/${schoolId}/teachers`, { method: 'POST', body: JSON.stringify({ code, fullName, email, password }) }) }
      if (kind === 'guardian') { const fullName = prompt('Guardian name?'); const email = prompt('Login email?'); const password = email ? prompt('Initial password?') : null; if (fullName) await api(`/api/v1/schools/${schoolId}/guardians`, { method: 'POST', body: JSON.stringify({ fullName, email, password }) }) }
      setMessage('Saved'); await load()
    } catch (err) { setMessage((err as Error).message) }
  }
  return <section><div className="toolbar"><select value={resource} onChange={e => setResource(e.target.value)}>{['students', 'teachers', 'guardians', 'classrooms', 'subjects', 'academic-years', 'semesters', 'teaching-assignments', 'timetable'].map(x => <option key={x}>{x}</option>)}</select>
    <button onClick={() => void create('student')}>+ Student</button><button onClick={() => void create('teacher')}>+ Teacher</button><button onClick={() => void create('guardian')}>+ Guardian</button><button onClick={() => void create('subject')}>+ Subject</button></div>
    {message && <p>{message}</p>}<Card title={`Manage ${resource}`}><Table rows={rows} /></Card><p className="hint">Enrollment, guardian links, teaching assignments and timetable are supported by the REST API; IDs listed here can be used for bulk administration and imports.</p></section>
}

function Teacher({ schoolId }: { schoolId: string }) {
  const [assignments, setAssignments] = useState<Row[]>([]); const [selected, setSelected] = useState(''); const [roster, setRoster] = useState<Row[]>([])
  const [session, setSession] = useState<Row | null>(null); const [status, setStatus] = useState<Record<string, string>>({}); const [message, setMessage] = useState('')
  const [assessments, setAssessments] = useState<Row[]>([]); const [assessmentId, setAssessmentId] = useState(''); const [scores, setScores] = useState<Record<string, string>>({}); const [leaveRequests, setLeaveRequests] = useState<Row[]>([])
  useEffect(() => { void api<Row[]>(`/api/v1/schools/${schoolId}/me/teaching-assignments`).then(r => { setAssignments(r); if (r[0]) setSelected(String(r[0].teaching_assignment_id)) }).catch(() => setAssignments([])); void api<Row[]>(`/api/v1/schools/${schoolId}/leave-requests/pending`).then(setLeaveRequests).catch(() => setLeaveRequests([])) }, [schoolId])
  const active = assignments.find(x => String(x.teaching_assignment_id) === selected)
  useEffect(() => {
    if (!active) return
    void api<Row[]>(`/api/v1/schools/${schoolId}/classrooms/${active.classroom_id}/roster`).then(r => { setRoster(r); setStatus(Object.fromEntries(r.map(x => [x.id, 'PRESENT']))); setScores(Object.fromEntries(r.map(x => [x.id, '']))) })
    void api<Row[]>(`/api/v1/schools/${schoolId}/teaching-assignments/${selected}/assessments`).then(r => { setAssessments(r); setAssessmentId(r[0]?.id ? String(r[0].id) : '') })
  }, [selected, schoolId])
  const createAttendance = async () => { if (!selected) return; try { const result = await api<{ id: string }>(`/api/v1/schools/${schoolId}/attendance/sessions`, { method: 'POST', body: JSON.stringify({ teachingAssignmentId: selected, date: new Date().toISOString().slice(0, 10), period: 1 }) }); setSession(await api<Row>(`/api/v1/attendance/sessions/${result.id}`)); setMessage('Attendance session created') } catch (err) { setMessage((err as Error).message) } }
  const saveAttendance = async () => { if (!session) return; try { const current = session.session; await api(`/api/v1/attendance/sessions/${current.id}/records`, { method: 'PUT', body: JSON.stringify({ version: current.version, records: roster.map(r => ({ studentId: r.id, status: status[r.id], note: null })) }) }); setSession(await api<Row>(`/api/v1/attendance/sessions/${current.id}`)); setMessage('Attendance saved') } catch (err) { setMessage((err as Error).message) } }
  const createAssessment = async () => { if (!selected) return; const title = prompt('Assessment title?'); if (!title) return; try { const result = await api<{ id: string }>(`/api/v1/schools/${schoolId}/assessments`, { method: 'POST', body: JSON.stringify({ teachingAssignmentId: selected, semesterId: null, title, category: 'QUIZ', maxScore: 10, weight: 1, assessmentDate: new Date().toISOString().slice(0, 10) }) }); setAssessmentId(result.id); setAssessments(await api<Row[]>(`/api/v1/schools/${schoolId}/teaching-assignments/${selected}/assessments`)); setMessage('Assessment created') } catch (err) { setMessage((err as Error).message) } }
  const saveScores = async () => { if (!assessmentId) return; const payload = roster.filter(r => scores[r.id] !== '').map(r => ({ studentId: r.id, score: Number(scores[r.id]) })); if (!payload.length) return; try { await api(`/api/v1/assessments/${assessmentId}/scores`, { method: 'PUT', body: JSON.stringify({ scores: payload, reason: 'Teacher entry' }) }); setMessage('Scores saved') } catch (err) { setMessage((err as Error).message) } }
  const submitScores = async () => { if (!assessmentId) return; try { await api(`/api/v1/assessments/${assessmentId}/submit`, { method: 'POST' }); setMessage('Scores submitted and parents notified') } catch (err) { setMessage((err as Error).message) } }
  const reviewLeave = async (id: string, action: 'approve' | 'reject') => { try { await api(`/api/v1/leave-requests/${id}/${action}`, { method: 'POST' }); setLeaveRequests(await api<Row[]>(`/api/v1/schools/${schoolId}/leave-requests/pending`)) } catch (err) { setMessage((err as Error).message) } }
  return <section><Card title="My teaching assignments"><select value={selected} onChange={e => setSelected(e.target.value)}><option value="">Choose assignment</option>{assignments.map(a => <option key={a.teaching_assignment_id} value={a.teaching_assignment_id}>{a.classroom_name} · {a.subject_name}</option>)}</select></Card>
    {message && <p>{message}</p>}<div className="grid2"><Card title="Attendance"><div className="toolbar"><button className="primary" onClick={() => void createAttendance()}>Create today session</button>{session && <button onClick={() => void saveAttendance()}>Save</button>}</div><table><thead><tr><th>Student</th><th>Status</th></tr></thead><tbody>{roster.map(r => <tr key={r.id}><td>{r.full_name}<small>{r.student_code}</small></td><td><select value={status[r.id] || 'PRESENT'} onChange={e => setStatus({ ...status, [r.id]: e.target.value })}>{['PRESENT', 'ABSENT', 'EXCUSED', 'LATE', 'EARLY_LEAVE'].map(s => <option key={s}>{s}</option>)}</select></td></tr>)}</tbody></table></Card>
      <Card title="Assessments & grading"><div className="toolbar"><button onClick={() => void createAssessment()}>+ Assessment</button><select value={assessmentId} onChange={e => setAssessmentId(e.target.value)}><option value="">Choose assessment</option>{assessments.map(a => <option key={a.id} value={a.id}>{a.title} · {a.status}</option>)}</select></div><table><thead><tr><th>Student</th><th>Score / 10</th></tr></thead><tbody>{roster.map(r => <tr key={r.id}><td>{r.full_name}</td><td><input type="number" min="0" max="10" step="0.1" value={scores[r.id] || ''} onChange={e => setScores({ ...scores, [r.id]: e.target.value })} /></td></tr>)}</tbody></table><div className="toolbar"><button onClick={() => void saveScores()}>Save scores</button><button className="primary" onClick={() => void submitScores()}>Submit & notify</button></div></Card></div>
    <Card title="Pending leave requests"><div className="table-wrap"><table><thead><tr><th>Student</th><th>Dates</th><th>Reason</th><th>Action</th></tr></thead><tbody>{leaveRequests.map(r => <tr key={r.id}><td>{r.student_name}</td><td>{String(r.start_date)} → {String(r.end_date)}</td><td>{r.reason}</td><td><button onClick={() => void reviewLeave(String(r.id), 'approve')}>Approve</button> <button onClick={() => void reviewLeave(String(r.id), 'reject')}>Reject</button></td></tr>)}</tbody></table></div></Card>
  </section>
}

function Parent({ schoolId }: { schoolId: string }) {
  const [children, setChildren] = useState<Row[]>([]); const [selected, setSelected] = useState(''); const [report, setReport] = useState<Row | null>(null); const [attendance, setAttendance] = useState<Row[]>([]); const [scores, setScores] = useState<Row[]>([])
  useEffect(() => { void api<Row[]>(`/api/v1/schools/${schoolId}/me/children`).then(r => { setChildren(r); if (r[0]) setSelected(String(r[0].id)) }) }, [schoolId])
  useEffect(() => { if (!selected) return; void api<Row>(`/api/v1/schools/${schoolId}/reports/students/${selected}`).then(setReport); void api<Row[]>(`/api/v1/schools/${schoolId}/students/${selected}/attendance`).then(setAttendance); void api<Row[]>(`/api/v1/schools/${schoolId}/students/${selected}/scores`).then(setScores) }, [selected, schoolId])
  const submitLeave = async () => { if (!selected) return; const start = prompt('Start date YYYY-MM-DD?', new Date().toISOString().slice(0, 10)); const end = prompt('End date YYYY-MM-DD?', start || ''); const reason = prompt('Reason?'); if (start && end && reason) await api(`/api/v1/schools/${schoolId}/students/${selected}/leave-requests`, { method: 'POST', body: JSON.stringify({ startDate: start, endDate: end, reason }) }) }
  return <section><div className="toolbar"><select value={selected} onChange={e => setSelected(e.target.value)}>{children.map(c => <option key={c.id} value={c.id}>{c.full_name} · {c.classroom_name || 'No class'}</option>)}</select><button onClick={() => void submitLeave()}>Submit leave request</button></div>
    {report && <div className="grid2"><Card title="Student profile"><pre>{JSON.stringify(report.student, null, 2)}</pre></Card><Card title="Risk & attendance"><pre>{JSON.stringify({ risk: report.risk, attendance: report.attendanceSummary }, null, 2)}</pre></Card></div>}
    <Card title="Scores"><Table rows={scores} /></Card><Card title="Attendance history"><Table rows={attendance} /></Card></section>
}

function Student({ schoolId }: { schoolId: string }) {
  const [student, setStudent] = useState<Row | null>(null); const [report, setReport] = useState<Row | null>(null)
  useEffect(() => { void api<Row>(`/api/v1/schools/${schoolId}/me/student`).then(async profile => { setStudent(profile); if (profile?.id) setReport(await api<Row>(`/api/v1/schools/${schoolId}/reports/students/${profile.id}`)) }).catch(() => undefined) }, [schoolId])
  if (!student) return <Empty text="No student profile linked to this account." />
  return <section><Card title={`${student.full_name} · ${student.student_code}`}><pre>{JSON.stringify(report, null, 2)}</pre></Card></section>
}

function Communication({ schoolId, canPublish }: { schoolId: string; canPublish: boolean }) {
  const [rows, setRows] = useState<Row[]>([]); const [conversations, setConversations] = useState<Row[]>([]); const [selected, setSelected] = useState(''); const [messages, setMessages] = useState<Row[]>([])
  const load = () => { void api<Row[]>(`/api/v1/schools/${schoolId}/announcements`).then(setRows); void api<Row[]>(`/api/v1/schools/${schoolId}/conversations`).then(setConversations).catch(() => setConversations([])) }
  useEffect(() => { load() }, [schoolId])
  useEffect(() => { if (selected) void api<Row[]>(`/api/v1/conversations/${selected}/messages`).then(setMessages) }, [selected])
  const publish = async () => { const title = prompt('Announcement title?'); const body = prompt('Message?'); if (title && body) { await api(`/api/v1/schools/${schoolId}/announcements`, { method: 'POST', body: JSON.stringify({ title, body, targetType: 'SCHOOL', targetId: null }) }); load() } }
  const send = async () => { if (!selected) return; const body = prompt('Message?'); if (body) { await api(`/api/v1/conversations/${selected}/messages`, { method: 'POST', body: JSON.stringify({ body }) }); setMessages(await api<Row[]>(`/api/v1/conversations/${selected}/messages`)) } }
  return <section>{canPublish && <div className="toolbar"><button className="primary" onClick={() => void publish()}>Publish school announcement</button></div>}<div className="cards">{rows.map(r => <article key={r.id} className="announcement"><small>{new Date(r.published_at).toLocaleString()}</small><h3>{r.title}</h3><p>{r.body}</p></article>)}</div>
    <Card title="Conversations"><select value={selected} onChange={e => setSelected(e.target.value)}><option value="">Choose conversation</option>{conversations.map(c => <option key={c.id} value={c.id}>{c.subject || 'Conversation'}</option>)}</select>{selected && <><Table rows={messages} /><button onClick={() => void send()}>Send message</button></>}</Card></section>
}

function Notifications({ rows, reload }: { rows: Row[]; reload: () => void }) {
  const read = async (id: string) => { await api(`/api/v1/notifications/${id}/read`, { method: 'POST' }); reload() }
  return <section><div className="cards">{rows.map(r => <article key={r.id} className={`notification ${r.read_at ? '' : 'unread'}`} onClick={() => void read(String(r.id))}><small>{r.type} · {new Date(r.created_at).toLocaleString()}</small><h3>{r.title}</h3><p>{r.body}</p></article>)}</div></section>
}

function Stat({ label, value }: { label: string; value: any }) { return <div className="stat"><span>{label}</span><strong>{String(value ?? 0)}</strong></div> }
function Card({ title, children }: { title: string; children: ReactNode }) { return <div className="card"><div className="card-title">{title}</div>{children}</div> }
function Empty({ text }: { text: string }) { return <div className="empty">{text}</div> }
function Table({ rows }: { rows: Row[] }) {
  const keys = useMemo(() => rows[0] ? Object.keys(rows[0]).slice(0, 8) : [], [rows])
  if (!rows.length) return <Empty text="No data yet" />
  return <div className="table-wrap"><table><thead><tr>{keys.map(k => <th key={k}>{k.replaceAll('_', ' ')}</th>)}</tr></thead><tbody>{rows.map((row, index) => <tr key={row.id || index}>{keys.map(k => <td key={k}>{row[k] === null || row[k] === undefined ? '—' : String(row[k])}</td>)}</tr>)}</tbody></table></div>
}
