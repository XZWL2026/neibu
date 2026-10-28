import { useState } from 'react';
import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { CreatorMediaProvider } from './CreatorMediaProvider';
import { ComposerMaterials } from './ComposerMaterials';
import type { CreatorMedia, LocalMediaRepositoryOpenState } from './types';

afterEach(cleanup);

it('keeps successful uploads in the library across remounts, reuses them without duplicate entries, and closes on Escape', async () => {
  const records = new Map<string, CreatorMedia>();
  const storage: LocalMediaRepositoryOpenState = { storageMode: 'session', reason: 'forced', repository: {
    list: async () => [...records.values()], put: async media => { records.set(media.id, media); },
    remove: async id => { records.delete(id); }, close: () => {},
  } };
  const upload = vi.fn().mockResolvedValue({ fileId: 'original-file', name: '保留的素材.png', preview: '' });
  const mount = () => render(<CreatorMediaProvider storage={storage} objectUrls={{ create: () => 'blob:preview', revoke: () => {} }}>
    <ComposerMaterials slots={[]} nextIndex={0} disabled={false} busy={false} onUpload={upload} onRemove={() => {}}>提示词</ComposerMaterials>
  </CreatorMediaProvider>);
  const view = mount();
  fireEvent.click(screen.getByRole('button', { name: '添加素材' }));
  const picker = vi.spyOn(HTMLInputElement.prototype, 'click');
  fireEvent.click(screen.getByRole('menuitem', { name: '本地上传' }));
  expect(picker).toHaveBeenCalledOnce(); picker.mockRestore();
  const file = new File(['image'], '保留的素材.png', { type: 'image/png' });
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [file] } });
  await waitFor(() => expect(records.size).toBe(1));
  expect([...records.values()][0].content).toBe(file);
  expect([...records.values()][0]).toMatchObject({ id: 'original-file', canvasFileIds: ['original-file'] });
  view.unmount(); mount();
  fireEvent.click(screen.getByRole('button', { name: '添加素材' }));
  fireEvent.click(screen.getByRole('menuitem', { name: '素材库' }));
  const library = screen.getByRole('dialog', { name: '素材库' });
  expect(await within(library).findByRole('option', { name: file.name })).toBeVisible();
  fireEvent.keyDown(library, { key: 'Escape' });
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: '添加素材' })).toHaveFocus();
  expect(upload).toHaveBeenCalledOnce();
  fireEvent.click(screen.getByRole('button', { name: '添加素材' }));
  fireEvent.click(screen.getByRole('menuitem', { name: '素材库' }));
  fireEvent.click(await screen.findByRole('option', { name: file.name }));
  fireEvent.click(screen.getByRole('button', { name: '确定' }));
  await waitFor(() => expect(upload).toHaveBeenCalledTimes(2));
  expect(upload).toHaveBeenLastCalledWith(expect.objectContaining({ name: file.name, type: file.type, size: file.size }), 0);
  expect(records.size).toBe(1);
});

it('does not store rejected uploads or send missing library content to generation', async () => {
  const put = vi.fn();
  const upload = vi.fn().mockResolvedValue(false);
  render(<CreatorMediaProvider storage={{ storageMode: 'session', reason: 'forced', repository: {
    list: async () => [{ id: 'missing', kind: 'image', name: '文件缺失.png', mimeType: 'image/png', size: 5 }],
    put, remove: async () => {}, close: () => {},
  } }}><ComposerMaterials slots={[]} nextIndex={0} disabled={false} busy={false} onUpload={upload} onRemove={() => {}}>提示词</ComposerMaterials></CreatorMediaProvider>);
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['bad'], 'invalid.gif', { type: 'image/gif' })] } });
  await waitFor(() => expect(upload).toHaveBeenCalledOnce());
  expect(put).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '添加素材' }));
  fireEvent.click(screen.getByRole('menuitem', { name: '素材库' }));
  fireEvent.click(await screen.findByRole('option', { name: '文件缺失.png' }));
  fireEvent.click(screen.getByRole('button', { name: '确定' }));
  expect(screen.getByRole('alert')).toHaveTextContent('本地文件不可用');
  expect(upload).toHaveBeenCalledOnce();
});

