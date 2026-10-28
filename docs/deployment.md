# 测试环境与正式环境部署操作

适用于本仓库的 Java 服务和 user、admin、partner 三个前端。常规流程是：锁定提交、构建制品、发布到 TEST 124、验收、发布到正式环境、保留回滚版本。

环境信息整理于 **2026-10-12**，依据“寻知未来-cyw”项目的部署对话、原工作区部署记录和本分支代码。本次仅编写说明，未连接服务器验证当前状态，也未执行部署。文中的主机分工和路径是已有部署约定；每次发布先执行现场核对，历史版本号不能当作当前指针。

## 1. 环境与操作边界

| 环境或角色 | 公网地址 | 私网地址 | 用途 |
| --- | --- | --- | --- |
| 正式 EDGE | `47.97.85.146` | `172.31.218.43` | `https://aiyomio.cn`，Nginx、前端、gateway、media、storage、routing、canvas、operations |
| 正式 APP | `47.99.240.62` | `172.31.218.44` | iam、user、access、payment、billing、promotion |
| 正式中间件与独立测试副本所在主机 | `115.29.222.124` | `172.31.218.45` | 原生产 MySQL/Redis/Nacos，以及独立的 `xzwl-test-124` |
| 1528 站点 | `47.121.179.59` | 本文不使用 | `yx1528.zhikeji.cn`，使用另一套目录、服务与 Cookie；不属于本次发布目标 |

`http://47.97.85.146/` 曾用于前端预览，但连接的是正式后端，**不能当作独立测试环境**。

### TEST 124 的隔离资源

| 项目 | 正式资源 | TEST 124 资源 |
| --- | --- | --- |
| MySQL | `mysql.service`，3306 | `xzwl-test-124-mysql.service`，回环 13306 |
| Redis | `redis-server.service`，6379 | `xzwl-test-124-redis.service`，回环 16379 |
| Nacos | `nacos.service`，8848 | `xzwl-test-124-nacos.service`，18848；gRPC 19848/19849、raft 17848 |
| Nacos 隔离 | `xzwl-prod` / `XZWL` 为源码正式默认值 | namespace `xzwl-test-124`，group `XZWL_TEST` |
| 应用服务 | `xzwl-<名称>.service` | `xzwl-test-124-<名称>.service` |
| 测试制品 | 不使用正式路径 | `/opt/xzwl-test-124/releases/<发布号>`，`current` 指向选中版本 |
| 测试配置 | 不覆盖正式配置 | `/etc/xzwl-test-124/*.env`、`*-production.properties` |
| 测试数据 | 不复制正式业务数据 | `/opt/xzwl-test-124/data/`，库名前缀 `xzwl_test_` |

`*-production.properties` 是历史“生产配置映射到测试”的文件名，内容必须保持测试数据库、端口和内部身份凭据，不能据文件名判断环境。

TEST 124 与生产中间件共用主机：发布前后记录原 `mysql.service`、`redis-server.service`、`nacos.service` 的 PID 和配置哈希；禁止使用 `systemctl restart xzwl-*` 等广泛匹配。测试资源归入 `xzwl-test-124.slice`，历史限制为 CPU 4 核、内存软限 10 GiB、硬限 12 GiB，不在此机做无约束压测。

### 应用端口

| 服务 | 正式默认端口 | 测试端口 | 正式主机 |
| --- | ---: | ---: | --- |
| gateway | 8200 | 18200 | EDGE |
| iam | 8210 | 18210 | APP |
| user | 8211 | 18211 | APP |
| access | 8212 | 18212 | APP |
| promotion | 8213 | 18213 | APP |
| billing | 8214 | 18214 | APP |
| payment | 8215 | 18215 | APP |
| routing | 8216 | 18216 | EDGE |
| media | 8217 | 18217 | EDGE |
| canvas | 8218 | 18218 | EDGE |
| storage | 8219 | 18219 | EDGE |
| operations | 8220 | 18220 | EDGE |

