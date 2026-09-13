import { FormEvent, ReactNode, useEffect, useMemo, useState } from 'react'
import { api, login, tokens } from './api'

type Membership = { school_id: string; school_code: string; school_name: string; role: string }
type Me = { user: { id: string; email: string; full_name: string; platform_role: string }; memberships: Membership[] }
type Row = Record<string, any>
type Preference = { category: string; inAppEnabled: boolean; realtimeEnabled: boolean }
type NotificationPage = { items: Row[]; nextCursor?: { beforeCreatedAt: string; beforeId: string } | null }

const notificationCategories = ['ALL', 'ATTENDANCE', 'LEAVE', 'SCORE', 'ANNOUNCEMENT', 'COMMENT', 'MESSAGE', 'SYSTEM']

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
  const [unreadCount, setUnreadCount] = useState(0)
  const [toast, setToast] = useState('')

  const loadMe = async () => {
    setLoading(true)
    try {
      const data = await api<Me>('/api/v1/me')
      setMe(data)
      if (!schoolId && data.memberships[0]) setSchoolId(String(data.memberships[0].school_id))
    } catch { tokens.clear(); setMe(null) } finally { setLoading(false) }
  }

  const loadNotifications = async () => {
    if (!me || !schoolId) return
    try {
      const [items, unread] = await Promise.all([
        api<Row[]>('/api/v1/notifications?limit=30'),
        api<{ count: number }>(`/api/v1/notifications/unread-count?schoolId=${schoolId}`)
      ])
      setNotifications(items.filter(item => String(item.school_id) === schoolId))
      setUnreadCount(unread.count)
    } catch { /* keep shell usable when notification API is unavailable */ }
  }

  useEffect(() => { if (tokens.access()) void loadMe(); else setLoading(false) }, [])
  useEffect(() => { if (me && schoolId) void loadNotifications() }, [me, schoolId])
  useEffect(() => {
    const token = tokens.access(); if (!token || !me) return
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
    const ws = new WebSocket(`${scheme}://${location.host}/realtime/v1/ws?access_token=${encodeURIComponent(token)}`)
    ws.onmessage = event => {
      try {
        const message = JSON.parse(event.data)
        setToast(message.data?.title || message.eventType || 'New update')
        void loadNotifications()
      } catch { /* ignore malformed pushed message */ }
    }
    return () => ws.close()
  }, [me, schoolId])

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
      <nav>{tabs.map(item => <button key={item} className={tab === item ? 'active' : ''} onClick={() => setTab(item)}><span>{item[0].toUpperCase() + item.slice(1)}</span>{item === 'notifications' && unreadCount > 0 && <b className="nav-badge">{unreadCount > 99 ? '99+' : unreadCount}</b>}</button>)}</nav>
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
        {tab === 'notifications' && <Notifications schoolId={schoolId} rows={notifications} reload={() => void loadNotifications()} />}
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
  const [announcements, setAnnouncements] = useState<Row[]>([])
  const [conversations, setConversations] = useState<Row[]>([])
  const [selected, setSelected] = useState('')
  const [messages, setMessages] = useState<Row[]>([])
  const [announcementTitle, setAnnouncementTitle] = useState('')
  const [announcementBody, setAnnouncementBody] = useState('')
  const [announcementPinned, setAnnouncementPinned] = useState(false)
  const [messageBody, setMessageBody] = useState('')
  const [status, setStatus] = useState('')

  const load = async () => {
    const [noticeRows, conversationRows] = await Promise.all([
      api<Row[]>(`/api/v1/schools/${schoolId}/announcements`),
      api<Row[]>(`/api/v1/schools/${schoolId}/conversations`).catch(() => [] as Row[])
    ])
    setAnnouncements(noticeRows)
    setConversations(conversationRows)
  }

  useEffect(() => { void load() }, [schoolId])
  useEffect(() => {
    if (!selected) { setMessages([]); return }
    void api<Row[]>(`/api/v1/conversations/${selected}/messages`).then(rows => {
      setMessages(rows)
      void load()
    })
  }, [selected])

  const publish = async (event: FormEvent) => {
    event.preventDefault()
    if (!announcementTitle.trim() || !announcementBody.trim()) return
    try {
      await api(`/api/v1/schools/${schoolId}/announcements`, { method: 'POST', body: JSON.stringify({ title: announcementTitle, body: announcementBody, targetType: 'SCHOOL', targetId: null, pinned: announcementPinned }) })
      setAnnouncementTitle(''); setAnnouncementBody(''); setAnnouncementPinned(false); setStatus('Announcement published'); await load()
    } catch (err) { setStatus((err as Error).message) }
  }

  const send = async (event: FormEvent) => {
    event.preventDefault()
    if (!selected || !messageBody.trim()) return
    try {
      await api(`/api/v1/conversations/${selected}/messages`, { method: 'POST', body: JSON.stringify({ body: messageBody }) })
      setMessageBody('')
      setMessages(await api<Row[]>(`/api/v1/conversations/${selected}/messages`))
      await load()
    } catch (err) { setStatus((err as Error).message) }
  }

  return <section>
    {status && <p className="status-line">{status}</p>}
    {canPublish && <Card title="Publish announcement"><form className="inline-form" onSubmit={publish}>
      <label>Title<input value={announcementTitle} onChange={e => setAnnouncementTitle(e.target.value)} maxLength={255} placeholder="School announcement title" /></label>
      <label>Message<textarea value={announcementBody} onChange={e => setAnnouncementBody(e.target.value)} rows={4} placeholder="Write the announcement…" /></label>
      <label className="check-row"><input type="checkbox" checked={announcementPinned} onChange={e => setAnnouncementPinned(e.target.checked)} /> Pin this announcement</label>
      <div><button className="primary" type="submit">Publish announcement</button></div>
    </form></Card>}

    <div className="section-head"><div><h3>Announcements</h3><p>Durable school and class updates.</p></div><span>{announcements.length} visible</span></div>
    <div className="cards">{announcements.length ? announcements.map(row => <article key={row.id} className="announcement">
      <small>{row.pinned ? 'Pinned · ' : ''}{new Date(row.published_at).toLocaleString()}</small><h3>{row.title}</h3><p>{row.body}</p>
    </article>) : <Empty text="No announcements yet" />}</div>

    <Card title="Messages"><div className="communication-grid">
      <div className="conversation-list">{conversations.length ? conversations.map(conversation => <button key={conversation.id} className={selected === String(conversation.id) ? 'conversation active' : 'conversation'} onClick={() => setSelected(String(conversation.id))}>
        <span><b>{conversation.subject || 'Conversation'}</b><small>{conversation.last_message || 'No messages yet'}</small></span>
        {Number(conversation.unread_count || 0) > 0 && <em>{conversation.unread_count}</em>}
      </button>) : <Empty text="No conversations yet" />}</div>
      <div className="message-pane">{selected ? <>
        <div className="message-list">{messages.length ? messages.map(message => <div className="message" key={message.id}><small>{message.sender_name} · {new Date(message.created_at).toLocaleString()}</small><p>{message.body}</p></div>) : <Empty text="No messages yet" />}</div>
        <form className="message-compose" onSubmit={send}><textarea value={messageBody} onChange={e => setMessageBody(e.target.value)} rows={3} placeholder="Write a message…" /><button className="primary" type="submit">Send</button></form>
      </> : <Empty text="Choose a conversation" />}</div>
    </div></Card>
  </section>
}

