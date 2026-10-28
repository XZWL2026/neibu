import '@testing-library/jest-dom/vitest';
import {render,screen,fireEvent,waitFor,cleanup} from '@testing-library/react';
import {MemoryRouter} from 'react-router-dom';
import {afterEach,beforeEach,it,expect,vi} from 'vitest';
import {LivePromotionProvider,LiveReferralsPage,LiveApplicationPage,LivePromotionLinks} from './LivePromotion';
const calls=vi.hoisted(()=>({read:vi.fn(),rewards:vi.fn(),submit:vi.fn(),session:{userId:'real-user',fixture:false,authenticated:true,accountType:'PERSONAL' as string|null,status:'ready'}}));
vi.mock('../api',async original=>({...await original<object>(),promotionParticipationGetApiV1PromotionsParticipation:calls.read,promotionRewardsGetApiV1PromotionsRewards:calls.rewards,promotionSubmitPostApiV1PromotionsApplications:calls.submit}));
vi.mock('../userSession',async original=>({...await original<object>(),useUserSession:()=>calls.session}));
const snapshot={accountType:'PERSONAL',eligible:true,invitationCode:'REAL-INVITE',application:null,promoter:null,directInvites:{items:[{nickname:'受邀用户',maskedPhone:'138****1234',joinedAt:'2026-09-12T00:00:00Z'}],total:1,page:0,size:20}};
const show=(page='invites')=>render(<MemoryRouter><LivePromotionProvider>{page==='invites'?<LiveReferralsPage/>:<LiveApplicationPage/>}</LivePromotionProvider></MemoryRouter>);
beforeEach(()=>{sessionStorage.clear();calls.session.userId='real-user';calls.session.authenticated=true;calls.session.accountType='PERSONAL';calls.session.status='ready';calls.read.mockReset().mockResolvedValue({data:snapshot});calls.submit.mockReset();calls.rewards.mockReset().mockResolvedValue({data:{earnedPoints4:'0.0000',postedPoints4:'0.0000',reversedPoints4:'0.0000',debtPoints4:'0.0000',items:[],page:0,size:20,total:0}});});
afterEach(()=>{cleanup();vi.unstubAllGlobals();});
it('uses the real invitation code and owner-scoped direct invites, copying the full registration link',async()=>{
 const copy=vi.fn().mockResolvedValue(undefined);Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:copy}});show();
 expect(await screen.findByText('受邀用户')).toBeVisible();expect(screen.getByText('138****1234')).toBeVisible();
 expect(screen.queryByText(/NOVA88|POINT_REWARD/)).not.toBeInTheDocument();
 fireEvent.click(screen.getByRole('button',{name:'复制邀请链接'}));await waitFor(()=>expect(copy).toHaveBeenCalledWith(expect.stringContaining('/home?ref=REAL-INVITE')));
 fireEvent.click(screen.getByRole('button',{name:'刷新'}));await waitFor(()=>expect(calls.read).toHaveBeenCalledTimes(2));
});
it('keeps a failed read as an error instead of inventing empty records',async()=>{calls.read.mockRejectedValue(new Error('offline'));show();expect(await screen.findByRole('alert')).toHaveTextContent('无法读取');expect(screen.queryByText('暂无直接邀请用户')).not.toBeInTheDocument();});
it('submits once and restores the exact failed application request across remount',async()=>{
 calls.submit.mockRejectedValueOnce(new Error('lost response')).mockImplementationOnce(async body=>{calls.read.mockResolvedValue({data:{...snapshot,application:{...body,id:body.applicationId,status:'PENDING',submittedAt:'2026-09-12T00:00:00Z'}}});return {data:{...body,status:'PENDING'}};});
 const first=show('apply');await screen.findByLabelText('申请理由');fireEvent.change(screen.getByLabelText('申请理由'),{target:{value:'申请代理'}});fireEvent.change(screen.getByLabelText('计划推广渠道'),{target:{value:'社群'}});fireEvent.click(screen.getByRole('checkbox'));
 fireEvent.click(screen.getByRole('button',{name:'提交推广申请'}));await screen.findByRole('alert');const body=calls.submit.mock.calls[0][0];expect(body).toMatchObject({reason:'申请代理',plannedChannel:'社群',agreementsAccepted:true});first.unmount();
 show('apply');fireEvent.click(await screen.findByRole('button',{name:'重试原申请'}));await screen.findByRole('heading',{name:'代理申请审核中'});expect(calls.submit.mock.calls[1][0]).toEqual(body);expect(sessionStorage.getItem('xzwl-agent-application:real-user')).toBeNull();
});
it('shows the server rejection or the existing agent identity',async()=>{
 calls.read.mockResolvedValue({data:{...snapshot,application:{status:'REJECTED',rejectionReason:'补充渠道'}}});const first=show('apply');expect(await screen.findByText('拒绝原因：补充渠道')).toBeVisible();first.unmount();
 calls.read.mockResolvedValue({data:{...snapshot,promoter:{status:'ENABLED',invitationCode:'REAL-INVITE'}}});show('apply');expect(await screen.findByText('你已成为代理人。')).toBeVisible();expect(screen.queryByRole('button',{name:'提交推广申请'})).not.toBeInTheDocument();
});