## 2. 连接与发布前核对

以下连接命令在 Windows PowerShell 执行，密钥文件名来自已有部署记录。只使用自己有权限的本机密钥，不把密钥、环境文件或登录凭据提交到 Git。

```powershell
# 正式 EDGE
ssh.exe -o StrictHostKeyChecking=yes -o IdentitiesOnly=yes -i "$env:USERPROFILE/.ssh/XZWL_47_97_85_146.pem" root@47.97.85.146
# 正式 APP
ssh.exe -o StrictHostKeyChecking=yes -o IdentitiesOnly=yes -i "$env:USERPROFILE/.ssh/server_47_99_240_62" root@47.99.240.62
# TEST 124；文件名带 155，但实际目标是 115.29.222.124
ssh.exe -o StrictHostKeyChecking=yes -o IdentitiesOnly=yes -i "$env:USERPROFILE/.ssh/server_155_29_222_24" root@115.29.222.124
```

首次连接尚未登记的主机，应先通过可信渠道核对主机指纹。不要通过关闭主机密钥检查解决连接失败。

登录后先执行只读核对：

```bash
hostname -I
cat /etc/os-release
df -h /opt
java -version
# TEST 124 上执行
systemctl list-units 'xzwl-test-124-*.service' --all --no-pager
readlink -f /opt/xzwl-test-124/current
systemctl show mysql.service redis-server.service nacos.service -p Id -p MainPID
# 正式 EDGE 上执行
readlink -f /opt/xzwl/current/frontends
systemctl show xzwl-gateway.service -p ExecStart -p EnvironmentFiles -p FragmentPath
# 正式 APP 上执行
systemctl show xzwl-iam.service -p ExecStart -p EnvironmentFiles -p FragmentPath
```

分别在对应主机执行，不要整块跨环境照抄。保存本次实际指针、制品 SHA-256、配置版本及受影响服务列表；环境文件只备份到服务器受限目录，不在共享终端或日志输出内容。后端 JAR 路径以实际 `ExecStart` 为准，不根据前端的 `/opt/xzwl/current/frontends` 推测。

## 3. 构建和制品准备

### 固定源码与检查

在独立、干净的构建目录检出待发布提交。当前仓库 `origin` 是本机 `D:/cyw/project/xzwl-cloud`，不是 GitHub；`git push` 不等于部署，也不能据此确认已推送到 GitHub。

构建机需要 JDK 21、Node.js ≥ 22.18.0、npm、Git LFS；Maven Wrapper 固定 3.9.9。以下为 Linux/WSL Bash，Windows 使用 `mvnw.cmd` 替代 `./mvnw`。

```bash
git rev-parse HEAD
git status --short
git lfs pull
npm ci
npm run typecheck
npm test
npm run check:api-generation
npm run check:api-new-system
./mvnw test
./mvnw -DskipTests package
```

这些是发布检查命令，并不代表本文已执行通过。依赖数据库的测试须配置独立测试实例，检查 Surefire 报告中的失败和跳过数量；不能把 skipped 当作验收成功。`scripts/verify-release-integration.sh` 固定使用本机 13316 测试 MySQL，`verify-release-gateway-contract.sh` 也会创建测试库；只在专用构建/测试环境使用，不指向生产 3306。

SDK 校验失败时先修复并提交；不要在部署过程中自动重新生成合同或 SDK 来绕过检查。

### 后端制品

共发布 11 个业务应用和 Gateway，不发布 `service-template` 或 `compatibility-smoke-service` 作为业务环境应用。复制前检查 JAR 含 `BOOT-INF/`，不能把普通依赖 JAR 当可执行包。

| 服务 | Maven target 下的可执行 JAR，当前版本为 0.1.0-SNAPSHOT |
| --- | --- |
| gateway | `gateways/gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar` |
| iam、media、promotion、operations | `services/<名称>-service/<名称>-bootstrap/target/<名称>-bootstrap-0.1.0-SNAPSHOT.jar` |
| user、access、billing、payment、routing、canvas、storage | 同目录的 `<名称>-bootstrap-0.1.0-SNAPSHOT-exec.jar` |

