import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createRef, useState, type FormEvent } from 'react';
import type { CanvasGenerationSettings } from '../canvas/model';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import * as api from '../api';
import * as session from '../userSession';
import { HomePromptComposer, homeModelSettings } from './HomePromptComposer';
import { homeCreationEntry, homePromptDraft, type HomeGenerationMode } from './home-entry';
import { saveComposerDraft } from '../creator/composer-draft';
import { ProductionImageComposer } from '../creator/ProductionImageComposer';
import { ProductionVideoComposer } from '../creator/ProductionVideoComposer';
import { CreatorMediaProvider } from '../creator/CreatorMediaProvider';
import { homeMaterialCapability, homeMaterialIssues, type HomeMaterials } from './HomeMaterialPicker';
import { readReferenceAudioDuration } from '../creator/reference-audio-duration';

vi.mock('../creator/reference-audio-duration', () => ({readReferenceAudioDuration: vi.fn()}));

vi.mock('../api', async original => ({ ...await original<typeof import('../api')>(), mediaOptionsGetApiV1MediaGenerationOptions: vi.fn(),
  mediaListGetApiV1MediaTasks: vi.fn(), mediaQuotePostApiV1MediaQuotes: vi.fn(), mediaSubmitPostApiV1MediaTasks: vi.fn(),
  storageUploadReferenceImagePostApiV1FilesReferenceImages: vi.fn(), storageUploadReferenceMediaPostApiV1FilesReferenceMedia: vi.fn(),
  storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl: vi.fn(), mediaPortraitListGetApiV1MediaPortraits: vi.fn(),
  mediaPortraitSubmitPostApiV1MediaPortraits: vi.fn(), mediaPortraitGetGetApiV1MediaPortraitsPortraitId: vi.fn() }));