it('preserves the direct application form while hiding its menu entry for an unactivated user',async()=>{
 calls.session.accountType=null;calls.session.status='not-open';
 const unset={...snapshot,accountType:'UNSET',eligible:false,invitationCode:null};calls.read.mockResolvedValue({data:unset});
 calls.submit.mockImplementation(async body=>{calls.read.mockResolvedValue({data:{...unset,application:{id:body.applicationId,status:'PENDING'}}});return {data:{...body,status:'PENDING'}};});
 render(<MemoryRouter><LivePromotionProvider><LivePromotionLinks/><LiveApplicationPage/></LivePromotionProvider></MemoryRouter>);
 expect(screen.queryByRole('link',{name:'申请成为代理'})).not.toBeInTheDocument();expect(screen.queryByRole('link',{name:'邀请有礼'})).not.toBeInTheDocument();
 fireEvent.change(await screen.findByLabelText('申请理由'),{target:{value:'推广内容'}});fireEvent.change(screen.getByLabelText('计划推广渠道'),{target:{value:'社群'}});fireEvent.click(screen.getByRole('checkbox'));
 fireEvent.click(screen.getByRole('button',{name:'提交推广申请'}));await screen.findByRole('heading',{name:'代理申请审核中'});expect(calls.submit).toHaveBeenCalledTimes(1);
 expect(calls.submit.mock.calls[0][0]).not.toHaveProperty('accountType');expect(screen.queryByRole('link',{name:'邀请有礼'})).not.toBeInTheDocument();
});
it('keeps disabled and anonymous users from the application form and entry',async()=>{
 calls.session.status='disabled';calls.read.mockResolvedValue({data:{...snapshot,eligible:false,accountType:'UNSET'}});
 const first=render(<MemoryRouter><LivePromotionProvider><LivePromotionLinks/><LiveApplicationPage/></LivePromotionProvider></MemoryRouter>);await waitFor(()=>expect(calls.read).toHaveBeenCalled());
 expect(screen.queryByRole('link',{name:'申请成为代理'})).not.toBeInTheDocument();expect(screen.queryByRole('button',{name:'提交推广申请'})).not.toBeInTheDocument();first.unmount();
 calls.session.authenticated=false;show('apply');expect(screen.getByRole('heading',{name:'请先登录'})).toBeVisible();expect(screen.queryByRole('button',{name:'提交推广申请'})).not.toBeInTheDocument();
});