打包时统一命名为 `jars/gateway.jar`、`jars/iam.jar` 等。版本号升级后以实际 POM/target 为准；清空独立构建目录中的旧产物，避免 glob 选中旧包。

### 前端构建参数

两个环境都通过各自的入口代理访问 API。admin 必须使用 `/admin` API 基址和 `admin/assets` 资源目录；API 控制台另走 `/api/session`。默认 `npm run build` 没有设置这整套部署布局，不能直接覆盖已有线上目录。

在仓库根目录以 Bash 执行以下构建片段，将 `DEPLOY_ENV` 设为 `test` 或 `prod`，产物位于忽略目录 `dist/deploy/web/`。测试与正式使用相同源码，分别构建测试标识；正式包不能含测试标识。

```bash
export DEPLOY_ENV=test
node --input-type=module <<'NODE'
import { build } from 'vite';
import { resolve } from 'node:path';
import { readFile, writeFile, mkdir, copyFile } from 'node:fs/promises';
const environment = process.env.DEPLOY_ENV;
if (!['test', 'prod'].includes(environment)) throw new Error('Set DEPLOY_ENV=test or prod');
for (const name of ['user', 'admin', 'partner']) {
  const outDir = resolve(`dist/deploy/web/${name}`);
  await build({
    root: resolve(`frontends/${name}-web`), base: '/', mode: 'production', envFile: false,
    define: {
      'import.meta.env.VITE_GATEWAY_BASE_URL': JSON.stringify(name === 'admin' ? '/admin' : ''),
      'import.meta.env.VITE_USER_WEB_URL': JSON.stringify('/'),
      'import.meta.env.VITE_LOGIN_URL': JSON.stringify(name === 'admin' ? '/admin/login' : '/user/login'),
      'import.meta.env.VITE_LOCAL_OTP_ENABLED': JSON.stringify('false'),
      'import.meta.env.VITE_PHONE_ONLY_LOGIN_ENABLED': JSON.stringify('false'),
    },
    build: { outDir, emptyOutDir: true, assetsDir: name === 'user' ? 'assets' : `${name}/assets` },
  });
  if (environment === 'test') {
    const index = resolve(outDir, 'index.html');
    const html = (await readFile(index, 'utf8'))
      .replace('<title>', '<title>[测试124] ')
      .replace('</body>', '<div style="position:fixed;right:12px;bottom:12px;z-index:2147483647;background:#fff3cd;color:#684a00;padding:8px;pointer-events:none">测试环境 · TEST 124 · 独立测试数据</div></body>');
    await writeFile(index, html);
  }
}
await mkdir('dist/deploy/web/user/assets', { recursive: true });
for (const name of ['NotoSansSC-OFL.txt', 'Nunito-OFL.txt']) {
  await copyFile(`frontends/user-web/src/assets/home-performance/${name}`, `dist/deploy/web/user/assets/${name}`);
}
NODE
```

前端环境变量写入构建结果，修改服务器环境文件不会改变已编译 JS。`VITE_*` 不得包含供应商密钥。保留 Git LFS 实体媒体、字体许可、Vite public 资源和带哈希的 assets；旧 `/homepage-v2/` 还有独立映射，见第 5 节。

### 发布包与清单

每次使用唯一发布号，例如日期加提交短 SHA 和环境名。包内布局：

```text
<发布号>/
  jars/                 # 本次需要部署的可执行 JAR
  web/user/
  web/admin/
  web/partner/
  manifest.json
  SHA256SUMS
```

清单记录完整 Git SHA、环境、构建命令、JDK/Node 版本、各文件哈希、已通过及未通过的检查。上传后先在 staging 验证 `sha256sum -c SHA256SUMS`，再放入新 release；压缩包成员不得包含绝对路径、`..` 或指向目录外的链接。包中不携带 `.env`、数据库数据、会话和凭据。

