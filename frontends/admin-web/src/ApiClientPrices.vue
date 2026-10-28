<script setup lang="ts">
import {computed,ref,watch} from 'vue';
import './api-price-settings.css';
import type {ResponseBillingDomainPricingMediaPrice as Price,RequestBillingDomainPricingMediaPrice as Override,ResponseRoutingInterfacesHttpRoutingAdminControllerModelView as Model} from './api';
const props=defineProps<{prices:Price[];modelValue:Override[];pointsPerYuan4:string;accountType?:string;readonly?:boolean;disabled?:boolean;modelLayout?:boolean;matrixLayout?:boolean;models?:Model[];savedPrices?:Override[]}>();
const emit=defineEmits<{'update:modelValue':[prices:Override[]];save:[]}>();
const discount=ref('100');
const dimension=(p:Price|Override)=>[p.type,p.model,p.resolution].join('/');
function units(value:string){if(!/^\d{1,12}(\.\d{1,4})?$/.test(value))return null;const[w,f='']=value.split('.');return BigInt(w!)*10000n+BigInt(f.padEnd(4,'0'));}
function decimal(value:bigint){return `${value/10000n}.${String(value%10000n).padStart(4,'0')}`;}
function base(price:Price){if(price.yuanPerUnit!=null)return price.yuanPerUnit;const points=units(price.pointsPerUnit||''),rate=units(props.pointsPerYuan4);return points!==null&&rate&&rate>0n?decimal(points*10000n/rate):'';}
const rows=computed(()=>props.prices.filter(p=>p.accountType===(props.accountType??'API')&&p.enabled).map(price=>({price,id:dimension(price),defaultPrice:base(price),override:props.modelValue.find(o=>dimension(o)===dimension(price))})));
const valid=computed(()=>props.modelValue.every(p=>{const value=units(p.yuanPerUnit||'');return value!==null&&value>=0n&&rows.value.some(r=>r.id===dimension(p));}));
function update(price:Price,value:string){if(props.readonly||props.disabled)return;const next=props.modelValue.filter(p=>dimension(p)!==dimension(price));if(value!=='')next.push({...price,pointsPerUnit:null,yuanPerUnit:value});emit('update:modelValue',next);}
function bulk(){const ratio=units(discount.value);if(props.readonly||props.disabled||ratio===null||ratio>1000000n)return;emit('update:modelValue',rows.value.flatMap(row=>{const value=units(row.defaultPrice);return value===null?[]:[{...row.price,pointsPerUnit:null,yuanPerUnit:decimal(value*ratio/1000000n)}];}));}
const search=ref(''),typeFilter=ref(''),selectedId=ref(''),detailOpen=ref(true),autoNext=ref(true),expanded=ref(new Set<string>());
const sections=[{type:'VIDEO',title:'视频生成模型',icon:'▷'},{type:'IMAGE',title:'图片生成模型',icon:'▧'}];
const modelName=(id:string|null)=>props.models?.find(m=>m.modelId===id)?.capability?.displayName||id||'未命名模型';
const providerName=(id:string|null)=>props.models?.find(m=>m.modelId===id)?.providerId;
const canonicalResolution=(value:string|null)=>(value||'').replace(/\s/g,'').replace(/k$/i,'K').replace(/p$/i,'p');
const matrixSections=computed(()=>sections.map(section=>{
 const columns=section.type==='VIDEO'?['480p','720p','1080p','4K']:['1K','2K','4K'];
 const groups:{id:string;familyId:string;name:string;cells:Map<string,typeof rows.value[number]>}[]=[];
 for(const row of rows.value.filter(r=>r.price.type===section.type)){
  const resolution=canonicalResolution(row.price.resolution)||'默认';
  if(!columns.includes(resolution))columns.push(resolution);
  const raw=row.price.model||'未命名模型';
  const family=raw.replace(/[-_](1k|2k|4k|480p|720p|1080p)$/i,(suffix,matched:string)=>canonicalResolution(matched)===resolution?'':suffix);
  const id=[section.type,row.price.unit,family].join('/');
  let group=groups.find(g=>g.familyId===id&&!g.cells.has(resolution));
  if(!group){group={id:id+'/'+raw,familyId:id,name:family===raw?modelName(row.price.model):family,cells:new Map()};groups.push(group);}
  group.cells.set(resolution,row);
 }
 return {...section,columns,groups};
}));
const invalidPrice=(value:string|null|undefined)=>value!=null&&units(value)===null;
const groupId=(p:Price)=>[p.type,p.model].join('/');
const groups=computed(()=>{
 const result:{id:string;type:string|null;model:string|null;rows:typeof rows.value}[]=[];
 for(const row of rows.value){if(typeFilter.value&&row.price.type!==typeFilter.value)continue;
 if(![row.price.model,modelName(row.price.model),row.price.resolution,providerName(row.price.model)].join(' ').toLowerCase().includes(search.value.trim().toLowerCase()))continue;
 const id=groupId(row.price),group=result.find(g=>g.id===id);if(group)group.rows.push(row);else result.push({id,type:row.price.type,model:row.price.model,rows:[row]});}
 return result.sort((a,b)=>sections.findIndex(s=>s.type===a.type)-sections.findIndex(s=>s.type===b.type));
});
const visibleRows=computed(()=>groups.value.flatMap(g=>g.rows));
const selected=computed(()=>visibleRows.value.find(r=>r.id===selectedId.value)||visibleRows.value[0]);
const specifications=computed(()=>rows.value.filter(r=>selected.value&&groupId(r.price)===groupId(selected.value.price)));
const original=computed(()=>selected.value?props.savedPrices?.find(p=>dimension(p)===selected.value!.id):undefined);
function select(id:string){selectedId.value=id;detailOpen.value=true;}
watch(()=>selected.value?.id,()=>{if(selected.value)expanded.value.add(groupId(selected.value.price));},{immediate:true});
function toggle(id:string){if(expanded.value.has(id))expanded.value.delete(id);else expanded.value.add(id);}
function cancel(){if(selected.value)update(selected.value.price,original.value?.yuanPerUnit??'');}
function advance(){if(!autoNext.value)return;const next=visibleRows.value[visibleRows.value.findIndex(r=>r.id===selected.value?.id)+1];if(next)select(next.id);}
defineExpose({valid,advance});
</script>
<template>
<div class="api-price-editor">
 <div v-if="matrixLayout" class="api-price-matrices">
  <section v-for="section in matrixSections" :key="section.type" class="api-price-matrix-section" :aria-label="section.title">
   <header><svg v-if="section.type==='VIDEO'" viewBox="0 0 24 24" aria-hidden="true" fill="currentColor"><rect x="2" y="5" width="14" height="14" rx="3"/><path d="m17 9 5-3v12l-5-3z"/></svg><svg v-else viewBox="0 0 24 24" aria-hidden="true" fill="currentColor"><path d="M4 3h16a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Zm0 15h16l-5-7-4 5-3-3-4 5Zm3-8a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z"/></svg><h3>{{section.type==='VIDEO'?'视频模型':'图片模型'}}</h3><span>{{section.type==='VIDEO'?'元/秒':'元/张'}}</span></header>
   <div class="api-price-matrix-scroll" tabindex="0" role="region" :aria-label="`${section.title}价格表，可横向滚动`"><table><caption class="sr-only">{{section.title}}客户价格</caption><thead><tr><th scope="col">模型</th><th v-for="column in section.columns" :key="column" scope="col">{{column}}</th></tr></thead><tbody>
    <tr v-for="group in section.groups" :key="group.id"><th scope="row">{{group.name}}</th><td v-for="column in section.columns" :key="column"><template v-if="group.cells.get(column)"><input v-if="!readonly" :value="group.cells.get(column)!.override?.yuanPerUnit??''" :placeholder="group.cells.get(column)!.defaultPrice" :aria-label="`${group.cells.get(column)!.price.model} ${group.cells.get(column)!.price.resolution} 客户价格`" :title="`默认价格 ${group.cells.get(column)!.defaultPrice} 元/${section.type==='VIDEO'?'秒':'张'}；留空使用默认价格`" :aria-invalid="invalidPrice(group.cells.get(column)!.override?.yuanPerUnit)" inputmode="decimal" autocomplete="off" :disabled="disabled||!group.cells.get(column)!.defaultPrice" @input="update(group.cells.get(column)!.price,($event.target as HTMLInputElement).value)" @keydown.enter.prevent/><span v-else class="api-price-matrix-readonly">{{group.cells.get(column)!.override?.yuanPerUnit??group.cells.get(column)!.defaultPrice}}</span><span v-if="invalidPrice(group.cells.get(column)!.override?.yuanPerUnit)" class="api-price-matrix-error">最多 4 位小数的非负金额</span></template><span v-else class="api-price-unavailable" :aria-label="`${group.name} 不支持 ${column}`">—</span></td></tr>
   </tbody></table><p v-if="!section.groups.length" class="api-price-matrix-empty">暂无{{section.type==='VIDEO'?'视频':'图片'}}模型价格</p></div>
  </section>
 </div>
 <div v-if="!readonly&&!matrixLayout" class="api-price-tools"><button type="button" :disabled="disabled||!modelValue.length" @click="emit('update:modelValue',[])">恢复默认价格</button><details><summary>批量调价</summary><div><label>统一折扣（%）<input v-model="discount" aria-label="统一折扣（%）" type="number" min="0" max="100" step="0.01" :disabled="disabled"/></label><button type="button" :disabled="disabled||units(discount)===null||Number(discount)>100" @click="bulk">应用</button></div></details></div>
 <div v-if="!modelLayout&&!matrixLayout" class="api-prices-table"><table><thead><tr><th>模型</th><th>分辨率</th><th>默认价格</th><th>客户价格</th><th>单位</th></tr></thead><tbody><tr v-for="row in rows" :key="row.id"><td>{{row.price.model}}</td><td>{{row.price.resolution}}</td><td>{{row.defaultPrice||'—'}}</td><td><span v-if="readonly">{{row.override?.yuanPerUnit??row.defaultPrice}}</span><input v-else :value="row.override?.yuanPerUnit??''" :placeholder="row.defaultPrice" :aria-label="`${row.price.model} ${row.price.resolution} 客户价格`" inputmode="decimal" :disabled="disabled||!row.defaultPrice" @input="update(row.price,($event.target as HTMLInputElement).value)"/></td><td>{{row.price.unit==='SECOND'?'元/秒':'元/张'}}</td></tr></tbody></table><p v-if="!rows.length" class="directory-empty">暂无模型价格</p></div>
 <div v-else-if="!matrixLayout" class="pricing-layout special-model-layout" :class="{'without-detail':!detailOpen||!selected}">
  <div class="pricing-main">
   <div class="pricing-filters special-model-filters"><select v-model="typeFilter" aria-label="模型类型" :disabled="disabled"><option value="">全部模型</option><option value="VIDEO">视频模型</option><option value="IMAGE">图片模型</option></select><input v-model="search" aria-label="搜索模型名称或规格" placeholder="搜索模型名称、规格…" :disabled="disabled"/></div>
   <section v-for="section in sections.filter(s=>!typeFilter||s.type===typeFilter)" :key="section.type" class="pricing-section" :aria-label="section.title">
    <header class="pricing-section-heading"><i>{{section.icon}}</i><h2>{{section.title}}</h2><span>{{groups.filter(g=>g.type===section.type).length}}</span></header>
    <article v-for="group in groups.filter(g=>g.type===section.type)" :key="group.id" class="pricing-model">
     <button type="button" class="pricing-model-heading" :aria-expanded="expanded.has(group.id)" @click="toggle(group.id)"><span aria-hidden="true">{{expanded.has(group.id)?'⌃':'›'}}</span><strong>{{modelName(group.model)}}</strong><span class="pricing-badge">已启用</span><small v-if="providerName(group.model)" class="pricing-provider">{{providerName(group.model)}}</small><small class="pricing-spec-count">{{group.rows.length}} 个规格</small></button>
     <div v-show="expanded.has(group.id)" class="pricing-table-wrap"><table><thead><tr><th>规格</th><th>默认价格（元/{{section.type==='VIDEO'?'秒':'张'}}）</th><th>客户价格</th><th>状态</th></tr></thead><tbody>
      <tr v-for="row in group.rows" :key="row.id" class="pricing-cell" :class="{selected:selected?.id===row.id&&detailOpen}" @click="select(row.id)"><td><button type="button" class="pricing-resolution" :aria-label="`编辑 ${modelName(row.price.model)} ${row.price.resolution}`" @click="select(row.id)">{{row.price.resolution}}</button></td><td>{{row.defaultPrice||'—'}}</td><td><input :value="row.override?.yuanPerUnit??''" :placeholder="row.defaultPrice" :aria-label="`${row.price.model} ${row.price.resolution} 客户价格`" class="price-input" inputmode="decimal" :disabled="disabled||readonly||!row.defaultPrice" @focus="select(row.id)" @input="update(row.price,($event.target as HTMLInputElement).value)" @keydown.enter.prevent="emit('save')"/></td><td><span class="pricing-badge">{{row.override?'专属价格':'默认价格'}}</span></td></tr>
     </tbody></table></div>
    </article><p v-if="!groups.some(g=>g.type===section.type)" class="pricing-empty">暂无符合条件的{{section.type==='VIDEO'?'视频':'图片'}}模型。</p>
   </section>
  </div>
  <aside v-if="detailOpen&&selected" class="pricing-detail" aria-label="编辑客户价格"><header><h2>编辑价格</h2><button type="button" aria-label="关闭价格编辑" @click="detailOpen=false">×</button></header>
   <div class="pricing-detail-model"><i>{{selected.price.type==='VIDEO'?'▷':'▧'}}</i><div><strong>{{modelName(selected.price.model)}}</strong><small v-if="providerName(selected.price.model)" class="pricing-provider">{{providerName(selected.price.model)}}</small></div><span class="pricing-badge">已启用</span></div>
   <form @submit.prevent="emit('save')" @keydown.esc.prevent="cancel"><fieldset :disabled="disabled||readonly"><legend class="sr-only">编辑所选规格</legend>
    <label class="pricing-field-label">选择规格</label><div class="pricing-specifications"><button v-for="row in specifications" :key="row.id" type="button" :aria-pressed="row.id===selected.id" @click="search='';select(row.id)">{{row.price.resolution}}</button></div>
    <label class="pricing-field-label" for="special-price-amount">价格（元/{{selected.price.unit==='SECOND'?'秒':'张'}}）</label><input id="special-price-amount" :value="selected.override?.yuanPerUnit??''" :placeholder="selected.defaultPrice" aria-label="编辑客户金额（元）" inputmode="decimal" @input="update(selected.price,($event.target as HTMLInputElement).value)"/>
    <div class="pricing-comparison"><span>原价 <strong>{{original?.yuanPerUnit??selected.defaultPrice}}</strong></span><span>→</span><span>新价 <strong>{{selected.override?.yuanPerUnit??selected.defaultPrice}}</strong></span><small>{{(original?.yuanPerUnit??null)===(selected.override?.yuanPerUnit??null)?'未变化':'待保存'}}</small></div>
    <button type="button" @click="update(selected.price,'')">恢复此规格默认价格</button>
    <label class="pricing-auto-next"><strong>快速操作</strong><span>修改后自动跳转到下一个规格</span><input v-model="autoNext" type="checkbox"/></label>
    <footer><button type="button" @click="cancel">取消 <small>Esc</small></button><button type="submit" class="primary-action" :disabled="!valid">保存修改 <small>Enter</small></button></footer>
   </fieldset></form>
  </aside>
 </div>

</div>
</template>
