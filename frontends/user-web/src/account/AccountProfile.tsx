import { t, useLanguage } from '../i18n';
import './AccountProfile.css';
import { ProfileEditor } from './ProfileEditor';
import { formatAmount } from '../../../shared/formatAmount';
import { useEffect, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { accessSecretGetApiV1AccessKeysIdSecret } from '../api';
import { accountStatusLabels, accountTypeLabels, useUserSession } from '../userSession';

function CopyIcon() {
  useLanguage();
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true"><rect x="8" y="7" width="12" height="14" rx="2"/><path d="M16 7V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h3"/></svg>;
}
function EyeIcon({ hidden }: { hidden: boolean }) {
  useLanguage();
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/>{hidden && <path d="m3 3 18 18"/>}</svg>;
}
function KeySecret({ keyId }: { keyId: string }) {
  useLanguage();
  const [secret, setSecret] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const request = useRef<AbortController | null>(null);
  useEffect(() => () => { request.current?.abort(); }, []);
  async function access(action: 'show' | 'copy') {
    if (request.current && !request.current.signal.aborted) return;
    const controller = new AbortController();
    request.current = controller;
    setBusy(true); setError(''); setNotice('');
    let value = secret;
    try {
      if (!value) {
        const { data } = await accessSecretGetApiV1AccessKeysIdSecret(keyId, { cache: 'no-store', signal: controller.signal });
        if (controller.signal.aborted) return;
        if (!data.secret) throw new Error('Missing secret');
        value = data.secret;
      }
      if (action === 'show') setSecret(value);
      else {
        await navigator.clipboard.writeText(value);
        if (!controller.signal.aborted) setNotice('Key 已复制');
      }
    } catch {
      if (!controller.signal.aborted) {
        if (value && action === 'copy') { setSecret(value); setError('复制失败，请手动复制已显示的 Key。'); }
        else setError('密钥读取失败，请重试。');
      }
    } finally {
      if (!controller.signal.aborted) { setBusy(false); controller.abort(); }
    }
  }
  function hide() {
    request.current?.abort(); setSecret(''); setError(''); setNotice(''); setBusy(false);
  }
  return <div className="profile-secret">
    <div className="profile-secret-row">
      <div className="profile-secret-field"><div><span className="profile-key-label">API Key</span><code className="key-id" aria-label={t("平台 Key")}>{secret || 'sk-••••••••••••••••••••••••••••••'}</code></div>
        <button type="button" className="profile-key-reveal" disabled={busy} aria-label={t(secret ? '隐藏 Key' : '显示 Key')} onClick={() => secret ? hide() : void access('show')}><EyeIcon hidden={!!secret}/>{t(busy ? '读取中…' : secret ? '隐藏' : '显示')}</button>
      </div>
      <button className="btn-primary profile-key-copy" type="button" disabled={busy} onClick={() => void access('copy')}><CopyIcon/>{t("复制 Key")}</button>
    </div>
    {error && <p role="alert">{t(error)}</p>}
    {notice && <p role="status">{t(notice)}</p>}
  </div>;
}

/** Shared owner-only key view for the profile and API portal. No secret is needed to bind. */
export function PlatformKeyPanel() {
  useLanguage();
  const { currentKey, keys, status, error, refresh, fixture, userId } = useUserSession();
  const { hash } = useLocation();
  const keysRef = useRef<HTMLElement>(null);
  useEffect(() => {
    if (hash !== '#keys') return;
    // Run after the frame's route focus so the deep-link target keeps focus.
    const timer = window.setTimeout(() => {
      keysRef.current?.focus({ preventScroll: true });
      keysRef.current?.scrollIntoView?.({ block: 'start' });
    }, 0);
    return () => window.clearTimeout(timer);
  }, [hash]);
  return <section className="account-section profile-key-panel" id="keys" aria-label={t("平台密钥")} ref={keysRef} tabIndex={-1}>
    <div className="api-section-title"><h2>{t("平台密钥")}</h2>{!error && <span className="profile-key-status" data-ready={status === 'ready'} role="status"><i aria-hidden="true"/>{t(accountStatusLabels[status])}</span>}<button className="profile-key-refresh" type="button" aria-label={t("刷新账户")} title={t("刷新账户")} disabled={status === 'loading'} onClick={() => void refresh()}><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true"><path d="M20 7v5h-5M4 17v-5h5M5 8a8 8 0 0 1 13-3l2 3M4 16l2 3a8 8 0 0 0 13-3"/></svg></button></div>
    {error && <p role="alert">{t(error)}</p>}
    {status === 'anonymous' && <Link to="/user/login">{t("登录账户")}</Link>}
    {status === 'not-open' && <Link to="/account/recharge">{t("前往充值开通")}</Link>}
    {keys.map(key => <article className="key-card" key={key.id}>
      <h3>{key.name}</h3>
      {status === 'ready' && currentKey?.id === key.id && key.relationship === 'Owner' && !fixture
        && <KeySecret key={`${userId}:${key.id}`} keyId={key.id} />}
      <dl className="profile-key-details"><div><dt>{t("密钥类型")}</dt><dd>{key.kind}</dd></div><div><dt>{t("归属")}</dt><dd>{t("本人")}</dd></div><div><dt>{t("可用积分")}</dt><dd className="profile-balance"><strong>{t(formatAmount(key.balance).replace(/\s*积分$/, '').replace(/^(\d+)(.*)$/, (_, whole: string, rest: string) => whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + rest))}</strong> <span>{t("积分")}</span></dd></div></dl>
    </article>)}
  </section>;
}

export function AccountProfile() {
  useLanguage();
  const { nickname, avatarUrl, userId, phone, accountType, status, fixture, authenticated } = useUserSession();
  const [editingUserId, setEditingUserId] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [copyError, setCopyError] = useState('');
  useEffect(() => { setCopied(false); setCopyError(''); if (status !== 'loading' && userId !== editingUserId) setEditingUserId(null); }, [userId, status, editingUserId]);
  const maskedPhone = phone?.replace(/^(?:\+?86)?(1\d{2})\d{4}(\d{4})$/, '+86 $1 **** $2') ?? '—';
  async function copyId() {
    if (!userId) return;
    setCopied(false); setCopyError('');
    try { await navigator.clipboard.writeText(userId); setCopied(true); }
    catch { setCopyError('复制失败，请手动复制用户 ID。'); }
  }
  return <div className="account-profile-cards">
    <section className="profile-identity-card" aria-label={t("个人资料")}>
      <div className="profile-person"><span className="profile-avatar">{avatarUrl ? <img src={avatarUrl} alt={t("用户头像")} /> : <svg aria-hidden="true" viewBox="0 0 64 64" fill="currentColor"><circle cx="32" cy="21" r="11"/><path d="M11 56v-6c0-20 42-20 42 0v6Z"/></svg>}</span>
        <div><div className="profile-name"><h2>{nickname || '—'}</h2>{authenticated && !fixture && !['loading', 'disabled', 'anonymous'].includes(status) && <button type="button" className="profile-edit-button" onClick={() => setEditingUserId(userId)}>{t("编辑资料")}</button>}</div><p>{maskedPhone}</p><span className="profile-account-badge">{t(accountType ? accountTypeLabels[accountType] : status === 'not-open' ? '尚未开通' : '—')}</span></div>
      </div>
      <div className="profile-user-id"><div><span>{t("用户 ID")}</span><p>{userId ?? '—'}</p></div><button type="button" disabled={!userId} aria-label={t("复制用户 ID")} title={t(copied ? '已复制' : '复制用户 ID')} onClick={() => void copyId()}>{copied ? <span aria-hidden="true">✓</span> : <CopyIcon/>}</button>{copyError && <p role="alert">{t(copyError)}</p>}</div>
    </section>
    <PlatformKeyPanel />
    {editingUserId && (userId === editingUserId || status === "loading") && <ProfileEditor key={editingUserId} onClose={() => setEditingUserId(null)} />}
  </div>;
}
