import { webcrypto, createHash } from 'node:crypto';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { reviewedLibraryMedia } from './reviewedLibraryMedia';
import * as api from '../api';
vi.mock('../api', async original => ({ ...await original<typeof import('../api')>(), storageMatchImagePostApiV1FilesFileIdMatch: vi.fn(), mediaPortraitListGetApiV1MediaPortraits: vi.fn(), mediaPortraitGetGetApiV1MediaPortraitsPortraitId: vi.fn(), storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl: vi.fn() }));
const reply = (data: unknown) => ({ data }) as never;
const row = { id: 'approved-review', fileId: 'original-file', model: 'video', kind: 'IMAGE', status: 'ACTIVE' };
const media = { id: 'local-id', canvasFileIds: ['original-file'], name: '已审核.png', kind: 'image' as const, mimeType: 'image/png', size: 8 };
beforeEach(() => { vi.resetAllMocks(); vi.mocked(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).mockResolvedValue(reply({ url: 'https://assets.example/original.png' })); vi.mocked(api.mediaPortraitListGetApiV1MediaPortraits).mockResolvedValue(reply([row])); vi.mocked(api.mediaPortraitGetGetApiV1MediaPortraitsPortraitId).mockResolvedValue(reply(row)); });
it('reuses the original file and receipt without needing local binary content', async () => {
 expect(await reviewedLibraryMedia(media)).toMatchObject({ fileId: row.fileId, reviewId: row.id, reviewStatus: 'ACTIVE', reviewPending: false, preview: 'https://assets.example/original.png' });
});
it.each(['PROCESSING', 'FAILED'])('does not reuse a receipt that is now %s', async status => {
 vi.mocked(api.mediaPortraitGetGetApiV1MediaPortraitsPortraitId).mockResolvedValue(reply({ ...row, status }));
 await expect(reviewedLibraryMedia(media)).rejects.toThrow('审核');
});
it('does not turn a missing original into a new upload', async () => {
 vi.mocked(api.mediaPortraitListGetApiV1MediaPortraits).mockResolvedValue(reply([]));
 await expect(reviewedLibraryMedia(media)).rejects.toThrow('审核记录');
 await expect(reviewedLibraryMedia({ ...media, canvasFileIds: undefined })).rejects.toThrow('审核记录');
 expect(await reviewedLibraryMedia({ ...media, kind: 'video', canvasFileIds: undefined })).toBeNull();
});
it('finds an older approved file beyond the first page', async () => {
 const first = Array.from({ length: 20 }, (_, index) => ({ ...row, id: `review-${index}`, fileId: `other-${index}` }));
 vi.mocked(api.mediaPortraitListGetApiV1MediaPortraits).mockResolvedValueOnce(reply(first)).mockResolvedValueOnce(reply([row]));
 expect((await reviewedLibraryMedia(media))?.fileId).toBe(row.fileId);
 expect(api.mediaPortraitListGetApiV1MediaPortraits).toHaveBeenLastCalledWith({ limit: 20, beforeId: 'review-19' });
});

afterEach(() => vi.unstubAllGlobals());
it('recovers a legacy image by exact content after skipping an unavailable original', async () => {
 vi.stubGlobal('crypto', webcrypto);
 const content = new Blob(['reference'], { type: 'image/png' });
 vi.mocked(api.mediaPortraitListGetApiV1MediaPortraits).mockResolvedValue(reply([{ ...row, id: 'deleted-review', fileId: 'deleted-file' }, row]));
 vi.mocked(api.storageMatchImagePostApiV1FilesFileIdMatch).mockRejectedValueOnce(new api.ApiError(new Response(null, { status: 404 }), {})).mockResolvedValueOnce(reply({ matches: true }));
 expect(await reviewedLibraryMedia({ ...media, canvasFileIds: undefined, content })).toMatchObject({ fileId: row.fileId, reviewId: row.id, reviewStatus: 'ACTIVE' });
 expect(api.storageMatchImagePostApiV1FilesFileIdMatch).toHaveBeenLastCalledWith(row.fileId, { sizeBytes: content.size, sha256: createHash('sha256').update('reference').digest('hex') });
});
it('never treats a same-sized different image as approved or repeats duplicate file reads', async () => {
 vi.stubGlobal('crypto', webcrypto);
 vi.mocked(api.mediaPortraitListGetApiV1MediaPortraits).mockResolvedValue(reply([row, { ...row, id: 'duplicate-review' }]));
 vi.mocked(api.storageMatchImagePostApiV1FilesFileIdMatch).mockResolvedValue(reply({ matches: false }));
 await expect(reviewedLibraryMedia({ ...media, canvasFileIds: undefined, content: new Blob(['different']) })).rejects.toThrow('审核记录');
 expect(api.storageMatchImagePostApiV1FilesFileIdMatch).toHaveBeenCalledTimes(1);
 expect(api.mediaPortraitGetGetApiV1MediaPortraitsPortraitId).not.toHaveBeenCalled();
});
it('prefers a known file on a later page without matching image content', async () => {
 vi.mocked(api.mediaPortraitListGetApiV1MediaPortraits).mockResolvedValueOnce(reply(Array.from({ length: 20 }, (_, i) => ({ ...row, id: `other-${i}`, fileId: `file-${i}` })))).mockResolvedValueOnce(reply([row]));
 expect((await reviewedLibraryMedia({ ...media, content: new Blob(['reference']) }))?.fileId).toBe(row.fileId);
 expect(api.storageMatchImagePostApiV1FilesFileIdMatch).not.toHaveBeenCalled();
});

it('reports an existing but invalid original review without approving or reuploading it', async () => {
 vi.stubGlobal('crypto', webcrypto);
 const invalid = { ...row, status: 'FAILED', errorMessage: '审核配置已变更，请重新上传审核' };
 vi.mocked(api.mediaPortraitListGetApiV1MediaPortraits).mockResolvedValue(reply([invalid]));
 vi.mocked(api.storageMatchImagePostApiV1FilesFileIdMatch).mockResolvedValue(reply({ matches: true }));
 vi.mocked(api.mediaPortraitGetGetApiV1MediaPortraitsPortraitId).mockResolvedValue(reply(invalid));
 await expect(reviewedLibraryMedia({ ...media, canvasFileIds: undefined, content: new Blob(['reference']) })).rejects.toThrow('审核配置已变更');
});

it.each(['FAILED', 'PROCESSING'])('continues past a %s linked receipt to another fresh ACTIVE receipt', async status => {
 const recent = { ...row, id: 'recent-review', model: 'another-model', status };
 vi.mocked(api.mediaPortraitListGetApiV1MediaPortraits).mockResolvedValue(reply([recent, row]));
 vi.mocked(api.mediaPortraitGetGetApiV1MediaPortraitsPortraitId).mockResolvedValueOnce(reply(recent)).mockResolvedValueOnce(reply(row));
 expect(await reviewedLibraryMedia(media)).toMatchObject({ fileId: row.fileId, reviewId: row.id, reviewStatus: 'ACTIVE' });
 expect(api.mediaPortraitGetGetApiV1MediaPortraitsPortraitId).toHaveBeenCalledTimes(2);
 expect(api.storageMatchImagePostApiV1FilesFileIdMatch).not.toHaveBeenCalled();
});
