import { FormEvent, useEffect, useMemo, useState } from 'react'
import { api } from '../../api'
import type { Row, TeachingAssignment } from '../../app/types'
import { Badge, DialogForm, Field, Modal, SectionHeading, Status } from '../../components/ui'

type Contact = Row & { userId?: string; user_id?: string; fullName?: string; full_name?: string; role?: string; context?: string }

export function CommunicationPage({ schoolId, isAdmin, isTeacher }: { schoolId: string; isAdmin: boolean; isTeacher: boolean }) {
  const [announcements, setAnnouncements] = useState<Row[]>([])
  const [conversations, setConversations] = useState<Row[]>([])
  const [contacts, setContacts] = useState<Contact[]>([])
  const [selected, setSelected] = useState('')
  const [messages, setMessages] = useState<Row[]>([])
  const [composer, setComposer] = useState('')
  const [publishOpen, setPublishOpen] = useState(false)
  const [newConversationOpen, setNewConversationOpen] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')

  const load = async () => {
    try {
      const [announcementRows, conversationRows, contactRows] = await Promise.all([
        api<Row[]>(`/api/v1/schools/${schoolId}/announcements`),
        api<Row[]>(`/api/v1/schools/${schoolId}/conversations`),
        api<Contact[]>(`/api/v1/schools/${schoolId}/communication/contacts`).catch(() => [] as Contact[])
      ])
      setAnnouncements(announcementRows); setConversations(conversationRows); setContacts(contactRows)
      setSelected(current => current || String(conversationRows[0]?.id || ''))
    } catch (err) { setError((err as Error).message) }
  }
  useEffect(() => { void load() }, [schoolId])

  const loadMessages = async () => {
    if (!selected) { setMessages([]); return }
    try {
      const rows = await api<Row[]>(`/api/v1/conversations/${selected}/messages`)
      setMessages(rows); await api(`/api/v1/conversations/${selected}/read`, { method: 'POST' })
      setConversations(current => current.map(row => String(row.id) === selected ? { ...row, unread_count: 0 } : row))
    } catch (err) { setError((err as Error).message) }
  }
  useEffect(() => { void loadMessages() }, [selected])

  const activeConversation = conversations.find(row => String(row.id) === selected)
  const send = async (event: FormEvent) => {
    event.preventDefault(); if (!selected || !composer.trim()) return
    setError('')
    try { await api(`/api/v1/conversations/${selected}/messages`, { method: 'POST', body: JSON.stringify({ body: composer.trim() }) }); setComposer(''); await loadMessages(); await load() }
    catch (err) { setError((err as Error).message) }
  }

  return <section className="workspace communication-workspace">
    <SectionHeading eyebrow="Liên lạc" title="Nhà trường & gia đình" description="Thông báo chính thức và hội thoại theo đúng quan hệ lớp học." actions={<><button onClick={() => setNewConversationOpen(true)}>Tin nhắn mới</button>{(isAdmin || isTeacher) && <button className="primary" onClick={() => setPublishOpen(true)}>Đăng thông báo</button>}</>} />
    <Status>{message}</Status><Status tone="error">{error}</Status>

    <div className="announcement-ribbon" aria-label="Thông báo gần đây">{announcements.slice(0, 3).map(row => <article key={String(row.id)} className={row.pinned ? 'pinned' : ''}><div><span>{row.pinned ? 'Đã ghim · ' : ''}{formatDateTime(String(row.published_at || ''))}</span><strong>{String(row.title || '')}</strong><p>{String(row.body || '')}</p></div></article>)}{!announcements.length && <p className="muted">Chưa có thông báo mới.</p>}</div>

    <div className="inbox-shell">
      <aside className="conversation-list" aria-label="Danh sách hội thoại"><div className="inbox-heading"><span>Hộp thư</span><b>{conversations.reduce((sum, row) => sum + Number(row.unread_count || 0), 0)} chưa đọc</b></div>{conversations.length ? conversations.map(row => <button className={String(row.id) === selected ? 'selected' : ''} key={String(row.id)} onClick={() => setSelected(String(row.id))}><div className="avatar-dot">{initials(String(row.subject || 'HT'))}</div><div><strong>{String(row.subject || 'Hội thoại')}</strong><span>{String(row.last_message || 'Chưa có tin nhắn')}</span></div>{Number(row.unread_count || 0) > 0 && <em>{Number(row.unread_count)}</em>}</button>) : <p className="inbox-empty">Chưa có hội thoại.</p>}</aside>

      <section className="message-pane">{activeConversation ? <><header className="message-header"><div><span>Hội thoại</span><strong>{String(activeConversation.subject || 'Trao đổi')}</strong></div>{Boolean(activeConversation.muted) && <Badge>Tắt realtime</Badge>}</header><div className="message-stream">{messages.map((row, index) => <article className="message-bubble" key={String(row.id || index)}><div><strong>{String(row.sender_name || 'Thành viên')}</strong><time>{formatDateTime(String(row.created_at || ''))}</time></div><p>{String(row.body || '')}</p></article>)}</div><form className="message-composer" onSubmit={send}><textarea aria-label="Nội dung tin nhắn" maxLength={5000} rows={2} value={composer} onChange={event => setComposer(event.target.value)} placeholder="Nhập nội dung cần trao đổi…" /><button className="primary" disabled={!composer.trim()}>Gửi</button></form></> : <div className="inbox-placeholder"><div className="mail-mark">↗</div><h3>Chọn một hội thoại</h3><p>Hoặc bắt đầu trao đổi mới với người được phép liên hệ.</p><button onClick={() => setNewConversationOpen(true)}>Tin nhắn mới</button></div>}</section>
    </div>

    <NewConversationModal open={newConversationOpen} contacts={contacts} schoolId={schoolId} onClose={() => setNewConversationOpen(false)} onSaved={async id => { setNewConversationOpen(false); await load(); setSelected(id); setMessage('Đã tạo hội thoại mới.') }} />
    <AnnouncementModal open={publishOpen} schoolId={schoolId} isAdmin={isAdmin} isTeacher={isTeacher} onClose={() => setPublishOpen(false)} onSaved={async () => { setPublishOpen(false); await load(); setMessage('Đã đăng thông báo và gửi tới đúng đối tượng.') }} />
  </section>
}

