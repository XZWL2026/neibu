import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import ApiRecharge from './ApiRecharge.vue';
const api = vi.hoisted(() => ({ options: vi.fn(), create: vi.fn(), get: vi.fn() }));
vi.mock('./api', async importOriginal => ({ ...await importOriginal<typeof import('./api')>(),
 paymentOptionsGetApiV1PaymentsRechargeOptions: api.options,
 paymentCreatePostApiV1PaymentsRecharges: api.create,
 paymentGetGetApiV1PaymentsRechargesId: api.get }));
enableAutoUnmount(afterEach);
const rules = (userId = 'owner-a', accountType: string | null = null) => ({ userId, accountType, firstRecharge: true, pointsPerYuan4: '10.0000', rules: [{ accountType: 'API', minimumAmount4: '50.0000', presetAmounts4: ['50.0000', '100.0000'] }] });
const order = (extra = {}) => ({ orderId: 'order-a', userId: 'owner-a', amount4: '50.0000', accountType: 'API', paymentMethod: 'ALIPAY', status: 'PENDING', fulfilled: false, rateSnapshot4: '10.0000', expectedPoints4: '500.0000', checkoutUrl: 'https://pay.example.test/checkout', ...extra });
beforeEach(() => { vi.clearAllMocks(); sessionStorage.clear(); api.options.mockResolvedValue({ data: rules() }); api.create.mockResolvedValue({ data: order() }); api.get.mockResolvedValue({ data: order() }); });
afterEach(() => vi.useRealTimers());
const open = async () => { const wrapper = mount(ApiRecharge, { props: { owner: 'owner-a' } }); await flushPromises(); return wrapper; };
it('uses API rules, exact amounts and current-owner SDK checkout without binding on entry', async () => {
 const wrapper = await open(); expect(api.create).not.toHaveBeenCalled();
 expect(wrapper.text()).toContain('500');
 for (const invalid of ['49.99', '50.001']) { await wrapper.get('input').setValue(invalid); expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined(); }
 await wrapper.get('input').setValue('50'); await wrapper.get('[data-method="WECHAT"]').trigger('click');
 await wrapper.get('form').trigger('submit'); await flushPromises();
 expect(api.create.mock.calls[0][0]).toMatchObject({ accountType: 'API', amount4: '50', paymentMethod: 'WECHAT' });
 expect(api.create.mock.calls[0][0]).not.toHaveProperty('userId');
 expect(wrapper.get('img[alt="收钱吧支付二维码"]').attributes('src')).toMatch(/^data:image\/svg\+xml/);
 expect(wrapper.emitted('settled')).toBeUndefined();
});
it('retries uncertain create with the same request ID across remount', async () => {
 api.create.mockRejectedValueOnce(new Error('lost response'));
 const first = await open(); await first.get('form').trigger('submit'); await flushPromises();
 const request = api.create.mock.calls[0][0]; first.unmount();
 const second = await open(); await second.get('form').trigger('submit'); await flushPromises();
 expect(api.create.mock.calls[1][0]).toEqual(request);
});
it('polls paid-but-unfulfilled and only refreshes bills after fulfillment', async () => {
 vi.useFakeTimers(); const wrapper = await open();
 await wrapper.get('form').trigger('submit'); await flushPromises();
 api.get.mockResolvedValueOnce({ data: order({ status: 'PAID', fulfilled: false }) });
 await vi.advanceTimersByTimeAsync(3000); await flushPromises();
 expect(wrapper.text()).toContain('正在入账'); expect(wrapper.emitted('settled')).toBeUndefined();
 api.get.mockResolvedValueOnce({ data: order({ status: 'PAID', fulfilled: true }) });
 await vi.advanceTimersByTimeAsync(3000); await flushPromises();
 expect(wrapper.text()).toContain('充值成功'); expect(wrapper.emitted('settled')).toHaveLength(1);
 expect(sessionStorage.getItem('xzwl-api-recharge:owner-a')).toBeNull();
});
it('rejects incompatible accounts, mismatched owners and unsafe checkout URLs', async () => {
 api.options.mockResolvedValueOnce({ data: rules('owner-a', 'PERSONAL') });
 const incompatible = await open(); expect(incompatible.find('form').exists()).toBe(false); incompatible.unmount();
 api.options.mockResolvedValueOnce({ data: rules('someone-else') });
 const mismatch = await open(); expect(mismatch.find('[role="alert"]').exists()).toBe(true); expect(mismatch.find('form').exists()).toBe(false); mismatch.unmount();
 api.create.mockResolvedValueOnce({ data: order({ userId: 'someone-else' }) });
 const owner = await open(); await owner.get('form').trigger('submit'); await flushPromises(); expect(owner.find('img').exists()).toBe(false); owner.unmount(); sessionStorage.clear();
 api.create.mockResolvedValueOnce({ data: order({ checkoutUrl: 'javascript:alert(1)' }) });
 const unsafe = await open(); await unsafe.get('form').trigger('submit'); await flushPromises(); expect(unsafe.find('img').exists()).toBe(false); expect(unsafe.find('[role="alert"]').exists()).toBe(true);
});
it('discards delayed checkout after owner switch', async () => {
 let resolve!: (value: unknown) => void; api.create.mockImplementationOnce(() => new Promise(done => { resolve = done; }));
 const wrapper = await open(); await wrapper.get('form').trigger('submit');
 api.options.mockResolvedValue({ data: rules('owner-b') }); await wrapper.setProps({ owner: 'owner-b' }); await flushPromises();
 resolve({ data: order() }); await flushPromises(); expect(wrapper.text()).not.toContain('order-a'); expect(wrapper.find('img').exists()).toBe(false);
 expect(sessionStorage.getItem('xzwl-api-recharge:owner-b')).toBeNull();
 wrapper.unmount();
});

