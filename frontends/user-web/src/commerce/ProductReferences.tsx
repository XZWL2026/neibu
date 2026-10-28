import { t, formatMessage, useLanguage } from '../i18n';
import { useEffect, useRef, useState } from 'react';
import { storageUploadReferenceImagePostApiV1FilesReferenceImages } from '../api';
import { mediaReference, resolvePreview } from '../canvas/production-api';
import { uploadErrorMessage } from '../creator/upload-error';

export type ProductReference = { fileId: string; name: string; preview: string };
export function ProductReferences({ files, onChange, limit, disabled = false, onBusy, label = '商品图片', showGuidance = false }: {
  files: ProductReference[]; onChange(files: ProductReference[]): void; limit: number; disabled?: boolean; onBusy(busy: boolean): void; label?: string; showGuidance?: boolean;
}) {
  useLanguage();
  const alive = useRef(true), sending = useRef(false);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  async function upload(batch: File[]) {
    if (!batch.length || disabled || sending.current) return;
    if (files.length + batch.length > limit) { setError(`最多上传 ${limit} 张图片`); return; }
    if (batch.some(file => !['image/png', 'image/jpeg'].includes(file.type) || file.size === 0 || file.size > 20 * 1024 * 1024)) {
      setError('商品图片仅支持 20MB 以内的 PNG 或 JPEG'); return;
    }
    sending.current = true; setBusy(true); onBusy(true); setError('');
    const next = [...files];
    try {
      for (const file of batch) {
        const { data } = await storageUploadReferenceImagePostApiV1FilesReferenceImages({ file });
        if (!data.fileId || !mediaReference(data.fileId).fileId) throw new Error('上传未返回有效文件 ID');
        if (!alive.current) return;
        let preview = '';
        try { preview = await resolvePreview(data.fileId); } catch { /* The uploaded file remains usable without a preview URL. */ }
        if (!alive.current) return;
        next.push({ fileId: data.fileId, name: file.name, preview });
        onChange([...next]);
      }
    } catch (cause) { if (alive.current) setError(uploadErrorMessage(cause)); }
    finally { if (alive.current) { sending.current = false; setBusy(false); onBusy(false); } }
  }
  return <div className="suite-upload">
    <div className="suite-upload-heading"><span className="suite-field-title">{t(label === '商品图片' ? '商品原图' : label)}</span>{showGuidance && limit > 0 && <small>{files.length} / {limit}</small>}</div>
    {busy && <span role="status">{t("图片上传中…")}</span>}
    <div className="suite-upload-list" data-empty={!files.length}>{files.map((file, index) => <figure key={file.fileId}>
      {file.preview && <img src={file.preview} alt={formatMessage("{0} {1}", t(label), index + 1)} />}
      <figcaption>{file.name}</figcaption><button type="button" disabled={disabled || busy} aria-label={formatMessage("移除{0} {1}", t(label), index + 1)} onClick={() => onChange(files.filter((_, i) => i !== index))}>{t("移除")}</button>
    </figure>)}<label onDragOver={event => event.preventDefault()} onDrop={event => { event.preventDefault(); void upload(Array.from(event.dataTransfer.files)); }} className="suite-upload-tile" data-disabled={disabled || busy || files.length >= limit}>
      {showGuidance ? <svg className="suite-upload-icon" width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true"><path d="M12 15V3m-4 4 4-4 4 4M4 14v5a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-5" /></svg> : <span aria-hidden="true">＋</span>}<span>{t("上传图片")}</span>{showGuidance && !files.length && <small>{t("点击或拖拽商品图片")}</small>}<input type="file" multiple={limit > 1} aria-label={formatMessage("上传{0}", t(label))} accept="image/png,image/jpeg" disabled={disabled || busy || files.length >= limit}
        onChange={event => { const batch = Array.from(event.target.files || []); event.target.value = ''; void upload(batch); }} />
    </label></div>
    {showGuidance && <small className="suite-upload-guidance">{t("PNG / JPEG，单张不超过 20MB")}</small>}
    {files.length > limit && <p role="alert">{formatMessage("当前模型最多使用 {0} 张商品图片，请移除多余图片。", limit)}</p>}
    {error && <p role="alert">{t(error)}</p>}
  </div>;
}
