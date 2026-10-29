# xzwl-cloud

Java 21 / Spring Boot 微服务与三个 TypeScript 前端。clean/core 基于 main 的 c5e9cb4f6a0a404b07cbf7eb142204cdbfef912a，保留业务代码、运行资源、配置、数据库迁移和测试。

## clean 视频交付规则

clean/core 的视频流程为：供应商生成源视频 → 腾讯 MPS 增强 → 校验、归档最终成品 → 交付用户。增强未完成或失败时，不交付源视频。视频页选择的是最终交付分辨率。

codex/internal-deployment 是内部使用项目：原生视频直接交付，用户可以另行选择增强。该分支的生成计划、可选增强入口和独立增强计费不适用于 clean/core。同步界面样式时必须单独移植，不能整条合入内部业务分支。

## 环境与构建

需要 JDK 21、Node.js >= 22.18.0、Git LFS；Maven Wrapper 固定 Maven 3.9.9。
Windows 使用 mvnw.cmd，Linux/macOS 使用 ./mvnw；.sh 脚本需要 Bash。
首次克隆运行 git lfs pull，获取首页视频资源。

```bash
npm ci
npm run typecheck
npm test
npm run build
./mvnw test
./mvnw -DskipTests package
```

数据库、第三方服务及视频测试可能要求独立测试环境和额外依赖；未配置时以实际跳过或失败结果为准。
前端开发分别运行：

```bash
npm exec --workspace @xzwl/user-web -- vite --host 127.0.0.1 --port 5173
npm run dev --workspace @xzwl/admin-web -- --host 127.0.0.1 --port 5176
npm exec --workspace @xzwl/partner-web -- vite --host 127.0.0.1 --port 5174
```

页面仍需配置可访问的 Gateway；启动前端不代表后端和外部服务已经启动。

当前项目的本地正式配置副本（Docker MySQL / Nacos / Redis / Nginx、12 项 Java 服务和前端）见 [本地部署说明](deployment/local/README.md)。可用 `./scripts/local/runtime.ps1` 启动，`-Action Stop` 停止并保留数据。

独立的全容器本地环境见 [neibu Docker 部署说明](deployment/docker/README.md)：运行 `./deployment/docker/run.ps1`，访问 `http://127.0.0.1:18390/`。入口容器名为 `neibu`，使用独立数据库与测试账号，不需要读取生产配置。

## 配置与目录

- platform/、gateways/、services/：公共模块、网关、业务服务及现有测试。
- frontends/：user、admin、partner 前端、SDK 和共享包。图片、字体、视频及许可是运行资源。
- contracts/、architecture/*.yaml：接口/协议源文件及机器可读目录。
- service-template/：Maven 聚合构建使用的服务模板。
- scripts/：服务生成、开发启动、集成验证入口。
- deployment/aiyomio/：Nginx 配置与首页资源打包/安装工具。
- docs/：基础网关接入说明和合同测试依赖的 Controller 清单。

配置位于各模块 src/main/resources/application*.yml，数据库迁移位于 src/main/resources/db/。
按环境提供数据源、服务地址和环境变量，密钥不要提交到 Git。各服务独立数据库/schema，前端通过 Gateway 和生成的 SDK 访问后端。

接口修改后使用 npm run generate:api 或 npm run generate:api-new-system 更新对应 SDK。
网关规则见 [基础接入说明](docs/release-gateway-contract.md)。
可选深度视频执行器见 [运行说明](services/media-service/depth-worker/README.md)。

历史规划、工作单、Agent 治理材料、设计截图、IDE 配置和发布日志不在本分支工作目录中；原 main 和 Git 历史保留。