function Notifications({ schoolId, rows, reload }: { schoolId: string; rows: Row[]; reload: () => void }) {
  const [category, setCategory] = useState('ALL')
  const [unreadOnly, setUnreadOnly] = useState(false)
  const [items, setItems] = useState<Row[]>(rows)
  const [preferences, setPreferences] = useState<Preference[]>([])
  const [unread, setUnread] = useState(0)
  const [message, setMessage] = useState('')

  const load = async () => {
    const params = new URLSearchParams({ schoolId, limit: '50', unreadOnly: String(unreadOnly) })
    if (category !== 'ALL') params.set('category', category)
    const [page, prefs, count] = await Promise.all([
      api<NotificationPage>(`/api/v1/notifications/page?${params}`),
      api<Preference[]>(`/api/v1/notifications/preferences?schoolId=${schoolId}`),
      api<{ count: number }>(`/api/v1/notifications/unread-count?schoolId=${schoolId}`)
    ])
    setItems(page.items); setPreferences(prefs); setUnread(count.count)
  }

  useEffect(() => { void load().catch(err => setMessage((err as Error).message)) }, [schoolId, category, unreadOnly])
  useEffect(() => { setItems(rows) }, [rows])

  const read = async (id: string) => { await api(`/api/v1/notifications/${id}/read`, { method: 'POST' }); await load(); reload() }
  const readAll = async () => { await api(`/api/v1/notifications/read-all?schoolId=${schoolId}`, { method: 'POST' }); await load(); reload() }
  const changePreference = async (preference: Preference, field: 'inAppEnabled' | 'realtimeEnabled', value: boolean) => {
    const next = { ...preference, [field]: value }
    try {
      await api(`/api/v1/notifications/preferences/${preference.category}?schoolId=${schoolId}`, { method: 'PUT', body: JSON.stringify({ inAppEnabled: next.inAppEnabled, realtimeEnabled: next.realtimeEnabled }) })
      setPreferences(current => current.map(item => item.category === next.category ? next : item))
      setMessage('Notification preference saved')
    } catch (err) { setMessage((err as Error).message) }
  }

  return <section>
    <div className="section-head"><div><h3>Internal notifications</h3><p>Durable in-app messages with optional realtime delivery.</p></div><span>{unread} unread</span></div>
    <div className="toolbar"><select value={category} onChange={e => setCategory(e.target.value)}>{notificationCategories.map(item => <option key={item}>{item}</option>)}</select>
      <label className="check-row"><input type="checkbox" checked={unreadOnly} onChange={e => setUnreadOnly(e.target.checked)} /> Unread only</label>
      <button onClick={() => void readAll()} disabled={!unread}>Mark all read</button></div>
    {message && <p className="status-line">{message}</p>}
    <div className="notification-layout"><div className="cards">{items.length ? items.map(row => <article key={row.id} className={`notification ${row.read_at ? '' : 'unread'}`} onClick={() => void read(String(row.id))}>
      <small>{row.category || row.type} · {new Date(row.created_at).toLocaleString()}</small><h3>{row.title}</h3><p>{row.body}</p>
    </article>) : <Empty text="No notifications match this filter" />}</div>
      <Card title="Delivery preferences"><div className="preference-list">{preferences.map(preference => <div className="preference-row" key={preference.category}><b>{preference.category}</b>
        <label><input type="checkbox" checked={preference.inAppEnabled} onChange={e => void changePreference(preference, 'inAppEnabled', e.target.checked)} /> In-app</label>
        <label><input type="checkbox" checked={preference.realtimeEnabled} onChange={e => void changePreference(preference, 'realtimeEnabled', e.target.checked)} /> Realtime</label>
      </div>)}</div><p className="hint">SMS Brandname, Zalo OA, email and push can be added later as delivery adapters without changing these preferences.</p></Card>
    </div>
  </section>
}

function Stat({ label, value }: { label: string; value: any }) { return <div className="stat"><span>{label}</span><strong>{String(value ?? 0)}</strong></div> }
function Card({ title, children }: { title: string; children: ReactNode }) { return <div className="card"><div className="card-title">{title}</div>{children}</div> }
function Empty({ text }: { text: string }) { return <div className="empty">{text}</div> }
function Table({ rows }: { rows: Row[] }) {
  const keys = useMemo(() => rows[0] ? Object.keys(rows[0]).slice(0, 8) : [], [rows])
  if (!rows.length) return <Empty text="No data yet" />
  return <div className="table-wrap"><table><thead><tr>{keys.map(k => <th key={k}>{k.replaceAll('_', ' ')}</th>)}</tr></thead><tbody>{rows.map((row, index) => <tr key={row.id || index}>{keys.map(k => <td key={k}>{row[k] === null || row[k] === undefined ? '—' : String(row[k])}</td>)}</tr>)}</tbody></table></div>
}
