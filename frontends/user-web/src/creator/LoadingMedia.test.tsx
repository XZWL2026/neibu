import '@testing-library/jest-dom/vitest';
import {cleanup,fireEvent,render,screen} from '@testing-library/react';
import {afterEach,expect,it,vi} from 'vitest';
import {LoadingMedia} from './LoadingMedia';
afterEach(()=>{cleanup();vi.restoreAllMocks();});
it('reveals an image only after loading and resets when its source changes',()=>{
 const view=render(<LoadingMedia kind="image" src="/one.png" label="参考图"/>);
 expect(screen.getByAltText('参考图')).not.toBeVisible();expect(screen.getByRole('status')).toHaveAttribute('aria-label','参考图 加载中');
 fireEvent.load(screen.getByAltText('参考图'));expect(screen.getByAltText('参考图')).toBeVisible();expect(screen.queryByRole('status')).toBeNull();
 view.rerender(<LoadingMedia kind="image" src="/two.png" label="参考图"/>);expect(screen.getByAltText('参考图')).not.toBeVisible();
 fireEvent.error(screen.getByAltText('参考图'));expect(screen.queryByRole('status')).toBeNull();expect(screen.getByLabelText('参考图 加载失败')).toBeInTheDocument();
});
it('keeps upload feedback on a loaded thumbnail and handles cached images',()=>{
 vi.spyOn(HTMLImageElement.prototype,'complete','get').mockReturnValue(true);vi.spyOn(HTMLImageElement.prototype,'naturalWidth','get').mockReturnValue(400);
 const view=render(<LoadingMedia kind="image" src="blob:cached" label="上传图" busy/>);
 expect(screen.getByAltText('上传图')).toBeVisible();expect(screen.getByRole('status')).toHaveAttribute('aria-label','上传图 上传中');
 view.rerender(<LoadingMedia kind="image" src="blob:cached" label="上传图"/>);expect(screen.queryByRole('status')).toBeNull();
});
it('waits for the video first frame, keeps controls during buffering and ends loading on errors',()=>{
 const failure=vi.fn();render(<LoadingMedia kind="video" src="/movie.mp4" label="视频" controls onError={failure}/>);
 const video=screen.getByLabelText('视频');fireEvent.loadedMetadata(video);expect(video).not.toBeVisible();expect(screen.getByRole('status')).toBeInTheDocument();
 fireEvent.loadedData(video);expect(video).toBeVisible();expect(video).toHaveAttribute('controls');expect(screen.queryByRole('status')).toBeNull();
 fireEvent.waiting(video);expect(video).toBeVisible();expect(screen.getByRole('status')).toBeInTheDocument();fireEvent.playing(video);expect(screen.queryByRole('status')).toBeNull();
 fireEvent.waiting(video);fireEvent.error(video);expect(failure).toHaveBeenCalledOnce();expect(screen.queryByRole('status')).toBeNull();
});

it('reports cached and newly loaded media readiness to the result reveal gate',()=>{
 const ready=vi.fn();
 const view=render(<LoadingMedia kind="video" src="/ready.mp4" label="就绪视频" onReady={ready}/>);
 fireEvent.loadedMetadata(screen.getByLabelText('就绪视频'));expect(ready).not.toHaveBeenCalled();
 fireEvent.loadedData(screen.getByLabelText('就绪视频'));expect(ready).toHaveBeenCalledOnce();
 vi.spyOn(HTMLImageElement.prototype,'complete','get').mockReturnValue(true);
 vi.spyOn(HTMLImageElement.prototype,'naturalWidth','get').mockReturnValue(400);
 view.rerender(<LoadingMedia kind="image" src="/cached.png" label="缓存图片" onReady={ready}/>);
 expect(ready).toHaveBeenCalledTimes(2);
});
