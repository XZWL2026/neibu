<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { ApiError, mediaGetGetApiV1AdminMediaMpsConfiguration, mediaSavePutApiV1AdminMediaMpsConfiguration, mediaTestPostApiV1AdminMediaMpsConfigurationTest,
  type ResponseMediaInterfacesHttpMpsConfigurationControllerView as View,
  type ResponseMediaApplicationProviderMpsConfigurationStoreEnhancementTemplate as Template,
  type ResponseMediaApplicationProviderMpsConfigurationStoreBitrateProfile as Profile,
  type RequestMediaInterfacesHttpMpsConfigurationControllerSaveRequest as SaveRequest,
  type ResponseMediaApplicationProviderMpsConfigurationProbeResult as ProbeResult } from './api';
const props = withDefaults(defineProps<{ section?: 'MPS' | 'BITRATE' }>(), { section: 'MPS' });
const emit = defineEmits<{ version: [value: { version: number; source?: string }]; 'navigate-bitrate': [] }>();
const saved = ref<View | null>(null);
const form = reactive({ secretId: '', secretKey: '', region: '', bucket: '', bucketRegion: '', outputDir: '' });
const busy = ref(false); const message = ref(''); const advancedOpen = ref(false);
const templates = computed(() => saved.value?.enhancementTemplates ?? []);
const profiles = computed(() => saved.value?.bitrateProfiles ?? []);
const templateDraft = ref<Template | null>(null); const originalTemplateId = ref<string | null>(null);
const profileDraft = ref<Profile | null>(null); const originalProfileId = ref<string | null>(null);
const executionMode = ref('DEFINITION'); const aspectRatio = ref('16:9'); const probe = ref<ProbeResult | null>(null);
const shapes = ['WIDE', 'SQUARE', 'STANDARD', 'ULTRAWIDE'] as const;
const shapeLabels = { WIDE: '宽屏 16:9 / 9:16', SQUARE: '方形 1:1', STANDARD: '标准 4:3 / 3:4', ULTRAWIDE: '超宽 21:9' };
const resolutions = ['480p', '720p', '1080p', '2K', '4K'];
const requiredInput: Record<string, string> = { '720p': '480p', '1080p': '720p', '2K': '1080p', '4K': '1080p' };
const selected = computed(() => templates.value.find(item => item.id === originalTemplateId.value) ?? null);
const intermediateResolution = computed(() => !selected.value?.definitionId ? [
  { output: '1080p', intermediate: '2K', schedules: [108307, 108308, 108309, 108310] },
  { output: '4K', intermediate: '8K', schedules: [108311, 108312, 108313, 108314] },
].find(chain => selected.value?.outputResolution === chain.output
  && shapes.every((shape, index) => selected.value?.scheduleIds?.[shape] === chain.schedules[index]))?.intermediate : undefined);
const isSingleStep720p = (item: Template) => item.outputResolution === '720p' && !item.definitionId
  && shapes.every((shape, index) => item.scheduleIds?.[shape] === [108412, 108413, 108414, 108415][index]);
