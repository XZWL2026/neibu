# neibu 本地 Docker 环境

在仓库根目录执行 `./deployment/docker/run.ps1`。首次运行会构建当前工作区，准备独立凭据与数据库，再启动并验证应用。需要已启动的 Docker Desktop、Java 21、Node.js >=22.18、Python 3；Maven 使用仓库自带 Wrapper。

Docker Compose 项目名是 `neibu`，网页入口容器名也是 `neibu`。12 个 Java 服务、MySQL 8.0.46、Redis 7.0.15 和 Nginx 1.24.0 共 15 个容器，均在此项目的独立网络内运行。Java 运行环境为 Temurin 21，Storage 镜像另外安装 FFmpeg/ffprobe。本地服务直接使用 Docker DNS，不依赖 Nacos。

| 入口 | 本地地址 |
| --- | --- |
| 用户端 | http://127.0.0.1:18390/ |
| 管理端 | http://127.0.0.1:18390/admin/login |
| API 门户 | http://127.0.0.1:18390/api/login |
| 推广门户 | http://127.0.0.1:18390/promoter/overview |
| 合作伙伴端 | http://127.0.0.1:18392/ |
| Gateway | http://127.0.0.1:18300/ |
| MySQL | 127.0.0.1:23326 |
| Redis | 127.0.0.1:26399 |

统一使用 `127.0.0.1` 访问，保持同源 Cookie 与 CSRF 会话。端口只发布到本机。

管理员为 `localadmin`，随机密码保存在 `target/.env.credentials.txt`；测试手机号 `19900000001`，固定验证码 `123456`。首次验证通过真实本地登录流程建立测试用户。所有数据库、内部服务令牌与管理员密码均独立生成，不读取生产配置，不复制其他本地部署的数据。生成记录、数据库与 Redis 数据使用名为 `neibu_mysql-data`、`neibu_redis-data` 的 Docker 卷持久保存。

```powershell
./deployment/docker/run.ps1                    # 启动或恢复，并验证
./deployment/docker/run.ps1 -Rebuild           # 源码变更后重新构建、部署
./deployment/docker/run.ps1 -Action Status     # 容器与健康状态
./deployment/docker/run.ps1 -Action Verify     # HTTP、登录、资源和数据库验证
./deployment/docker/run.ps1 -Action Stop       # 停止，保留数据
```

Compose 文件、每个服务的环境变量、JAR 副本、构建日志、凭据与验证报告保存在被 Git 忽略的 `target/`。普通启动保留已生成的密码与数据库。查看日志可运行：

```powershell
docker compose --project-directory deployment/docker --env-file deployment/docker/target/.env.compose -f deployment/docker/target/compose.json logs --tail 100
```

服务健康检查使用实际 HTTP 接口。验证还检查 11 个 Flyway schema、页面和静态资源、视频和 3D 模型的分段请求、管理员登录、固定验证码用户登录、会话隔离和部分管理接口。

当前部署支持页面、本地登录、后台管理和本地数据操作。云端模型生成、TOS/COS 上传、腾讯 MPS 与在线支付仍需要单独配置服务账号、密钥及回调；本环境不自动配置这些外部服务，不发送真实短信或提交付费生成请求。

`admin-web-next` 保留其独立锁文件与安装流程；根工作区部署 `user-web`、`admin-web`、`partner-web` 和共享包。