const base = { aspectRatios:['16:9','9:16'], resolutions:['720p','1080p'], maxCount:4, maxReferenceImages:0, minDurationSeconds:4, maxDurationSeconds:12, durationStepSeconds:2, referenceFollowsFirstFrame:false };
const video = { ...base, type:'VIDEO', model:'catalog-video', displayName:'Seedance 2.5' } as const;
const image = { ...base, type:'IMAGE', model:'catalog-image', displayName:'Image 2', resolutions:['1K','2K'] } as const;
const omni = {maxTotal:6,maxImages:3,maxVideos:2,maxAudios:1,maxVideoBytes:10000,maxAudioBytes:10000,maxVideoDurationSeconds:10,maxAudioDurationSeconds:10,audioOnly:false};
const referenceVideo = {...video,maxReferenceImages:2,omniReference:omni};
const reply = (data:unknown) => ({data,status:200,headers:new Headers()}) as never;
beforeEach(() => {
  localStorage.clear(); sessionStorage.clear(); vi.resetAllMocks();
  vi.spyOn(session,'useUserSession').mockReturnValue({authenticated:true,userId:'owner',status:'ready'} as never);
  vi.spyOn(session,'useOptionalUserSession').mockReturnValue({authenticated:true,userId:'owner',status:'ready',currentKey:{enabled:true,kind:'媒体 Key',relationship:'Owner'},availablePoints4:'1000.0000'} as never);
  vi.spyOn(window,'scrollTo').mockImplementation(()=>{});
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({models:[video,image]}));
  vi.mocked(api.mediaListGetApiV1MediaTasks).mockResolvedValue(reply([]));
  vi.mocked(api.mediaQuotePostApiV1MediaQuotes).mockResolvedValue(reply({points:'1.0000'}));
  vi.mocked(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).mockImplementation(async id => reply({url:`https://assets.example/${id}.png`}));
  vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mockResolvedValue(reply({fileId:'uploaded-image'}));
  vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).mockResolvedValue(reply({fileId:'uploaded-media',durationSeconds:4}));
  vi.mocked(api.mediaPortraitListGetApiV1MediaPortraits).mockResolvedValue(reply([]));
  vi.mocked(readReferenceAudioDuration).mockResolvedValue(4);
  vi.mocked(api.mediaPortraitSubmitPostApiV1MediaPortraits).mockImplementation(async body => reply({...body,id:`review-${body.fileId}`,status:'ACTIVE',kind:body.kind ?? 'IMAGE',durationSeconds:body.kind !== 'IMAGE' ? 4 : null}));
});
afterEach(() => {cleanup();vi.restoreAllMocks();vi.unstubAllGlobals();});
function mount(kind: 'video' | 'image' = 'video') {
  const onSubmit=vi.fn((event:FormEvent,_settings:CanvasGenerationSettings|null,_mode:HomeGenerationMode,_materials:HomeMaterials)=>event.preventDefault()), onModel=vi.fn();
  const view=render(<MemoryRouter><HomePromptComposer inputRef={createRef()} prompt="海边的晨光" kind={kind} modelName={kind === 'video' ? 'Seedance 2.5' : 'Image 2'} error=""
    choices={[{name:'Seedance 2.5',kind:'video'},{name:'Image 2',kind:'image'}]} onPrompt={()=>{}} onModel={onModel} onSubmit={onSubmit}/></MemoryRouter>);
  return {...view,onSubmit,onModel};
}
it('reveals catalog parameters on hover, keeps focused controls open, and restores prompt focus on Escape', async()=>{
  const {container,onSubmit}=mount(); const form=container.querySelector('form')!;
  expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
  fireEvent.pointerEnter(form,{pointerType:'mouse'});
  const summary = await screen.findByRole('button', {name:'视频参数摘要：16:9，720p，4秒'});
  summary.focus(); fireEvent.click(summary);
  const dialog=screen.getByRole('dialog',{name:'视频设置'});
  fireEvent.click(within(dialog).getByRole('button',{name:'9:16'}));
  fireEvent.click(within(dialog).getByRole('button',{name:'1080p'}));
  fireEvent.change(within(dialog).getByRole('slider',{name:'视频时长'}),{target:{value:'10'}});
  fireEvent.pointerDown(dialog); fireEvent.pointerLeave(form);
  expect(form).toHaveAttribute('data-expanded','true');
  fireEvent.click(screen.getByRole('button',{name:'关闭视频设置'}));
  expect(summary).toHaveFocus();
  fireEvent.click(screen.getByRole('button',{name:'开始创作'}));
  expect(onSubmit.mock.calls[0][1]).toEqual({type:'VIDEO',model:'catalog-video',aspectRatio:'9:16',resolution:'1080p',count:1,durationSeconds:10});
  fireEvent.keyDown(summary,{key:'Escape'});
  expect(form).toHaveAttribute('data-expanded','false'); expect(screen.getByRole('textbox')).toHaveFocus();
  expect(container.querySelector('#ym-prompt-parameters')).toHaveAttribute('inert');
  expect(api.mediaSubmitPostApiV1MediaTasks).not.toHaveBeenCalled();
});
it('handles loading failure and retry without inventing parameter choices',async()=>{
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockRejectedValueOnce(new Error('offline'));
  const {container}=mount();fireEvent.focus(screen.getByRole('textbox'));
  await waitFor(()=>expect(screen.getByText('参数加载失败')).toBeVisible());
  expect(screen.getByRole('button',{name:'视频参数摘要'})).toBeDisabled();
  fireEvent.click(screen.getByRole('button',{name:'重试'}));
  await waitFor(()=>expect(screen.getByRole('button',{name:/视频参数摘要：/})).toBeEnabled());
  fireEvent.pointerDown(document.body); expect(container.querySelector('form')).toHaveAttribute('data-expanded','false');
});
it('reconciles unsupported settings and rejects malformed home handoffs',()=>{
  const bad={type:'VIDEO',model:'catalog-video',aspectRatio:'bogus',resolution:'bogus',count:500,durationSeconds:7} as const;
  expect(homeModelSettings(bad,video as never)).toEqual({type:'VIDEO',model:'catalog-video',aspectRatio:'16:9',resolution:'720p',count:1,durationSeconds:4});
  expect(homeCreationEntry({source:'yomio-home',prompt:'text',settings:bad})?.settings).toBeUndefined();
});
it('uses the workspace image settings, resets changes, and hands off the selected quantity', async () => {
  const {onSubmit}=mount('image');
  fireEvent.focus(screen.getByRole('textbox'));
  fireEvent.click(await screen.findByRole('button',{name:'图片参数摘要：16:9，1K，1张'}));
  const panel=screen.getByRole('dialog',{name:'图片设置'});
  fireEvent.click(within(panel).getByRole('button',{name:'9:16'}));
  fireEvent.click(within(panel).getByRole('button',{name:'2K'}));
  fireEvent.change(within(panel).getByRole('spinbutton',{name:'生成数量'}),{target:{value:'3'}});
  fireEvent.click(within(panel).getByRole('button',{name:'重置'}));
  expect(within(panel).getByRole('button',{name:'16:9'})).toHaveAttribute('aria-pressed','true');
  expect(within(panel).getByRole('spinbutton',{name:'生成数量'})).toHaveValue(1);
  fireEvent.change(within(panel).getByRole('spinbutton',{name:'生成数量'}),{target:{value:'3'}});
  fireEvent.click(within(panel).getByRole('button',{name:'关闭图片设置'}));
  fireEvent.click(screen.getByRole('button',{name:'开始创作'}));
  expect(onSubmit.mock.calls[0][1]).toMatchObject({type:'IMAGE',count:3,resolution:'1K',aspectRatio:'16:9'});
});
it('rejects invalid durations and keeps the parameter dialog open until corrected', async () => {
  const {onSubmit,container}=mount();
  fireEvent.focus(screen.getByRole('textbox'));
  fireEvent.click(await screen.findByRole('button',{name:/视频参数摘要：/}));
  fireEvent.change(screen.getByRole('spinbutton',{name:'时长秒数'}),{target:{value:'7'}});
  expect(screen.getByRole('alert')).toHaveTextContent('请按当前模型');
  fireEvent.click(screen.getByRole('button',{name:'关闭视频设置'}));
  fireEvent.click(screen.getByRole('button',{name:'开始创作'}));
  expect(onSubmit).not.toHaveBeenCalled();
  expect(screen.getByRole('dialog',{name:'视频设置'})).toBeVisible();
  fireEvent(screen.getByRole('dialog'), new Event('cancel', { bubbles: false }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(container.querySelector('form')).toHaveAttribute('data-expanded','true');
});
it('offers featured models without inventing parameters for signed-out visitors', () => {
  vi.mocked(session.useUserSession).mockReturnValue({authenticated:false,status:'ready'} as never);
  const {onModel}=mount(); fireEvent.focus(screen.getByRole('textbox'));
  expect(screen.getByRole('button',{name:'视频参数摘要'})).toBeDisabled();
  fireEvent.click(screen.getByRole('button',{name:'选择视频模型：Seedance 2.5'}));
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button',{name:'Seedance 2.5'}));
  expect(onModel).toHaveBeenCalledWith('video','Seedance 2.5');
  expect(api.mediaOptionsGetApiV1MediaGenerationOptions).not.toHaveBeenCalled();
});
it('groups image resolution variants like the workspace and transfers the matching model id', async () => {
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({models:[
    {...image,model:'gpt-image-2',resolutions:['1K']},
    {...image,model:'gpt-image-2-4K',resolutions:['4K']},
  ]}));
  const {onSubmit}=mount('image'); fireEvent.focus(screen.getByRole('textbox'));
  await screen.findByRole('button',{name:'图片参数摘要：16:9，1K，1张'});
  fireEvent.click(screen.getByRole('button',{name:'选择图片模型：Image 2'}));
  expect(within(screen.getByRole('dialog')).getAllByRole('button',{name:'gpt-image-2'})).toHaveLength(1);
  fireEvent.click(screen.getByRole('button',{name:'关闭模型选择'}));
  fireEvent.click(screen.getByRole('button',{name:/图片参数摘要：/}));
  fireEvent.click(screen.getByRole('button',{name:'4K'}));
  fireEvent.click(screen.getByRole('button',{name:'关闭图片设置'}));
  fireEvent.click(screen.getByRole('button',{name:'开始创作'}));
  expect(onSubmit.mock.calls[0][1]).toMatchObject({model:'gpt-image-2-4K',resolution:'4K'});
});
it.each(['IMAGE','VIDEO'] as const)('transfers %s parameters into the real workspace and revalidates them against its catalog',async type=>{
  const settings={type,model:type==='IMAGE'?image.model:video.model,aspectRatio:'9:16',resolution:type==='IMAGE'?'2K':'1080p',count:type==='IMAGE'?3:1,durationSeconds:type==='IMAGE'?0:10};
  const Component=type==='IMAGE'?ProductionImageComposer:ProductionVideoComposer;
  render(<MemoryRouter initialEntries={[{pathname:`/create/${type.toLowerCase()}`,state:{source:'yomio-home',prompt:'首页参数交接',modelName:type==='IMAGE'?'Image 2':'Seedance 2.5',settings}}]}><CreatorMediaProvider><Component/></CreatorMediaProvider></MemoryRouter>);
  await waitFor(()=>expect(screen.getByRole('button',{name:type==='IMAGE'?'图片参数摘要：9:16，2K，3张':'视频参数摘要：9:16，1080p，10秒'})).toBeEnabled());
  expect(screen.getByRole('textbox')).toHaveValue('首页参数交接'); expect(api.mediaSubmitPostApiV1MediaTasks).not.toHaveBeenCalled();
});

it('keeps image and video mode selections separate and passes the chosen mode on submission', async () => {
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({models:[
    {...video,maxReferenceImages:2,omniReference:{maxImages:4}}, {...image,maxReferenceImages:4},
  ]}));
  const submit = vi.fn((event: FormEvent, _settings: CanvasGenerationSettings | null, _mode: HomeGenerationMode) => event.preventDefault());
  function Home() {
    const [kind, setKind] = useState<'image' | 'video'>('image');
    return <HomePromptComposer inputRef={createRef()} prompt="首页模式交接" kind={kind} modelName={kind === 'image' ? 'Image 2' : 'Seedance 2.5'} error=""
      choices={[{name:'Seedance 2.5',kind:'video'},{name:'Image 2',kind:'image'}]} onPrompt={() => {}} onModel={setKind} onSubmit={submit} />;
  }
  render(<MemoryRouter><Home /></MemoryRouter>);
  fireEvent.focus(screen.getByRole('textbox'));
  await screen.findByRole('button', {name: /图片参数摘要：/});
  fireEvent.click(screen.getByRole('button', {name: '图片生成模式'}));
  expect(screen.getAllByRole('menuitemradio').map(item => item.textContent?.replace('✓',''))).toEqual(['智能图片','文生图片','图片参考']);
  fireEvent.click(screen.getByRole('menuitemradio', {name: '图片参考'}));
  fireEvent.click(screen.getByRole('button', {name: '开始创作'}));
  expect(submit).not.toHaveBeenCalled();
  expect(screen.getByRole('alert')).toHaveTextContent('请添加参考图片');
  fireEvent.click(within(screen.getByRole('group', {name: '生成类型'})).getByRole('button', {name: '视频'}));
  const trigger = screen.getByRole('button', {name: '视频生成模式'});
  fireEvent.keyDown(trigger, {key: 'ArrowDown'});
  expect(screen.getAllByRole('menuitemradio').map(item => item.textContent?.replace('✓',''))).toEqual(['文生视频','全能参考','首尾帧']);
  fireEvent.click(screen.getByRole('menuitemradio', {name: '全能参考'}));
  fireEvent.click(screen.getByRole('button', {name: '开始创作'}));
  expect(submit).not.toHaveBeenCalled();
  expect(screen.getByRole('alert')).toHaveTextContent('请添加参考素材');
  fireEvent.click(within(screen.getByRole('group', {name: '生成类型'})).getByRole('button', {name: '图片'}));
  expect(screen.getByRole('button', {name: '图片生成模式'})).toHaveTextContent('图片参考');
  fireEvent.click(within(screen.getByRole('group', {name: '生成类型'})).getByRole('button', {name: '视频'}));
  expect(screen.getByRole('button', {name: '视频生成模式'})).toHaveTextContent('全能参考');
  expect(api.mediaSubmitPostApiV1MediaTasks).not.toHaveBeenCalled();
});

