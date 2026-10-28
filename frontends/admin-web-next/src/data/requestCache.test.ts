import { describe, expect, it, vi } from 'vitest';
import { RequestCache, cacheLifetime } from './requestCache';
const deferred = <T>() => { let resolve!: (value:T)=>void; let reject!: (error:unknown)=>void; const promise=new Promise<T>((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject}; };
describe('memory-only request cache',()=>{
  it('coalesces concurrent requests and caches only successful responses',async()=>{
    const cache=new RequestCache(),pending=deferred<{value:number}>(),loader=vi.fn(()=>pending.promise);
    const a=cache.read('same',loader,1000),b=cache.read('same',loader,1000);
    expect(loader).toHaveBeenCalledTimes(1);pending.resolve({value:7});
    expect(await a).toEqual(await b);await cache.read('same',loader,1000);
    expect(loader).toHaveBeenCalledTimes(1);expect(cache.metrics).toEqual({hits:1,misses:1,coalesced:1});
  });
  it('expires data at the exact TTL instead of returning stale finance',async()=>{
    let time=0;const cache=new RequestCache(10,()=>time),loader=vi.fn(async()=>time);
    expect(await cache.read('money',loader,100)).toBe(0);time=99;
    expect(await cache.read('money',loader,100)).toBe(0);time=100;
    expect(await cache.read('money',loader,100)).toBe(100);expect(loader).toHaveBeenCalledTimes(2);
  });
  it('bounds memory with LRU eviction',async()=>{
    const cache=new RequestCache(2),loader=vi.fn(async()=>1);
    await cache.read('a',loader,1000);await cache.read('b',loader,1000);await cache.read('a',loader,1000);await cache.read('c',loader,1000);
    expect(cache.size).toBe(2);await cache.read('b',loader,1000);expect(loader).toHaveBeenCalledTimes(4);
  });
  it('does not cache rejection and allows retry',async()=>{
    const cache=new RequestCache(),loader=vi.fn().mockRejectedValueOnce(Error('offline')).mockResolvedValueOnce(9);
    await expect(cache.read('a',loader,1000)).rejects.toThrow('offline');
    expect(await cache.read('a',loader,1000)).toBe(9);expect(cache.size).toBe(1);
  });
  it('isolates accounts, roles and primary permission changes',async()=>{
    const cache=new RequestCache(),loader=vi.fn(async()=>1);
    cache.setScope('owner:ADMIN:primary');await cache.read('users',loader,1000);
    cache.setScope('owner:NORMAL_ADMIN:false');await cache.read('users',loader,1000);
    cache.setScope('other:ADMIN:primary');await cache.read('users',loader,1000);
    expect(loader).toHaveBeenCalledTimes(3);
  });
  it('refuses late responses after logout even when a transport ignores abort',async()=>{
    const cache=new RequestCache(),pending=deferred<number>();
    const request=cache.read('a',()=>pending.promise,1000);cache.clear();pending.resolve(1);
    await expect(request).rejects.toMatchObject({name:'AbortError'});expect(cache.size).toBe(0);
  });
  it('allows one consumer to cancel without aborting the other',async()=>{
    const cache=new RequestCache(),pending=deferred<number>(),a=new AbortController();let signal!:AbortSignal;
    const loader=(value:AbortSignal)=>{signal=value;return pending.promise;};
    const first=cache.read('a',loader,1000,a.signal),second=cache.read('a',loader,1000);
    a.abort();await expect(first).rejects.toMatchObject({name:'AbortError'});expect(signal.aborted).toBe(false);
    pending.resolve(4);expect(await second).toBe(4);
  });
  it('aborts the transport when the last subscriber cancels',async()=>{
    const cache=new RequestCache(),pending=deferred<number>(),controller=new AbortController();let signal!:AbortSignal;
    const request=cache.read('a',value=>{signal=value;return pending.promise;},1000,controller.signal);
    controller.abort();await expect(request).rejects.toMatchObject({name:'AbortError'});expect(signal.aborted).toBe(true);
    pending.resolve(1);await Promise.resolve();expect(cache.size).toBe(0);
  });
  it('does not start transport for an already aborted signal',async()=>{
    const cache=new RequestCache(),controller=new AbortController(),loader=vi.fn(async()=>1);controller.abort();
    await expect(cache.read('a',loader,1000,controller.signal)).rejects.toMatchObject({name:'AbortError'});expect(loader).not.toHaveBeenCalled();
  });
  it('bypasses an existing value for explicit refresh',async()=>{
    const cache=new RequestCache(),loader=vi.fn().mockResolvedValueOnce(1).mockResolvedValueOnce(2);
    await cache.read('a',loader,1000);expect(await cache.read('a',loader,1000,undefined,true)).toBe(2);
  });
  it('invalidates pending and stored queries at mutation boundaries',async()=>{
    const cache=new RequestCache(),pending=deferred<number>();await cache.read('old',async()=>1,1000);
    const request=cache.read('a',()=>pending.promise,1000);cache.invalidate();expect(cache.size).toBe(0);pending.resolve(7);
    await expect(request).rejects.toMatchObject({name:'AbortError'});
  });
});
it.each(['/api/v1/auth/session','/api/v1/admin/access/keys/abc/secret','/api/v1/admin/users/abc/ticket','/api/v1/admin/configuration/credentials'])('excludes sensitive/session reads %s',path=>{expect(cacheLifetime(path)).toBeNull();});
it('uses shorter TTLs for financial reads',()=>{expect(cacheLifetime('/api/v1/admin/billing/daily-finance?limit=30')).toBe(10000);expect(cacheLifetime('/api/v1/admin/users/directory')).toBe(20000);});
