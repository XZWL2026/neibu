import { t, useLanguage } from '../i18n';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { storageUploadAvatarPostApiV1FilesAvatars, userUpdateSelfPatchApiV1UsersMe } from '../api';
import { useUserSession } from '../userSession';

export function ProfileEditor({ onClose }: { onClose(): void }) {
  useLanguage();
  const { nickname, userId, status, refresh } = useUserSession();
  const [editingUserId] = useState(userId);
  const [name, setName] = useState(nickname);
  const [file, setFile] = useState<File | null>(null);
  const [uploadedId, setUploadedId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const dialog = useRef<HTMLDialogElement>(null);
  const saving = useRef(false);
  const currentUser = useRef(userId);
  // A retained draft owner during focus revalidation is not permission to save.
  currentUser.current = status === 'loading' ? null : userId;
  const request = useRef<AbortController | null>(null);
  useEffect(() => () => request.current?.abort(), []);
  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const element = dialog.current;
    if (element?.showModal) element.showModal(); else element?.setAttribute('open', '');
    element?.querySelector('input')?.focus();
    return () => { element?.close?.(); previous?.focus(); };
  }, []);
  async function save(event: FormEvent) {
    event.preventDefault();
    if (saving.current) return;
    if (!editingUserId || currentUser.current !== editingUserId) { setError("账户已变化，请重新打开编辑资料。"); return; }
    if (!name.trim() || name.length > 64 || /[\u0000-\u001f\u007f-\u009f]/.test(name)) { setError('昵称需为 1–64 个字符。'); return; }
    if (file && (!['image/jpeg', 'image/png'].includes(file.type) || !file.size || file.size > 5 * 1024 * 1024)) { setError('请选择 5MB 以内的 PNG 或 JPEG 图片。'); return; }
    saving.current = true; setBusy(true); setError('');
    const controller = new AbortController(); request.current = controller;
    try {
      let avatarFileId = uploadedId;
      if (file && !avatarFileId) {
        const { data } = await storageUploadAvatarPostApiV1FilesAvatars({ file }, { signal: controller.signal });
        if (!data.fileId) throw new Error('Missing uploaded file');
        avatarFileId = data.fileId; setUploadedId(avatarFileId);
      }
      if (controller.signal.aborted || currentUser.current !== editingUserId) throw new Error("Account changed");
      await userUpdateSelfPatchApiV1UsersMe({ nickname: name.trim(), ...(avatarFileId ? { avatarFileId } : {}) }, { signal: controller.signal });
      onClose();
      await refresh();
    } catch { if (!controller.signal.aborted) setError('保存失败，请重试。'); }
    finally { saving.current = false; setBusy(false); }
  }
  return <dialog ref={dialog} className="profile-editor" aria-label={t("编辑资料")} aria-modal="true" onCancel={event => { event.preventDefault(); if (!busy) onClose(); }}>
    <form onSubmit={event => void save(event)}>
      <h2>{t("编辑资料")}</h2>
      <label>{t("昵称")}<input value={name} maxLength={64} required disabled={busy} onChange={event => setName(event.target.value)} /></label>
      <label>{t("选择头像")}<input type="file" accept="image/png,image/jpeg" disabled={busy} onChange={event => { setFile(event.target.files?.[0] ?? null); setUploadedId(null); setError(''); }} /></label>
      {error && <p role="alert">{t(error)}</p>}
      <div className="profile-editor-actions"><button type="button" disabled={busy} onClick={onClose}>{t("取消")}</button><button className="btn-primary" type="submit" disabled={busy}>{t(busy ? '保存中…' : '保存资料')}</button></div>
    </form>
  </dialog>;
}
