import { ChangeEvent, useEffect, useMemo, useRef, useState } from 'react'
import { api, apiDownload, saveDownload } from '../../api'
import type { Row } from '../../app/types'
import { Badge, Empty, Status } from '../../components/ui'

type ImportType = 'STUDENT' | 'TEACHER' | 'GUARDIAN' | 'GUARDIAN_LINK' | 'ENROLLMENT'
type Format = 'CSV' | 'XLSX'
type ImportJob = { id:string; importType:ImportType; sourceFormat:string; sourceFileName:string; status:string; totalRows:number; validRows:number; invalidRows:number; errorMessage?:string|null; version:number; createdAt:string; committedAt?:string|null }
type ImportRow = { rowNumber:number; rawData:Record<string,unknown>; normalizedData:Record<string,unknown>; status:string; errors:string[]; warnings:string[]; committedEntityId?:string|null }
type StageResult = { jobId:string; status:string; idempotentReplay:boolean }

type ImportDefinition = { type:ImportType; label:string; copy:string; columns:string[] }
const definitions:ImportDefinition[]=[
  {type:'STUDENT',label:'Học sinh',copy:'Hồ sơ học sinh theo mã học sinh.',columns:['student_code','full_name','date_of_birth','gender','email']},
  {type:'TEACHER',label:'Giáo viên',copy:'Hồ sơ giáo viên theo mã giáo viên.',columns:['teacher_code','full_name','email','phone']},
  {type:'GUARDIAN',label:'Phụ huynh',copy:'Hồ sơ phụ huynh theo email.',columns:['guardian_email','full_name','phone']},
  {type:'GUARDIAN_LINK',label:'Liên kết gia đình',copy:'Ghép học sinh và phụ huynh bằng khóa tự nhiên.',columns:['student_code','guardian_email','relationship','primary_contact']},
  {type:'ENROLLMENT',label:'Xếp lớp',copy:'Đưa học sinh vào lớp theo mã lớp và năm học.',columns:['student_code','classroom_code','academic_year','start_date']}
]
const terminal=new Set(['PREVIEW_READY','VALIDATION_FAILED','COMMITTED','FAILED'])

