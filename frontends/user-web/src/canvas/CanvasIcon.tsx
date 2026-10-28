export function CanvasIcon({ name }: { name: 'audio' | 'image' | 'video' | 'text' | 'plus' | 'trash' | 'expand' | 'arrow' | 'back' | 'home' | 'save' | 'copy' | 'save-as' | 'reload' | 'library' | 'upload' | 'group' | 'ungroup' | 'edit' | 'check' | 'locate' }) {
  const paths = {
    locate: <><circle cx="12" cy="12" r="6" /><path d="M12 2v4m0 12v4M2 12h4m12 0h4" /></>,
    check: <path d="m5 12 4 4L19 6" />,
    group: <><rect x="3" y="3" width="18" height="18" rx="3" strokeDasharray="3 3" /><rect x="7" y="7" width="4" height="4" rx="1" /><rect x="13" y="13" width="4" height="4" rx="1" /></>,
    ungroup: <><path d="M8 3H3v5M16 3h5v5M21 16v5h-5M8 21H3v-5" /><path d="m9 9 6 6m0-6-6 6" /></>,
    edit: <><path d="m15 4 5 5M4 20l5-1L21 7l-5-5L4 14Z" /></>,
    image: <><rect x="3" y="3" width="18" height="18" rx="3" /><circle cx="8" cy="8" r="1.5" /><path d="m3 16 5-5 4 4 3-3 6 6" /></>,
    audio: <><path d="M9 18V5l11-2v13M9 8l11-2" /><circle cx="6" cy="18" r="3" /><circle cx="17" cy="16" r="3" /></>,
    video: <><rect x="3" y="4" width="18" height="16" rx="3" /><path d="m10 8 6 4-6 4Z" /></>,
    text: <path d="M4 5h16M12 5v15M8 20h8" />,
    plus: <path d="M12 5v14M5 12h14" />,
    trash: <><path d="M3 6h18M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7M14 10v7" /></>,
    expand: <path d="M8 3H3v5M16 3h5v5M21 16v5h-5M8 21H3v-5" />,
    arrow: <path d="M12 19V5m-6 6 6-6 6 6" />,
    home: <><path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1Z" /></>,
    save: <><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h12l4 4v12a2 2 0 0 1-2 2Z" /><path d="M7 3v6h10V3M7 21v-8h10v8" /></>,
    copy: <><rect x="8" y="8" width="13" height="13" rx="2" /><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3" /></>,
    'save-as': <><path d="M12 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h12l4 4v5M7 3v6h10V3M7 21v-8h5M18 15v6M15 18h6" /></>,
    reload: <><path d="M20 7v5h-5M4 17v-5h5M5 7a8 8 0 0 1 13-2l2 2M19 17a8 8 0 0 1-13 2l-2-2" /></>,
    library: <><path d="M3 7V5a2 2 0 0 1 2-2h5l2 3h7a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z" /><path d="m6 17 4-4 3 3 2-2 3 3" /><circle cx="16" cy="10" r="1" /></>,
    upload: <><path d="M12 16V3m-5 5 5-5 5 5M4 15v5a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-5" /></>,
    back: <path d="m14 6-6 6 6 6" />,
  };
  return <svg aria-hidden="true" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">{paths[name]}</svg>;
}
