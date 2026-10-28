import { useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';

const storageKey = 'xzwl-pending-invitation';
let pending: string | null | undefined;

function readPending() {
  if (pending === undefined) {
    try { pending = sessionStorage.getItem(storageKey); } catch { pending = null; }
  }
  return pending;
}

export function usePendingInvitation() {
  const [query] = useSearchParams();
  const incoming = query.get('ref')?.trim() || null;
  const code = incoming || readPending();
  useEffect(() => {
    if (!incoming) return;
    pending = incoming;
    try { sessionStorage.setItem(storageKey, incoming); } catch { /* Navigation still retains the source in memory. */ }
  }, [incoming]);
  return code;
}

export function clearPendingInvitation(code?: string) {
  if (code !== undefined && readPending() !== code) return;
  pending = null;
  try { sessionStorage.removeItem(storageKey); } catch { /* Memory has already been cleared. */ }
}