function NewConversationModal({ open, contacts, schoolId, onClose, onSaved }: { open: boolean; contacts: Contact[]; schoolId: string; onClose: () => void; onSaved: (id: string) => Promise<void> }) {
  const [participantId, setParticipantId] = useState('')
  const [subject, setSubject] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  useEffect(() => { if (open) setParticipantId(contactId(contacts[0]) || '') }, [open, contacts])
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); if (!participantId) return
    setBusy(true); setError('')
    try { const result = await api<{ id: string }>(`/api/v1/schools/${schoolId}/conversations`, { method: 'POST', body: JSON.stringify({ subject: subject.trim() || null, participantIds: [participantId] }) }); setSubject(''); await onSaved(result.id) }
    catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }
  return <Modal open={open} onClose={onClose} title="Tin nhắn mới"><DialogForm onSubmit={submit} onCancel={onClose} submitLabel="Bắt đầu trao đổi" busy={busy}><Status tone="error">{error}</Status><Field label="Người nhận" hint="Chỉ hiển thị những người bạn được phép liên hệ theo quan hệ lớp học."><select required value={participantId} onChange={event => setParticipantId(event.target.value)}><option value="">Chọn người nhận</option>{contacts.map((contact, index) => <option key={contactId(contact) || index} value={contactId(contact)}>{contactName(contact)} · {roleLabel(String(contact.role || ''))}{contact.context ? ` · ${String(contact.context)}` : ''}</option>)}</select></Field><Field label="Chủ đề"><input maxLength={255} value={subject} onChange={event => setSubject(event.target.value)} placeholder="Ví dụ: Trao đổi tình hình học tập" /></Field></DialogForm></Modal>
}