it('collapses successful materials into the original trigger and expands to add, replace or remove', async () => {
  const remove = vi.fn(); const upload = vi.fn().mockResolvedValue(true);
  render(<ComposerMaterials slots={[
    { label: '参考图 1', file: { fileId: 'one', name: '人物.png', preview: 'https://assets.example/one.png' } },
    { label: '参考图 2', file: { fileId: 'two', name: '服装.jpg', preview: 'https://assets.example/two.jpg' } },
  ]} capability={{ acceptedKinds: ['image'], acceptedMimeTypes: ['image/png'], acceptedSources: ['upload', 'library'], maxFileBytes: 1000, maxItems: 3, verified: true }} nextIndex={2} disabled={false} busy={false} onUpload={upload} onRemove={remove}>提示词</ComposerMaterials>);
  const trigger = screen.getByRole('button', { name: '添加素材' });
  expect(trigger).toHaveAttribute('aria-expanded', 'false');
  expect(within(trigger).getAllByRole('img', { hidden: true })).toHaveLength(2);
  expect(screen.queryByRole('group', { name: '已添加素材（按提交顺序）' })).not.toBeInTheDocument();
  fireEvent.pointerEnter(trigger, { pointerType: 'mouse' });
  expect(screen.getByRole('group', { name: '已添加素材（按提交顺序）' })).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '添加素材' }));
  expect(screen.getByRole('menuitem', { name: '本地上传' })).toBeVisible();
  fireEvent.keyDown(screen.getByRole('menu'), { key: 'Escape' });
  expect(trigger).toHaveAttribute('aria-expanded', 'false');
  fireEvent.click(trigger);
  fireEvent.click(screen.getByRole('button', { name: '移除参考图 2' }));
  expect(remove).toHaveBeenCalledWith(1);
  expect(screen.getByLabelText('替换参考图 1')).not.toHaveAttribute('multiple');
  fireEvent.change(screen.getByLabelText('替换参考图 1'), { target: { files: [new File(['png'], '替换.png', { type: 'image/png' })] } });
  await waitFor(() => expect(upload).toHaveBeenCalledWith(expect.objectContaining({ name: '替换.png' }), 0));
});

it('keeps a full stack inspectable and removable while preventing more uploads', () => {
  render(<ComposerMaterials slots={[{ label: '首帧', file: { fileId: 'one', name: '首帧.png', preview: '' } }]}
    nextIndex={-1} disabled={false} busy={false} onUpload={vi.fn()} onRemove={vi.fn()}>提示词</ComposerMaterials>);
  const trigger = screen.getByRole('button', { name: '添加素材' });
  expect(trigger).toBeEnabled();
  fireEvent.focus(trigger);
  fireEvent.click(trigger);
  expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  expect(screen.getByLabelText('上传素材')).toBeDisabled();
  expect(screen.getByRole('button', { name: '移除首帧' })).toBeEnabled();
  fireEvent.keyDown(trigger, { key: 'Escape' });
  expect(trigger).toHaveAttribute('aria-expanded', 'false');
});

it('allows uploading again after the last material is removed', () => {
  const props = { nextIndex: 0, disabled: false, busy: false, onUpload: vi.fn(), onRemove: vi.fn(), children: '提示词' };
  const view = render(<ComposerMaterials {...props} slots={[{ label: '参考图 1', file: { fileId: 'one', name: '人物.png', preview: '' } }]} />);
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  fireEvent.click(screen.getByRole('button', { name: '移除参考图 1' }));
  view.rerender(<ComposerMaterials {...props} slots={[]} />);
  fireEvent.click(screen.getByRole('button', { name: '添加素材' }));
  expect(screen.getByRole('menuitem', { name: '本地上传' })).toBeVisible();
});

