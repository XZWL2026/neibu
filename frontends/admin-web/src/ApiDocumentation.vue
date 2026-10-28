<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { apiBaseUrl, docSections, sectionForHash, languages, requestExample, retryText, errorText, type DocLanguage } from '../../shared/apiDocumentation';
import './api-documentation.css';
const route=useRoute();
const router=useRouter();
function selectSection(event: Event) { void router.push({ hash: (event.target as HTMLSelectElement).value }); }
const activeApiCategory=computed(()=>sectionForHash(route.hash));
const language=ref<DocLanguage>('cURL'),exampleIndex=ref(0),copied=ref('');
watch(activeApiCategory,()=>{exampleIndex.value=0;copied.value='';},{immediate:true});
const example=computed(()=>activeApiCategory.value.examples[exampleIndex.value]??activeApiCategory.value.examples[0]);
const code=computed(()=>requestExample(example.value,language.value));
watch(code,()=>{copied.value='';});
async function copyExample(){try{await navigator.clipboard.writeText(code.value);copied.value='示例已复制';}catch{copied.value='复制失败，请手动选择代码';}}
</script>
<template>
 <div class="console-doc-layout">
  <div class="console-doc-mobile-nav">
   <label for="api-doc-section">文档章节</label>
   <select id="api-doc-section" :value="route.hash || '#'+activeApiCategory.id" @change="selectSection"><optgroup v-for="group in ['快速开始','API 指南']" :key="group" :label="group"><option v-for="section in docSections.filter(item=>item.group===group)" :key="section.id" :value="'#'+section.id">{{section.title}}</option></optgroup><optgroup label="接入实践"><option value="#console-api-retry">生成与重试</option><option value="#console-api-errors">错误处理</option></optgroup></select>
   <RouterLink to="/api/dashboard">返回控制台</RouterLink>
  </div>
  <nav class="console-doc-nav" aria-label="API 文档分类"><p class="console-doc-nav-title">API 文档</p><template v-for="group in ['快速开始','API 指南']" :key="group"><p>{{group}}</p><RouterLink v-for="section in docSections.filter(item=>item.group===group)" :key="section.id" :to="{hash:'#'+section.id}" :aria-current="activeApiCategory.id===section.id&&!route.hash.startsWith('#console-api-')?'page':undefined">{{section.title}}</RouterLink></template><p>接入实践</p><RouterLink :to="{hash:'#console-api-retry'}" :aria-current="route.hash==='#console-api-retry'?'location':undefined">生成与重试</RouterLink><RouterLink :to="{hash:'#console-api-errors'}" :aria-current="route.hash==='#console-api-errors'?'location':undefined">错误处理</RouterLink><RouterLink class="console-doc-return" to="/api/dashboard">← 返回控制台</RouterLink></nav>
  <article class="console-doc-body"><header class="console-doc-heading"><p>API 控制台 <span aria-hidden="true">/</span> API 文档</p><h1>API 文档</h1><div class="console-doc-base-url"><strong>BASE_URL</strong><code>{{apiBaseUrl}}</code></div></header><section :id="activeApiCategory.id" class="api-doc-content" aria-labelledby="api-category-title"><h2 id="api-category-title">{{activeApiCategory.title}}</h2><p>{{activeApiCategory.description}}</p><p v-for="paragraph in activeApiCategory.paragraphs" :key="paragraph">{{paragraph}}</p>
   <div v-if="activeApiCategory.endpoints.length" class="console-doc-table" role="region" tabindex="0" :aria-label="activeApiCategory.title+'接口列表表格'"><table><caption class="sr-only">{{activeApiCategory.title}}接口列表</caption><thead><tr><th>方法</th><th>路径</th><th>说明</th></tr></thead><tbody><tr v-for="endpoint in activeApiCategory.endpoints" :key="endpoint[0]+endpoint[1]"><td><code class="console-doc-method" :class="endpoint[0].toLowerCase()">{{endpoint[0]}}</code></td><td><code>{{endpoint[1]}}</code></td><td>{{endpoint[2]}}</td></tr></tbody></table></div>
   <template v-if="activeApiCategory.fields.length"><h3>参数说明</h3><div class="console-doc-table" role="region" tabindex="0" aria-label="参数说明表格"><table aria-label="请求参数"><thead><tr><th>字段</th><th>类型</th><th>说明</th></tr></thead><tbody><tr v-for="field in activeApiCategory.fields" :key="field[0]"><td><code>{{field[0]}}</code></td><td>{{field[1]}}</td><td>{{field[2]}}</td></tr></tbody></table></div></template>
  </section><section id="console-api-auth"><h2>接口与认证</h2><p>Authorization: Bearer 平台Key。BASE_URL 为 {{apiBaseUrl}}；示例在您的服务端执行，文档页面不会发送生成请求。</p></section><section id="console-api-retry"><h2>生成与重试</h2><p>{{retryText}}</p></section><section id="console-api-errors"><h2>错误处理</h2><p>{{errorText}}</p></section></article>
  <aside class="console-doc-examples" aria-label="请求与响应示例"><section class="console-doc-code"><header><h2>Request</h2><span>{{language}}</span></header><div class="console-doc-languages" role="group" aria-label="示例语言"><button v-for="item in languages" :key="item" :aria-label="item+' 示例'" :aria-pressed="language===item" @click="language=item">{{item}}</button></div><label v-if="activeApiCategory.examples.length>1" class="console-doc-example-select">示例操作<select v-model="exampleIndex"><option v-for="(item,index) in activeApiCategory.examples" :key="item.title" :value="index">{{item.title}}</option></select></label><div class="console-doc-code-toolbar"><h3>{{example.title}}</h3><button @click="copyExample">复制示例</button></div><span v-if="copied" class="console-doc-copy-status" role="status">{{copied}}</span><pre tabindex="0" aria-label="请求示例"><code>{{code}}</code></pre><details><summary>示例参数</summary><p>请设置 API_KEY；提交操作另设 REQUEST_ID，同一次重试保持原值。将 MODEL_ID、FILE_ID、TASK_ID、PORTRAIT_ID 替换为本人实际标识；视频时长按模型能力填写。</p></details></section><section class="console-doc-code"><header><h2>Response</h2><span>JSON</span></header><h3>响应字段示意</h3><pre tabindex="0" aria-label="响应字段示意"><code>{{JSON.stringify(example.response,null,2)}}</code></pre></section></aside>
 </div>
</template>
