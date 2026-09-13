import { FormEvent, useState } from 'react'
import { login } from '../../api'

export function LoginPage({ onDone }: { onDone: () => void }) {
  const [email, setEmail] = useState('admin@eclassroom.local')
  const [password, setPassword] = useState('Admin123!')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setBusy(true); setError('')
    try { await login(email.trim(), password); onDone() }
    catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }

  return <div className="login-page"><form className="login-card" onSubmit={submit}>
    <div className="brand-mark">EC</div>
    <h1>E-Classroom</h1>
    <p>Sổ liên lạc điện tử giữa nhà trường, giáo viên, học sinh và phụ huynh.</p>
    <label>Email<input autoComplete="username" value={email} onChange={event => setEmail(event.target.value)} /></label>
    <label>Mật khẩu<input autoComplete="current-password" type="password" value={password} onChange={event => setPassword(event.target.value)} /></label>
    {error && <div className="error">{error}</div>}
    <button className="primary" disabled={busy}>{busy ? 'Đang đăng nhập…' : 'Đăng nhập'}</button>
    <small>Tài khoản demo local: admin@eclassroom.local / Admin123!</small>
  </form></div>
}
