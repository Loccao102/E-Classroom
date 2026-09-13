import { useEffect, useState } from 'react'
import { api } from '../../api'
import type { NotificationPage, Preference, Row } from '../../app/types'
import { Badge, SectionHeading, Status } from '../../components/ui'

const categories = [
  ['ALL', 'Tất cả'], ['ATTENDANCE', 'Điểm danh'], ['LEAVE', 'Nghỉ học'], ['SCORE', 'Điểm số'],
  ['ANNOUNCEMENT', 'Thông báo'], ['COMMENT', 'Nhận xét'], ['MESSAGE', 'Tin nhắn'], ['SYSTEM', 'Hệ thống']
] as const

export function NotificationsPage({ schoolId, onUnreadChange }: { schoolId: string; onUnreadChange?: (count: number) => void }) {
  const [rows, setRows] = useState<Row[]>([])
  const [category, setCategory] = useState('ALL')
  const [unreadOnly, setUnreadOnly] = useState(false)
  const [unread, setUnread] = useState(0)
  const [preferences, setPreferences] = useState<Preference[]>([])
  const [nextCursor, setNextCursor] = useState<{ beforeCreatedAt: string; beforeId: string } | null>(null)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [loading, setLoading] = useState(false)

  const load = async (append = false, cursor = nextCursor) => {
    setLoading(true); setError('')
    try {
      const query = new URLSearchParams({ schoolId, limit: '30', unreadOnly: String(unreadOnly) })
      if (category !== 'ALL') query.set('category', category)
      if (append && cursor) { query.set('beforeCreatedAt', cursor.beforeCreatedAt); query.set('beforeId', cursor.beforeId) }
      const [page, count] = await Promise.all([
        api<NotificationPage>(`/api/v1/notifications/page?${query}`),
        api<{ count: number }>(`/api/v1/notifications/unread-count?schoolId=${schoolId}`)
      ])
      setRows(current => append ? [...current, ...page.items] : page.items)
      setNextCursor(page.nextCursor || null); setUnread(count.count); onUnreadChange?.(count.count)
    } catch (err) { setError((err as Error).message) }
    finally { setLoading(false) }
  }

  const loadPreferences = async () => {
    try { setPreferences(await api<Preference[]>(`/api/v1/notifications/preferences?schoolId=${schoolId}`)) }
    catch { setPreferences([]) }
  }
  useEffect(() => { void load(false, null); void loadPreferences() }, [schoolId, category, unreadOnly])

  const read = async (id: string) => { await api(`/api/v1/notifications/${id}/read`, { method: 'POST' }); await load(false, null) }
  const readAll = async () => { const result = await api<{ updated: number }>(`/api/v1/notifications/read-all?schoolId=${schoolId}`, { method: 'POST' }); setMessage(`Đã đánh dấu ${result.updated} thông báo là đã đọc.`); await load(false, null) }
  const togglePreference = async (item: Preference, field: 'inAppEnabled' | 'realtimeEnabled') => {
    const next = { ...item, [field]: !item[field] }
    try { await api(`/api/v1/notifications/preferences/${item.category}?schoolId=${schoolId}`, { method: 'PUT', body: JSON.stringify({ inAppEnabled: next.inAppEnabled, realtimeEnabled: next.realtimeEnabled }) }); setPreferences(current => current.map(row => row.category === item.category ? next : row)) }
    catch (err) { setError((err as Error).message) }
  }

  return <section className="workspace notification-workspace">
    <SectionHeading eyebrow="Trung tâm thông báo" title="Không bỏ lỡ điều quan trọng" description={`${unread} thông báo chưa đọc trong trường đang chọn.`} actions={<button disabled={!unread} onClick={() => void readAll()}>Đánh dấu tất cả đã đọc</button>} />
    <Status>{message}</Status><Status tone="error">{error}</Status>

    <div className="notification-controls"><div className="category-tabs" role="tablist" aria-label="Lọc thông báo">{categories.map(([value, label]) => <button role="tab" aria-selected={category === value} className={category === value ? 'active' : ''} key={value} onClick={() => setCategory(value)}>{label}</button>)}</div><label className="check-row"><input type="checkbox" checked={unreadOnly} onChange={event => setUnreadOnly(event.target.checked)} /><span>Chỉ chưa đọc</span></label></div>

    <div className="notification-layout"><div className="notification-feed">{rows.length ? rows.map(row => <article className={`notification-item ${row.read_at ? '' : 'unread'}`} key={String(row.id)}><div className="notification-icon" aria-hidden="true">{iconFor(String(row.category || row.type || 'SYSTEM'))}</div><div className="notification-copy"><div className="notification-meta"><Badge tone={toneFor(String(row.category || 'SYSTEM'))}>{categoryLabel(String(row.category || 'SYSTEM'))}</Badge><time>{formatDateTime(String(row.created_at || ''))}</time></div><h3>{String(row.title || '')}</h3><p>{String(row.body || '')}</p></div>{!row.read_at && <button className="text-button" onClick={() => void read(String(row.id))}>Đã đọc</button>}</article>) : <div className="inbox-placeholder"><div className="mail-mark">✓</div><h3>Không có thông báo phù hợp</h3><p>Thử bỏ bộ lọc hoặc quay lại sau.</p></div>}{nextCursor && <button className="load-more" disabled={loading} onClick={() => void load(true, nextCursor)}>{loading ? 'Đang tải…' : 'Xem thêm'}</button>}</div>

      <aside className="preference-panel"><span className="eyebrow">Tùy chọn nhận tin</span><h3>Kiểm soát thông báo</h3><p>Tắt realtime sẽ không xóa lịch sử trong hệ thống nếu In-app vẫn bật.</p><div className="preference-list">{preferences.map(item => <article key={item.category}><div><strong>{categoryLabel(item.category)}</strong><span>{preferenceDescription(item.category)}</span></div><label><input type="checkbox" checked={item.inAppEnabled} onChange={() => void togglePreference(item, 'inAppEnabled')} /><span>In-app</span></label><label><input type="checkbox" checked={item.realtimeEnabled} onChange={() => void togglePreference(item, 'realtimeEnabled')} /><span>Realtime</span></label></article>)}</div></aside>
    </div>
  </section>
}