it.each([['video', 'video/quicktime', 'movie.mov'], ['audio', 'audio/mpeg', 'sound.mp3']] as const)('preserves %s kind, filters the library and reuploads selected local content', async (kind, mimeType, name) => {
  const records: CreatorMedia[] = [];
  const upload = vi.fn().mockResolvedValue(true);
  render(<CreatorMediaProvider storage={{ storageMode: 'session', reason: 'forced', repository: {
    list: async () => records, put: async item => { records.push(item); }, remove: async () => {}, close: () => {},
  } }} objectUrls={{ create: () => 'blob:media', revoke: () => {} }}><ComposerMaterials
    capability={{ acceptedKinds: ['image', 'video', 'audio'], acceptedMimeTypes: ['image/png', 'video/quicktime', 'audio/mpeg'], acceptedSources: ['upload', 'library'], maxFileBytes: 200 * 1024 * 1024, maxItems: 1, verified: true }}
    slots={[]} nextIndex={0} disabled={false} busy={false} onUpload={upload} onRemove={() => {}}>提示词</ComposerMaterials></CreatorMediaProvider>);
  fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['media'], name, { type: mimeType })] } });
  await waitFor(() => expect(records).toHaveLength(1));
  expect(records[0]).toMatchObject({ kind, mimeType, name });
  fireEvent.click(screen.getByRole('button', { name: '添加素材' }));
  fireEvent.click(screen.getByRole('menuitem', { name: '素材库' }));
  const library = await screen.findByRole('dialog', { name: '素材库' });
  fireEvent.click(within(library).getByRole('tab', { name: kind === 'video' ? '视频' : '音频' }));
  const option = await within(library).findByRole('option', { name });
  if (kind === 'video') expect(within(option).getByLabelText(`${name} 预览`)).not.toHaveAttribute('controls');
  else expect(option).toHaveTextContent(name);
  fireEvent.click(option); fireEvent.click(within(library).getByRole('button', { name: '确定' }));
  await waitFor(() => expect(upload).toHaveBeenCalledTimes(2));
  expect(upload).toHaveBeenLastCalledWith(expect.objectContaining({ type: mimeType, name }), 0);
});
it('renders native video and audio previews without treating them as image URLs', () => {
  render(<ComposerMaterials slots={[
    { label: '视频1', file: { fileId: 'motion', name: 'motion.mp4', kind: 'VIDEO', preview: 'https://assets.example/a.mp4' } },
    { label: '音频1', file: { fileId: 'sound', name: 'sound.wav', kind: 'AUDIO', preview: 'https://assets.example/a.wav' } },
  ]} nextIndex={2} disabled={false} busy={false} onUpload={vi.fn()} onRemove={vi.fn()}>提示词</ComposerMaterials>);
  fireEvent.click(screen.getByRole('button', { name: '添加素材' }));
  expect(screen.getByLabelText('motion.mp4').tagName).toBe('VIDEO');
  expect(screen.getByLabelText('sound.wav').tagName).toBe('AUDIO');
  expect(screen.queryByRole('img')).not.toBeInTheDocument();
});

it('selects up to the remaining capacity and uploads in selection order into empty slots without overwriting existing media', async () => {
  const records: CreatorMedia[] = ['first', 'second', 'third'].map(id => ({ id, name: `${id}.png`, kind: 'image', mimeType: 'image/png', size: 3, content: new Blob(['png']) }));
  let finishFirst!: (value: boolean) => void;
  const upload = vi.fn().mockImplementationOnce(() => new Promise<boolean>(resolve => { finishFirst = resolve; })).mockResolvedValue(true);
  render(<CreatorMediaProvider storage={{ storageMode: 'session', reason: 'forced', repository: {
    list: async () => records, put: async () => {}, remove: async () => {}, close: () => {},
  } }}><ComposerMaterials capability={{ acceptedKinds: ['image'], acceptedMimeTypes: ['image/png'], acceptedSources: ['library'], maxFileBytes: 100, maxItems: 3, verified: true }}
    slots={[{ label: '图片1', file: null }, { label: '图片2', file: { fileId: 'existing', name: 'existing.png', preview: '' } }]}
    nextIndex={0} disabled={false} busy={false} onUpload={upload} onRemove={() => {}}>保留提示词</ComposerMaterials></CreatorMediaProvider>);
  fireEvent.click(screen.getByRole('button', { name: '添加素材' }));
  fireEvent.click(screen.getByRole('menuitem', { name: '素材库' }));
  expect(screen.getByRole('listbox')).toHaveAttribute('aria-multiselectable', 'true');
  fireEvent.click(await screen.findByRole('option', { name: 'second.png' }));
  fireEvent.click(screen.getByRole('option', { name: 'first.png' }));
  fireEvent.click(screen.getByRole('option', { name: 'third.png' }));
  expect(screen.getByText('已选：2')).toBeVisible();
  expect(screen.getByText('最多选择 2 项素材')).toHaveAttribute('role', 'status');
  fireEvent.click(screen.getByRole('button', { name: '确定' }));
  await waitFor(() => expect(upload).toHaveBeenCalledOnce());
  expect(upload).toHaveBeenNthCalledWith(1, expect.objectContaining({ name: 'second.png' }), 0);
  finishFirst(true);
  await waitFor(() => expect(upload).toHaveBeenCalledTimes(2));
  expect(upload).toHaveBeenNthCalledWith(2, expect.objectContaining({ name: 'first.png' }), 2);
});

