import {flushPromises,mount} from '@vue/test-utils';
import {beforeEach,expect,it,vi} from 'vitest';
const api=vi.hoisted(()=>({session:vi.fn(),options:vi.fn(),history:vi.fn(),manual:vi.fn(),confirm:vi.fn(),reject:vi.fn(),user:vi.fn(),keys:vi.fn(),debit:vi.fn()}));
vi.mock('./api',async original=>({...await original<object>(),refreshSession:api.session,paymentAdminOptionsGetApiV1AdminPaymentsRechargeOptions:api.options,
 paymentAdminHistoryGetApiV1AdminPaymentsRecharges:api.history,paymentManualPostApiV1AdminPaymentsRecharges:api.manual,
 paymentConfirmManualPostApiV1AdminPaymentsRechargesIdConfirm:api.confirm,paymentRejectManualPostApiV1AdminPaymentsRechargesIdReject:api.reject,userDetailGetApiV1AdminUsersId:api.user,accessAdminListGetApiV1AdminAccessKeys:api.keys,billingDebitPostApiV1AdminBillingDebits:api.debit}));
import RechargeManagement from './RechargeManagement.vue';
beforeEach(()=>{Object.defineProperty(HTMLDialogElement.prototype,'showModal',{configurable:true,value(){this.setAttribute('open','');}});Object.defineProperty(HTMLDialogElement.prototype,'close',{configurable:true,value(){this.removeAttribute('open');}});vi.clearAllMocks();sessionStorage.clear();api.user.mockResolvedValue({data:{id:'verified-id',nickname:'小星'}});api.keys.mockResolvedValue({data:[{keyId:'real-key',ownerId:'verified-id'}]});api.session.mockResolvedValue({authenticated:true,user:{id:"admin"}});api.history.mockResolvedValue({data:[]});api.options.mockResolvedValue({data:{userId:'verified-id',accountType:'ENTERPRISE',firstRecharge:false,pointsPerYuan4:'10.0000',rules:[{accountType:'ENTERPRISE',minimumAmount4:'2000.0000',presetAmounts4:['2000.0000','5000.0000','10000.0000','50000.0000']}]}});});
it('uses the real target identity and waits for explicit manual submission',async()=>{
 const wrapper=mount(RechargeManagement);await flushPromises();
 await wrapper.get('input[aria-label="目标用户 ID"]').setValue('verified-id');await wrapper.get('[data-testid="load-target"]').trigger('click');await flushPromises();
 expect(api.options).toHaveBeenCalledWith({userId:'verified-id'});
 expect(wrapper.text()).toContain('企业');expect(wrapper.text()).toContain('2000');
 expect(wrapper.find('select[aria-label="充值身份"]').exists()).toBe(false);
 expect(wrapper.text()).toContain('审核确认');expect(api.manual).not.toHaveBeenCalled();
});
it('submits a reviewed yuan request and requires a separate explicit confirmation',async()=>{
 const order={orderId:'manual-1',userId:'verified-id',accountType:'ENTERPRISE',amount4:'2000.0000',expectedPoints4:'20000.0000',source:'MANUAL',status:'REVIEW_PENDING',remark:'到账凭证',submittedBy:'admin'};
 api.manual.mockResolvedValue({data:order});api.confirm.mockResolvedValue({data:{...order,status:'PAID',fulfilled:false}});
 const wrapper=mount(RechargeManagement);await flushPromises();await wrapper.get('input[aria-label="目标用户 ID"]').setValue('verified-id');await wrapper.get('[data-testid="load-target"]').trigger('click');await flushPromises();
 await wrapper.get('input[aria-label="企业名称"]').setValue('企业');
 await wrapper.get('input[aria-label="到账凭证与审核备注"]').setValue('到账凭证');
 api.history.mockResolvedValue({data:[order]});await wrapper.get('form').trigger('submit');await flushPromises();
 expect(api.manual).toHaveBeenCalledWith(expect.objectContaining({userId:'verified-id',amount4:'2000.00',accountType:'ENTERPRISE',remark:'到账凭证'}));expect(api.confirm).not.toHaveBeenCalled();
 await wrapper.findAll('button').find(b=>b.text()==='通过')!.trigger('click');expect(api.confirm).not.toHaveBeenCalled();
 await wrapper.findAll('button').find(b=>b.text()==='确认已收到人民币款项')!.trigger('click');await flushPromises();expect(api.confirm).toHaveBeenCalledWith('manual-1');
});
it('submits API funding for review without crediting directly, and clears a changed target',async()=>{
 api.options.mockResolvedValue({data:{userId:'verified-id',accountType:'API',firstRecharge:false,pointsPerYuan4:'10.0000',rules:[{accountType:'API',minimumAmount4:'5000.0000',presetAmounts4:['5000.0000']}]}});
 api.manual.mockResolvedValue({data:{orderId:'api-pending',status:'REVIEW_PENDING',fulfilled:false}});
 const wrapper=mount(RechargeManagement);await flushPromises();await wrapper.get('input[aria-label="目标用户 ID"]').setValue('verified-id');await wrapper.get('[data-testid="load-target"]').trigger('click');await flushPromises();
 await wrapper.get('input[aria-label="到账凭证与审核备注"]').setValue('已收到转账，提交审核');
 await wrapper.get('form').trigger('submit');await flushPromises();
 expect(api.manual).toHaveBeenCalledWith(expect.objectContaining({userId:'verified-id',accountType:'API',amount4:'5000.00',requestId:expect.any(String)}));
 expect(api.confirm).not.toHaveBeenCalled();expect(api.debit).not.toHaveBeenCalled();expect(wrapper.text()).toContain('尚未到账');
 await wrapper.get('input[aria-label="目标用户 ID"]').setValue('different-id');expect(wrapper.find('form').exists()).toBe(false);
});

