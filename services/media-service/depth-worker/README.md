# 本地深度视频执行器

画布上传视频后，Java媒体任务通过内部Bearer认证传输Storage已授权的原文件字节；执行器不接受URL或本地路径作为HTTP输入。
仅一个推理线程，Java任务表负责等待；任务目录保存原输入、身份、状态和原子完成输出。重启会重新处理未完成输入；同任务ID参数冲突拒绝。

## 安装和运行

Python3.11+、FFmpeg/ffprobe、Depth Anything V2 Small的Hugging Face格式权重目录（包含config、preprocessor_config、model.safetensors）。
权重、视频、虚拟环境和令牌放在仓库外。CPU/CUDA的PyTorch安装按主机选择；Mac使用MPS。

```bash
python3 -m venv /path/to/depth-env
/path/to/depth-env/bin/python -m pip install -r services/media-service/depth-worker/requirements.txt
export DEPTH_PYTHON=/path/to/depth-env/bin/python
export DEPTH_MODEL_PATH=/path/to/depth-anything-v2-small
export DEPTH_JOBS_PATH=/path/to/private-depth-jobs
export DEPTH_DEVICE=auto
export MEDIA_DEPTH_TOKEN='replace-with-a-private-random-token-at-least-32-characters'
bash scripts/local/start-depth-video.sh
```

执行器只监听127.0.0.1:18727。Java媒体服务另行以local profile、`MEDIA_DEPTH_ENABLED=true`、相同令牌和对应worker URL开启；具体Java配置见上级README。
本轮不提供生产部署或商业定价。没有配置local开关时不开放深度提交。

## 输出规则

30秒以内可选480p/720p，超过30秒只允许480p。以视频轨时长校验，尾随音轨不延长结果。
短边以选择值为上限且不放大；偶数像素取整后用SAR保留精确显示比例。90度旋转烘焙入像素，保留逐帧PTS与完整时长，输出仅一个视频轨。
推理按镜头进行相对深度范围平滑，并使用轻量光流减少抖动；它是黑白相对深度图，不是绝对距离或3D重建。

通过[PyAV的时间基处理](https://pyav.org/docs/stable/api/time.html)保留源PTS，编码时显式传递帧/包持续时间，避免把VFR强制改成CFR。

```bash
python3 services/media-service/depth-worker/test_worker.py
python3 scripts/local/verify-depth-video.py --worker-python /path/to/depth-env/bin/python --model /path/to/model
```

验证会生成独立临时样例，覆盖短片720p、31秒480p、竖屏、带旋转/SAR的VFR、无音轨、HTTP鉴权、幂等与处理中重启。
也可追加`--input /path/to/video.mp4 --output-dir /path/to/results`检查实际视频；不提交测试视频或模型权重。

本地已知上限：单输入200MiB；源/显示边最长8192像素且不超过3600万像素；Java结果传输和Storage仍受既有归档大小限制。
完成作业目录暂保留用于幂等重放，没有自动清理；上线前需确定保留期和商业计费，并按实测扩容。本轮没有新增云资源。