TEST 124 既有工具依赖 `manifest.json` 的 `git_head` 和 `services.<名称>.{sha256,size,native_nacos}`。打包时保留这一结构；`native_nacos` 按 JAR 内是否存在 Nacos discovery 依赖判定，不能凭服务名填写。只更新部分应用时，复制上一完整 release，再替换本次制品并更新完整清单。

## 4. 发布到 TEST 124

### 访问测试站点

推荐使用 SSH 隧道。在单独的 PowerShell 窗口运行并保持连接：

```powershell
ssh.exe -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o StrictHostKeyChecking=yes -i "$env:USERPROFILE/.ssh/server_155_29_222_24" -L 127.0.0.1:28080:127.0.0.1:18080 -L 127.0.0.1:28848:127.0.0.1:18848 -L 127.0.0.1:28082:127.0.0.1:18082 root@115.29.222.124
```

| 页面 | 隧道入口 |
| --- | --- |
| 用户端 | `http://127.0.0.1:28080/home` |
| 管理端 | `http://127.0.0.1:28080/admin/login` |
| API 控制台 | `http://127.0.0.1:28080/api/login` |
| 推广端 | `http://127.0.0.1:28080/promotion/overview` |
| 环境身份 | `http://127.0.0.1:28080/test-environment.json` |
| 测试 Nacos | `http://127.0.0.1:28848/nacos/` |

环境身份必须是 `TEST ONLY - xzwl-test-124`，且 `production_data=false`。公网端口为 32124/32125/32126；2026-10-10 的记录是本机访问成功、公网超时，不能据此认定安全组已放行。

### 升级步骤

1. 核对主机私网地址为 `172.31.218.45`，记录当前 release、测试配置和生产中间件基线。存在数据库迁移时，先备份测试 13306 上受影响的 `xzwl_test_*` 库，并确认恢复步骤。
2. 将包上传至 `/root/xzwl-test-124-stage/<发布号>/`。校验清单后，准备 `/opt/xzwl-test-124/releases/<发布号>/`；目录须包含完整 `jars/`、`web/` 和 manifest，文件对运行账号 `xzwl-test` 可读。
3. 核对新 JAR 与现有 `/etc/xzwl-test-124/` 配置兼容。测试沿用 `SPRING_PROFILES_ACTIVE=test` 和外置配置；源码没有 `application-test.yml`，仅指定 test profile 不足以启动环境。保留 `SERVER_PORT`、数据库 13306、Redis 16379、测试 Nacos namespace/group 及测试内部 token。
4. Nacos import 必须继续使用测试 common 配置和 `<名称>-test-124.yaml`；不能套用源码 prod profile 中写死的 `group=XZWL`。canvas、operations 历史制品不含 Nacos starter，由 `xzwl-test-124-registry.service` 补充注册，Gateway 对它们使用测试回环地址。
5. 在维护窗口内，暂停本次受影响的测试应用；涉及 Media 时先处理在途任务。原子切换测试 `current`，再启动受影响服务。纯前端更新无需重启 Java。
6. 核对测试元数据 `/opt/xzwl-test-124/environment-public.json` 中的提交及渠道状态；更新本次非敏感发布标识，保留实际渠道开关。不能复用初始安装时写着固定 OTP 开启的旧元数据。

以下是**已完成上述准备之后**的切换示例，在 TEST 124 的 Bash 中执行。`RELEASE_ID` 替换成本次实际发布号；不要填写历史示例版本。

