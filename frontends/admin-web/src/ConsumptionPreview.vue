<script setup lang="ts">
import {computed,ref,watch,onBeforeUnmount} from 'vue';
import {billingAccessGetApiV1AdminBillingConsumptionsIdFilesFileIdAccessUrl as access} from './api';
const props=defineProps<{recordId:string;fileId:string;label:string;video?:boolean;audio?:boolean;thumbnail?:boolean;inline?:boolean}>();
const url=ref(''),busy=ref(false),error=ref(''),expires=ref(0),expanded=ref(false),notice=ref('');
const dialog=ref<HTMLDialogElement>();
const mediaLabel=computed(()=>props.video?'视频':props.audio?'音频':'图片');
let version=0,refreshUsed=false;
function safe(value:string){const parsed=new URL(value,window.location.origin);if(parsed.username||parsed.password||parsed.protocol!=='https:'&&!(parsed.origin===window.location.origin&&value.startsWith('/')))throw Error('invalid file URL');return value;}
async function load(){const own=++version;busy.value=true;error.value='';url.value='';notice.value='';try{const response=(await access(props.recordId,props.fileId)).data;if(!response.url||!response.expiresAt)throw Error('missing file');const next=safe(response.url),until=Date.parse(response.expiresAt);if(!Number.isFinite(until)||until<=Date.now())throw Error('expired file');if(own===version){url.value=next;expires.value=until;return next;}}catch{if(own===version)error.value='文件已删除、链接失效或暂时无法读取，请重新获取。';}finally{if(own===version)busy.value=false;}}
async function open(){refreshUsed=false;const next=await load();if(!next)return;expanded.value=true;dialog.value?.showModal();}
async function download(){const next=await load();if(!next)return;const link=document.createElement('a');link.href=next;link.download=props.fileId+(props.video?'.mp4':'');link.rel='noopener';link.target='_blank';link.click();}
function close(){expanded.value=false;dialog.value?.close();}
async function copy(){const next=await load();if(!next)return;try{await navigator.clipboard.writeText(next);notice.value='链接已复制';}catch{error.value='复制失败，请允许剪贴板访问后重试。';}}
function failed(){if(!refreshUsed&&expires.value<=Date.now()+5000){refreshUsed=true;void load();}else{url.value='';error.value='文件已删除、链接失效或暂时无法读取，请重新获取。';}}
watch(()=>[props.recordId,props.fileId,props.thumbnail,props.inline,props.video,props.audio],()=>{version++;close();url.value='';error.value='';notice.value='';expires.value=0;refreshUsed=false;if(props.thumbnail||props.inline)void load();},{immediate:true});
onBeforeUnmount(()=>{version++;});
</script>
<template>
 <div class="consumption-file" :class="{'is-inline':inline,'is-thumbnail':thumbnail}" :aria-label="label">
  <strong>{{ label }}</strong>
  <template v-if="inline&&url"><video v-if="video" :src="url" controls playsinline preload="metadata" :aria-label="label" @error="failed"/><audio v-else-if="audio" :src="url" controls preload="metadata" :aria-label="label" @error="failed"/><img v-else :src="url" :alt="label" @error="failed"/></template>
  <audio v-if="thumbnail&&url&&audio" :src="url" controls preload="metadata" :aria-label="label" @error="failed"/>
  <button v-else-if="thumbnail&&url" type="button" class="consumption-thumbnail" :aria-label="(video?'查看视频：':'查看大图：')+label" @click="open"><video v-if="video" :src="url" muted playsinline preload="metadata" :aria-label="label" @error="failed"/><img v-else :src="url" :alt="label" loading="lazy" @error="failed"></button>
  <div class="consumption-file-actions"><button type="button" :disabled="busy" @click="open">打开{{ mediaLabel }}</button><button type="button" :disabled="busy" @click="copy">复制{{ mediaLabel }}链接</button><button v-if="inline||thumbnail" type="button" :disabled="busy" @click="download">下载{{mediaLabel}}</button></div>
  <small v-if="busy" role="status">正在获取授权链接…</small>
  <small v-if="notice" role="status">{{ notice }}</small>
  <small v-if="expires&&url&&!inline&&!thumbnail">链接有效至 {{ new Date(expires).toLocaleString('zh-CN',{hour12:false}) }}，再次操作将重新获取。</small>
  <p v-if="error" role="alert">{{ error }} <button v-if="inline||thumbnail" type="button" :disabled="busy" @click="load">重新获取文件</button></p>
  <dialog ref="dialog" class="consumption-lightbox" :aria-label="label+(video?'视频':audio?'音频':'大图')" @cancel="expanded=false" @click="event=>{if(event.target===dialog)close();}">
   <button type="button" @click="close">关闭预览</button>
   <template v-if="expanded&&url"><video v-if="video" :src="url" controls playsinline preload="metadata" :aria-label="label" @error="failed"></video><audio v-else-if="audio" :src="url" controls preload="metadata" :aria-label="label" @error="failed"></audio><img v-else :src="url" :alt="label" @error="failed"></template>
   <p v-if="error" role="alert">{{ error }}</p><button v-if="error" type="button" :disabled="busy" @click="load">重新获取文件</button>
  </dialog>
 </div>
</template>