it('shows server reward totals and actual posting receipts without summing the current page',async()=>{
 const {LiveRewardRecords}=await import('./LivePromotion');
 const onPage=vi.fn();
 render(<LiveRewardRecords snapshot={{earnedPoints4:'70.1234',postedPoints4:'60.0000',reversedPoints4:'5.0000',debtPoints4:'2.0000',page:0,size:20,total:41,items:[{eventId:'actual-reward',action:'GRANT',points4:'25.0000',status:'DELIVERED',settlementId:'actual-settlement',allocationId:'actual-allocation',orderId:'actual-order',creditedPoints4:'23.0000',recoveredPoints4:'0.0000',debtPoints4:'0.0000',occurredAt:'2026-09-15T00:00:00Z'}]}} busy={false} error="" onPage={onPage}/>);
 expect(screen.getByText('70.1234 积分')).toBeVisible();expect(screen.getByText('23.0000')).toBeVisible();expect(screen.getByText('共 41 条')).toBeVisible();
 fireEvent.click(screen.getByRole('button',{name:'下一页积分'}));expect(onPage).toHaveBeenCalledWith(1);
 expect(screen.queryByText('before')).not.toBeInTheDocument();
});

it('loads real owner reward pages and keeps a failed response as an error',async()=>{
 calls.rewards.mockRejectedValueOnce(new Error('offline'));show();
 expect(await screen.findByText('积分奖励记录加载失败，请重试。')).toBeVisible();
 expect(screen.queryByText('暂无积分奖励记录')).not.toBeInTheDocument();
 fireEvent.click(screen.getByRole('button',{name:'刷新积分记录'}));
 expect(await screen.findByText('暂无积分奖励记录')).toBeVisible();expect(calls.rewards).toHaveBeenLastCalledWith({page:0,size:20});
});
it('discards a previous owner reward response after the user changes',async()=>{
 let finish!:(value:unknown)=>void;calls.rewards.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve;}));
 const view=show();await waitFor(()=>expect(calls.rewards).toHaveBeenCalledTimes(1));
 calls.session.userId='another-user';view.rerender(<MemoryRouter><LivePromotionProvider><LiveReferralsPage/></LivePromotionProvider></MemoryRouter>);
 await waitFor(()=>expect(calls.rewards).toHaveBeenCalledTimes(2));
 finish({data:{earnedPoints4:'999.0000',postedPoints4:'999.0000',reversedPoints4:'0.0000',debtPoints4:'0.0000',items:[],page:0,size:20,total:0}});
 await waitFor(()=>expect(screen.getByText('暂无积分奖励记录')).toBeVisible());expect(screen.queryByText('999.0000 积分')).not.toBeInTheDocument();
});

 it('shares real reward totals with the overview and preserves invite paging',async()=>{
  calls.read.mockResolvedValueOnce({data:{...snapshot,directInvites:{...snapshot.directInvites,total:41}}}).mockResolvedValue({data:{...snapshot,directInvites:{...snapshot.directInvites,items:[],total:0}}});
  show();
  expect(await screen.findByRole('heading',{name:'我的邀请码'})).toBeVisible();
  expect(screen.getByRole('heading',{name:'邀请记录'})).toBeVisible();
  expect(screen.getByRole('heading',{name:'奖励记录'})).toBeVisible();
  expect(screen.getByLabelText('已邀请好友')).toHaveTextContent('41');
  await waitFor(()=>expect(screen.getByLabelText('累计奖励')).toHaveTextContent('0.00'));
  expect(calls.rewards).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button',{name:'下一页'}));
  await waitFor(()=>expect(calls.read).toHaveBeenLastCalledWith({page:1,size:20}));
  expect(calls.rewards).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button',{name:'上一页'}));
  await waitFor(()=>expect(calls.read).toHaveBeenLastCalledWith({page:0,size:20}));
 });

it('keeps the previous-page action when a reward page becomes empty',async()=>{
 const {LiveRewardRecords}=await import('./LivePromotion');const onPage=vi.fn();
 render(<LiveRewardRecords snapshot={{earnedPoints4:'0.0000',postedPoints4:'0.0000',reversedPoints4:'0.0000',debtPoints4:'0.0000',page:1,size:20,total:0,items:[]}} busy={false} error="" onPage={onPage}/>);
 fireEvent.click(screen.getByRole('button',{name:'上一页积分'}));expect(onPage).toHaveBeenCalledWith(0);
});
