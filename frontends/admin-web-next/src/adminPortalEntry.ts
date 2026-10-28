import {iamIssueAgentPostApiV1AdminAdminsIdAgentTicket,iamIssueApiUserPostApiV1AdminApiUsersIdAgentTicket} from './api';

// Both portal roles use the existing browser-bound, single-use admin handoff.
export async function enterAdminPortal(targetId:string,kind:'credential'|'api-user'='credential') {
 let tab:Window|null=null;
 try {
  tab=window.open('about:blank','_blank');
  if(!tab)throw new Error('Popup blocked');
  tab.opener=null;
  const hex=(bytes:Uint8Array)=>Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');
  const namespace=hex(crypto.getRandomValues(new Uint8Array(16)));
  const verifier=hex(crypto.getRandomValues(new Uint8Array(32)));
  tab.sessionStorage.clear();tab.sessionStorage.setItem('xzwl.portal.verifier',verifier);
  const challenge=hex(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(verifier))));
  if(tab.closed)throw new Error('Entry cancelled');
  const {data}=await (kind==='api-user'?iamIssueApiUserPostApiV1AdminApiUsersIdAgentTicket:iamIssueAgentPostApiV1AdminAdminsIdAgentTicket)(targetId,{challenge});
  if(tab.closed||!data.ticket||!/^[0-9a-f]{64}$/.test(data.ticket))throw new Error('Invalid handoff');
  tab.location.replace(`/agent/${namespace}/admin/portal-entry#${data.ticket}`);
 } catch(error) {if(tab&&!tab.closed)tab.close();throw error;}
}