it('freezes and restores a lost-response manual request with its original request ID',async()=>{
 api.manual.mockRejectedValueOnce(new TypeError('lost response')).mockResolvedValueOnce({data:{orderId:'only-order'}});
 const wrapper=mount(RechargeManagement);await flushPromises();await wrapper.get('input[aria-label="目标用户 ID"]').setValue('verified-id');await wrapper.get('[data-testid="load-target"]').trigger('click');await flushPromises();
 await wrapper.get('input[aria-label="企业名称"]').setValue('企业');
 await wrapper.get('input[aria-label="到账凭证与审核备注"]').setValue('真实凭证');await wrapper.get('form').trigger('submit');await flushPromises();
 const original=api.manual.mock.calls[0][0];expect(wrapper.get('input[aria-label="目标用户 ID"]').attributes('disabled')).toBeDefined();expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined();
 expect(wrapper.get('[data-testid="load-target"]').attributes('disabled')).toBeDefined();wrapper.unmount();
 const restored=mount(RechargeManagement);await flushPromises();expect(restored.text()).toContain('原人工申请尚未确认受理');await restored.findAll('button').find(b=>b.text()==='重试原人工申请')!.trigger('click');await flushPromises();
 expect(api.manual.mock.calls[1][0]).toEqual(original);expect(sessionStorage.getItem('xzwl-amount-adjustment:admin:verified-id')).toBeNull();
});

it.each([false,true])('refreshes the selected account rules after confirmation and preserves draft/pending=%s',async unresolved=>{
 const first={userId:'verified-id',accountType:'PERSONAL',firstRecharge:true,pointsPerYuan4:'10.0000',rules:[{accountType:'PERSONAL',minimumAmount4:'50.0000',presetAmounts4:['50.0000']}]};
 const order={orderId:'first-order',userId:'verified-id',accountType:'PERSONAL',amount4:'50.0000',expectedPoints4:'500.0000',source:'MANUAL',status:'REVIEW_PENDING'};
 api.options.mockResolvedValueOnce({data:first}).mockResolvedValue({data:{...first,firstRecharge:false,rules:[{accountType:'PERSONAL',minimumAmount4:'100.0000',presetAmounts4:['100.0000']}]}});api.history.mockResolvedValue({data:[order]});api.confirm.mockResolvedValue({data:{...order,status:'PAID',fulfilled:true}});
 const wrapper=mount(RechargeManagement);await flushPromises();await wrapper.get('input[aria-label="目标用户 ID"]').setValue('verified-id');await wrapper.get('[data-testid="load-target"]').trigger('click');await flushPromises();await wrapper.get('input[aria-label="到账凭证与审核备注"]').setValue('保留备注');
 if(unresolved){api.manual.mockRejectedValue(new TypeError('lost response'));await wrapper.get('form').trigger('submit');await flushPromises();}
 const original=sessionStorage.getItem('xzwl-amount-adjustment:admin:verified-id');
 await wrapper.findAll('button').find(b=>b.text()==='通过')!.trigger('click');await wrapper.findAll('button').find(b=>b.text()==='确认已收到人民币款项')!.trigger('click');await flushPromises();
 expect(api.options).toHaveBeenCalledTimes(2);expect(wrapper.get('.amount-presets').text()).toContain('¥ 100');expect(wrapper.text()).not.toContain('充值最低');expect((wrapper.get('input[aria-label="人民币金额（元）"]').element as HTMLInputElement).value).toBe('50.00');expect((wrapper.get('input[aria-label="到账凭证与审核备注"]').element as HTMLInputElement).value).toBe('保留备注');expect(sessionStorage.getItem('xzwl-amount-adjustment:admin:verified-id')).toBe(original);
 if(unresolved)expect(wrapper.text()).toContain('重试原人工申请');
});

