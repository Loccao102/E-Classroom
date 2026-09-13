import { FormEvent, useEffect, useState } from 'react'
import { api } from '../../api'
import type { CurrentUser, SecuritySession } from '../../app/types'
import { Badge, Card, Field, Status } from '../../components/ui'

export function SecurityPage({ me, forced, onSessionEnded }: { me: CurrentUser; forced: boolean; onSessionEnded: () => void }) {
  const [sessions, setSessions] = useState<SecuritySession[]>([])
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const loadSessions = async () => {
    try { setSessions(await api<SecuritySession[]>('/api/v1/account/sessions')) }
    catch (err) { setError((err as Error).message) }
  }
  useEffect(() => { void loadSessions() }, [])

  const changePassword = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setMessage(''); setError('')
    if (newPassword !== confirmPassword) { setError('Mật khẩu xác nhận chưa trùng khớp.'); return }
    setBusy(true)
    try {
      await api('/api/v1/account/password', { method: 'PUT', body: JSON.stringify({ currentPassword, newPassword }) }, false)
      setMessage('Mật khẩu đã được thay đổi. Vui lòng đăng nhập lại.')
      onSessionEnded()
    } catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }

  const revoke = async (session: SecuritySession) => {
    setMessage(''); setError('')
    try {
      await api(`/api/v1/account/sessions/${session.sessionId}`, { method: 'DELETE' }, false)
      if (session.current) { onSessionEnded(); return }
      setMessage('Đã thu hồi phiên đã chọn.'); await loadSessions()
    } catch (err) { setError((err as Error).message) }
  }

  const revokeAll = async () => {
    setMessage(''); setError('')
    try { await api('/api/v1/account/sessions/revoke-all', { method: 'POST' }, false); onSessionEnded() }
    catch (err) { setError((err as Error).message) }
  }

  return <section className="workspace security-workspace">
    <div className="page-intro"><div><p className="eyebrow">Tài khoản & bảo mật</p><h3>{forced ? 'Đổi mật khẩu tạm thời' : 'Bảo vệ tài khoản của bạn'}</h3><p>{forced ? 'Quản trị viên vừa cấp mật khẩu tạm. Hãy tạo mật khẩu riêng trước khi tiếp tục.' : 'Quản lý mật khẩu và các thiết bị đang đăng nhập.'}</p></div></div>
    {forced && <div className="security-required"><strong>Yêu cầu bảo mật</strong><span>Bạn cần đổi mật khẩu trước khi mở các chức năng khác.</span></div>}
    <Status>{message}</Status><Status tone="error">{error}</Status>
    <div className="security-grid">
      <Card title="Đổi mật khẩu"><form className="security-form" onSubmit={changePassword}>
        <Field label="Mật khẩu hiện tại"><input autoComplete="current-password" type="password" required value={currentPassword} onChange={event => setCurrentPassword(event.target.value)} /></Field>
        <Field label="Mật khẩu mới" hint="10-128 ký tự, có chữ hoa, chữ thường và số."><input autoComplete="new-password" type="password" minLength={10} maxLength={128} required value={newPassword} onChange={event => setNewPassword(event.target.value)} /></Field>
        <Field label="Nhập lại mật khẩu mới"><input autoComplete="new-password" type="password" minLength={10} maxLength={128} required value={confirmPassword} onChange={event => setConfirmPassword(event.target.value)} /></Field>
        <button className="primary" disabled={busy}>{busy ? 'Đang cập nhật…' : 'Đổi mật khẩu'}</button>
      </form></Card>
      <Card title="Hồ sơ đăng nhập"><dl className="detail-list security-profile">
        <div><dt>Họ tên</dt><dd>{me.user.full_name}</dd></div><div><dt>Email</dt><dd>{me.user.email}</dd></div>
        <div><dt>Trạng thái</dt><dd><Badge tone="success">Đang hoạt động</Badge></dd></div>
        <div><dt>Đổi mật khẩu gần nhất</dt><dd>{me.user.password_changed_at ? dateTime(me.user.password_changed_at) : 'Chưa ghi nhận'}</dd></div>
      </dl></Card>
    </div>
    <Card title="Thiết bị & phiên đăng nhập" actions={<button className="danger-ghost" onClick={() => void revokeAll()}>Đăng xuất tất cả</button>}>
      <div className="session-list">{sessions.length === 0 && <div className="empty compact">Chưa có thông tin phiên đăng nhập.</div>}{sessions.map(session => <div className={`session-row ${session.current ? 'current' : ''}`} key={session.sessionId}>
        <div className="session-icon" aria-hidden="true">◫</div><div className="session-copy"><div><strong>{session.deviceLabel}</strong>{session.current && <Badge tone="info">Thiết bị này</Badge>}{!session.active && <Badge tone="neutral">Đã kết thúc</Badge>}</div><span>Hoạt động gần nhất {dateTime(session.lastSeenAt)} · Bắt đầu {dateTime(session.createdAt)}</span></div>
        {session.active && <button className="text-button danger-text" onClick={() => void revoke(session)}>{session.current ? 'Đăng xuất' : 'Thu hồi'}</button>}
      </div>)}</div>
      <p className="hint security-hint">Nếu thấy thiết bị lạ, hãy thu hồi phiên đó và đổi mật khẩu.</p>
    </Card>
  </section>
}

function dateTime(value: string) { try { return new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) } catch { return value } }
