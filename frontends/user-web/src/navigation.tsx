import type { ReactNode } from 'react';

function Icon({ children }: { children: ReactNode }) {
  return <svg aria-hidden="true" focusable="false" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">{children}</svg>;
}

const sparkle = <path d="m19 2 1.4 3.6L24 7l-3.6 1.4L19 12l-1.4-3.6L14 7l3.6-1.4Z" fill="currentColor" stroke="none" />;

export const userNavigation = [
  { label: "首页", to: "/home", icon: <Icon><path d="m3 9 9-7 9 7v11a1 1 0 0 1-1 1h-5v-8H9v8H4a1 1 0 0 1-1-1Z" /></Icon>, reserved: false },
  { label: "图片生成", to: "/create/image", icon: <Icon><path d="M12 4H5a3 3 0 0 0-3 3v13a2 2 0 0 0 2 2h14a3 3 0 0 0 3-3v-5" /><circle cx="7.5" cy="9" r="1.5" fill="currentColor" stroke="none" /><path d="m3 19 6-6 4 4 3-3 5 5v1a2 2 0 0 1-2 2H4Z" fill="currentColor" stroke="none" />{sparkle}</Icon>, reserved: false },
  { label: "视频生成", to: "/create/video", icon: <Icon><path d="M12 4H5a3 3 0 0 0-3 3v12a3 3 0 0 0 3 3h13a3 3 0 0 0 3-3v-5" /><path d="m10 9 6 4-6 4Z" fill="currentColor" stroke="none" />{sparkle}</Icon>, reserved: false },
  { label: "创作画布", to: "/canvas", icon: <Icon><path d="M5 2v20M19 2v9M2 6h20M2 18h9" /><path d="m18 13 1.5 3.5L23 18l-3.5 1.5L18 23l-1.5-3.5L13 18l3.5-1.5Z" fill="currentColor" stroke="none" /></Icon>, reserved: false },
  { label: "电商创作", to: "/commerce", icon: <Icon><path d="M12 7H5L3 20a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2l-.7-6M8 9V6a4 4 0 0 1 6-3.5" />{sparkle}</Icon>, reserved: false }
] as const;