it.each(['image', 'video'] as const)('limits %s reference modes to the selected catalog capabilities', async kind => {
  mount(kind); fireEvent.focus(screen.getByRole('textbox'));
  await screen.findByRole('button', {name: kind === 'image' ? /图片参数摘要：/ : /视频参数摘要：/});
  fireEvent.click(screen.getByRole('button', {name: kind === 'image' ? '图片生成模式' : '视频生成模式'}));
  expect(screen.queryByRole('menuitemradio', {name: '图片参考'})).not.toBeInTheDocument();
  expect(screen.queryByRole('menuitemradio', {name: '全能参考'})).not.toBeInTheDocument();
  expect(screen.queryByRole('menuitemradio', {name: '首尾帧'})).not.toBeInTheDocument();
});

it.each([
  ['IMAGE', 'auto', '智能图片'], ['IMAGE', 'text', '文生图片'], ['IMAGE', 'reference', '图片参考'],
  ['VIDEO', 'text', '文生视频'], ['VIDEO', 'omni', '全能参考'], ['VIDEO', 'frames', '首尾帧'],
] as const)('restores homepage %s mode %s in the real composer over an older draft', async (type, mode, label) => {
  const olderMode = type === 'IMAGE' ? 'text' : 'frames';
  saveComposerDraft('owner', 'create', type, {prompt:'旧草稿',selection:null,mode:olderMode,materials:[],bindings:[]});
  const Component = type === 'IMAGE' ? ProductionImageComposer : ProductionVideoComposer;
  render(<MemoryRouter initialEntries={[{pathname:`/create/${type.toLowerCase()}`,state:{source:'yomio-home',prompt:'首页选择的模式',mode}}]}><CreatorMediaProvider><Component /></CreatorMediaProvider></MemoryRouter>);
  await waitFor(() => expect(screen.getByRole('button', {name:type === 'IMAGE' ? '图片生成模式' : '视频生成模式'})).toHaveTextContent(label));
  expect(screen.getByRole('textbox')).toHaveValue('首页选择的模式');
  expect(api.mediaSubmitPostApiV1MediaTasks).not.toHaveBeenCalled();
});