it('stops a library batch on failure, preserving successful uploads and not sending the remainder', async () => {
  const records: CreatorMedia[] = ['one', 'two', 'three'].map(id => ({ id, name: `${id}.png`, kind: 'image', mimeType: 'image/png', size: 3, content: new Blob(['png']) }));
  const upload = vi.fn().mockResolvedValueOnce(true).mockResolvedValueOnce(false);
  render(<CreatorMediaProvider publicMedia={records}><ComposerMaterials
    capability={{ acceptedKinds: ['image'], acceptedMimeTypes: ['image/png'], acceptedSources: ['library'], maxFileBytes: 100, maxItems: 3, verified: true }}
    slots={[]} nextIndex={0} disabled={false} busy={false} onUpload={upload} onRemove={() => {}}>提示词</ComposerMaterials></CreatorMediaProvider>);
  fireEvent.click(screen.getByRole('button', { name: '添加素材' })); fireEvent.click(screen.getByRole('menuitem', { name: '素材库' }));
  fireEvent.click(screen.getByRole('tab', { name: '公开' }));
  for (const item of records) fireEvent.click(screen.getByRole('option', { name: item.name }));
  fireEvent.click(screen.getByRole('button', { name: '确定' }));
  await waitFor(() => expect(upload).toHaveBeenCalledTimes(2));
  expect(await screen.findByRole('alert')).toHaveTextContent('已添加的素材保留');
});

it.each(['model', 'capacity'] as const)('stops remaining uploads if the %s changes during the first upload', async change => {
  const media: CreatorMedia[] = ['one', 'two'].map(id => ({ id, name: `${id}.png`, kind: 'image', mimeType: 'image/png', size: 3, content: new Blob(['png']) }));
  const onSelecting = vi.fn();
  let finish!: (result: boolean) => void;
  const upload = vi.fn().mockImplementation(() => new Promise<boolean>(resolve => { finish = resolve; }));
  const capability = { acceptedKinds: ['image'] as const, acceptedMimeTypes: ['image/png'], acceptedSources: ['library'] as const, maxFileBytes: 100, maxItems: 2, verified: true };
  const mount = (changed = false) => <CreatorMediaProvider publicMedia={media}><ComposerMaterials
    review={{owner: 'user-1', model: 'video', onSelecting, onReview: vi.fn()}}
    selectionKey={changed && change === 'model' ? 'next-model:frames' : 'original-model:omni'} capability={{ ...capability, maxItems: changed && change === 'capacity' ? 1 : 2 }}
    slots={[]} nextIndex={0} disabled={false} busy={false} onUpload={upload} onRemove={() => {}}>提示词</ComposerMaterials></CreatorMediaProvider>;
  const view = render(mount());
  fireEvent.click(screen.getByRole('button', { name: '添加素材' })); fireEvent.click(screen.getByRole('menuitem', { name: '素材库' }));
  fireEvent.click(screen.getByRole('tab', { name: '公开' }));
  for (const item of media) fireEvent.click(screen.getByRole('option', { name: item.name }));
  fireEvent.click(screen.getByRole('button', { name: '确定' }));
  expect(upload).toHaveBeenCalledOnce();
  expect(onSelecting).toHaveBeenLastCalledWith(true);
  view.rerender(mount(true)); finish(true);
  await waitFor(() => expect(onSelecting).toHaveBeenLastCalledWith(false));
  expect(await screen.findByRole('alert')).toHaveTextContent('模型或素材能力已变化');
  expect(upload).toHaveBeenCalledOnce();
});

