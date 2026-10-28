import { t, useLanguage } from './i18n';
import {useEffect, useRef, useState} from 'react';
import {iamOptionsGetApiV1AuthPortalOptions, iamIssuePostApiV1AuthPortalTickets} from './api';

export function PortalLoginLinks({enabled}: {enabled: boolean}) {
  useLanguage();
  const [roles,setRoles]=useState<string[]>([]);
  const [checking,setChecking]=useState(enabled);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const alive=useRef(false);
  const pending=useRef(false);
  useEffect(()=>{
    alive.current=true;
    let current=true;
    setRoles([]);
    setChecking(enabled);
    if(enabled) void (async()=>{
      try { const result=await iamOptionsGetApiV1AuthPortalOptions(); if(current) setRoles(result.data.roles ?? []); }
      catch { /* Keep API entry disabled and hide unverified promoter identity. */ }
      finally { if(current) setChecking(false); }
    })();
    return()=>{current=false;alive.current=false;};
  },[enabled]);
  async function enter(role:string) {
    if(role==='API_USER' && !busy && (!enabled || !roles.includes(role))) { window.location.assign('/api/login'); return; }
    if(pending.current || !enabled || !roles.includes(role)) return;
    pending.current=true;setBusy(true);setError('');
    let verifier='';
    let navigating=false;
    try {
      const hex=(bytes:Uint8Array)=>Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');
      verifier=hex(crypto.getRandomValues(new Uint8Array(32)));
      const challenge=hex(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(verifier))));
      sessionStorage.setItem('xzwl.portal.verifier',verifier);
      const result=await iamIssuePostApiV1AuthPortalTickets({role,challenge});
      if(alive.current && /^[0-9a-f]{64}$/.test(result.data.ticket ?? '')) {
        window.location.assign(`${role==='API_USER'?'/api/portal-entry':'/admin/portal-entry'}#${result.data.ticket}`);
        navigating=true;
        return;
      }
      throw new Error('登录失败');
    } catch {
      if(alive.current) setError('登录失败，请重试');
    } finally {
      if(!navigating) {
        try {if(sessionStorage.getItem('xzwl.portal.verifier')===verifier) sessionStorage.removeItem('xzwl.portal.verifier');} catch {}
      }
      pending.current=false;if(alive.current)setBusy(false);
    }
  }
  return <><span className="portal-login-links" aria-busy={busy}>
    {([['API_USER','API 登录'],['PROMOTER','推广登录']] as const).filter(([role])=>role!=='PROMOTER'||(enabled&&roles.includes(role))).map(([role,label])=>
      <button key={role} type="button" disabled={busy || (role==='API_USER' ? checking : !enabled || !roles.includes(role))} onClick={()=>void enter(role)}>{t(label)}</button>)}
  </span>{error && <span className="portal-login-error" role="alert">{t(error)}</span>}</>;
}