it('ignores invalid handoff modes and saves an explicit homepage mode over the previous draft', () => {
  expect(homeCreationEntry({source:'yomio-home',prompt:'描述',mode:'bogus'})?.mode).toBeUndefined();
  const previous = {prompt:'旧描述',selection:null,mode:'reference',materials:[],bindings:[]} as const;
  expect(homePromptDraft('新描述', previous as never, 'IMAGE', null, 'text').mode).toBe('text');
});

it('uploads and reviews a homepage reference image, replaces it, and hands off its real identity', async () => {
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({models:[referenceVideo,{...image,maxReferenceImages:2}]}));
  const {onSubmit}=mount('image'); fireEvent.focus(screen.getByRole('textbox'));
  await screen.findByRole('button',{name:/图片参数摘要：/});
  fireEvent.click(screen.getByRole('button',{name:'图片生成模式'}));
  fireEvent.click(screen.getByRole('menuitemradio',{name:'图片参考'}));
  const first = new File(['image'], '参考.png', {type:'image/png'});
  fireEvent.change(screen.getByLabelText('上传素材'),{target:{files:[first]}});
  await waitFor(() => expect(api.mediaPortraitSubmitPostApiV1MediaPortraits).toHaveBeenCalledWith(expect.objectContaining({fileId:'uploaded-image'}),expect.anything()));
  await waitFor(() => expect(screen.getByRole('button',{name:'开始创作'})).toBeEnabled());
  await waitFor(() => expect(screen.getByRole('button',{name:'移除参考图 1'})).toBeEnabled());
  fireEvent.click(screen.getByRole('button',{name:'开始创作'}));
  expect(onSubmit.mock.calls.at(-1)?.[3]).toEqual([expect.objectContaining({fileId:'uploaded-image',name:'参考.png',kind:'IMAGE',reviewStatus:'ACTIVE',reviewId:'review-uploaded-image'})]);
  expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).toHaveBeenCalledWith({file:first});
  vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mockResolvedValueOnce(reply({fileId:'replacement-image'}));
  fireEvent.change(screen.getByLabelText('替换参考图 1'),{target:{files:[new File(['new'],'替换.jpg',{type:'image/jpeg'})]}});
  await waitFor(() => expect(api.mediaPortraitSubmitPostApiV1MediaPortraits).toHaveBeenCalledWith(expect.objectContaining({fileId:'replacement-image'}),expect.anything()));
  await waitFor(() => expect(screen.getByRole('button',{name:'移除参考图 1'})).toBeEnabled());
  fireEvent.click(screen.getByRole('button',{name:'开始创作'}));
  expect(onSubmit.mock.calls.at(-1)?.[3]?.[0]).toMatchObject({fileId:'replacement-image',reviewStatus:'ACTIVE'});
  fireEvent.click(screen.getByRole('button',{name:'移除参考图 1'}));
  const count=onSubmit.mock.calls.length;
  fireEvent.click(screen.getByRole('button',{name:'开始创作'}));
  expect(onSubmit).toHaveBeenCalledTimes(count);
  expect(screen.getByRole('alert')).toHaveTextContent('请添加参考图片');
  expect(api.mediaSubmitPostApiV1MediaTasks).not.toHaveBeenCalled();
});

