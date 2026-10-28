import {enableAutoUnmount,mount} from '@vue/test-utils';
import {afterEach,expect,it} from 'vitest';
import {axe} from 'vitest-axe';
import ApiPriceSettings from './ApiPriceSettings.vue';
import ApiClientPrices from './ApiClientPrices.vue';
import type {ResponseBillingDomainPricingMediaPrice as Price} from './api';
enableAutoUnmount(afterEach);
const price=(model:string,resolution:string,type='IMAGE'):Price=>({accountType:'API',type,unit:type==='VIDEO'?'SECOND':'IMAGE',model,resolution,yuanPerUnit:'0.2500',pointsPerUnit:null,enabled:true});
it('pivots real catalog dimensions without changing identifiers, merging suffixed image models and preserving extra specifications',async()=>{
 const catalog=[price('banana_pro-1K','1K'),price('banana_pro-2K','2K'),price('banana_pro-4K','4K'),price('video','720P','VIDEO'),price('video','2160p','VIDEO')];
 const view=mount(ApiClientPrices,{props:{prices:catalog,modelValue:[],pointsPerYuan4:'10',matrixLayout:true}});
 expect(view.findAll('.api-price-matrix-section')).toHaveLength(2);
 const image=view.findAll('.api-price-matrix-section')[1]!;
 expect(image.findAll('tbody tr')).toHaveLength(1);expect(image.get('tbody th').text()).toBe('banana_pro');
 const video=view.findAll('.api-price-matrix-section')[0]!;
 expect(video.findAll('thead th').map(th=>th.text())).toEqual(['模型','480p','720p','1080p','4K','2160p']);
 expect(video.findAll('.api-price-unavailable')).toHaveLength(3);
 await view.get('[aria-label="banana_pro-2K 2K 客户价格"]').setValue('0.1234');
 expect(view.emitted('update:modelValue')?.[0]?.[0]).toEqual([{...catalog[1],yuanPerUnit:'0.1234'}]);
});
it('does not overwrite distinct models with the same family and resolution',()=>{
 const view=mount(ApiClientPrices,{props:{prices:[price('model','1K'),price('model-1K','1K')],modelValue:[],pointsPerYuan4:'10',matrixLayout:true}});
 expect(view.findAll('tbody tr')).toHaveLength(2);expect(view.findAll('tbody input')).toHaveLength(2);
});
it('keeps edits local until Save and never mutates caller values on cancel',async()=>{
 const original=[{...price('image','1K'),yuanPerUnit:'0.3000'}];
 const view=mount(ApiPriceSettings,{props:{prices:[price('image','1K')],initialPrices:original,initialMode:'custom',pointsPerYuan4:'10'}});
 await view.get('[aria-label="image 1K 客户价格"]').setValue('0.1234');
 expect(view.emitted('save')).toBeUndefined();expect(original[0]!.yuanPerUnit).toBe('0.3000');
 await view.get('[aria-label="关闭价格设置"]').trigger('click');expect(view.emitted('cancel')).toHaveLength(1);
 await view.findAll('button').find(b=>b.text()==='保存价格')!.trigger('click');
 expect(view.emitted('save')?.[0]).toEqual([[{...original[0],yuanPerUnit:'0.1234'}],'custom']);
});
it('shows defaults when selected, retains the custom draft when switching back, and saves inheritance',async()=>{
 const original=[{...price('image','1K'),yuanPerUnit:'0.3000'}];
 const view=mount(ApiPriceSettings,{props:{prices:[price('image','1K')],initialPrices:original,initialMode:'custom',pointsPerYuan4:'10'}});
 await view.get('input[value="default"]').setValue(true);
 expect(view.get('.api-price-matrix-readonly').text()).toBe('0.2500');expect(view.find('tbody input').exists()).toBe(false);
 await view.get('input[value="custom"]').setValue(true);
 expect(view.get<HTMLInputElement>('tbody input').element.value).toBe('0.3000');
 await view.get('input[value="default"]').setValue(true);await view.findAll('button').find(b=>b.text()==='保存价格')!.trigger('click');
 expect(view.emitted('save')?.[0]).toEqual([[],'default']);
});
it('blocks invalid amounts and makes unsupported cells unavailable to editing',async()=>{
 const view=mount(ApiPriceSettings,{props:{prices:[price('video','720p','VIDEO')],initialPrices:[],initialMode:'custom',pointsPerYuan4:'10'}});
 expect(view.findAll('tbody input')).toHaveLength(1);
 await view.get('tbody input').setValue('-1');expect(view.get('tbody input').attributes('aria-invalid')).toBe('true');
 const save=view.findAll('button').find(b=>b.text()==='保存价格')!;expect(save.attributes('disabled')).toBeDefined();
 await view.get('tbody input').setValue('0.12345');expect(save.attributes('disabled')).toBeDefined();
 await view.get('tbody input').setValue('0');expect(save.attributes('disabled')).toBeUndefined();
 expect((await axe(view.element,{rules:{'color-contrast':{enabled:false}}})).violations).toEqual([]);
});
