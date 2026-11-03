# 内部站 Seedance 2.5 切换到 LetsLLM

2026-10-30 将 `http://115.29.222.124:32124` 的 `seedance-2.5` 从
`volc-ark-live` 切换为 aiyomio 当前使用的 `letsllm`。

- 供应商：LetsLLM，接口 `https://api.letsllm.com/api/v3`。
- API Key：服务器内读取 aiyomio 的供应商连接，保存到内部站对应连接；密钥不写入本文或源码。
- Routing 保留 `doubao-seedance-2-5-260628`，发送到 LetsLLM 时使用其别名 `doubao-seedance-2-5`。
- 仅切换 `seedance-2.5` 的供应商；其内部站能力配置、价格、原生视频交付和独立增强流程保留。
- 149 条原来审核通过的素材（139 张图片、10 条音频）导入并通过新渠道审核，原记录和历史任务绑定保留。
- 原失败任务 `dedeefff-ba67-4fba-8290-739a6a07cf87` 的 4 个原素材引用已通过应用校验；失败任务与已完成退款保留，没有重发视频生成。

## 验证

供应商连接验证、生成任务查询均通过；查询接口返回 HTTP 200。
内部 Routing 的 `/internal/v1/models` 返回 `seedance-2.5` 对应 `providerId=letsllm`。
内部 Media 健康状态为 UP，其他受核对服务的 PID 保持一致。

本地 `LetsLlmProviderTest` 6 项、`PortraitReviewsTest` 55 项、`ArkModelCatalogTest`
2 项全部通过，无失败或跳过。适配源码还使用线上当前版本的 98 份精确依赖编译通过。
验证没有创建付费视频生成任务，因此不代表已完成一次真实视频生成。

## 制品与恢复记录

仅修改内部 Media 制品的供应商适配类，其他 JAR 条目逐项核验一致。

- 原 Media：`/opt/xzwl-test-124/releases/20261028-2k-enhancement-tier-0edb9f34/jars/media.jar`。
- 原 SHA-256：`9a899051fa13f298684095856145d86a2673a22c42c3c68a1cdd5c8806775c66`。
- 新 Media：`/opt/xzwl-test-124/releases/20261030-letsllm-seedance25/jars/media.jar`。
- 新 SHA-256：`71b833ff0388d3a109949e05bc42e780a3bb4a5b90742832ed33f751584002a0`。
- 服务覆盖：`/etc/systemd/system/xzwl-test-124-media.service.d/99-letsllm-seedance25.conf`。
- 附加环境配置：`/etc/xzwl-test-124/letsllm-seedance25.env`，仅向允许主机列表加入 LetsLLM。
- 服务器受限留档：`/root/xzwl-test-124-stage/20261030-letsllm-switch/`，包含配置备份、制品校验、源码、连接与素材验证结果。

恢复时先核对在途任务与当前配置，再按留档恢复模型路由及服务覆盖；不要清理原素材、
新渠道审核回执或历史任务绑定。原 Ark Key 当时被供应商返回 `The API key status is not active`，
仅恢复旧路由不能恢复其鉴权能力。