it('reopens after the file chooser loses focus and keeps upload feedback and references visible', async () => {
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({models:[referenceVideo,{...image,maxReferenceImages:2}]}));
  let finishUpload!: (value: never) => void;
  vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mockImplementationOnce(() => new Promise(resolve => { finishUpload = resolve; }));
  const {container,onSubmit}=mount('image'); const form=container.querySelector('form')!;
  fireEvent.focus(screen.getByRole('textbox'));
  await screen.findByRole('button',{name:/图片参数摘要：/});
  fireEvent.blur(screen.getByRole('textbox'),{relatedTarget:null}); fireEvent.pointerLeave(form);
  expect(form).toHaveAttribute('data-expanded','false');
  fireEvent.change(screen.getByLabelText('上传素材'),{target:{files:[new File(['image'],'文件选择框.png',{type:'image/png'})]}});
  await screen.findByText('正在上传素材…');
  expect(form).toHaveAttribute('data-expanded','true');
  fireEvent.blur(screen.getByRole('textbox'),{relatedTarget:null}); fireEvent.pointerLeave(form); fireEvent.pointerDown(document.body);
  expect(form).toHaveAttribute('data-expanded','true');
  await act(async () => finishUpload(reply({fileId:'chooser-image'})));
  await screen.findByText('已添加 1 份素材');
  fireEvent.pointerLeave(form);
  expect(screen.getByRole('group',{name:'参考素材'})).toBeVisible();
  const preview=within(screen.getByRole('group',{name:'参考素材'})).getByRole('img',{name:'文件选择框.png'});
  fireEvent.load(preview);
  expect(preview).toBeVisible();
  expect(screen.getByRole('button',{name:'添加素材'}).querySelector('.composer-stack-count')).toHaveTextContent('1');
  fireEvent.click(screen.getByRole('button',{name:'开始创作'}));
  expect(onSubmit.mock.calls.at(-1)?.[3]?.[0]).toMatchObject({fileId:'chooser-image',reviewStatus:'ACTIVE'});
});

it.each(['image','video'] as const)('keeps the %s homepage open while the native file chooser is open and after cancelling it', async kind => {
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({models:[referenceVideo,{...image,maxReferenceImages:2}]}));
  vi.spyOn(HTMLInputElement.prototype,'click').mockImplementation(() => {});
  const {container}=mount(kind); const form=container.querySelector('form')!;
  fireEvent.focus(screen.getByRole('textbox')); await screen.findByRole('button',{name:kind === 'image' ? /图片参数摘要：/ : /视频参数摘要：/});
  if (kind === 'video') {
    fireEvent.click(screen.getByRole('button',{name:'视频生成模式'}));
    fireEvent.click(screen.getByRole('menuitemradio',{name:'首尾帧'}));
  }
  fireEvent.click(screen.getByRole('button',{name:'添加素材'}));
  const local=screen.getByRole('menuitem',{name:'本地上传'}); local.focus();
  fireEvent.click(local);
  // Native chooser steals focus before there is a file or upload queue.
  fireEvent.blur(form,{relatedTarget:null}); fireEvent.pointerLeave(form); fireEvent.pointerDown(document.body);
  expect(form).toHaveAttribute('data-expanded','true');
  expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).not.toHaveBeenCalled();
  fireEvent(screen.getByLabelText('上传素材'),new Event('cancel'));
  expect(form).toHaveAttribute('data-expanded','true');
  expect(screen.getByRole('button',{name:'添加素材'})).toHaveFocus();
  fireEvent.pointerDown(document.body);
  expect(form).toHaveAttribute('data-expanded','false');
});

it('holds the composer open for the replacement chooser and restores the old material after cancel', async () => {
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({models:[referenceVideo,{...image,maxReferenceImages:2}]}));
  const {container,onSubmit}=mount('image'); const form=container.querySelector('form')!;
  fireEvent.focus(screen.getByRole('textbox')); await screen.findByRole('button',{name:/图片参数摘要：/});
  fireEvent.change(screen.getByLabelText('上传素材'),{target:{files:[new File(['image'],'原素材.png',{type:'image/png'})]}});
  await waitFor(() => expect(screen.getByRole('button',{name:'移除参考图 1'})).toBeEnabled());
  const replacement=screen.getByLabelText('替换参考图 1');
  fireEvent.click(replacement);
  fireEvent.blur(form,{relatedTarget:null}); fireEvent.pointerDown(document.body);
  expect(form).toHaveAttribute('data-expanded','true');
  fireEvent(replacement,new Event('cancel'));
  fireEvent.pointerDown(document.body);
  expect(form).toHaveAttribute('data-expanded','false');
  fireEvent.click(screen.getByRole('button',{name:'开始创作'}));
  expect(onSubmit.mock.calls.at(-1)?.[3]?.[0]).toMatchObject({fileId:'uploaded-image',name:'原素材.png'});
  expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).toHaveBeenCalledOnce();
});

it('reveals upload errors after losing focus without adding a failed file', async () => {
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({models:[{...image,maxReferenceImages:2}]}));
  vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mockRejectedValueOnce(new TypeError('offline'));
  const {container}=mount('image'); const form=container.querySelector('form')!;
  fireEvent.focus(screen.getByRole('textbox')); await screen.findByRole('button',{name:/图片参数摘要：/});
  fireEvent.blur(screen.getByRole('textbox'),{relatedTarget:null});
  fireEvent.change(screen.getByLabelText('上传素材'),{target:{files:[new File(['image'],'失败.png',{type:'image/png'})]}});
  expect(await screen.findByRole('alert')).toHaveTextContent('上传失败：网络连接失败');
  fireEvent.pointerLeave(form); fireEvent.pointerDown(document.body);
  expect(form).toHaveAttribute('data-expanded','true');
  expect(screen.queryByText('已添加 1 份素材')).not.toBeInTheDocument();
  expect(screen.queryByRole('button',{name:'移除参考图 1'})).not.toBeInTheDocument();
});