it('resumes a pending order after remount and stops polling after unmount', async () => {
 vi.useFakeTimers(); const first = await open(); await first.get('form').trigger('submit'); await flushPromises(); first.unmount();
 const second = await open(); expect(api.create).toHaveBeenCalledTimes(1); expect(api.get).toHaveBeenCalledWith('order-a', expect.anything());
 expect(second.find('img').exists()).toBe(true); second.unmount(); const calls = api.get.mock.calls.length;
 await vi.advanceTimersByTimeAsync(6000); expect(api.get).toHaveBeenCalledTimes(calls);
});
it('ignores a delayed successful response after unmount', async () => {
 let resolve!: (value: unknown) => void; api.create.mockImplementationOnce(() => new Promise(done => { resolve = done; }));
 const wrapper = await open(); await wrapper.get('form').trigger('submit'); wrapper.unmount();
 resolve({ data: order({ status: 'PAID', fulfilled: true }) }); await flushPromises();
 expect(wrapper.emitted('settled')).toBeUndefined();
 expect(JSON.parse(sessionStorage.getItem('xzwl-api-recharge:owner-a')!)).not.toHaveProperty('orderId');
});
it('recovers options and the saved checkout after an initial rules outage', async () => {
 sessionStorage.setItem('xzwl-api-recharge:owner-a', JSON.stringify({ requestId: 'retry-id', amount4: '50', accountType: 'API', paymentMethod: 'ALIPAY', orderId: 'order-a' }));
 api.options.mockRejectedValueOnce(new Error('temporary rules outage'));
 const wrapper = await open(); expect(wrapper.find('[role="alert"]').exists()).toBe(true);
 await wrapper.get('header button').trigger('click'); await flushPromises();
 expect(api.options).toHaveBeenCalledTimes(2); expect(wrapper.find('[role="alert"]').exists()).toBe(false);
 expect(wrapper.get('img[alt="收钱吧支付二维码"]').isVisible()).toBe(true);
 expect(api.create).not.toHaveBeenCalled();
});
