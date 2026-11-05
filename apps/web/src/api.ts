import {createRefreshGate} from "./auth-refresh"
export type TokenResponse={accessToken:string;refreshToken:string;expiresAt:string;tokenType:string;mustChangePassword?:boolean}
const ACCESS='eclassroom.access',REFRESH='eclassroom.refresh'
export const tokens={access:()=>localStorage.getItem(ACCESS),refresh:()=>localStorage.getItem(REFRESH),set:(t:TokenResponse)=>{localStorage.setItem(ACCESS,t.accessToken);localStorage.setItem(REFRESH,t.refreshToken)},clear:()=>{localStorage.removeItem(ACCESS);localStorage.removeItem(REFRESH)}}

export class ApiError extends Error{constructor(public status:number,public code:string,message:string){super(message)}}

async function parseError(response:Response){let message=`HTTP ${response.status}`,code='HTTP_ERROR';try{const body=await response.json() as {message?:string;code?:string};message=body.message||message;code=body.code||code}catch{}return new ApiError(response.status,code,message)}
// Separate explicit account changes from a successful refresh-token rotation.
let accountGeneration=0
if(typeof window!=="undefined"){
  window.addEventListener("storage",event=>{
    if(event.key===ACCESS||event.key===REFRESH||event.key===null)accountGeneration++
  })
}
const refreshGate=createRefreshGate(
  ()=>{const accessToken=tokens.access(),refreshToken=tokens.refresh();
    return accessToken&&refreshToken?{accessToken,refreshToken}:null},
  next=>{localStorage.setItem(ACCESS,next.accessToken);localStorage.setItem(REFRESH,next.refreshToken)},
  ()=>tokens.clear(),
  async refreshToken=>{
    const r=await fetch("/api/v1/auth/refresh",{method:"POST",headers:{"Content-Type":"application/json"},
      body:JSON.stringify({refreshToken})})
    if(!r.ok)throw new ApiError(401,"SESSION_EXPIRED","Session expired")
    return await r.json() as TokenResponse
  }
)
async function refreshToken(){return refreshGate.refresh()}
async function authorizedFetch(path:string,init:RequestInit={},retry=true){
  const generation=accountGeneration
  const originalAccess=tokens.access()
  const headers=new Headers(init.headers)
  if(originalAccess)headers.set("Authorization",`Bearer ${originalAccess}`)
  if(init.body&&!(init.body instanceof FormData)&&!headers.has("Content-Type"))headers.set("Content-Type","application/json")
  let r=await fetch(path,{...init,headers})
  if(r.status===401&&retry&&tokens.refresh()){
    if(generation!==accountGeneration)
      throw new ApiError(401,"SESSION_CHANGED","Tài khoản đã thay đổi trong lúc tải dữ liệu.")
    let access=tokens.access()
    if(!access||access===originalAccess){
      try{access=await refreshToken()}
      catch{throw new ApiError(401,"SESSION_EXPIRED","Phiên đăng nhập đã hết hạn")}
    }
    if(generation!==accountGeneration||!access||!tokens.refresh())
      throw new ApiError(401,"SESSION_CHANGED","Tài khoản đã thay đổi trong lúc làm mới phiên.")
    headers.set("Authorization",`Bearer ${access}`)
    r=await fetch(path,{...init,headers})
  }
  return r
}
export async function api<T=unknown>(path:string,init:RequestInit={},retry=true):Promise<T>{const r=await authorizedFetch(path,init,retry);if(!r.ok)throw await parseError(r);if(r.status===204)return undefined as T;const text=await r.text();return(text?JSON.parse(text):undefined)as T}
export async function apiDownload(path:string,init:RequestInit={}){const r=await authorizedFetch(path,init);if(!r.ok)throw await parseError(r);const disposition=r.headers.get('Content-Disposition')||'';const utf=disposition.match(/filename\*=UTF-8''([^;]+)/i);const plain=disposition.match(/filename="?([^";]+)"?/i);const fileName=utf?decodeURIComponent(utf[1]):plain?.[1]||'download';return{blob:await r.blob(),fileName}}
export function saveDownload(blob:Blob,fileName:string){const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download=fileName;document.body.appendChild(a);a.click();a.remove();URL.revokeObjectURL(url)}
export async function login(email:string,password:string){const r=await fetch('/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email,password})});if(!r.ok)throw new ApiError(r.status,'INVALID_CREDENTIALS','Email hoặc mật khẩu không đúng');const t=await r.json() as TokenResponse;accountGeneration++;tokens.set(t);return t}
export async function logout(){
  const refreshToken=tokens.refresh()
  accountGeneration++
  // Revoke the local session immediately: an in-flight old refresh can no
  // longer restore it while the remote logout request is still pending.
  tokens.clear()
  if(!refreshToken)return
  await fetch("/api/v1/auth/logout",{method:"POST",headers:{"Content-Type":"application/json"},
    body:JSON.stringify({refreshToken})})
}