it('shows nickname and read-only details with distinct pending review actions',async()=>{
 const order={orderId:'review-1',userId:'verified-id',amount4:'12.3456',rateSnapshot4:'10.0000',expectedPoints4:'123.4560',accountType:'PERSONAL',source:'MANUAL',status:'REVIEW_PENDING',createdAt:'2026-09-14T01:00:00Z',remark:'银行到账凭证',submittedBy:'submitter'};
 api.history.mockResolvedValue({data:[order,{...order,orderId:'paid',status:'PAID',fulfilled:true}]});
 const wrapper=mount(RechargeManagement);await flushPromises();
 expect(wrapper.findAll('th').map(n=>n.text())).toContain('用户昵称');expect(wrapper.text()).toContain('小星');expect(api.user).toHaveBeenCalledTimes(1);
 expect(wrapper.findAll('button').filter(b=>b.text()==='通过')).toHaveLength(1);expect(wrapper.findAll('button').filter(b=>b.text()==='拒绝')).toHaveLength(1);
 await wrapper.findAll('button').find(b=>b.text()==='详情')!.trigger('click');await flushPromises();
 const detail=wrapper.get('dialog[aria-label="充值详情"]');expect(detail.attributes('open')).toBeDefined();expect(wrapper.find('tbody dialog').exists()).toBe(false);expect(detail.text()).toContain('review-1');expect(detail.text()).toContain('小星');expect(detail.text()).toContain('银行到账凭证');expect(detail.text()).toContain('12.34');
 expect(api.confirm).not.toHaveBeenCalled();expect(api.reject).not.toHaveBeenCalled();
 await detail.get('[aria-label="关闭充值详情"]').trigger('click');expect(wrapper.find('dialog').exists()).toBe(false);
 await wrapper.findAll('button').find(b=>b.text()==='详情')!.trigger('click');await flushPromises();await wrapper.get('dialog').trigger('cancel');expect(wrapper.find('dialog').exists()).toBe(false);
 await wrapper.findAll('button').find(b=>b.text()==='详情')!.trigger('click');await flushPromises();await wrapper.get('dialog').trigger('click');expect(wrapper.find('dialog').exists()).toBe(false);
});
it('rejects once while busy and displays the persisted rejected state without confirming funds',async()=>{
 const order={orderId:'review-1',userId:'verified-id',amount4:'50.0000',source:'MANUAL',status:'REVIEW_PENDING'};
 api.history.mockResolvedValue({data:[order]});let finish!:(value:unknown)=>void;api.reject.mockImplementation(()=>new Promise(resolve=>{finish=resolve;}));
 const wrapper=mount(RechargeManagement);await flushPromises();
 const reject=wrapper.findAll('button').find(b=>b.text()==='拒绝')!;await reject.trigger('click');await reject.trigger('click');
 expect(api.reject).toHaveBeenCalledTimes(1);expect(api.reject).toHaveBeenCalledWith('review-1');expect(reject.attributes('disabled')).toBeDefined();
 api.history.mockResolvedValue({data:[{...order,status:'REJECTED',rejectedBy:'reviewer',rejectedAt:'2026-09-14T02:00:00Z'}]});finish({data:{...order,status:'REJECTED'}});await flushPromises();
 expect(wrapper.text()).toContain('已拒绝');expect(wrapper.findAll('button').some(b=>b.text()==='通过'||b.text()==='拒绝')).toBe(false);expect(api.confirm).not.toHaveBeenCalled();
});
it('keeps records visible if nickname lookup fails and retains failed review for retry',async()=>{
 api.history.mockResolvedValue({data:[{orderId:'review-1',userId:'verified-id',source:'MANUAL',status:'REVIEW_PENDING'}]});api.user.mockRejectedValue(new Error('unavailable'));api.reject.mockRejectedValue(new Error('network'));
 const wrapper=mount(RechargeManagement);await flushPromises();expect(wrapper.text()).toContain('review-1');
 await wrapper.findAll('button').find(b=>b.text()==='拒绝')!.trigger('click');await flushPromises();
 expect(wrapper.find('[role="alert"]').exists()).toBe(true);expect(wrapper.findAll('button').find(b=>b.text()==='拒绝')!.attributes('disabled')).toBeUndefined();
});
