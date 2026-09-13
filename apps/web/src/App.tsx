import { useEffect, useMemo, useState } from 'react'
import { api, logout, tokens } from './api'
import type { CurrentUser } from './app/types'
import { AdminPage } from './features/admin/AdminPage'
import { LoginPage } from './features/auth/LoginPage'
import { CommunicationPage } from './features/communication/CommunicationPage'
import { DashboardPage } from './features/dashboard/DashboardPage'
import { NotificationsPage } from './features/notifications/NotificationsPage'
import { ParentPage } from './features/parent/ParentPage'
import { SecurityPage } from './features/security/SecurityPage'
import { StudentPage } from './features/student/StudentPage'
import { TeacherPage } from './features/teacher/TeacherPage'

type TabKey = 'dashboard' | 'admin' | 'teacher' | 'parent' | 'student' | 'communication' | 'notifications' | 'security'
type NavItem = { key: TabKey; label: string; hint: string; mark: string }

export default function App() {
  const [me, setMe] = useState<CurrentUser | null>(null)
  const [loading, setLoading] = useState(true)
  const [schoolId, setSchoolId] = useState('')
  const [tab, setTab] = useState<TabKey>('dashboard')
  const [unreadCount, setUnreadCount] = useState(0)
  const [toast, setToast] = useState('')
  const [mobileNavOpen, setMobileNavOpen] = useState(false)

  const loadMe = async () => {
    setLoading(true)
    try {
      const data = await api<CurrentUser>('/api/v1/me')
      setMe(data)
      setSchoolId(current => current || String(data.memberships[0]?.school_id || ''))
      if (data.user.must_change_password) setTab('security')
    } catch { tokens.clear(); setMe(null) }
    finally { setLoading(false) }
  }

  useEffect(() => { if (tokens.access()) void loadMe(); else setLoading(false) }, [])
  useEffect(() => {
    if (!me || !schoolId || me.user.must_change_password) return
    void api<{ count: number }>(`/api/v1/notifications/unread-count?schoolId=${schoolId}`).then(result => setUnreadCount(result.count)).catch(() => undefined)
  }, [me, schoolId])

  useEffect(() => {
    const token = tokens.access()
    if (!token || !me || me.user.must_change_password) return
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
    const socket = new WebSocket(`${scheme}://${location.host}/realtime/v1/ws?access_token=${encodeURIComponent(token)}`)
    socket.onmessage = event => {
      try {
        const payload = JSON.parse(event.data) as { eventType?: string; data?: { title?: string } }
        setToast(payload.data?.title || readableEvent(payload.eventType || '') || 'Có cập nhật mới')
        if (schoolId) void api<{ count: number }>(`/api/v1/notifications/unread-count?schoolId=${schoolId}`).then(result => setUnreadCount(result.count))
      } catch { /* malformed realtime payload is ignored */ }
    }
    return () => socket.close()
  }, [me, schoolId])

  const schoolMemberships = useMemo(() => me?.memberships.filter(item => String(item.school_id) === schoolId) || [], [me, schoolId])
  const roles = schoolMemberships.map(item => item.role)
  const isAdmin = Boolean(me && (roles.includes('SCHOOL_ADMIN') || me.user.platform_role === 'SUPER_ADMIN'))
  const isTeacher = roles.includes('TEACHER')
  const isParent = roles.includes('PARENT')
  const isStudent = roles.includes('STUDENT')
  const currentSchool = me?.memberships.find(item => String(item.school_id) === schoolId)
  const uniqueSchools = useMemo(() => me?.memberships.filter((item, index, all) => all.findIndex(other => other.school_id === item.school_id) === index) || [], [me])

  const nav = useMemo<NavItem[]>(() => {
    if (me?.user.must_change_password) return [{ key: 'security', label: 'Bảo mật', hint: 'Đổi mật khẩu tạm', mark: '01' }]
    const items: NavItem[] = []
    if (isAdmin || isTeacher) items.push({ key: 'dashboard', label: 'Tổng quan', hint: 'Sức khỏe & việc hôm nay', mark: '01' })
    if (isAdmin) items.push({ key: 'admin', label: 'Quản trị', hint: 'Học vụ & dữ liệu nền', mark: '02' })
    if (isTeacher) items.push({ key: 'teacher', label: 'Lớp học', hint: 'Điểm danh & đánh giá', mark: isAdmin ? '03' : '02' })
    if (isParent) items.push({ key: 'parent', label: 'Gia đình', hint: 'Theo dõi con', mark: '01' })
    if (isStudent) items.push({ key: 'student', label: 'Học tập', hint: 'Kết quả của tôi', mark: '01' })
    items.push({ key: 'communication', label: 'Liên lạc', hint: 'Thông báo & hội thoại', mark: '↗' })
    items.push({ key: 'notifications', label: 'Thông báo', hint: 'Cập nhật của bạn', mark: '•' })
    items.push({ key: 'security', label: 'Bảo mật', hint: 'Mật khẩu & thiết bị', mark: '⌁' })
    return items
  }, [isAdmin, isTeacher, isParent, isStudent, me?.user.must_change_password])

  useEffect(() => {
    if (!nav.length || nav.some(item => item.key === tab)) return
    setTab(nav[0].key)
  }, [nav, tab])

  if (loading) return <div className="app-loading"><div className="brand-symbol">EC</div><span>Đang mở E-Classroom…</span></div>
  if (!me) return <LoginPage onDone={() => void loadMe()} />

  const endLocalSession = () => { tokens.clear(); setMe(null); setSchoolId(''); setTab('dashboard'); setUnreadCount(0) }
  const signOut = () => { void logout().finally(endLocalSession) }
  const openTab = (next: TabKey) => { if (me.user.must_change_password && next !== 'security') return; setTab(next); setMobileNavOpen(false) }

  return <div className="product-shell">
    {toast && <button className="toast" onClick={() => setToast('')}><span className="toast-dot" />{toast}<small>Nhấn để đóng</small></button>}

    <aside className={`product-sidebar ${mobileNavOpen ? 'mobile-open' : ''}`}>
      <div className="brand"><div className="brand-symbol">EC</div><div><strong>E-Classroom</strong><span>Sổ liên lạc điện tử</span></div></div>
      {!me.user.must_change_password && <div className="school-context"><span>Không gian hiện tại</span><select aria-label="Chọn trường" value={schoolId} onChange={event => { setSchoolId(event.target.value); setTab('dashboard') }}>{uniqueSchools.map(item => <option key={item.school_id} value={item.school_id}>{item.school_name}</option>)}</select></div>}
      <nav className="primary-nav" aria-label="Điều hướng chính">{nav.map(item => <button key={item.key} className={tab === item.key ? 'active' : ''} onClick={() => openTab(item.key)}><span className="nav-mark">{item.mark}</span><span className="nav-copy"><strong>{item.label}</strong><small>{item.hint}</small></span>{item.key === 'notifications' && unreadCount > 0 && <em>{unreadCount > 99 ? '99+' : unreadCount}</em>}</button>)}</nav>
      <div className="sidebar-footer"><div className="user-avatar">{initials(me.user.full_name)}</div><div><strong>{me.user.full_name}</strong><span>{me.user.must_change_password ? 'Cần đổi mật khẩu' : roleSummary(roles, me.user.platform_role)}</span></div><button className="text-button" onClick={signOut}>Đăng xuất</button></div>
    </aside>

    <main className="product-main">
      <header className="mobile-header"><button className="menu-button" aria-label="Mở điều hướng" onClick={() => setMobileNavOpen(value => !value)}>☰</button><div className="brand compact"><div className="brand-symbol">EC</div><strong>E-Classroom</strong></div>{!me.user.must_change_password && <button className="notification-shortcut" aria-label={`${unreadCount} thông báo chưa đọc`} onClick={() => openTab('notifications')}>{unreadCount || '•'}</button>}</header>
      <div className="context-header"><div><span>{me.user.must_change_password ? 'Thiết lập bảo mật' : currentSchool?.school_name || 'E-Classroom'}</span><strong>{nav.find(item => item.key === tab)?.label}</strong></div><div className="context-actions"><span className="role-chip">{me.user.must_change_password ? 'Mật khẩu tạm thời' : roleSummary(roles, me.user.platform_role)}</span></div></div>
      <div className="page-stage">
        {tab === 'security' ? <SecurityPage me={me} forced={me.user.must_change_password} onSessionEnded={endLocalSession} /> : !schoolId ? <div className="empty">Tài khoản chưa có thành viên trường học.</div> : <>
          {tab === 'dashboard' && <DashboardPage schoolId={schoolId} />}
          {tab === 'admin' && <AdminPage schoolId={schoolId} />}
          {tab === 'teacher' && <TeacherPage schoolId={schoolId} />}
          {tab === 'parent' && <ParentPage schoolId={schoolId} onOpenMessages={() => setTab('communication')} />}
          {tab === 'student' && <StudentPage schoolId={schoolId} />}
          {tab === 'communication' && <CommunicationPage schoolId={schoolId} isAdmin={isAdmin} isTeacher={isTeacher} />}
          {tab === 'notifications' && <NotificationsPage schoolId={schoolId} onUnreadChange={setUnreadCount} />}
        </>}
      </div>
    </main>
    {mobileNavOpen && <button className="nav-scrim" aria-label="Đóng điều hướng" onClick={() => setMobileNavOpen(false)} />}
  </div>
}

function initials(value: string) { return value.split(/\s+/).filter(Boolean).slice(-2).map(part => part[0]).join('').toUpperCase() }
function roleSummary(roles: string[], platformRole: string) { if (platformRole === 'SUPER_ADMIN') return 'Quản trị nền tảng'; const labels = roles.map(role => role === 'SCHOOL_ADMIN' ? 'Quản trị' : role === 'TEACHER' ? 'Giáo viên' : role === 'PARENT' ? 'Phụ huynh' : role === 'STUDENT' ? 'Học sinh' : role); return labels.join(' · ') || 'Thành viên' }
function readableEvent(value: string) { if (value.includes('attendance')) return 'Có cập nhật điểm danh'; if (value.includes('score')) return 'Có điểm mới'; if (value.includes('message')) return 'Có tin nhắn mới'; if (value.includes('announcement')) return 'Có thông báo mới'; return value }