export function BulkDataPanel({schoolId}:{schoolId:string}){
  const [type,setType]=useState<ImportType>('STUDENT')
  const [format,setFormat]=useState<Format>('CSV')
  const [file,setFile]=useState<File|null>(null)
  const [idempotencyKey,setIdempotencyKey]=useState(()=>crypto.randomUUID())
  const [activeJob,setActiveJob]=useState<ImportJob|null>(null)
  const [rows,setRows]=useState<ImportRow[]>([])
  const [jobs,setJobs]=useState<ImportJob[]>([])
  const [rowFilter,setRowFilter]=useState('')
  const [busy,setBusy]=useState(false)
  const [error,setError]=useState('')
  const [message,setMessage]=useState('')
  const pollRef=useRef<number|undefined>(undefined)

  const definition=useMemo(()=>definitions.find(item=>item.type===type)!,[type])
  const loadJobs=async()=>{try{setJobs(await api<ImportJob[]>(`/api/v1/schools/${schoolId}/imports`))}catch(err){setError((err as Error).message)}}
  const loadRows=async(jobId:string,filter=rowFilter)=>{try{setRows(await api<ImportRow[]>(`/api/v1/schools/${schoolId}/imports/${jobId}/rows?limit=200${filter?`&status=${filter}`:''}`))}catch(err){setError((err as Error).message)}}
  const loadJob=async(jobId:string)=>{const job=await api<ImportJob>(`/api/v1/schools/${schoolId}/imports/${jobId}`);setActiveJob(job);if(terminal.has(job.status)){window.clearTimeout(pollRef.current);await loadRows(jobId);await loadJobs()}else pollRef.current=window.setTimeout(()=>void loadJob(jobId),900)}

  useEffect(()=>{setActiveJob(null);setRows([]);setFile(null);setIdempotencyKey(crypto.randomUUID());void loadJobs();return()=>window.clearTimeout(pollRef.current)},[schoolId])
  useEffect(()=>{if(activeJob&&terminal.has(activeJob.status))void loadRows(activeJob.id,rowFilter)},[rowFilter])

  const chooseFile=(event:ChangeEvent<HTMLInputElement>)=>{const next=event.target.files?.[0]||null;setFile(next);setActiveJob(null);setRows([]);setError('');setMessage('');setIdempotencyKey(crypto.randomUUID())}
  const upload=async()=>{if(!file)return;setBusy(true);setError('');setMessage('');try{const form=new FormData();form.append('file',file);const result=await api<StageResult>(`/api/v1/schools/${schoolId}/imports?type=${type}`,{method:'POST',headers:{'Idempotency-Key':idempotencyKey},body:form});setMessage(result.idempotentReplay?'Đã mở lại job import trước đó.':'File đã được đưa vào hàng đợi kiểm tra.');await loadJob(result.jobId)}catch(err){setError((err as Error).message)}finally{setBusy(false)}}
  const commit=async()=>{if(!activeJob)return;setBusy(true);setError('');try{await api(`/api/v1/schools/${schoolId}/imports/${activeJob.id}/commit`,{method:'POST',body:JSON.stringify({version:activeJob.version})});setMessage('Đã ghi dữ liệu hợp lệ vào hệ thống.');await loadJob(activeJob.id)}catch(err){setError((err as Error).message)}finally{setBusy(false)}}
  const downloadTemplate=async()=>{setError('');try{const result=await apiDownload(`/api/v1/schools/${schoolId}/imports/templates/${type}?format=${format}`);saveDownload(result.blob,result.fileName)}catch(err){setError((err as Error).message)}}
  const openJob=async(job:ImportJob)=>{window.clearTimeout(pollRef.current);setActiveJob(job);setType(job.importType);setError('');setMessage('');if(terminal.has(job.status))await loadRows(job.id);else await loadJob(job.id)}

  return <section className="bulk-data" aria-labelledby="bulk-data-title">
    <div className="bulk-data-heading"><div><p className="eyebrow">Onboarding hàng loạt</p><h3 id="bulk-data-title">Nhập dữ liệu có bước kiểm tra trước khi ghi</h3><p>CSV/XLSX được đưa vào staging, kiểm từng dòng và chỉ thay đổi dữ liệu thật khi bạn bấm Commit.</p></div><div className="bulk-security-note"><strong>Không nhập mật khẩu</strong><span>Tài khoản và mật khẩu tạm được quản lý riêng trong Bảo mật tài khoản.</span></div></div>
    <Status tone="success">{message}</Status><Status tone="error">{error}</Status>

    <div className="bulk-steps" aria-label="Quy trình import"><Step n="01" label="Chọn dữ liệu" active={!file}/><Step n="02" label="Tải mẫu & upload" active={Boolean(file&&!activeJob)}/><Step n="03" label="Kiểm tra" active={Boolean(activeJob&&activeJob.status!=='COMMITTED')}/><Step n="04" label="Commit" active={activeJob?.status==='PREVIEW_READY'}/></div>

    <div className="bulk-grid">
      <section className="bulk-card import-config"><div className="bulk-card-head"><div><span>Loại import</span><strong>{definition.label}</strong></div><select value={type} disabled={Boolean(activeJob&&!terminal.has(activeJob.status))} onChange={event=>{setType(event.target.value as ImportType);setFile(null);setActiveJob(null);setRows([]);setIdempotencyKey(crypto.randomUUID())}}>{definitions.map(item=><option key={item.type} value={item.type}>{item.label}</option>)}</select></div>
        <p>{definition.copy}</p><div className="schema-strip">{definition.columns.map(column=><code key={column}>{column}</code>)}</div>
        <div className="template-actions"><select value={format} onChange={event=>setFormat(event.target.value as Format)}><option value="CSV">CSV</option><option value="XLSX">XLSX</option></select><button onClick={()=>void downloadTemplate()}>Tải file mẫu</button></div>
        <label className="file-drop"><input type="file" accept=".csv,.xlsx" onChange={chooseFile}/><span>{file?'Đã chọn file':'Chọn CSV hoặc XLSX'}</span><strong>{file?.name||'Tối đa 5 MB · 5.000 dòng'}</strong><small>Upload chưa làm thay đổi dữ liệu học vụ.</small></label>
        <button className="primary bulk-upload" disabled={!file||busy} onClick={()=>void upload()}>{busy?'Đang xử lý…':'Tải lên & kiểm tra'}</button>
      </section>

      <section className="bulk-card job-state"><div className="bulk-card-head"><div><span>Job hiện tại</span><strong>{activeJob?statusLabel(activeJob.status):'Chưa có job'}</strong></div>{activeJob&&<JobBadge status={activeJob.status}/>}</div>
        {!activeJob?<div className="bulk-placeholder"><strong>Preview sẽ xuất hiện ở đây</strong><p>Chọn file và tải lên. Hệ thống sẽ kiểm format, khóa tự nhiên, tenant và tham chiếu trước.</p></div>:<>
          <div className="bulk-kpis"><Metric label="Tổng dòng" value={activeJob.totalRows}/><Metric label="Hợp lệ" value={activeJob.validRows}/><Metric label="Có lỗi" value={activeJob.invalidRows} danger={activeJob.invalidRows>0}/></div>
          {['QUEUED','PROCESSING'].includes(activeJob.status)&&<div className="processing-line"><span/><div><strong>Đang phân tích file…</strong><small>Có thể rời trang và mở lại từ lịch sử job.</small></div></div>}
          {activeJob.errorMessage&&<Status tone="error">{activeJob.errorMessage}</Status>}
          {activeJob.status==='VALIDATION_FAILED'&&<div className="commit-gate danger"><strong>Chưa thể commit</strong><span>Sửa tất cả dòng lỗi trong file rồi upload lại với một job mới.</span></div>}
          {activeJob.status==='PREVIEW_READY'&&<div className="commit-gate success"><div><strong>Sẵn sàng commit</strong><span>Không có lỗi validation. Đây là bước đầu tiên làm thay đổi dữ liệu thật.</span></div><button className="primary" disabled={busy} onClick={()=>void commit()}>Commit {activeJob.validRows} dòng</button></div>}
          {activeJob.status==='COMMITTED'&&<div className="commit-gate success"><strong>Đã hoàn tất</strong><span>{activeJob.totalRows} dòng đã được xử lý. Retry commit không tạo dữ liệu trùng.</span></div>}
        </>}
      </section>
    </div>

    {activeJob&&rows.length>0&&<section className="bulk-preview"><div className="bulk-preview-head"><div><span className="eyebrow">Preview dòng dữ liệu</span><h4>{activeJob.sourceFileName}</h4></div><select value={rowFilter} onChange={event=>setRowFilter(event.target.value)}><option value="">Tất cả</option><option value="INVALID">Chỉ dòng lỗi</option><option value="VALID">Chỉ hợp lệ</option><option value="COMMITTED">Đã commit</option></select></div><div className="preview-list">{rows.map(row=><article key={row.rowNumber} className={row.status==='INVALID'?'invalid':''}><div className="preview-line"><strong>Dòng {row.rowNumber}</strong><Badge tone={row.status==='INVALID'?'danger':row.status==='COMMITTED'?'success':'info'}>{row.status}</Badge></div><div className="preview-values">{Object.entries(row.normalizedData).filter(([,v])=>v!==null&&v!=='').slice(0,6).map(([key,value])=><span key={key}><small>{key}</small>{String(value)}</span>)}</div>{row.errors.length>0&&<ul className="row-errors">{row.errors.map(item=><li key={item}>{item}</li>)}</ul>}{row.warnings.length>0&&<ul className="row-warnings">{row.warnings.map(item=><li key={item}>{item}</li>)}</ul>}</article>)}</div></section>}

    <ExportPanel schoolId={schoolId}/>

    <section className="bulk-history"><div className="bulk-preview-head"><div><span className="eyebrow">Lịch sử gần đây</span><h4>Import jobs</h4></div><button onClick={()=>void loadJobs()}>Làm mới</button></div>{jobs.length?<div className="job-list">{jobs.slice(0,8).map(job=><button key={job.id} onClick={()=>void openJob(job)}><div><strong>{definitionLabel(job.importType)}</strong><span>{job.sourceFileName}</span></div><div><JobBadge status={job.status}/><small>{new Date(job.createdAt).toLocaleString('vi-VN')}</small></div></button>)}</div>:<Empty text="Chưa có lịch sử import."/>}</section>
  </section>
}

