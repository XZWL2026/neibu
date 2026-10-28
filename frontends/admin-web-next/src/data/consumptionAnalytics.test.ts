import { afterEach, expect, it, vi } from 'vitest';
const api=vi.hoisted(()=>({analytics:vi.fn()}));
vi.mock('../api',()=>({billingAnalyticsGetApiV1AdminBillingConsumptionsAnalytics:api.analytics}));
import {consumptionAnalytics} from './consumptionAnalytics';
afterEach(()=>{vi.useRealTimers();vi.clearAllMocks();});
it('aborts a stalled report after one minute without changing the query',async()=>{
 vi.useFakeTimers();
 api.analytics.mockImplementation((_query,{signal})=>new Promise((_resolve,reject)=>signal.addEventListener('abort',()=>reject(new DOMException('Timed out','AbortError')))));
 const query={to:'2026-10-14T10:00:00Z',userId:'owner'};
 const pending=consumptionAnalytics(query);const result=expect(pending).rejects.toMatchObject({name:'AbortError'});
 await vi.advanceTimersByTimeAsync(60_000);await result;
 expect(api.analytics.mock.calls[0][0]).toEqual(query);expect(api.analytics.mock.calls[0][1].signal.aborted).toBe(true);expect(vi.getTimerCount()).toBe(0);
});
it('clears the deadline after a successful report',async()=>{
 vi.useFakeTimers();api.analytics.mockResolvedValue({data:{total:0}});
 await consumptionAnalytics({to:'2026-10-14T10:00:00Z'});expect(vi.getTimerCount()).toBe(0);expect(api.analytics.mock.calls[0][1].signal.aborted).toBe(false);
});