it('removes empty cards without shifting the underlying first/last slot actions', () => {
  const remove = vi.fn();
  const props = { nextIndex: 0, disabled: false, busy: false, onUpload: vi.fn(), onRemove: remove, children: '提示词' };
  const view = render(<ComposerMaterials {...props} slots={[
    { label: '首帧', file: { fileId: 'one', name: '首帧.png', preview: '' } },
    { label: '尾帧', file: { fileId: 'two', name: '尾帧.png', preview: '' } },
  ]} />);
  fireEvent.click(screen.getByRole('button', { name: '添加素材' }));
  fireEvent.click(screen.getByRole('button', { name: '移除首帧' }));
  expect(remove).toHaveBeenCalledWith(0);
  view.rerender(<ComposerMaterials {...props} slots={[
    { label: '图片0', file: null },
    { label: '尾帧', file: { fileId: 'two', name: '尾帧.png', preview: '' } },
  ]} />);
  const cards = screen.getByRole('group', { name: '已添加素材（按提交顺序）' });
  expect(cards.querySelectorAll('.composer-material')).toHaveLength(1);
  expect(within(cards).queryByText('图片0')).not.toBeInTheDocument();
  expect(within(cards).queryByLabelText('上传图片0')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '移除尾帧' }));
  expect(remove).toHaveBeenLastCalledWith(1);
  expect(screen.getByRole('button', { name: '添加素材' })).toBeEnabled();
});

it('expands beside the original material entry on hover, collapses on leave or blur, and uploads from that entry', async () => {
  render(<ComposerMaterials slots={[{ label: '图片1', file: { fileId: 'one', name: '人物.png', preview: 'https://assets.example/one.png' } }]}
    capability={{ acceptedKinds: ['image'], acceptedMimeTypes: ['image/png'], acceptedSources: ['upload', 'library'], maxFileBytes: 1000, maxItems: 2, verified: true }} nextIndex={1} disabled={false} busy={false} onUpload={vi.fn()} onRemove={vi.fn()}><button>提示词</button></ComposerMaterials>);
  const trigger = screen.getByRole('button', { name: '添加素材' });
  fireEvent.pointerEnter(trigger, { pointerType: 'mouse' });
  expect(screen.getByRole('group', { name: '已添加素材（按提交顺序）' })).toBeVisible();
  expect(within(trigger).getByText('素材')).toBeVisible();
  expect(screen.queryByRole('button', { name: '继续添加素材' })).not.toBeInTheDocument();
  fireEvent.pointerLeave(trigger.parentElement!, { pointerType: 'mouse' });
  await waitFor(() => expect(trigger).toHaveAttribute('aria-expanded', 'false'));
  fireEvent.focus(trigger);
  expect(screen.getByRole('group', { name: '已添加素材（按提交顺序）' })).toBeVisible();
  fireEvent.blur(trigger, { relatedTarget: screen.getByRole('button', { name: '提示词' }) });
  expect(trigger).toHaveAttribute('aria-expanded', 'false');
  fireEvent.click(trigger);
  const picker = vi.spyOn(HTMLInputElement.prototype, 'click');
  fireEvent.click(screen.getByRole('menuitem', { name: '本地上传' }));
  expect(picker).toHaveBeenCalledOnce(); picker.mockRestore();
});