```bash
set -euo pipefail
RELEASE_ID='REPLACE_WITH_TEST_RELEASE_ID'
[[ "$RELEASE_ID" != REPLACE_* && "$RELEASE_ID" =~ ^[A-Za-z0-9._-]+$ ]]
test -L /opt/xzwl-test-124/current
PREVIOUS_TEST=$(readlink -f /opt/xzwl-test-124/current)
NEXT_TEST="/opt/xzwl-test-124/releases/$RELEASE_ID"
test -d "$NEXT_TEST/jars" && test -f "$NEXT_TEST/web/user/index.html"
test "$(readlink -f "$NEXT_TEST")" = "$NEXT_TEST"
# 先把 PREVIOUS_TEST 保存到本次受限发布记录中，再切换。
printf '%s\n' "$PREVIOUS_TEST"
test ! -e /opt/xzwl-test-124/current.next && test ! -L /opt/xzwl-test-124/current.next
ln -s "$NEXT_TEST" /opt/xzwl-test-124/current.next
mv -Tf /opt/xzwl-test-124/current.next /opt/xzwl-test-124/current
# 示例：本次只修改 IAM；其他改动按实际服务列表逐一启动。
systemctl restart xzwl-test-124-iam.service
systemctl is-active xzwl-test-124-iam.service
```

完整后端更新建议先恢复 user/iam/access、routing/storage/billing/promotion，再恢复 payment/media/canvas/operations，最后 gateway；每批确认就绪再继续。顺序不能代替内部依赖、迁移和业务验证。

测试 Web 使用独立 Nginx 二进制；修改其配置时运行：

```bash
runuser -u xzwl-test -- /opt/xzwl-test-124/tools/nginx -t -p /opt/xzwl-test-124/ -c /etc/xzwl-test-124/nginx.conf
systemctl restart xzwl-test-124-web.service
```

必须以 Web 服务用户 `xzwl-test` 检查配置。以 root 执行 `nginx -t` 会把请求体等临时目录的属主改为默认用户 `nobody`，使较大的价格保存和上传请求返回 500。若已发生，只修正 `/opt/xzwl-test-124/run/` 下 `client_body`、`proxy`、`fastcgi`、`uwsgi`、`scgi` 五个临时目录为 `xzwl-test:xzwl-test`；无需重启服务。发布后运行 `node deployment/test-124/scripts/verify-request-body-buffering.mjs http://115.29.222.124:32124`，确认大请求可以到达认证层；该检查不会保存价格。

仅替换静态目录且配置没变时不必重启 Web。不要为测试发布重载原生产 Nginx，也不要重启生产 mysql/redis-server/nacos。

### 测试环境的渠道状态

2026-10-10 配置同步后的约定：固定 OTP 和手机号免验证码登录关闭；支付 provider 为 `disabled`；IAM 短信出网已开启；Media/TOS 使用同步的生产供应商账号及存储桶。因此“独立测试数据”不代表外部渠道也是沙箱，真实短信、生成和上传会使用实际供应商资源。

配置文件中的 MySQL、Redis、Nacos、内部地址及凭据必须继续独立。生产配置同步只允许经过核对的配置表白名单，不导入生产用户、订单、余额、任务或会话。当前精简分支没有完整同步工具，常规版本发布不自动同步生产配置。

不要重跑历史 `install.py configure`、首次 `activate.py` 或固定 OTP 的 `verify.py`：首次激活拒绝覆盖已有 current，初始化还会与后续渠道配置冲突。历史服务器上若仍保留 `/root/xzwl-test-124-stage/verify-production-config.py`，先审阅其适用版本，再作为补充核对；正常验收按第 6 节执行。

## 5. 发布到正式环境

### 发布条件

测试通过的源码 SHA 与正式候选一致，后端使用测试过的同一 JAR 哈希；正式前端仅改变已记录的环境标识/部署参数并重新验证。已备份受影响服务配置、Nacos 配置、数据库和旧制品，确认数据库向后兼容性。数据库迁移由服务启动时的 Flyway 执行，不关闭校验、不手工篡改 schema history 来掩盖冲突。

