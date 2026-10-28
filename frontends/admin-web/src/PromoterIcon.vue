<script setup lang="ts">
defineProps<{ name: string }>();
const paths: Record<string,string> = {
 'chevron-left':'m15 5-7 7 7 7',
 'chevron-right':'m9 5 7 7-7 7',
 bolt:'m13 2-9 12h7l-1 8 10-12h-7Z',
 bank:'m2 8 10-6 10 6ZM4 10v9M9 10v9M15 10v9M20 10v9M2 22h20M3 19h18',
 eye:'M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Zm13 0a3 3 0 1 1-6 0 3 3 0 0 1 6 0',
 copy:'M9 3h12v15H9ZM5 7H3v15h12v-2',
 trash:'M3 6h18M9 6V4h6v2M5 6l1 15h12l1-15M10 10v7M14 10v7',
 download:'M12 3v12m-5-5 5 5 5-5M3 16v5h18v-5',
 upload:'M12 16V3m-5 5 5-5 5 5M3 15v6h18v-6',
 send:'m22 2-8 20-4-8-8-4Zm0 0L10 14',
 headset:'M4 14v-3a8 8 0 0 1 16 0v7l-4 3h-4M2 11h4v7H2ZM18 11h4v7h-4Z',
 card:'M3 4h18v16H3ZM3 9h18M7 15h4',
 home:'m3 10 9-7 9 7v11h-6v-7H9v7H3Z',
 user:'M20 21v-2a7 7 0 0 0-14 0v2M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z',
 'user-plus':'M16 21v-2a6 6 0 0 0-12 0v2M14 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM20 11v6M17 14h6',
 users:'M16 21v-2a6 6 0 0 0-12 0v2M14 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM18 4a4 4 0 0 1 0 8M20 15a5 5 0 0 1 2 4v2',
 document:'M14 2H4v20h16V8ZM14 2v6h6M8 12h8M8 16h5',
 money:'m6 3 6 8 6-8M5 12h14M5 16h14M12 11v11',
 settings:'m9 3 1-1h4l1 3 3 1 3 2v4l-2 2-1 3-3 2h-4l-2-2-3-1-2-3V9l2-2 3-1ZM15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z',
 database:'M21 5c0 2-4 3-9 3S3 7 3 5s4-3 9-3 9 1 9 3ZM3 5v14c0 2 4 3 9 3s9-1 9-3V5M3 12c0 2 4 3 9 3s9-1 9-3',
 cart:'M2 3h3l3 12h11l3-9H6M10 20h.01M18 20h.01',
 gift:'M3 8h18v5H3ZM5 13v9h14v-9M12 8v14M12 8H7a3 3 0 1 1 3-3ZM12 8h5a3 3 0 1 0-3-3Z',
 lock:'M6 10h12v12H6ZM8 10V6a4 4 0 0 1 8 0v4M12 15v3',
 refresh:'M20 7a9 9 0 0 0-15-2L2 8M2 2v6h6M4 17a9 9 0 0 0 15 2l3-3M22 22v-6h-6',
 globe:'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0ZM3 12h18M12 3c5 5 5 13 0 18-5-5-5-13 0-18Z',
 search:'M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0Zm-2 5 7 7',
 calendar:'M3 5h18v17H3ZM7 2v6M17 2v6M3 11h18',
 chart:'M5 20v-6M12 20V8M19 20V3'
};
</script>
<template><svg class="promoter-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] || paths.document"/></svg></template>