const linkedProfile = (item: Template) => profiles.value.find(profile => profile.id === item.bitrateProfileId);
const referenced = (id: string | null) => templates.value.some(item => item.bitrateProfileId === id);
const canManage = computed(() => !!saved.value?.secretKeyConfigured && !!saved.value?.secretId && !!saved.value?.bucket);
const editing = computed(() => props.section === 'MPS' ? !!templateDraft.value : !!profileDraft.value);
watch(aspectRatio, () => { probe.value = null; });
function remember(data: View) { saved.value = data; probe.value = null; emit('version', { version: data.version, source: data.source ?? undefined }); }
function resetGlobal() { const data = saved.value!; Object.assign(form, { secretId: data.secretId ?? '', secretKey: '', region: data.region ?? '', bucket: data.bucket ?? '', bucketRegion: data.bucketRegion ?? '', outputDir: data.outputDir ?? '' }); }
function editTemplate(item?: Template) {
  originalTemplateId.value = item?.id ?? null;
  templateDraft.value = item ? { ...item, acceptedInputResolutions: [...(item.acceptedInputResolutions ?? [])], scheduleIds: { ...(item.scheduleIds ?? {}) } }
    : { id: '', outputResolution: '720p', acceptedInputResolutions: ['480p'], enabled: true, bitrateProfileId: null, definitionId: null, scheduleIds: {} };
  executionMode.value = item && Object.keys(item.scheduleIds ?? {}).length ? 'SCHEDULE' : 'DEFINITION';
  probe.value = null; message.value = '';
}
function cancelTemplate() { templateDraft.value = null; originalTemplateId.value = null; probe.value = null; }
function editProfile(item?: Profile) { originalProfileId.value = item?.id ?? null; profileDraft.value = item ? { ...item } : { id: '', name: '', videoBitrateKbps: 8000 }; message.value = ''; }
function cancelProfile() { profileDraft.value = null; originalProfileId.value = null; }
async function load() {
  busy.value = true; message.value = '';
  try {
    const { data } = await mediaGetGetApiV1AdminMediaMpsConfiguration(); remember(data); resetGlobal(); cancelTemplate(); cancelProfile();
    advancedOpen.value = !data.bucket || !data.bucketRegion;
    const initial = templates.value.find(item => item.outputResolution === '1080p') ?? templates.value[0];
    if (initial) editTemplate(initial);
  } catch { message.value = '超分配置加载失败，请重新加载。'; }
  finally { busy.value = false; }
}
function basePayload(): SaveRequest {
  const data = saved.value!;
  return { expectedVersion: data.version, secretId: data.secretId, secretKey: '', region: data.region, bucket: data.bucket, bucketRegion: data.bucketRegion, outputDir: data.outputDir,
    template720p: data.template720p, template1080p: data.template1080p, template4k: data.template4k,
    ...(data.policyConfigured ? { enhancementTemplates: templates.value, bitrateProfiles: profiles.value } : {}) };
}
function errorMessage(error: unknown, fallback: string) { return error instanceof ApiError && error.status === 409 ? '配置已被其他管理员修改，请重新加载后编辑。' : fallback; }
async function persist(payload: SaveRequest): Promise<boolean> {
  if (busy.value || !saved.value) return false;
  busy.value = true; message.value = ''; probe.value = null;
  try { const { data } = await mediaSavePutApiV1AdminMediaMpsConfiguration(payload); remember(data); message.value = '已保存。'; return true; }
  catch (error) { message.value = errorMessage(error, '保存失败，请检查配置后重试。'); return false; }
  finally { busy.value = false; }
}
const positiveId = (value: unknown) => typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
function validBinding(item: Template) { return positiveId(item.definitionId) || shapes.every(shape => positiveId(item.scheduleIds?.[shape])); }
function policyTemplates(items = templates.value) { return saved.value?.policyConfigured ? items : items.filter(validBinding); }
async function saveGlobal() {
  if (!saved.value) return;
  const payload = { ...basePayload(), ...form };
  if (!saved.value.policyConfigured && [saved.value.template720p, saved.value.template1080p, saved.value.template4k].some(id => id < 1)) {
    payload.enhancementTemplates = policyTemplates(); payload.bitrateProfiles = profiles.value;
  }
  if (await persist(payload)) resetGlobal();
}
function validIdentifier(id: string | null) { return !!id && /^[A-Za-z0-9_-]{1,64}$/.test(id); }
async function saveTemplate() {
  if (!templateDraft.value) return;
  const item: Template = { ...templateDraft.value, definitionId: executionMode.value === 'DEFINITION' ? templateDraft.value.definitionId : null,
    scheduleIds: executionMode.value === 'SCHEDULE' ? { ...templateDraft.value.scheduleIds } : {}, bitrateProfileId: templateDraft.value.bitrateProfileId || null };
  if (!validIdentifier(item.id) || !item.outputResolution || !item.acceptedInputResolutions?.includes(requiredInput[item.outputResolution])) { message.value = '请填写有效编号，并选择输出规格对应的输入规格。'; return; }
  if (!validBinding(item)) { message.value = '请填写有效腾讯模板 ID 或完整的四组编排 ID。'; return; }
  if (policyTemplates().some(other => other.id !== originalTemplateId.value && (other.id === item.id || (item.enabled && other.enabled && other.outputResolution === item.outputResolution)))) { message.value = '模板编号不能重复，每个输出规格只能启用一个模板。'; return; }
  const next = originalTemplateId.value ? templates.value.map(other => other.id === originalTemplateId.value ? item : other) : [...templates.value, item];
  if (await persist({ ...basePayload(), enhancementTemplates: policyTemplates(next), bitrateProfiles: profiles.value })) { const latest = templates.value.find(other => other.id === item.id); if (latest) editTemplate(latest); }
}
async function deleteTemplate(item: Template) {
  if (await persist({ ...basePayload(), enhancementTemplates: policyTemplates().filter(other => other.id !== item.id), bitrateProfiles: profiles.value })) { if (originalTemplateId.value === item.id) cancelTemplate(); }
}
async function saveProfile() {
  const item = profileDraft.value;
  if (!item) return;
  if (!validIdentifier(item.id) || !item.name?.trim() || !Number.isInteger(item.videoBitrateKbps) || item.videoBitrateKbps < 128 || item.videoBitrateKbps > 100000) { message.value = '请填写有效编号、名称及 128–100000 Kbps 的整数码率。'; return; }
  if (profiles.value.some(other => other.id !== originalProfileId.value && other.id === item.id)) { message.value = '码率编号不能重复。'; return; }
  const next = originalProfileId.value ? profiles.value.map(other => other.id === originalProfileId.value ? { ...item } : other) : [...profiles.value, { ...item }];
  if (await persist({ ...basePayload(), enhancementTemplates: policyTemplates(), bitrateProfiles: next })) { const latest = profiles.value.find(other => other.id === item.id); if (latest) editProfile(latest); }
}
async function deleteProfile(item: Profile) {
  if (referenced(item.id)) return;
  if (await persist({ ...basePayload(), enhancementTemplates: policyTemplates(), bitrateProfiles: profiles.value.filter(other => other.id !== item.id) })) { if (originalProfileId.value === item.id) cancelProfile(); }
}
async function checkTemplate() {
  if (busy.value || !selected.value?.id || !saved.value) return;
  busy.value = true; message.value = ''; probe.value = null;
  try { const { data } = await mediaTestPostApiV1AdminMediaMpsConfigurationTest({ expectedVersion: saved.value.version, templateId: selected.value.id, aspectRatio: aspectRatio.value }); probe.value = data; }
  catch (error) { message.value = errorMessage(error, '配置校验失败，请检查腾讯连接和模板配置。'); }
  finally { busy.value = false; }
}
onMounted(load);
</script>
<template>
  <section class="mps-layout" :class="{ 'mps-layout-editing': editing }" :aria-label="section === 'MPS' ? '腾讯云 MPS 超分配置' : '码率配置管理'">
    <div class="mps-main">
      <template v-if="section === 'MPS'">
        <section class="mps-card" aria-labelledby="mps-heading">
          <header class="mps-card-heading"><h2 id="mps-heading">腾讯增强 API 全局配置</h2><button type="button" class="mps-secondary" :disabled="busy" @click="load">重新加载</button></header>
          <form aria-label="全局连接配置" @submit.prevent="saveGlobal">
            <fieldset :disabled="busy || !saved"><legend class="mps-sr-only">连接与输出位置</legend>
              <div class="mps-connection-grid">
                <label>SecretId<input v-model.trim="form.secretId" name="secretId" required maxlength="256" autocomplete="off"></label>
                <label>SecretKey<input v-model="form.secretKey" name="secretKey" type="password" :required="!saved?.secretKeyConfigured" maxlength="4096" autocomplete="new-password" :placeholder="saved?.secretKeyConfigured ? '已配置，留空保留原密钥' : '填写腾讯云 SecretKey'"></label>
                <label>MPS 接入地域<input v-model.trim="form.region" name="region" required placeholder="ap-beijing"></label>
              </div>
              <details class="mps-advanced" :open="advancedOpen"><summary>高级设置</summary><div class="mps-connection-grid">
                <label>COS 输出桶<input v-model.trim="form.bucket" name="bucket" required placeholder="bucket-appid"></label>
                <label>COS 桶地域<input v-model.trim="form.bucketRegion" name="bucketRegion" required placeholder="ap-guangzhou"></label>
                <label>输出目录前缀<input v-model.trim="form.outputDir" name="outputDir" maxlength="512" placeholder="enhance_output/"></label>
              </div></details>
              <div class="mps-actions"><button type="submit" class="mps-primary">保存全局配置</button></div>
            </fieldset>
          </form>
        </section>
        <section class="mps-card" aria-labelledby="mps-templates-heading">
          <header class="mps-card-heading"><h2 id="mps-templates-heading">增强模板中心 <span class="mps-count">{{ templates.length }} 个模板</span></h2><button class="mps-primary" aria-label="新增增强模板" :disabled="busy || !canManage" @click="editTemplate()">新增模板</button></header>
          <div class="mps-template-grid">
            <article v-for="item in templates" :key="item.id ?? ''" class="mps-template-card" :class="{ 'mps-template-selected': originalTemplateId === item.id }">
              <div class="mps-template-heading"><h3>{{ item.outputResolution?.toUpperCase() }} 增强</h3><span class="mps-badge" :class="{ 'mps-badge-off': !item.enabled }">{{ item.enabled ? '已启用' : '已停用' }}</span></div>
              <dl><dt>配置编号</dt><dd>{{ item.id }}</dd><dt>{{ isSingleStep720p(item) ? '处理方式' : item.definitionId ? '腾讯模板 ID' : '腾讯编排' }}</dt><dd>{{ isSingleStep720p(item) ? '单步增强（按画幅）' : item.definitionId || (Object.keys(item.scheduleIds ?? {}).length ? '4 组编排' : '未配置') }}</dd><dt>输入规格</dt><dd>{{ item.acceptedInputResolutions?.join(' / ') }}</dd><dt>输出规格</dt><dd>{{ item.outputResolution }}</dd><dt>目标码率</dt><dd>{{ linkedProfile(item) ? `${linkedProfile(item)?.videoBitrateKbps} Kbps` : '跟随腾讯模板' }}</dd></dl>
              <div class="mps-card-actions"><button class="mps-secondary" :aria-label="`编辑模板 ${item.id}`" :disabled="busy" @click="editTemplate(item)">编辑</button><button class="mps-delete" :aria-label="`删除模板 ${item.id}`" :disabled="busy || !canManage" @click="deleteTemplate(item)">删除</button></div>
            </article>
          </div>
        </section>
        <section v-if="selected" class="mps-card" aria-labelledby="mps-flow-heading">
          <header class="mps-card-heading"><h2 id="mps-flow-heading">增强链路预览</h2><span class="mps-count">{{ selected.id }}</span></header>
          <ol class="mps-flow"><li><span class="mps-flow-icon" aria-hidden="true">▷</span><div><strong>输入视频</strong><span>{{ selected.acceptedInputResolutions?.join(' / ') }}</span></div></li><li><span class="mps-flow-icon" aria-hidden="true">↗</span><div><strong>{{ isSingleStep720p(selected) ? '单步超分' : intermediateResolution ? '超分增强' : '腾讯增强模板' }}</strong><span v-if="isSingleStep720p(selected)">{{ selected.outputResolution }}</span><span v-else-if="intermediateResolution">{{ intermediateResolution }}</span><span v-if="!isSingleStep720p(selected)">{{ selected.definitionId || Object.values(selected.scheduleIds ?? {}).join(' / ') }}</span></div></li><li><span class="mps-flow-icon" aria-hidden="true">▤</span><div><strong>{{ intermediateResolution ? '缩放输出' : '输出视频' }}</strong><span>{{ selected.outputResolution }} · {{ linkedProfile(selected) ? `${linkedProfile(selected)?.videoBitrateKbps} Kbps` : '模板码率' }}</span></div></li></ol>
        </section>
      </template>
      <section v-else class="mps-card" aria-labelledby="mps-bitrate-heading">
        <header class="mps-card-heading"><h2 id="mps-bitrate-heading">码率配置 <span class="mps-count">{{ profiles.length }} 个配置</span></h2><div class="mps-card-actions"><button class="mps-secondary" :disabled="busy" @click="load">重新加载</button><button class="mps-primary" aria-label="新增码率配置" :disabled="busy || !canManage" @click="editProfile()">新增码率</button></div></header>
        <div class="mps-template-grid"><article v-for="item in profiles" :key="item.id ?? ''" class="mps-template-card" :class="{ 'mps-template-selected': originalProfileId === item.id }">
          <div class="mps-template-heading"><h3>{{ item.name }}</h3><span class="mps-template-icon" aria-hidden="true">⇄</span></div><dl><dt>配置编号</dt><dd>{{ item.id }}</dd><dt>目标码率</dt><dd>{{ item.videoBitrateKbps }} Kbps</dd><dt>关联模板</dt><dd>{{ templates.filter(t => t.bitrateProfileId === item.id).map(t => t.id).join('、') || '—' }}</dd></dl>
          <div class="mps-card-actions"><button class="mps-secondary" :aria-label="`编辑码率 ${item.id}`" :disabled="busy" @click="editProfile(item)">编辑</button><button class="mps-delete" :aria-label="`删除码率 ${item.id}`" :disabled="busy || referenced(item.id)" @click="deleteProfile(item)">删除</button></div>
        </article></div>
      </section>
      <p v-if="message" class="mps-message" role="status">{{ message }}</p>
    </div>
    <aside v-if="section === 'MPS' && templateDraft" class="mps-card mps-editor" aria-labelledby="mps-editor-heading">
      <header class="mps-card-heading"><h2 id="mps-editor-heading">{{ originalTemplateId ? '编辑' : '新增' }}增强模板</h2><button class="mps-close" aria-label="关闭模板编辑" :disabled="busy" @click="cancelTemplate">×</button></header>
      <form aria-label="增强模板配置" @submit.prevent="saveTemplate"><fieldset :disabled="busy || !canManage"><legend class="mps-sr-only">增强模板字段</legend>
        <label>配置编号<input v-model.trim="templateDraft.id" name="templateId" :readonly="!!originalTemplateId" required pattern="[A-Za-z0-9_-]{1,64}" maxlength="64"></label>
        <label>输出规格<select v-model="templateDraft.outputResolution" name="outputResolution"><option>720p</option><option>1080p</option><option>2K</option><option>4K</option></select></label>
        <fieldset class="mps-checkbox-group"><legend>接受的输入规格</legend><label v-for="resolution in resolutions" :key="resolution"><input v-model="templateDraft.acceptedInputResolutions" type="checkbox" :value="resolution">{{ resolution }}</label></fieldset>
        <details :key="originalTemplateId ?? 'new-template'" class="mps-advanced mps-execution" :open="!originalTemplateId"><summary>高级设置</summary><div class="mps-execution-fields">
        <label>腾讯执行配置<select v-model="executionMode" name="executionMode"><option value="DEFINITION">单个转码模板</option><option value="SCHEDULE">按画幅选择编排</option></select></label>
        <label v-if="executionMode === 'DEFINITION'">腾讯转码模板 ID<input v-model.number="templateDraft.definitionId" name="definitionId" type="number" min="1" max="9007199254740991" step="1" required></label>
        <template v-else><label v-for="shape in shapes" :key="shape">{{ shapeLabels[shape] }} 编排 ID<input v-model.number="templateDraft.scheduleIds![shape]" :name="shape" type="number" min="1" max="9007199254740991" step="1" required></label></template>
        </div></details>
        <label class="mps-checkbox"><input v-model="templateDraft.enabled" name="enabled" type="checkbox" role="switch">启用模板</label>
        <label>关联码率配置<select v-model="templateDraft.bitrateProfileId" name="bitrateProfileId"><option :value="null">跟随腾讯模板</option><option v-for="profile in profiles" :key="profile.id ?? ''" :value="profile.id">{{ profile.name }} · {{ profile.videoBitrateKbps }} Kbps</option></select></label>
        <button type="button" class="mps-secondary" @click="emit('navigate-bitrate')">查看码率配置</button>
        <div class="mps-actions"><button type="button" class="mps-secondary" aria-label="取消编辑模板" @click="cancelTemplate">取消</button><button type="submit" class="mps-primary">保存模板</button></div>
      </fieldset></form>
      <section v-if="selected" class="mps-probe" aria-label="已保存模板配置校验"><label>校验画幅<select v-model="aspectRatio" :disabled="busy"><option v-for="ratio in ['16:9', '9:16', '1:1', '4:3', '3:4', '21:9']" :key="ratio">{{ ratio }}</option></select></label><button type="button" class="mps-secondary" aria-label="校验当前模板配置" :disabled="busy || !canManage" @click="checkTemplate">{{ busy ? '处理中…' : '校验已保存配置' }}</button>
        <div v-if="probe" class="mps-probe-result" role="status"><strong>{{ probe.success ? '配置校验通过' : '配置校验失败' }}</strong><template v-if="probe.success"><span>{{ probe.width }} × {{ probe.height }}</span><span v-if="probe.videoBitrateKbps">目标码率 {{ probe.videoBitrateKbps }} Kbps</span><span>转码模板 {{ probe.definitions?.join(' / ') }}</span></template><span v-else>{{ probe.message }}</span><span>版本 {{ probe.version }} · {{ probe.durationMs }} ms</span></div>
      </section>
    </aside>
    <aside v-if="section === 'BITRATE' && profileDraft" class="mps-card mps-editor" aria-labelledby="mps-profile-heading">
      <header class="mps-card-heading"><h2 id="mps-profile-heading">{{ originalProfileId ? '编辑' : '新增' }}码率配置</h2><button class="mps-close" aria-label="关闭码率编辑" :disabled="busy" @click="cancelProfile">×</button></header>
      <form aria-label="码率配置" @submit.prevent="saveProfile"><fieldset :disabled="busy || !canManage"><legend class="mps-sr-only">码率字段</legend>
        <label>配置编号<input v-model.trim="profileDraft.id" name="profileId" :readonly="!!originalProfileId" pattern="[A-Za-z0-9_-]{1,64}" maxlength="64" required></label>
        <label>配置名称<input v-model.trim="profileDraft.name" name="profileName" maxlength="128" required></label>
        <label>目标视频码率（Kbps）<input v-model.number="profileDraft.videoBitrateKbps" name="videoBitrateKbps" type="number" min="128" max="100000" step="1" required></label>
        <div class="mps-actions"><button type="button" class="mps-secondary" aria-label="取消编辑码率" @click="cancelProfile">取消</button><button type="submit" class="mps-primary">保存码率</button></div>
      </fieldset></form>
    </aside>
  </section>