正式默认使用 `prod` 配合生产 Nacos；`prod-fixed` 是关闭 discovery/config 的另一配置路径，不是测试模式。以实际 systemd 和配置中心设置为准，不在升级时擅自切换 profile。`prod` 配置需要 `SERVICE_BIND_ADDRESS`、Nacos 凭据和发布 revision 元数据；固定地址模式还需明确服务间 URL 与 Gateway upstream，避免默认回退到本机服务或未启用的 `lb://`。

### 仅发布前端

在 EDGE 操作。正式前端入口指针是 `/opt/xzwl/current/frontends`，目标位于 `/opt/xzwl/frontend-releases/<发布号>/sms`。不要把它与后端总 release 指针混为一谈。

1. 记录 `readlink -f /opt/xzwl/current/frontends`，备份现有 Nginx 配置；记录两台正式应用主机的服务 PID，发布后应保持不变。
2. 上传并校验正式前端包。在新的 `.../<发布号>/sms` 目录准备 `user/` 和 `admin/`；保留当前指针下其他应用和链接，未改动端不要用空目录覆盖。partner 是否对外发布以现场 Nginx 映射为准，不能只上传就宣称已上线。
3. 新目录中保留上一版带哈希的 JS/CSS/媒体，再覆盖本次新文件，避免已打开的页面加载旧 chunk 时立即 404。可先 `cp -a` 复制现有目录，但检查继承链接，确保覆盖文件时不会沿链接修改旧 release；不要对与旧版硬链接共享的文件原地写入。
4. 清理或重新生成本次被替换文件对应的旧 `.gz`/`.br`，避免 Nginx 返回过期压缩内容。检查页面引用的全部资源、字体许可及视频 Range 响应。
5. 原子切换**前端指针**，执行第 6 节正式验收。配置未改动时无需重启 Java，也无需 reload Nginx。

切换命令结构与测试相同，但目标严格限定为前端目录：

```bash
set -euo pipefail
RELEASE_ID='REPLACE_WITH_PROD_FRONTEND_RELEASE_ID'
[[ "$RELEASE_ID" != REPLACE_* && "$RELEASE_ID" =~ ^[A-Za-z0-9._-]+$ ]]
POINTER=/opt/xzwl/current/frontends
PREVIOUS_FRONTEND=$(readlink -f "$POINTER")
NEXT_FRONTEND="/opt/xzwl/frontend-releases/$RELEASE_ID/sms"
test -L "$POINTER"
test -f "$NEXT_FRONTEND/user/index.html" && test -f "$NEXT_FRONTEND/admin/index.html"
test "$(readlink -f "$NEXT_FRONTEND")" = "$NEXT_FRONTEND"
printf '%s\n' "$PREVIOUS_FRONTEND"
test ! -e "$POINTER.next" && test ! -L "$POINTER.next"
ln -s "$NEXT_FRONTEND" "$POINTER.next"
mv -Tf "$POINTER.next" "$POINTER"
```

正式 admin 构建使用 base `/`、assetsDir `admin/assets`、`VITE_GATEWAY_BASE_URL=/admin`。保留用户、管理员、API 和代理代入入口的独立 Cookie/CSRF 映射。

仓库保留了 [API 会话 maps](../deployment/aiyomio/nginx/api-session-maps.conf) 和 [locations](../deployment/aiyomio/nginx/api-session-locations.conf)：前者属于 Nginx http 层，后者属于 `aiyomio.cn` TLS server 且应在 SPA 正则之前。它们是增量片段，不是完整站点配置；已 include 时不要重复安装。保留 `/admin/api/v1`、`/agent/...`、支付回调和资源映射。配置变更先备份，再 `nginx -t`，成功后 `systemctl reload nginx`。

`/homepage-v2/` 历史资源由 `/opt/xzwl/homepage-asset-releases/current` 单独承载，不能随普通前端发布删除。当前 [首页安装脚本](../deployment/aiyomio/scripts/install-homepage-assets.py) 硬编码了历史发布日期及旧 Nginx 文本匹配，是一次性修复脚本，不能当通用升级器直接重跑。Git archive 也可能取得 LFS 指针而非媒体实体，制作旧资源包时必须验证实际字节。

