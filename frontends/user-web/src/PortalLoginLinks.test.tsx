import '@testing-library/jest-dom/vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {webcrypto,createHash} from 'node:crypto';
import {PortalLoginLinks} from './PortalLoginLinks';
const api=vi.hoisted(()=>({options:vi.fn(),issue:vi.fn(),navigate:vi.fn()}));
vi.mock('./api',()=>({iamOptionsGetApiV1AuthPortalOptions:api.options,iamIssuePostApiV1AuthPortalTickets:api.issue}));
beforeEach(()=>{vi.resetAllMocks();sessionStorage.clear();vi.stubGlobal('crypto',webcrypto);vi.stubGlobal('location',{assign:api.navigate});api.options.mockResolvedValue({data:{roles:['API_USER','PROMOTER']}});api.issue.mockResolvedValue({data:{ticket:'d'.repeat(64)}});});
afterEach(()=>{cleanup();vi.unstubAllGlobals();});
it.each([['API_USER','API 登录'],['PROMOTER','推广登录']])('enters %s with a browser-bound ticket and no second password',async(role,label)=>{
 render(<PortalLoginLinks enabled/>);const button=await screen.findByRole('button',{name:label});await waitFor(()=>expect(button).toBeEnabled());fireEvent.click(button);
 await waitFor(()=>expect(api.navigate).toHaveBeenCalledWith((role==='API_USER'?'/api/portal-entry#':'/admin/portal-entry#')+'d'.repeat(64)));
 const verifier=sessionStorage.getItem('xzwl.portal.verifier')!;expect(verifier).toMatch(/^[0-9a-f]{64}$/);
 expect(api.issue).toHaveBeenCalledWith({role,challenge:createHash('sha256').update(verifier).digest('hex')});expect(api.navigate.mock.calls[0][0]).not.toContain(verifier);
});
it('opens the dedicated API login for anonymous visitors without issuing a ticket',async()=>{
 const view=render(<PortalLoginLinks enabled={false}/>);expect(api.options).not.toHaveBeenCalled();fireEvent.click(screen.getByRole('button',{name:'API 登录'}));expect(api.navigate).toHaveBeenCalledWith('/api/login');expect(api.issue).not.toHaveBeenCalled();
 api.options.mockResolvedValue({data:{roles:['API_USER']}});view.rerender(<PortalLoginLinks enabled/>);await waitFor(()=>expect(screen.getByRole('button',{name:'API 登录'})).toBeEnabled());expect(screen.queryByRole('button',{name:'推广登录'})).not.toBeInTheDocument();
});
it('blocks duplicate clicks and clears the verifier on failed issuance',async()=>{
 let reject!:(e:Error)=>void;api.issue.mockImplementation(()=>new Promise((_,r)=>reject=r));render(<PortalLoginLinks enabled/>);
 const button=screen.getByRole('button',{name:'API 登录'});await waitFor(()=>expect(button).toBeEnabled());fireEvent.click(button);fireEvent.click(button);await waitFor(()=>expect(api.issue).toHaveBeenCalledTimes(1));expect(button).toBeDisabled();reject(Error('offline'));
 await screen.findByRole('alert');expect(api.navigate).not.toHaveBeenCalled();expect(sessionStorage.getItem('xzwl.portal.verifier')).toBeNull();expect(button).toBeEnabled();
});
it('does not issue when browser storage is unavailable or redirect after unmount',async()=>{
 const store=vi.spyOn(Storage.prototype,'setItem').mockImplementation(()=>{throw new DOMException('blocked','SecurityError');});
 const view=render(<PortalLoginLinks enabled/>);const button=screen.getByRole('button',{name:'API 登录'});await waitFor(()=>expect(button).toBeEnabled());fireEvent.click(button);await screen.findByRole('alert');expect(api.issue).not.toHaveBeenCalled();store.mockRestore();
 let resolve!:(v:unknown)=>void;api.issue.mockImplementation(()=>new Promise(r=>resolve=r));fireEvent.click(button);await waitFor(()=>expect(api.issue).toHaveBeenCalledTimes(1));view.unmount();resolve({data:{ticket:'d'.repeat(64)}});await waitFor(()=>expect(sessionStorage.getItem('xzwl.portal.verifier')).toBeNull());expect(api.navigate).not.toHaveBeenCalled();
});

it('hides promoter entry when the server has no approved active promoter identity',async()=>{
 api.options.mockResolvedValue({data:{roles:[]}});render(<PortalLoginLinks enabled/>);
 await waitFor(()=>expect(api.options).toHaveBeenCalled());
 expect(screen.queryByRole('button',{name:'推广登录'})).not.toBeInTheDocument();
 await waitFor(()=>expect(screen.getByRole('button',{name:'API 登录'})).toBeEnabled());
});

it('hides promotion login while identity is unconfirmed, on lookup failure, and after sign out',async()=>{
 let reject!:(reason:Error)=>void;
 api.options.mockImplementationOnce(()=>new Promise((_,r)=>{reject=r;}));
 const view=render(<PortalLoginLinks enabled/>);
 expect(screen.queryByRole('button',{name:'推广登录'})).not.toBeInTheDocument();
 reject(Error('offline'));
 await waitFor(()=>expect(api.options).toHaveBeenCalledTimes(1));
 expect(screen.queryByRole('button',{name:'推广登录'})).not.toBeInTheDocument();
 view.rerender(<PortalLoginLinks enabled={false}/>);
 view.rerender(<PortalLoginLinks enabled/>);
 expect(await screen.findByRole('button',{name:'推广登录'})).toBeEnabled();
 view.rerender(<PortalLoginLinks enabled={false}/>);
 expect(screen.queryByRole('button',{name:'推广登录'})).not.toBeInTheDocument();
});