it('keeps a local thumbnail when preview signing fails and releases it on removal', async () => {
  let nextUrl=0;
  vi.stubGlobal('URL',class extends URL { static createObjectURL=vi.fn(() => `blob:local-${++nextUrl}`); static revokeObjectURL=vi.fn(); });
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({models:[referenceVideo,{...image,maxReferenceImages:2}]}));
  vi.mocked(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).mockRejectedValueOnce(new Error('signing unavailable'));
  mount('image'); fireEvent.focus(screen.getByRole('textbox')); await screen.findByRole('button',{name:/图片参数摘要：/});
  fireEvent.change(screen.getByLabelText('上传素材'),{target:{files:[new File(['image'],'本地预览.png',{type:'image/png'})]}});
  await screen.findByText('已添加 1 份素材');
  const preview=within(screen.getByRole('group',{name:'参考素材'})).getByRole('img',{name:'本地预览.png'});
  const url=preview.getAttribute('src'); expect(url).toMatch(/^blob:local-/);
  fireEvent.load(preview); expect(preview).toBeVisible();
  await waitFor(() => expect(screen.getByRole('button',{name:'移除参考图 1'})).toBeEnabled());
  fireEvent.click(screen.getByRole('button',{name:'图片生成模式'}));
  fireEvent.click(screen.getByRole('menuitemradio',{name:'文生图片'}));
  expect(URL.revokeObjectURL).not.toHaveBeenCalledWith(url);
  fireEvent.click(screen.getByRole('button',{name:'图片生成模式'}));
  fireEvent.click(screen.getByRole('menuitemradio',{name:'智能图片'}));
  expect(within(screen.getByRole('group',{name:'参考素材'})).getByRole('img',{name:'本地预览.png'})).toHaveAttribute('src',url);
  fireEvent.click(screen.getByRole('button',{name:'移除参考图 1'}));
  expect(URL.revokeObjectURL).toHaveBeenCalledWith(url);
});

it('keeps optional smart-image references but sends no references in text mode', async () => {
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({models:[referenceVideo,{...image,maxReferenceImages:2}]}));
  const {onSubmit}=mount('image'); fireEvent.focus(screen.getByRole('textbox'));
  await screen.findByRole('button',{name:/图片参数摘要：/});
  fireEvent.change(screen.getByLabelText('上传素材'),{target:{files:[new File(['image'],'可选.png',{type:'image/png'})]}});
  await waitFor(() => expect(screen.getByRole('button',{name:'移除参考图 1'})).toBeEnabled());
  await waitFor(() => expect(screen.getByRole('button',{name:'图片生成模式'})).toBeEnabled());
  await waitFor(() => expect(screen.getByRole('group',{name:'已添加素材（按提交顺序）'}).querySelector('[data-approved="true"]')).not.toBeNull());
  fireEvent.click(screen.getByRole('button',{name:'图片生成模式'}));
  fireEvent.click(screen.getByRole('menuitemradio',{name:'文生图片'}));
  fireEvent.click(screen.getByRole('button',{name:'开始创作'}));
  expect(onSubmit.mock.calls.at(-1)?.[3]).toEqual([]);
  fireEvent.click(screen.getByRole('button',{name:'图片生成模式'}));
  fireEvent.click(screen.getByRole('menuitemradio',{name:'智能图片'}));
  fireEvent.click(screen.getByRole('button',{name:'开始创作'}));
  expect(onSubmit.mock.calls.at(-1)?.[3]?.[0]?.fileId).toBe('uploaded-image');
});

it.each(['frames','omni'] as const)('uses the correct %s upload API and keeps frame positions on removal', async mode => {
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({models:[referenceVideo]}));
  const {onSubmit}=mount(); fireEvent.focus(screen.getByRole('textbox'));
  await screen.findByRole('button',{name:/视频参数摘要：/});
  fireEvent.click(screen.getByRole('button',{name:'视频生成模式'}));
  fireEvent.click(screen.getByRole('menuitemradio',{name:mode === 'frames' ? '首尾帧' : '全能参考'}));
  const file = new File(['material'],mode === 'frames' ? '首帧.png' : '参考.mp4',{type:mode === 'frames' ? 'image/png' : 'video/mp4'});
  fireEvent.change(screen.getByLabelText('上传素材'),{target:{files:[file]}});
  const label = mode === 'frames' ? '首帧' : '视频1';
  await waitFor(() => expect(screen.getByRole('button',{name:`移除${label}`})).toBeEnabled());
  await waitFor(() => expect(screen.getByRole('group',{name:'已添加素材（按提交顺序）'}).querySelector('[data-approved="true"]')).not.toBeNull());
  fireEvent.click(screen.getByRole('button',{name:'开始创作'}));
  expect(onSubmit.mock.calls.at(-1)?.[3]?.[0]).toMatchObject({fileId:mode === 'frames' ? 'uploaded-image' : 'uploaded-media',kind:mode === 'frames' ? 'IMAGE' : 'VIDEO',reviewStatus:'ACTIVE'});
  expect(mode === 'frames' ? api.storageUploadReferenceImagePostApiV1FilesReferenceImages : api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).toHaveBeenCalledWith({file});
  if(mode === 'frames') {
    vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mockResolvedValueOnce(reply({fileId:'tail-frame'}));
    fireEvent.change(screen.getByLabelText('上传素材'),{target:{files:[new File(['tail'],'尾帧.png',{type:'image/png'})]}});
    await waitFor(() => expect(screen.getByRole('button',{name:'移除尾帧'})).toBeEnabled());
    fireEvent.click(screen.getByRole('button',{name:'移除首帧'}));
    const count=onSubmit.mock.calls.length;
    fireEvent.click(screen.getByRole('button',{name:'开始创作'}));
    expect(onSubmit).toHaveBeenCalledTimes(count);
    expect(screen.getAllByRole('alert').some(item=>item.textContent?.includes('尾帧不能单独提交'))).toBe(true);
  }
  expect(api.mediaSubmitPostApiV1MediaTasks).not.toHaveBeenCalled();
});

