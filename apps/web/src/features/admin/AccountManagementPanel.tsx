import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api'
import type { Row } from '../../app/types'
import { Badge, Card, Modal, Status } from '../../components/ui'

type ManagedAccount = { userId: string; name: string; email: string; role: string; status: string }
type PendingAction = { account: ManagedAccount; kind: 'RESET' | 'STATUS' } | null

export function AccountManagementPanel({ schoolId }: { schoolId: string }) {
  const [accounts, setAccounts] = useState<ManagedAccount[]>([])
  const [query, setQuery] = useState('')
  const [pending, setPending] = useState<PendingAction>(null)
  const [temporaryPassword, setTemporaryPassword] = useState('')
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const load = async () => {
    setError('')
    try {
      const [students, teachers, guardians] = await Promise.all([
        api<Row[]>(`/api/v1/schools/${schoolId}/students`),
        api<Row[]>(`/api/v1/schools/${schoolId}/teachers`),
        api<Row[]>(`/api/v1/schools/${schoolId}/guardians`)
      ])
      const mapped = [
        ...students.map(row => mapAccount(row, 'Học sinh')),
        ...teachers.map(row => mapAccount(row, 'Giáo viên')),
        ...guardians.map(row => mapAccount(row, 'Phụ huynh'))
      ].filter((value): value is ManagedAccount => value !== null)
      setAccounts(mapped.sort((a, b) => a.name.localeCompare(b.name, 'vi')))
    } catch (err) { setError((err as Error).message) }
  }
  useEffect(() => { void load() }, [schoolId])

  const filtered = useMemo(() => {
    const value = query.trim().toLocaleLowerCase('vi')
    if (!value) return accounts
    return accounts.filter(account => `${account.name} ${account.email} ${account.role}`.toLocaleLowerCase('vi').includes(value))
  }, [accounts, query])

  const resetPassword = async (account: ManagedAccount) => {
    setBusy(true); setError('')
    try {
      const result = await api<{ temporaryPassword: string }>(`/api/v1/schools/${schoolId}/accounts/${account.userId}/temporary-password`, { method: 'POST' })
      setTemporaryPassword(result.temporaryPassword)
      setPending(null)
      setMessage(`Đã đặt mật khẩu tạm cho ${account.name}.`)
      await load()
    } catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }

  const changeStatus = async (account: ManagedAccount) => {
    setBusy(true); setError('')
    const next = account.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
    try {
      await api(`/api/v1/schools/${schoolId}/accounts/${account.userId}/status`, { method: 'PUT', body: JSON.stringify({ status: next }) })
      setPending(null); setMessage(next === 'ACTIVE' ? `Đã mở lại tài khoản ${account.name}.` : `Đã vô hiệu hóa tài khoản ${account.name}.`)
      await load()
    } catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }

  return <section className="managed-accounts">
    <div className="resource-toolbar"><div><span className="eyebrow">Bảo mật tài khoản</span><h3>Tài khoản do nhà trường quản lý</h3><p>Đặt mật khẩu tạm hoặc chặn truy cập khi cần. Mọi thao tác đều thu hồi các phiên đang hoạt động.</p></div><input className="account-search" aria-label="Tìm tài khoản" placeholder="Tìm tên hoặc email…" value={query} onChange={event => setQuery(event.target.value)} /></div>
    <Status>{message}</Status><Status tone="error">{error}</Status>
    <Card title={`${filtered.length} tài khoản đã cấp đăng nhập`}>
      <div className="account-list">{filtered.length === 0 && <div className="empty compact">Chưa có tài khoản phù hợp.</div>}{filtered.map(account => <div className="account-row" key={account.userId}>
        <div className="account-avatar">{initials(account.name)}</div><div className="account-copy"><div><strong>{account.name}</strong><Badge tone={account.status === 'ACTIVE' ? 'success' : 'neutral'}>{account.status === 'ACTIVE' ? 'Đang hoạt động' : 'Đã khóa'}</Badge></div><span>{account.role} · {account.email}</span></div>
        <div className="account-actions"><button onClick={() => { setTemporaryPassword(''); setPending({ account, kind: 'RESET' }) }}>Đặt mật khẩu tạm</button><button className={account.status === 'ACTIVE' ? 'danger-ghost' : ''} onClick={() => setPending({ account, kind: 'STATUS' })}>{account.status === 'ACTIVE' ? 'Vô hiệu hóa' : 'Mở lại'}</button></div>
      </div>)}</div>
    </Card>

    <Modal open={pending !== null} title={pending?.kind === 'RESET' ? 'Đặt mật khẩu tạm' : pending?.account.status === 'ACTIVE' ? 'Vô hiệu hóa tài khoản' : 'Mở lại tài khoản'} onClose={() => !busy && setPending(null)}>
      {pending && <div className="confirmation-panel"><p>{pending.kind === 'RESET' ? <>Tạo mật khẩu tạm mới cho <strong>{pending.account.name}</strong>? Tất cả phiên đăng nhập hiện tại sẽ bị thu hồi và người dùng buộc phải đổi mật khẩu sau khi đăng nhập.</> : pending.account.status === 'ACTIVE' ? <>Vô hiệu hóa <strong>{pending.account.name}</strong>? Người dùng sẽ bị đăng xuất trên mọi thiết bị và không thể đăng nhập cho tới khi được mở lại.</> : <>Cho phép <strong>{pending.account.name}</strong> đăng nhập trở lại?</>}</p><div className="dialog-actions"><button disabled={busy} onClick={() => setPending(null)}>Hủy</button><button disabled={busy} className={pending.kind === 'STATUS' && pending.account.status === 'ACTIVE' ? 'danger-button' : 'primary'} onClick={() => void (pending.kind === 'RESET' ? resetPassword(pending.account) : changeStatus(pending.account))}>{busy ? 'Đang xử lý…' : 'Xác nhận'}</button></div></div>}
    </Modal>

    <Modal open={temporaryPassword !== ''} title="Mật khẩu tạm đã tạo" onClose={() => setTemporaryPassword('')}>
      <div className="temporary-password-result"><p>Mật khẩu này chỉ hiển thị trong lần này. Hãy gửi cho người dùng qua kênh phù hợp và không lưu trong ghi chú công khai.</p><code>{temporaryPassword}</code><button className="primary" onClick={() => void navigator.clipboard?.writeText(temporaryPassword)}>Sao chép mật khẩu</button></div>
    </Modal>
  </section>
}

function mapAccount(row: Row, role: string): ManagedAccount | null {
  const userId = String(row.user_id || '')
  if (!userId) return null
  return { userId, name: String(row.full_name || 'Chưa đặt tên'), email: String(row.email || 'Chưa có email'), role, status: String(row.status || 'ACTIVE') }
}
function initials(value: string) { return value.split(/\s+/).filter(Boolean).slice(-2).map(part => part[0]).join('').toUpperCase() }
