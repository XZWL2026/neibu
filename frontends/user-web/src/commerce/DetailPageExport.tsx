import { t, formatMessage, useLanguage } from '../i18n';
import { useEffect, useRef, useState } from 'react';
import { buildDetailPageImage } from './detail-page-export';

type Props = { productName: string; fileIds: string[]; disabled?: boolean };

export function DetailPageExport(props: Props) {
  useLanguage();
  return <ExportSession key={JSON.stringify([props.productName, props.fileIds, Boolean(props.disabled)])} {...props} />;
}

function ExportSession({ productName, fileIds, disabled }: Props) {
  useLanguage();
  const request = useRef<AbortController | null>(null);
  const resource = useRef('');
  const [url, setUrl] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => () => {
    request.current?.abort();
    if (resource.current) URL.revokeObjectURL(resource.current);
  }, []);
  const build = async () => {
    if (disabled || busy || !fileIds.length || fileIds.length > 14) return;
    const controller = new AbortController();
    request.current = controller;
    if (resource.current) URL.revokeObjectURL(resource.current);
    resource.current = ''; setUrl(''); setError(''); setBusy(true);
    try {
      const blob = await buildDetailPageImage(fileIds, controller.signal);
      if (controller.signal.aborted) return;
      resource.current = URL.createObjectURL(blob);
      setUrl(resource.current);
    } catch {
      if (!controller.signal.aborted) setError('长图拼接失败，请重试');
    } finally {
      if (!controller.signal.aborted) setBusy(false);
    }
  };
  const filename = `${productName.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '').trim().slice(0, 100) || '商品'}-详情长图.png`;
  return <section aria-label={t("详情长图")} style={{ display: 'grid', gap: 12, justifyItems: 'start' }}>
    <button className="suite-button" type="button" disabled={disabled || busy || !fileIds.length || fileIds.length > 14} onClick={() => void build()}>
      {t(busy ? '正在拼接…' : '拼接详情长图')}
    </button>
    {error && <p role="alert">{t(error)}</p>}
    {url && <>
      <a className="suite-button" href={url} download={filename}>{t("下载详情长图")}</a>
      <div style={{ maxHeight: 640, overflow: 'auto', width: '100%' }}>
        <img src={url} alt={formatMessage("{0}详情长图", productName)} style={{ display: 'block', maxWidth: '100%', height: 'auto' }} />
      </div>
    </>}
  </section>;
}