it('rejects unsupported files before upload and preserves recovery after upload failure', async () => {
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({models:[referenceVideo,{...image,maxReferenceImages:2}]}));
  mount('image'); fireEvent.focus(screen.getByRole('textbox'));
  await screen.findByRole('button',{name:/图片参数摘要：/});
  fireEvent.change(screen.getByLabelText('上传素材'),{target:{files:[new File(['gif'],'错误.gif',{type:'image/gif'})]}});
  await screen.findByText('参考图片仅支持 20MB 以内的 PNG 或 JPEG');
  expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).not.toHaveBeenCalled();
  vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mockRejectedValueOnce(new Error('offline'));
  fireEvent.change(screen.getByLabelText('上传素材'),{target:{files:[new File(['png'],'重试.png',{type:'image/png'})]}});
  await waitFor(()=>expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).toHaveBeenCalledOnce());
  await waitFor(()=>expect(screen.getByRole('button',{name:'开始创作'})).toBeEnabled());
  fireEvent.change(screen.getByLabelText('上传素材'),{target:{files:[new File(['png'],'重试.png',{type:'image/png'})]}});
  await waitFor(()=>expect(screen.getByRole('button',{name:'移除参考图 1'})).toBeEnabled());
});

it.each(['IMAGE','VIDEO'] as const)('restores homepage %s materials, receipt and fresh preview over older workspace references', async type => {
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({models:[referenceVideo,{...image,maxReferenceImages:2}]}));
  saveComposerDraft('owner','create',type,{prompt:'旧描述',selection:null,mode:type === 'IMAGE' ? 'reference' : 'frames',materials:[{fileId:'older',name:'旧素材.png',preview:'',kind:'IMAGE',reviewStatus:'ACTIVE'}],bindings:[]});
  const material = {fileId:'homepage-reference',name:'首页参考.png',preview:'blob:expired',kind:'IMAGE',reviewStatus:'ACTIVE',reviewId:'homepage-receipt'};
  const Component=type === 'IMAGE' ? ProductionImageComposer : ProductionVideoComposer;
  render(<MemoryRouter initialEntries={[{pathname:`/create/${type.toLowerCase()}`,state:{source:'yomio-home',prompt:'素材交接',mode:type === 'IMAGE' ? 'reference' : 'frames',ownerId:'owner',materials:[material,null]}}]}><CreatorMediaProvider><Component/></CreatorMediaProvider></MemoryRouter>);
  await waitFor(()=>expect(screen.getByRole('button',{name:'添加素材'}).textContent).toContain('1'));
  await waitFor(()=>expect(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).toHaveBeenCalledWith('homepage-reference'));
  fireEvent.click(screen.getByRole('button',{name:'添加素材'}));
  expect(screen.getByRole('button',{name:type === 'IMAGE' ? '移除参考图 1' : '移除首帧'})).toBeEnabled();
  expect(screen.queryByText('旧素材.png')).not.toBeInTheDocument();
  expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).not.toHaveBeenCalled();
  expect(api.mediaPortraitSubmitPostApiV1MediaPortraits).not.toHaveBeenCalled();
  expect(api.mediaSubmitPostApiV1MediaTasks).not.toHaveBeenCalled();
});

it('validates handoff metadata and discards expired preview URLs', () => {
  const state={source:'yomio-home',prompt:'描述',ownerId:'owner',materials:[{fileId:'ref',name:'图.png',kind:'IMAGE',preview:'javascript:bad',reviewStatus:'ACTIVE'}]};
  expect(homeCreationEntry(state)?.materials?.[0]?.preview).toBe('');
  expect(homeCreationEntry({...state,materials:[{...state.materials[0],durationSeconds:NaN}]})?.materials).toBeUndefined();
  expect(homeCreationEntry({...state,ownerId:undefined})?.materials).toBeUndefined();
});

it('uses server per-kind, duration, size and audio-only limits for omni references', () => {
  expect(homeMaterialCapability('video','omni',referenceVideo as never)).toMatchObject({maxItems:6,maxItemsByKind:{image:3,video:2,audio:1},maxFileBytesByKind:{video:10000,audio:10000}});
  const videoFile={fileId:'video',name:'video.mp4',preview:'',kind:'VIDEO',durationSeconds:8,reviewStatus:'ACTIVE'} as const;
  expect(homeMaterialIssues('video','omni',[videoFile,{...videoFile,fileId:'second'}],referenceVideo as never,'catalog-video')).toContain('视频总时长不能超过 10 秒');
  expect(homeMaterialIssues('video','omni',[{...videoFile,sizeBytes:10001}],referenceVideo as never,'catalog-video')).toContain('素材超过当前模型大小限制，请更换素材或模型');
  expect(homeMaterialIssues('video','omni',[{...videoFile,kind:'AUDIO'}],referenceVideo as never,'catalog-video')).toContain('当前模型不支持仅音频参考，请添加图片或视频');
});

