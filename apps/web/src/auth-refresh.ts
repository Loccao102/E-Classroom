export type RefreshTokens = {accessToken:string;refreshToken:string};

/**
 * Deduplicate refresh-token exchanges inside one browser tab.
 * Login/logout can change sessions while an exchange is in flight: never
 * overwrite a different session, nor erase its tokens on an old 401.
 */
export function createRefreshGate(
  read:()=>RefreshTokens|null,
  commit:(tokens:RefreshTokens)=>void,
  clear:()=>void,
  exchange:(refreshToken:string)=>Promise<RefreshTokens>
) {
  let pending:Promise<string>|null=null;
  function refresh():Promise<string>{
    if(pending)return pending;
    const source=read()?.refreshToken;
    if(!source)return Promise.reject(new Error("Session has no refresh token"));
    const task=(async()=>{
      try {
        const next=await exchange(source);
        if(!next?.accessToken||!next.refreshToken)throw new Error("Invalid refresh response");
        // A newer login/logout wins. Do not replay an old request using another identity.
        if(read()?.refreshToken!==source)throw new Error("Session changed during refresh");
        commit(next);
        return next.accessToken;
      } catch(error) {
        // Only clear the session that actually attempted the failing exchange.
        if(read()?.refreshToken===source)clear();
        throw error;
      }
    })();
    pending=task.finally(()=>{pending=null;});
    return pending;
  }
  return {refresh};
}