function AnnouncementModal({ open, schoolId, isAdmin, isTeacher, onClose, onSaved }: { open: boolean; schoolId: string; isAdmin: boolean; isTeacher: boolean; onClose: () => void; onSaved: () => Promise<void> }) {
  const [title, setTitle] = useState('')
  const [body, setBody] = useState('')
  const [targetType, setTargetType] = useState(isAdmin ? 'SCHOOL' : 'CLASSROOM')
  const [targetId, setTargetId] = useState('')
  const [pinned, setPinned] = useState(false)
  const [classes, setClasses] = useState<Row[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => {
    if (!open) return
    if (isTeacher && !isAdmin) void api<TeachingAssignment[]>(`/api/v1/schools/${schoolId}/me/teaching-assignments`).then(rows => setClasses(uniqueClasses(rows))).catch(() => setClasses([]))
    else void api<Row[]>(`/api/v1/schools/${schoolId}/classrooms`).then(setClasses).catch(() => setClasses([]))
  }, [open, schoolId, isAdmin, isTeacher])
  useEffect(() => { if (classes[0] && !targetId) setTargetId(String(classes[0].id || classes[0].classroom_id || '')) }, [classes])
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setBusy(true); setError('')
    try { await api(`/api/v1/schools/${schoolId}/announcements`, { method: 'POST', body: JSON.stringify({ title: title.trim(), body: body.trim(), targetType, targetId: targetType === 'SCHOOL' ? null : targetId, pinned, expiresAt: null }) }); setTitle(''); setBody(''); setPinned(false); await onSaved() }
    catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }
  return <Modal title="Đăng thông báo" open={open} onClose={onClose}><DialogForm onSubmit={submit} onCancel={onClose} submitLabel="Đăng thông báo" busy={busy}><Status tone="error">{error}</Status>{isAdmin && <Field label="Phạm vi"><select value={targetType} onChange={event => setTargetType(event.target.value)}><option value="SCHOOL">Toàn trường</option><option value="CLASSROOM">Một lớp</option></select></Field>}{targetType === 'CLASSROOM' && <Field label="Lớp"><select required value={targetId} onChange={event => setTargetId(event.target.value)}>{classes.map((row, index) => { const id = String(row.id || row.classroom_id || ''); return <option value={id} key={id || index}>{String(row.name || row.classroom_name || 'Lớp học')}</option> })}</select></Field>}<Field label="Tiêu đề"><input required maxLength={255} value={title} onChange={event => setTitle(event.target.value)} /></Field><Field label="Nội dung"><textarea required maxLength={20000} rows={6} value={body} onChange={event => setBody(event.target.value)} /></Field><label className="check-row"><input type="checkbox" checked={pinned} onChange={event => setPinned(event.target.checked)} /><span>Ghim thông báo lên đầu danh sách</span></label></DialogForm></Modal>
}

function uniqueClasses(assignments: TeachingAssignment[]): Row[] { const seen = new Set<string>(); return assignments.flatMap(item => { if (seen.has(item.classroom_id)) return []; seen.add(item.classroom_id); return [{ id: item.classroom_id, name: item.classroom_name }] }) }
function contactId(contact?: Contact) { return contact ? String(contact.userId || contact.user_id || contact.id || '') : '' }
function contactName(contact: Contact) { return String(contact.fullName || contact.full_name || contact.name || 'Thành viên') }
function initials(value: string) { return value.split(/\s+/).filter(Boolean).slice(0, 2).map(part => part[0]).join('').toUpperCase() }
function roleLabel(value: string) { return value === 'TEACHER' ? 'Giáo viên' : value === 'PARENT' ? 'Phụ huynh' : value === 'STUDENT' ? 'Học sinh' : value === 'SCHOOL_ADMIN' ? 'Quản trị' : value }
function formatDateTime(value: string) { const date = new Date(value); return Number.isNaN(date.getTime()) ? value : date.toLocaleString('vi-VN', { dateStyle: 'short', timeStyle: 'short' }) }