it('keeps the homepage expanded while interacting with the material library portal', async () => {
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({models:[referenceVideo,{...image,maxReferenceImages:2}]}));
  const {container}=mount('image'); fireEvent.focus(screen.getByRole('textbox'));
  await screen.findByRole('button',{name:/图片参数摘要：/});
  fireEvent.click(screen.getByRole('button',{name:'添加素材'}));
  fireEvent.click(screen.getByRole('menuitem',{name:'素材库'}));
  const dialog=await screen.findByRole('dialog',{name:'素材库'});
  fireEvent.pointerDown(dialog); fireEvent.pointerLeave(container.querySelector('form')!);
  expect(container.querySelector('form')).toHaveAttribute('data-expanded','true');
  fireEvent.click(screen.getByRole('button',{name:'关闭素材库'}));
  expect(screen.queryByRole('dialog',{name:'素材库'})).not.toBeInTheDocument();
  expect(container.querySelector('form')).toHaveAttribute('data-expanded','true');
});

it('discards a late upload when the homepage switches accounts', async () => {
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({models:[referenceVideo,{...image,maxReferenceImages:2}]}));
  let resolveUpload!: (value: Awaited<ReturnType<typeof api.storageUploadReferenceImagePostApiV1FilesReferenceImages>>) => void;
  vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mockReturnValueOnce(new Promise(resolve=>{resolveUpload=resolve;}));
  const view=mount('image'); fireEvent.focus(screen.getByRole('textbox'));
  await screen.findByRole('button',{name:/图片参数摘要：/});
  fireEvent.change(screen.getByLabelText('上传素材'),{target:{files:[new File(['image'],'旧账号.png',{type:'image/png'})]}});
  await waitFor(()=>expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).toHaveBeenCalledOnce());
  vi.mocked(session.useUserSession).mockReturnValue({authenticated:true,userId:'other-owner',status:'ready'} as never);
  view.rerender(<MemoryRouter><HomePromptComposer inputRef={createRef()} prompt="新账号" kind="image" modelName="Image 2" error="" choices={[{name:'Image 2',kind:'image'}]} onPrompt={()=>{}} onModel={()=>{}} onSubmit={view.onSubmit}/></MemoryRouter>);
  await act(async()=>resolveUpload(reply({fileId:'old-account-upload'})));
  await screen.findByRole('button',{name:/图片参数摘要：/});
  expect(screen.queryByRole('button',{name:'移除参考图 1'})).not.toBeInTheDocument();
  expect(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).not.toHaveBeenCalledWith('old-account-upload');
  fireEvent.click(screen.getByRole('button',{name:'开始创作'}));
  expect(view.onSubmit.mock.calls.at(-1)?.[3]).toEqual([]);
});

it.each(['IMAGE','VIDEO'] as const)('does not restore %s material identities from a different account', async type => {
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({models:[referenceVideo,{...image,maxReferenceImages:2}]}));
  const Component=type === 'IMAGE' ? ProductionImageComposer : ProductionVideoComposer;
  render(<MemoryRouter initialEntries={[{pathname:`/create/${type.toLowerCase()}`,state:{source:'yomio-home',prompt:'描述',ownerId:'different-owner',mode:type === 'IMAGE' ? 'reference' : 'frames',materials:[{fileId:'foreign-reference',name:'他人素材.png',kind:'IMAGE',reviewStatus:'ACTIVE'}]}}]}><CreatorMediaProvider><Component/></CreatorMediaProvider></MemoryRouter>);
  await screen.findByRole('button',{name:type === 'IMAGE' ? /图片参数摘要：/ : /视频参数摘要：/});
  expect(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).not.toHaveBeenCalledWith('foreign-reference');
  expect(screen.queryByText('他人素材.png')).not.toBeInTheDocument();
});

it('checks both local and server audio duration before accepting an omni reference', async () => {
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({models:[referenceVideo]}));
  mount(); fireEvent.focus(screen.getByRole('textbox'));
  await screen.findByRole('button',{name:/视频参数摘要：/});
  fireEvent.click(screen.getByRole('button',{name:'视频生成模式'}));
  fireEvent.click(screen.getByRole('menuitemradio',{name:'全能参考'}));
  const file=new File(['audio'],'参考.mp3',{type:'audio/mpeg'});
  vi.mocked(readReferenceAudioDuration).mockResolvedValueOnce(11);
  fireEvent.change(screen.getByLabelText('上传素材'),{target:{files:[file]}});
  await screen.findByText(/音频总时长不能超过 10 秒，剩余 10 秒/);
  expect(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).not.toHaveBeenCalled();
  vi.mocked(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).mockResolvedValueOnce(reply({fileId:'long-server-audio',durationSeconds:12}));
  fireEvent.change(screen.getByLabelText('上传素材'),{target:{files:[file]}});
  await waitFor(()=>expect(api.storageUploadReferenceMediaPostApiV1FilesReferenceMedia).toHaveBeenCalledOnce());
  await waitFor(()=>expect(screen.getByRole('button',{name:'开始创作'})).toBeEnabled());
  expect(screen.queryByRole('button',{name:'移除音频1'})).not.toBeInTheDocument();
  expect(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).not.toHaveBeenCalledWith('long-server-audio');
});
