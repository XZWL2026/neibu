import { useEffect, useRef, useState } from 'react';
import { t } from '../i18n';

type Product = { id: string; title: string; subtitle: string };
type ProductViewer = { load(url: string, id: string): Promise<void>; destroy(): void; reset(): void; setAutoRotate(value: boolean): void };
type ViewerFactory = (element: HTMLElement, options: Record<string, unknown>) => ProductViewer;
declare global { interface Window { createProductModelViewer?: ViewerFactory } }

let viewerScript: Promise<void> | undefined;
function loadViewerScript() {
  if (window.createProductModelViewer) return Promise.resolve();
  viewerScript ??= new Promise<void>((resolve, reject) => {
    const script = document.createElement('script');
    script.src = `${import.meta.env.BASE_URL}new-frontend/commerce/vendor/product-model-viewer.js`;
    script.async = true;
    script.onload = () => window.createProductModelViewer ? resolve() : reject(new Error('Model viewer is unavailable'));
    script.onerror = () => reject(new Error('Model viewer failed to load'));
    document.head.append(script);
  }).catch(error => { viewerScript = undefined; throw error; });
  return viewerScript;
}

function Model({ product, inline = false }: { product: Product; inline?: boolean }) {
  const host = useRef<HTMLDivElement>(null);
  const viewer = useRef<ProductViewer | null>(null);
  const latestProduct = useRef(product);
  latestProduct.current = product;
  const [ready, setReady] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void loadViewerScript().then(() => {
      if (cancelled || !host.current || !window.createProductModelViewer) return;
      viewer.current = window.createProductModelViewer(host.current, {
        inlineStage: inline, float: 0, hoverTilt: 0, intro: 'rise',
        autoRotateSpeed: .12, zoom: false, minZoom: 1, maxZoom: 1,
      });
      const current = latestProduct.current;
      return viewer.current.load(`${import.meta.env.BASE_URL}new-frontend/commerce/models/${current.id}.glb`, current.id);
    }).then(() => { if (!cancelled) setReady(true); }).catch(() => { if (!cancelled) setFailed(true); });
    return () => { cancelled = true; viewer.current?.destroy(); viewer.current = null; };
  }, [inline]);

  useEffect(() => {
    if (!viewer.current) return;
    setReady(false);
    void viewer.current.load(`${import.meta.env.BASE_URL}new-frontend/commerce/models/${product.id}.glb`, product.id)
      .then(() => setReady(true)).catch(() => setFailed(true));
  }, [product.id]);

  return <div className="commerce-model" data-ready={ready} data-failed={failed}>
    <div ref={host} className="commerce-model-host" />
    {!ready && <span className="commerce-model-status" role="status">{t(failed ? '3D 预览不可用' : '正在准备三维模型…')}</span>}
  </div>;
}

export function ProductGalleryStage({ product }: { product: Product }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [open, setOpen] = useState(false);
  useEffect(() => {
    const element = dialog.current;
    if (!element) return;
    if (open && !element.open) element.showModal();
    else if (!open && element.open) element.close();
  }, [open]);

  return <div className="commerce-product-stage">
    <Model product={product} inline />
    <div className="commerce-product-stage-copy"><strong>{product.title}</strong><button type="button" onClick={() => setOpen(true)}>{t('3D 实物预览')} ↗</button><span>{t(product.subtitle)}</span></div>
    <dialog ref={dialog} className="commerce-product-dialog" aria-label={`${product.title} ${t('3D 实物预览')}`} onClose={() => setOpen(false)}>
      <button type="button" className="commerce-product-close" aria-label={t('关闭 3D 预览')} onClick={() => setOpen(false)}>×</button>
      {open && <Model product={product} />}
      <div className="commerce-product-dialog-copy"><h2>{product.title}</h2><p>{t(product.subtitle)}</p>
        <a href={`${import.meta.env.BASE_URL}new-frontend/commerce/models/${product.id}.glb`} download={`${product.id}.glb`}>{t('下载 3D 模型')} ↗</a>
      </div>
    </dialog>
  </div>;
}
