import assert from "node:assert/strict"
import {test} from "node:test"
import {createRefreshGate} from "../src/auth-refresh.ts"

const pair=(accessToken,refreshToken)=>({accessToken,refreshToken})
const deferred=()=>{
  let resolve,reject
  const promise=new Promise((ok,fail)=>{resolve=ok;reject=fail})
  return {promise,resolve,reject}
}

test("twenty expired requests share one refresh exchange",async()=>{
  let current=pair("expired","refresh-0"),calls=0
  const wait=deferred()
  const gate=createRefreshGate(()=>current,next=>{current=next},()=>{current=null},async token=>{
    calls++
    assert.equal(token,"refresh-0")
    return await wait.promise
  })
  const results=Array.from({length:20},()=>gate.refresh())
  assert.equal(calls,1)
  wait.resolve(pair("valid","refresh-1"))
  assert.deepEqual(await Promise.all(results),Array(20).fill("valid"))
  assert.deepEqual(current,pair("valid","refresh-1"))
  assert.equal(await gate.refresh().then(value=>value), "valid") // each *new* explicit refresh is supported
  assert.equal(calls,2)
})

test("old refresh response never overwrites a new account",async()=>{
  let current=pair("old-access","old-refresh"),clears=0
  const wait=deferred()
  const gate=createRefreshGate(()=>current,next=>{current=next},()=>{clears++;current=null},async()=>wait.promise)
  const request=gate.refresh()
  current=pair("new-access","new-refresh")
  wait.resolve(pair("obsolete-access","obsolete-refresh"))
  await assert.rejects(request,/Session changed/)
  assert.deepEqual(current,pair("new-access","new-refresh"))
  assert.equal(clears,0)
})

test("logout while refreshing cannot restore a revoked session",async()=>{
  let current=pair("old-access","old-refresh"),clears=0
  const wait=deferred()
  const gate=createRefreshGate(()=>current,next=>{current=next},()=>{clears++;current=null},async()=>wait.promise)
  const request=gate.refresh()
  current=null
  wait.resolve(pair("late-access","late-refresh"))
  await assert.rejects(request,/Session changed/)
  assert.equal(current,null)
  assert.equal(clears,0)
})

test("a failed exchange clears only the matching account",async()=>{
  let current=pair("expired","refresh-0"),clears=0
  const gate=createRefreshGate(()=>current,next=>{current=next},()=>{clears++;current=null},async()=>{throw new Error("unauthorized")})
  await assert.rejects(gate.refresh(),/unauthorized/)
  assert.equal(current,null)
  assert.equal(clears,1)
})

test("missing refresh token avoids any network request",async()=>{
  let calls=0
  const gate=createRefreshGate(()=>null,()=>{},()=>{},async()=>{calls++;return pair("a","b")})
  await assert.rejects(gate.refresh(),/no refresh token/)
  assert.equal(calls,0)
})

test("malformed provider response never becomes an active session",async()=>{
  let current=pair("expired","refresh-0"),clears=0
  const gate=createRefreshGate(()=>current,next=>{current=next},()=>{clears++;current=null},async()=>({accessToken:"",refreshToken:"replacement"}))
  await assert.rejects(gate.refresh(),/Invalid refresh response/)
  assert.equal(current,null)
  assert.equal(clears,1)
})