### 发布后端

在对应 EDGE/APP 主机按服务发布，保留现有 systemd 参数、运行账号、环境文件、Nacos 和内部 token。当前精简仓库没有完整生产安装器，以下是对**已有服务**的升级流程，不是新机器初始化脚本。

1. 在目标主机运行 `systemctl show xzwl-<名称>.service -p ExecStart -p EnvironmentFiles`，确认实际 JAR、参数和配置来源；对 JAR 路径执行 `readlink -f`，分辨它是否跟随共享 release 指针。
2. 将新 JAR 放到本次独立 staging/release，核对哈希和可执行结构。保留完整旧 JAR、配置及数据备份，记录恢复路径。
3. 在维护窗口停止受影响服务。Media 先停止接收新任务并等待/记录在途任务，Payment 核对在途订单与回调，不能靠重启抹掉处理中状态。
4. 如果已有逐服务发布指针，使用该机制原子切换；如果 ExecStart 是固定 JAR 路径，则在停止后以同目录临时文件替换，保留旧文件权限/属主。若路径位于不可变 release 内，应创建新 release，并更新该服务的启动路径；不要就地修改声称不可变的历史版本。
5. 只有 unit/drop-in 改动才执行 `systemctl daemon-reload`；配置更改与 JAR 更改一并记录。启动明确的 `xzwl-<名称>.service`，检查日志中的配置加载、Flyway 和就绪结果，再继续下一个服务。
6. 先升级兼容的依赖服务，再升级调用方，Gateway 路由变更与后端能力协调发布；前端最后切换。单实例重启存在短暂中断，不能将该流程描述为零停机。

例如 Media 的日志/状态操作只在 EDGE 执行：

```bash
systemctl start xzwl-media.service
systemctl is-active xzwl-media.service
journalctl -u xzwl-media.service -n 100 --no-pager
```

日志检查应在受限终端完成，分享前去除凭据、Cookie、验证码及个人数据。canvas、operations 当前没有 Actuator 依赖，应检查它们真实业务接口的预期鉴权响应与迁移结果，不虚构 `/actuator/health`。

## 6. 发布验收

### 不产生业务费用的基础核对

- 服务存活、监听端口和 Nacos 注册符合环境；`is-active` 仅代表进程存在，不能替代 API 验收。
- 测试首页显示 TEST 124，环境 JSON 的 `production_data=false`，版本与本次清单一致；正式首页不含测试标识。
- 用户、管理员、API 控制台、推广/代理入口按实际部署范围打开；HTML 返回 200，JS/CSS/字体/图片返回正确 Content-Type，不能被 SPA fallback 替换为 HTML。
- 视频 Range 请求返回 206 和正确 Content-Range；检查压缩资源是否与本次文件一致。
- 分别 GET `/api/v1/auth/session`、`/admin/api/v1/auth/session`、`/api/session/api/v1/auth/session`，应返回 JSON，匿名会话为 `authenticated=false`。每个检查使用独立 Cookie 容器。
- 受保护业务接口匿名访问应返回预期 401/403；`/internal/**` 和 `/api/v1/internal/**` 经公网网关应返回 404。不能只检查登录页 200。
- 正式 API Cookie 使用 `XZWL_API_SESSION`、Path `/api`，保留 Secure/HttpOnly/SameSite；测试 user/admin/API 分别使用 `XZWL_TEST124_USER_SESSION`、`XZWL_TEST124_ADMIN_SESSION`、`XZWL_TEST124_API_SESSION`。测试 HTTP 的 Secure 配置不能复制到正式 HTTPS。

示例命令只读取匿名接口：