function ExportPanel({schoolId}:{schoolId:string}){
  const [type,setType]=useState('STUDENTS'),[format,setFormat]=useState<Format>('XLSX'),[classes,setClasses]=useState<Row[]>([]),[classroomId,setClassroomId]=useState(''),[from,setFrom]=useState(''),[to,setTo]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState('')
  const scoped=['ROSTER','ATTENDANCE','SCORES'].includes(type)
  useEffect(()=>{void api<Row[]>(`/api/v1/schools/${schoolId}/classrooms`).then(rows=>{setClasses(rows);setClassroomId(current=>current||String(rows[0]?.id||''))}).catch(()=>undefined)},[schoolId])
  const download=async()=>{setBusy(true);setError('');try{const q=new URLSearchParams({format});if(scoped)q.set('classroomId',classroomId);if(type!=='ROSTER'&&scoped){if(from)q.set('from',from);if(to)q.set('to',to)}const result=await apiDownload(`/api/v1/schools/${schoolId}/exports/${type}?${q}`);saveDownload(result.blob,result.fileName)}catch(err){setError((err as Error).message)}finally{setBusy(false)}}
  return <section className="bulk-export"><div className="bulk-preview-head"><div><span className="eyebrow">Xuất dữ liệu</span><h4>File phục vụ vận hành & đối soát</h4></div></div><Status tone="error">{error}</Status><div className="export-controls"><label><span>Dữ liệu</span><select value={type} onChange={event=>setType(event.target.value)}><option value="STUDENTS">Danh sách học sinh</option><option value="TEACHERS">Danh sách giáo viên</option><option value="GUARDIANS">Danh sách phụ huynh</option><option value="ROSTER">Danh sách lớp</option><option value="ATTENDANCE">Điểm danh</option><option value="SCORES">Điểm đã công bố</option></select></label>{scoped&&<label><span>Lớp</span><select value={classroomId} onChange={event=>setClassroomId(event.target.value)}>{classes.map(row=><option key={String(row.id)} value={String(row.id)}>{String(row.name||row.code||'Lớp')}</option>)}</select></label>}{scoped&&type!=='ROSTER'&&<><label><span>Từ ngày</span><input type="date" value={from} onChange={event=>setFrom(event.target.value)}/></label><label><span>Đến ngày</span><input type="date" value={to} onChange={event=>setTo(event.target.value)}/></label></>}<label><span>Định dạng</span><select value={format} onChange={event=>setFormat(event.target.value as Format)}><option value="XLSX">XLSX</option><option value="CSV">CSV</option></select></label><button className="primary" disabled={busy||(scoped&&!classroomId)} onClick={()=>void download()}>{busy?'Đang tạo…':'Xuất file'}</button></div></section>
}

function Step({n,label,active}:{n:string;label:string;active:boolean}){return <div className={active?'active':''}><span>{n}</span><strong>{label}</strong></div>}
function Metric({label,value,danger=false}:{label:string;value:number;danger?:boolean}){return <div className={danger?'danger':''}><span>{label}</span><strong>{value}</strong></div>}
function JobBadge({status}:{status:string}){const tone=status==='COMMITTED'||status==='PREVIEW_READY'?'success':status==='VALIDATION_FAILED'||status==='FAILED'?'danger':status==='PROCESSING'||status==='QUEUED'?'info':'neutral';return <Badge tone={tone}>{statusLabel(status)}</Badge>}
function statusLabel(status:string){return({QUEUED:'Đang chờ',PROCESSING:'Đang kiểm tra',PREVIEW_READY:'Sẵn sàng commit',VALIDATION_FAILED:'Có lỗi cần sửa',COMMITTING:'Đang ghi dữ liệu',COMMITTED:'Đã hoàn tất',FAILED:'Xử lý thất bại'} as Record<string,string>)[status]||status}
function definitionLabel(type:ImportType){return definitions.find(item=>item.type===type)?.label||type}