it('attaches a pending upload to its own thumbnail, keeps the material label, and releases the preview on failure', async()=>{
 const create=vi.fn(()=> 'blob:pending-image'),revoke=vi.fn();
 const oldCreate=URL.createObjectURL,oldRevoke=URL.revokeObjectURL;URL.createObjectURL=create;URL.revokeObjectURL=revoke;
 let finish!:(value:boolean)=>void;
 try{
  render(<ComposerMaterials slots={[]} nextIndex={0} disabled={false} busy={false} onUpload={()=>new Promise(resolve=>{finish=resolve;})} onRemove={()=>{}}>提示词</ComposerMaterials>);
  fireEvent.change(screen.getByLabelText('上传素材'),{target:{files:[new File(['image'],'正在上传.png',{type:'image/png'})]}});
  const thumb=await screen.findByAltText('正在上传.png');
  expect(thumb).toHaveAttribute('src','blob:pending-image');
  expect(thumb.closest('.composer-material')).toHaveAttribute('aria-busy','true');
  expect(within(thumb.closest('.composer-material') as HTMLElement).getByRole('status')).toHaveAttribute('aria-label','正在上传.png 上传中');
  expect(screen.getByRole('button',{name:'添加素材'})).toHaveTextContent('素材');
  expect(screen.getByRole('button',{name:'添加素材'})).not.toHaveTextContent('上传中');
  finish(false);await waitFor(()=>expect(screen.queryByAltText('正在上传.png')).not.toBeInTheDocument());
  expect(revoke).toHaveBeenCalledWith('blob:pending-image');
 }finally{URL.createObjectURL=oldCreate;URL.revokeObjectURL=oldRevoke;}
});

it('shows a replacement in only its target slot and discards local previews after a context change', async()=>{
 const originalCreate=URL.createObjectURL,originalRevoke=URL.revokeObjectURL;
 URL.createObjectURL=vi.fn(()=> 'blob:replacement');const revoke=vi.fn();URL.revokeObjectURL=revoke;
 let finish!:(value:boolean)=>void;const put=vi.fn();
 const slots=[{label:'图片1',file:{fileId:'one',name:'保留.png',preview:'https://assets.example/one.png'}},{label:'图片2',file:{fileId:'two',name:'原图.png',preview:'https://assets.example/two.png'}}];
 const build=(selectionKey:string)=><CreatorMediaProvider storage={{storageMode:'session',reason:'forced',repository:{list:async()=>[],put,remove:async()=>{},close:()=>{}}}}><ComposerMaterials selectionKey={selectionKey} slots={slots} nextIndex={2} disabled={false} busy={false} onUpload={()=>new Promise(resolve=>{finish=resolve;})} onRemove={()=>{}}>提示词</ComposerMaterials></CreatorMediaProvider>;
 try{const view=render(build('owner-a'));
  fireEvent.click(screen.getByRole('button',{name:'添加素材'}));fireEvent.change(screen.getByLabelText('替换图片2'),{target:{files:[new File(['new'],'替换.png',{type:'image/png'})]}});
  expect(screen.getByAltText('保留.png').closest('.composer-material')).toHaveAttribute('aria-busy','false');
  expect(screen.getByAltText('替换.png').closest('.composer-material')).toHaveAttribute('aria-busy','true');
  view.rerender(build('owner-b'));await waitFor(()=>expect(revoke).toHaveBeenCalledWith('blob:replacement'));
  expect(screen.queryByAltText('替换.png')).not.toBeInTheDocument();finish(true);await waitFor(()=>expect(screen.getByLabelText('替换图片2')).toBeEnabled());expect(put).not.toHaveBeenCalled();view.unmount();
 }finally{URL.createObjectURL=originalCreate;URL.revokeObjectURL=originalRevoke;}
});