```bash
# TEST 124 本机
curl -fsS http://127.0.0.1:18080/test-environment.json
curl -fsS http://127.0.0.1:18080/api/v1/auth/session
# 正式 EDGE 本机，使用真实域名校验 TLS 与站点路由
curl -fsS --resolve aiyomio.cn:443:127.0.0.1 https://aiyomio.cn/api/v1/auth/session
curl -fsS --resolve aiyomio.cn:443:127.0.0.1 https://aiyomio.cn/admin/api/v1/auth/session
curl -fsS --resolve aiyomio.cn:443:127.0.0.1 https://aiyomio.cn/api/session/api/v1/auth/session
```

再从发布机执行一次真实公网域名检查，并用浏览器检查桌面/手机页面、控制台错误、深链接刷新及静态资源。服务器本机通过不代表公网或浏览器通过。

### 功能验收与完成记录

使用明确授权的测试账号验收登录、CSRF、会话互不覆盖、退出、角色权限和本次改动的业务功能。真实短信、对象上传、媒体生成与支付分别记录验收范围；TEST 124 支付当前禁用，不因发布顺便开启。固定 OTP 已关闭，不能继续使用旧测试验证码。

后端版本还需记录 Flyway 成功、数据兼容、在途任务/幂等重试结果。测试发布后对比生产中间件 PID/配置哈希；正式纯前端发布后对比两台应用机 PID 与 Nginx 配置哈希。每个发布记录包含：提交、制品哈希、配置版本、旧/新指针、操作时间、验证结果、未完成项、回滚入口。未通过的检查保留原始结论。

## 7. 回滚

**纯前端：** 使用本次发布前记录的完整旧目标路径，按第 5 节同样的临时链接加 `mv -Tf` 恢复 `/opt/xzwl/current/frontends`。同时恢复本次确实修改的 Nginx 配置，`nginx -t` 后 reload。不要误切后端 `/opt/xzwl/current`；旧哈希资源和首页独立资源目录保留到确认不再被客户端引用。

**测试：** 将 `/opt/xzwl-test-124/current` 原子恢复到本次记录的旧测试 release，仅重启本次受影响的 `xzwl-test-124-<名称>.service`；同步恢复本次修改的测试配置、Nacos 配置和环境版本元数据。不能恢复生产目录或操作生产 3306。

**正式后端：** 停止本次受影响服务，恢复它的旧 JAR/启动指针及匹配配置，再启动并重新验收。若同时改过 unit，恢复后 daemon-reload。保持其他服务不动；跨服务变更按已验证的兼容顺序退回。

**数据库：** 回滚 JAR 不会撤销 Flyway。先判断旧代码是否兼容新 schema；不兼容时优先前向修复或执行已演练的恢复方案。恢复备份会覆盖备份后的写入，需先停止相关写入并明确恢复时间点，不能自动执行 SQL down migration。回滚后重复第 6 节验收并保存结果。

## 8. 依据与历史工具位置

- 当前代码：[根说明](../README.md)、[Gateway 接入规则](release-gateway-contract.md)、各模块 `application*.yml`、POM、前端 Vite 配置和 `deployment/aiyomio/nginx/`。
- “寻知未来-cyw”对话：`启动本地前后端项目`、`将页面背景换成首页视频`、`连接到指定服务器`；分别用于核对正式站点、发布指针和 1528 环境区别。
- 原工作区 `D:/cyw/project/xzwl-cloud/deployment/test-124/README.md`、`install.py`、`sync-production-config.py`、`read-production-config.py`：TEST 124 端口、服务、数据隔离及正式主机分工。
- 原工作区 `D:/cyw/project/xzwl-cloud/deployment/aiyomio/README.md`：正式前端布局和会话映射；`D:/cyw/project/deployments/ui-migration-146/` 保存历史分次发布脚本。

这些原工作区路径是取证位置，不是当前仓库随附工具。历史脚本包含固定提交、指针和前置哈希，不能换个目录直接复用。新机器首次安装需要另行准备中间件、systemd、TLS、数据库账户、配置中心和外部渠道配置；本操作文件覆盖已有环境的日常发布与回滚。
