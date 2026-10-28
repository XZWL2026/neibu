import { ApiError } from '../api';

export function uploadErrorMessage(cause: unknown, media = false): string {
  const reasons: Record<number, string> = {
    400: media ? '文件为空、内容损坏或实际媒体格式不符' : '文件为空、内容损坏或实际格式与 JPG/PNG 不符',
    401: '登录已失效，请重新登录', 403: '会话校验或文件权限被拒绝，请重试或重新登录',
    413: media ? '文件超过当前媒体类型的上传大小限制' : '文件超过上传大小限制（单张最多 20MB）',
    415: media ? '文件格式不支持，请使用 JPG/PNG、MP4/MOV 或 MP3/WAV' : '文件格式不支持，请使用 JPG 或 PNG',
    422: media ? '文件未通过校验，请检查媒体格式、时长和大小' : '文件未通过校验，请检查图片格式和大小',
    500: '文件存储服务发生错误', 502: '文件存储服务暂不可用', 503: '文件存储服务暂不可用',
  };
  const mediaReasons: Record<string, string> = {
    'reference video requires H.264/H.265 video and AAC/MP3 audio': '视频须采用 H.264/H.265 编码，音轨须采用 AAC/MP3 编码',
    'reference image metadata unavailable or outside allowed limits; upload again': '图片缺少可信尺寸信息或超出限制，请重新上传',
    'reference media duration must be 2–30 seconds': '时长须为 2–30 秒',
    'reference dimensions must be 300–6000 with aspect ratio 0.4–2.5': '宽高须为 300–6000 像素，宽高比须为 0.4–2.5',
    'reference video pixel count is outside the allowed range': '视频总像素须为 407696–8295044',
    'reference video frame rate must be 24–60 fps': '视频帧率须为 24–60 FPS',
    'reference media metadata is unavailable': '无法读取媒体信息，请重新导出文件后上传',
    'reference media metadata is missing; upload the file again': '素材缺少媒体信息，请重新上传',
    'media container or stream type does not match the upload': '媒体容器或音视频轨道与所选格式不符，请重新导出',
  };
  const reason = cause instanceof ApiError ? (media && cause.status === 400 && Object.hasOwn(mediaReasons, cause.message) ? mediaReasons[cause.message] : undefined) ?? reasons[cause.status] ?? `服务返回 HTTP ${cause.status}`
    : cause instanceof TypeError ? '网络连接失败，请检查网络'
    : cause instanceof Error && cause.message === '上传未返回有效文件 ID' ? cause.message : '上传请求未完成';
  return `上传失败：${reason}；已有素材已保留，请重试。`;
}