it.each([true, false])('accepts another upload while pending, counts reservations and releases failures (first succeeds=%s)', async firstSucceeds => {
  const finishes: ((accepted: boolean) => void)[] = [];
  const calls: string[] = [];
  function Harness() {
    const [files, setFiles] = useState<{ fileId: string; name: string; preview: string }[]>([]);
    const [uploading, setUploading] = useState(false);
    return <ComposerMaterials slots={files.map((file, index) => ({ label: `图片${index + 1}`, file }))}
      capability={{ acceptedKinds: ['image'], acceptedMimeTypes: ['image/png'], acceptedSources: ['upload', 'library'], maxFileBytes: 1000, maxItems: 2, verified: true }}
      nextIndex={files.length < 2 ? files.length : -1} disabled={false} busy={uploading}
      onUpload={async (file, index) => {
        calls.push(file.name); setUploading(true);
        const accepted = await new Promise<boolean>(resolve => finishes.push(resolve));
        const material = { fileId: file.name, name: file.name, preview: `https://assets.example/${file.name}` };
        if (accepted) setFiles(current => { const next = [...current]; next[index] = material; return next; });
        setUploading(false); return accepted ? material : false;
      }} onRemove={() => {}}>提示词</ComposerMaterials>;
  }
  render(<Harness />);
  const add = (name: string) => fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['png'], name, { type: 'image/png' })] } });
  add('one.png'); await waitFor(() => expect(calls).toEqual(['one.png']));
  fireEvent.click(screen.getByRole('button', { name: '添加素材' }));
  expect(screen.getByRole('menuitem', { name: '本地上传' })).toBeEnabled();
  expect(screen.getByLabelText('上传素材')).toBeEnabled();
  add('two.png');
  expect(screen.getByText('two.png', { selector: 'span[title]' })).toBeVisible();
  expect(calls).toEqual(['one.png']);
  vi.useFakeTimers();
  try {
    fireEvent.click(screen.getByRole('button', { name: '添加素材' }));
    expect(screen.getByRole('alert')).toHaveTextContent('最多上传 2 张图片');
    act(() => { vi.advanceTimersByTime(4999); });
    expect(screen.getByRole('alert')).toBeVisible();
    act(() => { vi.advanceTimersByTime(1); });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  } finally { vi.useRealTimers(); }
  await act(async () => { finishes[0](firstSucceeds); });
  await waitFor(() => expect(calls).toEqual(['one.png', 'two.png']));
  await act(async () => { finishes[1](true); });
  await waitFor(() => expect(screen.getByLabelText('替换图片1')).toBeEnabled());
  expect(screen.getAllByRole('link', { name: /^预览/ })).toHaveLength(firstSucceeds ? 2 : 1);
  if (!firstSucceeds) {
    fireEvent.click(screen.getByRole('button', { name: '添加素材' }));
    expect(screen.getByRole('menuitem', { name: '本地上传' })).toBeEnabled();
  }
});


it('counts queued media by kind and discards waiting uploads when the model changes', async () => {
  let finish!: (accepted: boolean) => void;
  const upload = vi.fn(() => new Promise<boolean>(resolve => { finish = resolve; }));
  const build = (selectionKey: string) => <ComposerMaterials selectionKey={selectionKey} slots={[]} nextIndex={0} disabled={false} busy={false}
    capability={{ acceptedKinds: ['image', 'video'], acceptedMimeTypes: ['image/png', 'video/mp4'], acceptedSources: ['upload'], maxFileBytes: 1000, maxItems: 3, maxItemsByKind: { image: 1, video: 2 }, verified: true }}
    onUpload={upload} onRemove={() => {}}>提示词</ComposerMaterials>;
  const view = render(build('first-model'));
  const select = (name: string, type: string) => fireEvent.change(screen.getByLabelText('上传素材'), { target: { files: [new File(['media'], name, { type })] } });
  select('one.png', 'image/png');
  await waitFor(() => expect(upload).toHaveBeenCalledTimes(1));
  select('extra.png', 'image/png');
  expect(screen.getByRole('alert')).toHaveTextContent('最多上传 1 张图片');
  expect(screen.queryByText('extra.png', { selector: 'span[title]' })).not.toBeInTheDocument();
  select('queued.mp4', 'video/mp4');
  expect(screen.getByText('queued.mp4', { selector: 'span[title]' })).toBeVisible();
  view.rerender(build('second-model'));
  expect(screen.queryByText('queued.mp4', { selector: 'span[title]' })).not.toBeInTheDocument();
  await act(async () => { finish(false); });
  expect(upload).toHaveBeenCalledTimes(1);
});

