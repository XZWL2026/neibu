export async function extractVideoFrames(url: string) {
  const video = document.createElement('video');
  video.crossOrigin = 'anonymous'; video.preload = 'auto'; video.muted = true;
  const wait = (event: string, action: () => void) => new Promise<void>((resolve, reject) => {
    const finish = (error?: Error) => {
      clearTimeout(timer); video.removeEventListener(event, ready); video.removeEventListener('error', failed);
      error ? reject(error) : resolve();
    };
    const ready = () => finish();
    const failed = () => finish(new Error('无法读取参考视频，请重新上传 MP4 视频后重试'));
    const timer = setTimeout(failed, 15_000);
    video.addEventListener(event, ready, { once: true }); video.addEventListener('error', failed, { once: true });
    try { action(); } catch { failed(); }
  });
  try {
    await wait('loadedmetadata', () => { video.src = url; });
    const durationSeconds = video.duration;
    if (!Number.isFinite(durationSeconds) || durationSeconds < 0.1 || durationSeconds > 120 || !video.videoWidth || !video.videoHeight)
      throw new Error('请使用 120 秒以内、可正常播放的参考视频');
    const canvas = document.createElement('canvas');
    const scale = Math.min(1, 960 / Math.max(video.videoWidth, video.videoHeight));
    canvas.width = Math.max(1, Math.round(video.videoWidth * scale)); canvas.height = Math.max(1, Math.round(video.videoHeight * scale));
    const context = canvas.getContext('2d'); if (!context) throw new Error('浏览器无法读取视频画面');
    const frames: { timestamp: number; file: File }[] = [];
    // ponytail: eight uniform samples cover short reference clips; use scene detection for dense editing later.
    for (let index = 0; index < 8; index++) {
      const timestamp = Number((durationSeconds * (index + 0.5) / 8).toFixed(3));
      await wait('seeked', () => { video.currentTime = timestamp; });
      context.drawImage(video, 0, 0, canvas.width, canvas.height);
      const blob = await new Promise<Blob>((resolve, reject) => canvas.toBlob(value => value ? resolve(value) : reject(new Error('视频抽帧失败')), 'image/jpeg', 0.85));
      frames.push({ timestamp, file: new File([blob], `video-frame-${index + 1}.jpg`, { type: 'image/jpeg' }) });
    }
    return { durationSeconds, frames };
  } finally { video.removeAttribute('src'); video.load(); }
}
