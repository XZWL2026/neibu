import '@testing-library/jest-dom/vitest';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
import { ProductReferences, type ProductReference } from './ProductReferences';
import { storageUploadReferenceImagePostApiV1FilesReferenceImages as upload } from '../api';
vi.mock('../api', () => ({ storageUploadReferenceImagePostApiV1FilesReferenceImages: vi.fn() }));
vi.mock('../canvas/production-api', () => ({ mediaReference: (fileId: string) => ({ fileId }), resolvePreview: async (id: string) => `https://example.test/${id}` }));
afterEach(() => { cleanup(); vi.resetAllMocks(); });
function Fixture() { const [files, setFiles] = useState<ProductReference[]>([]); return <ProductReferences files={files} onChange={setFiles} limit={3} onBusy={() => {}} />; }
it('uploads multiple images in order without overwriting earlier results', async () => {
  vi.mocked(upload).mockResolvedValueOnce({ data: { fileId: 'one' } } as never).mockResolvedValueOnce({ data: { fileId: 'two' } } as never);
  render(<Fixture />);
  fireEvent.change(screen.getByLabelText('上传商品图片'), { target: { files: [new File(['1'], 'one.png', { type: 'image/png' }), new File(['2'], 'two.png', { type: 'image/png' })] } });
  await screen.findByText('two.png'); expect(screen.getByText('one.png')).toBeVisible(); expect(upload).toHaveBeenCalledTimes(2);
});
it('rejects over-limit batches before uploading and accepts a dropped image', async () => {
  vi.mocked(upload).mockResolvedValue({ data: { fileId: 'dropped' } } as never); render(<Fixture />);
  const files=Array.from({length:4},(_,i)=>new File(['x'],`${i}.png`,{type:'image/png'}));
  fireEvent.change(screen.getByLabelText('上传商品图片'),{target:{files}});
  await screen.findByRole('alert'); expect(upload).not.toHaveBeenCalled();
  fireEvent.drop(screen.getByLabelText('上传商品图片').parentElement!,{dataTransfer:{files:[files[0]]}});
  await waitFor(()=>expect(upload).toHaveBeenCalledTimes(1)); await screen.findByText('0.png');
});
