import { expect, it } from 'vitest';
import { ApiError } from '../api';
import { uploadErrorMessage } from './upload-error';

it.each([
  ['reference video requires H.264/H.265 video and AAC/MP3 audio', '视频须采用 H.264/H.265 编码，音轨须采用 AAC/MP3 编码'],
  ['reference image metadata unavailable or outside allowed limits; upload again', '图片缺少可信尺寸信息或超出限制，请重新上传'],
  ['reference media duration must be 2–30 seconds', '时长须为 2–30 秒'],
  ['reference dimensions must be 300–6000 with aspect ratio 0.4–2.5', '宽高须为 300–6000 像素，宽高比须为 0.4–2.5'],
  ['reference video pixel count is outside the allowed range', '视频总像素须为 407696–8295044'],
  ['reference video frame rate must be 24–60 fps', '视频帧率须为 24–60 FPS'],
  ['reference media metadata is unavailable', '无法读取媒体信息，请重新导出文件后上传'],
  ['reference media metadata is missing; upload the file again', '素材缺少媒体信息，请重新上传'],
  ['media container or stream type does not match the upload', '媒体容器或音视频轨道与所选格式不符，请重新导出'],
])('explains fixed Storage validation %s', (message, translated) => {
  expect(uploadErrorMessage(new ApiError(new Response(null, { status: 400 }), { message }), true)).toContain(translated);
});
it('keeps unknown server messages generic and preserves legacy image wording', () => {
  expect(uploadErrorMessage(new ApiError(new Response(null, { status: 400 }), { message: 'constructor' }), true)).toContain('实际媒体格式不符');
  expect(uploadErrorMessage(new ApiError(new Response(null, { status: 415 }), {}))).toContain('JPG 或 PNG');
});