it.each([true, false])('uploads a local multi-selection in order within total and kind limits (first succeeds=%s)', async firstSucceeds => {
  const finishes: ((accepted: boolean) => void)[] = [];
  const calls: [string, number][] = [];
  function Harness() {
    const [files, setFiles] = useState([{ fileId: 'existing', name: 'existing.png', preview: '', kind: 'IMAGE' as 'IMAGE' | 'VIDEO' }]);
    return <ComposerMaterials slots={files.map((file, index) => ({ label: `素材${index + 1}`, file }))}
      capability={{ acceptedKinds: ['image', 'video'], acceptedMimeTypes: ['image/png', 'video/mp4'], acceptedSources: ['upload'], maxFileBytes: 1000, maxItems: 4, maxItemsByKind: { image: 2, video: 2 }, verified: true }}
      nextIndex={files.length < 4 ? files.length : -1} disabled={false} busy={false}
      onUpload={async (file, index) => {
        calls.push([file.name, index]);
        const accepted = await new Promise<boolean>(resolve => finishes.push(resolve));
        const material = { fileId: file.name, name: file.name, preview: '', kind: file.type === 'video/mp4' ? 'VIDEO' as const : 'IMAGE' as const };
        if (accepted) setFiles(current => { const next = [...current]; next[index] = material; return next; });
        return accepted ? material : false;
      }} onRemove={() => {}}>提示词</ComposerMaterials>;
  }
  render(<Harness />);
  const input = screen.getByLabelText('上传素材');
  fireEvent.change(input, { target: { files: ['one.png', 'extra.png', 'two.mp4', 'three.mp4', 'extra.mp4'].map(name =>
    new File(['media'], name, { type: name.endsWith('.png') ? 'image/png' : 'video/mp4' })) } });
  await waitFor(() => expect(calls).toEqual([['one.png', 1]]));
  expect(screen.getByText('two.mp4', { selector: 'span[title]' })).toBeVisible();
  expect(screen.getByText('three.mp4', { selector: 'span[title]' })).toBeVisible();
  expect(screen.queryByText('extra.png', { selector: 'span[title]' })).not.toBeInTheDocument();
  expect(screen.queryByText('extra.mp4', { selector: 'span[title]' })).not.toBeInTheDocument();
  await act(async () => { finishes[0](firstSucceeds); });
  await waitFor(() => expect(calls).toHaveLength(2));
  expect(calls[1]).toEqual(['two.mp4', firstSucceeds ? 2 : 1]);
  await act(async () => { finishes[1](true); });
  await waitFor(() => expect(calls).toHaveLength(3));
  expect(calls[2]).toEqual(['three.mp4', firstSucceeds ? 3 : 2]);
  await act(async () => { finishes[2](true); });
  await waitFor(() => expect(screen.getByLabelText('替换素材1')).toBeEnabled());
  expect(screen.getByText('existing.png', { selector: 'span[title]' })).toBeVisible();
  expect(screen.getAllByRole('button', { name: /^移除素材/ })).toHaveLength(firstSucceeds ? 4 : 3);
  expect(input).toHaveAttribute('multiple');
  expect(screen.getByLabelText('替换素材1')).not.toHaveAttribute('multiple');
});

it('keeps a single-material capability single-select and admits only one local file', async () => {
  const upload = vi.fn().mockResolvedValue(false);
  render(<ComposerMaterials slots={[]} nextIndex={0} disabled={false} busy={false} onUpload={upload} onRemove={() => {}}>提示词</ComposerMaterials>);
  const input = screen.getByLabelText('上传素材');
  expect(input).not.toHaveAttribute('multiple');
  fireEvent.change(input, { target: { files: ['one.png', 'two.png'].map(name => new File(['png'], name, { type: 'image/png' })) } });
  await waitFor(() => expect(upload).toHaveBeenCalledOnce());
  expect(upload).toHaveBeenCalledWith(expect.objectContaining({ name: 'one.png' }), 0);
  await waitFor(() => expect(screen.queryByText('one.png', { selector: 'span[title]' })).not.toBeInTheDocument());
  expect(upload).toHaveBeenCalledOnce();
});