</template>
<style scoped>
.mps-layout { display: grid; gap: 18px; align-items: start; color: #111740; }
.mps-layout-editing { grid-template-columns: minmax(0, 1fr) 360px; }
.mps-main { min-width: 0; display: grid; gap: 16px; }
.mps-card { min-width: 0; padding: 22px; border: 1px solid #e1eafa; border-radius: 12px; background: #fff; }
.mps-card-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 20px; }
.mps-card h2, .mps-card h3 { color: #111740; font-weight: 650; margin: 0; }
.mps-card h2 { font-size: 19px; }
.mps-card h3 { font-size: 18px; }
.mps-card fieldset { min-width: 0; border: 0; padding: 0; margin: 0; }
.mps-card label { display: grid; gap: 9px; min-width: 0; font-size: 13px; }
.mps-card :is(input, select) { width: 100%; min-width: 0; box-sizing: border-box; border: 1px solid #dce5f4; border-radius: 6px; height: 40px; padding: 0 11px; font: inherit; color: #111740; background: #fff; }
.mps-card input[readonly] { background: #f6f8fc; color: #66749a; }
.mps-card input::placeholder { color: #8995b4; }
.mps-card button { cursor: pointer; min-height: 38px; padding: 8px 15px; border-radius: 6px; font: inherit; font-size: 13px; font-weight: 550; }
.mps-card button:disabled { cursor: default; opacity: .55; }
.mps-card :is(input, select, button, summary):focus-visible { outline: 2px solid #006bff; outline-offset: 3px; }
.mps-primary { background: #006bff; border: 1px solid #006bff; color: #fff; }
.mps-secondary { background: #fff; border: 1px solid #b7ccf6; color: #006bff; }
.mps-connection-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 15px; }
.mps-advanced { border-radius: 7px; background: #f1f6ff; margin-top: 17px; }
.mps-advanced summary { cursor: pointer; padding: 13px 15px; color: #263b66; font-size: 13px; font-weight: 550; }
.mps-advanced .mps-connection-grid { padding: 5px 15px 16px; }
.mps-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px; }
.mps-count { color: #7684a9; font-size: 12px; }
.mps-template-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
.mps-template-card { min-width: 0; border: 1px solid #e1eafa; border-radius: 8px; padding: 17px; }
.mps-template-selected { border-color: #8ab6ff; box-shadow: 0 0 0 1px #e7f0ff; }
.mps-template-heading { display: flex; align-items: center; justify-content: space-between; gap: 7px; }
.mps-template-icon { color: #006bff; font-size: 19px; }
.mps-template-card dl { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 12px; margin: 22px 0; font-size: 12px; }
.mps-template-card dt { color: #7684a9; }
.mps-template-card dd { margin: 0; overflow-wrap: anywhere; text-align: right; }
.mps-template-card button { width: 100%; }
.mps-editor { position: sticky; top: 20px; }
.mps-editor fieldset { display: grid; gap: 22px; }
.mps-editor .mps-actions { margin-top: 18px; }
.mps-editor .mps-actions button { flex: 1; }
.mps-close { border: 0; background: transparent; color: #53628c; font-size: 24px !important; padding: 0 5px !important; }
.mps-flow { list-style: none; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 24px; margin: 0; padding: 0; }
.mps-flow li { position: relative; display: flex; align-items: center; gap: 10px; padding: 14px 10px; border-radius: 8px; background: #f5f8ff; }
.mps-flow li + li::before { content: '→'; position: absolute; left: -20px; color: #006bff; }
.mps-flow-icon { display: grid; place-items: center; width: 35px; height: 35px; flex-shrink: 0; border-radius: 50%; background: #fff; color: #006bff; font-size: 22px; }
.mps-flow li div { min-width: 0; display: grid; gap: 7px; font-size: 12px; }
.mps-flow li strong { font-weight: 550; }
.mps-flow li div span { color: #7183ab; overflow-wrap: anywhere; }
.mps-message { margin: 0; font-size: 13px; }
.mps-sr-only { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
@media (max-width: 1200px) { .mps-layout-editing { grid-template-columns: minmax(0, 1fr); } .mps-editor { position: static; } }
@media (max-width: 640px) { .mps-card { padding: 17px; } .mps-connection-grid, .mps-template-grid { grid-template-columns: 1fr; } .mps-flow { grid-template-columns: 1fr; gap: 22px; } .mps-flow li + li::before { content: '↓'; left: 25px; top: -21px; } }
.mps-card-actions { display: flex; gap: 8px; }
.mps-card-actions button { flex: 1; }
.mps-delete { border: 1px solid #e1eafa; color: #697793; background: #fff; }
.mps-badge { flex-shrink: 0; border-radius: 4px; padding: 4px 6px; color: #006bff; background: #edf4ff; font-size: 11px; }
.mps-badge-off { color: #77829b; background: #f1f3f7; }
.mps-card .mps-checkbox, .mps-checkbox-group label { display: flex; align-items: center; gap: 7px; }
.mps-card input[type=checkbox] { width: 16px; height: 16px; padding: 0; accent-color: #006bff; }
.mps-card .mps-checkbox-group { display: flex; flex-wrap: wrap; gap: 14px; }
.mps-checkbox-group legend { font-size: 13px; margin-bottom: 12px; }
.mps-probe { border-top: 1px solid #e1eafa; padding-top: 20px; margin-top: 22px; display: grid; gap: 14px; }
.mps-probe-result { display: grid; gap: 8px; font-size: 12px; color: #53628c; }
.mps-execution { margin-top: 0; }
.mps-execution-fields { display: grid; gap: 16px; padding: 5px 15px 16px; }
.mps-card input[role=switch] { appearance: none; position: relative; width: 34px; height: 20px; border: 0; border-radius: 12px; background: #bac5d9; cursor: pointer; }
.mps-card input[role=switch]::before { content: ''; position: absolute; left: 3px; top: 3px; width: 14px; height: 14px; background: #fff; border-radius: 50%; transition: transform .15s; }
.mps-card input[role=switch]:checked { background: #006bff; }
.mps-card input[role=switch]:checked::before { transform: translateX(14px); }
</style>