function categoryLabel(value: string) { return categories.find(([key]) => key === value)?.[1] || (value === 'TEACHER_COMMENT' ? 'Nhận xét' : value === 'DIRECT_MESSAGE' ? 'Tin nhắn' : value) }
function preferenceDescription(value: string) { const copy: Record<string, string> = { ATTENDANCE: 'Vắng, đi muộn, thay đổi điểm danh', LEAVE: 'Đơn nghỉ và kết quả duyệt', SCORE: 'Điểm mới được công bố', ANNOUNCEMENT: 'Thông báo trường/lớp', COMMENT: 'Nhận xét giáo viên', MESSAGE: 'Tin nhắn trực tiếp', SYSTEM: 'Sự kiện tài khoản & hệ thống' }; return copy[value] || 'Thông báo trong hệ thống' }
function iconFor(value: string) { if (value.includes('SCORE')) return '10'; if (value.includes('ATTEND')) return '✓'; if (value.includes('MESSAGE')) return '↗'; if (value.includes('LEAVE')) return '□'; if (value.includes('ANNOUNC')) return '!'; return '·' }
function toneFor(value: string): 'neutral' | 'success' | 'warning' | 'danger' | 'info' { if (value === 'ATTENDANCE') return 'warning'; if (value === 'SCORE') return 'success'; if (value === 'MESSAGE') return 'info'; return 'neutral' }
function formatDateTime(value: string) { const date = new Date(value); return Number.isNaN(date.getTime()) ? value : date.toLocaleString('vi-VN', { dateStyle: 'short', timeStyle: 'short' }) }
