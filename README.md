<p align="center">
  <!-- 图标占位，后续添加 -->
  <!-- <img src="docs/images/arp-banner.png" alt="ARP Banner" width="60%"> -->
</p>

<h1 align="center">Agent Reverse Proxy (ARP)</h1>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="GPLv3 License"></a>
  <a href="https://github.com/KaiXuanSama/ARP"><img src="https://img.shields.io/badge/GitHub-KaiXuanSama%2FARP-181717?logo=github" alt="GitHub Repo"></a>
</p>

<p align="center">
  本地多账号池 + 反向代理 + B 端 API Key 派发，专为 CodeBuddy 设计的轻量级网关。
</p>

<p align="center">
  同时提供 <strong>OpenAI</strong> 与 <strong>Anthropic</strong> 两套兼容协议端点，
  支持 Claude Code / OpenAI SDK / Anthropic SDK 直接接入。
</p>

<p align="center">
  <strong>注意：本项目与 Tencent / CodeBuddy 官方无任何关联，仅供学习研究使用。</strong>
</p>

## 快速开始

ARP 提供两种部署形态:Windows 本地直接跑(JDK + Maven Wrapper),Linux 服务器跑 Docker(Compose 一键起)。

### 方式一:Windows 本地运行(开发/单用户使用)

> **环境要求**
> - **JDK 17 或更高**(项目使用 `java.version=17`;OpenJDK / Temurin / Oracle 都行)
>   - 没有 Java?装 [Eclipse Temurin 17](https://adoptium.net/temurin/releases/?version=17) 并把 `JAVA_HOME` 加到 `PATH`
> - Node 22(由 `frontend-maven-plugin` 在首次构建时自动下载,**无需手动装**)
> - Git(仅克隆仓库用)

**1. 克隆仓库**
```powershell
git clone https://github.com/KaiXuanSama/ARP.git
cd ARP
```

**2. 启动服务(首次会自动构建前端,会跑 1-3 分钟)**
```powershell
.\mvnw.cmd spring-boot:run
```

或者先打 jar 再跑(产物在 `target\agentreproxy-0.0.1-SNAPSHOT.jar`):
```powershell
.\mvnw.cmd clean package -DskipTests
java -jar target\agentreproxy-0.0.1-SNAPSHOT.jar
```

**3. 访问管理后台**

打开 <http://localhost:8351>。数据(SQLite 数据库 `agentreproxy.db`)默认落在项目根目录;模型目录从上游实时拉取、只存内存,不落盘。

> **小贴士**
> - PowerShell 里务必用 `.\mvnw.cmd` 而不是 `mvnw`,直接打 `mvnw` 在 PowerShell 下不会走 `.cmd` 后缀
> - 端口冲突?改 `src\main\resources\application.yml` 里的 `server.port`
> - 想用本地 CodeBuddy `.info` 文件自动导入账号?确认你的 Windows `%LOCALAPPDATA%\CodeBuddyExtension\Data\Public\Auth\workbuddy-desktop.info` 存在
> - 首次启动请先在管理面板「添加账户」导入账号 —— 模型目录依赖账号凭证从上游拉取,无账号时 `/v1/models` 会返回 503

### 方式二:Linux 服务器 Docker 部署(生产/共享使用)

> **环境要求**
> - Linux 服务器(Ubuntu 22.04+ / Debian 12+ / CentOS 9+ 都行)
> - **Docker 24+** 与 **Docker Compose v2**(新版 Docker Desktop / Docker Engine 已自带 `docker compose` 子命令)

**1. 把仓库拉到服务器**
```bash
git clone https://github.com/KaiXuanSama/ARP.git
cd ARP
```

**2. 一键启动**

默认配置:数据全部落到 `./Config/`(SQLite 数据库;模型目录只存内存,无文件)。
```bash
docker compose up -d --build
```
首次构建会从源码编译 Spring Boot jar(包含前端 Vue 构建),约 5-10 分钟;后续 `docker compose up -d` 直接复用镜像缓存,秒级启动。

> **构建期的下载源已默认走国内镜像**
>
> `frontend-maven-plugin` 需要下载 Node.js 二进制(npm 随 node 一起,无需单独下载)。
> 默认源 `nodejs.org` 在国内服务器/容器内**不通**(实测报
> `Unknown host nodejs.org: Temporary failure in name resolution`)。
> 因此在 `pom.xml` 的 properties 里默认指向 `registry.npmmirror.com`:
>
> ```xml
> <node.download.root>https://registry.npmmirror.com/-/binary/node/</node.download.root>
> <npm.registry.url>https://registry.npmmirror.com</npm.registry.url>
> ```
>
> 海外环境想换回官方源,三种方式任选:
> ```bash
> # ① Maven 命令行覆盖(本地构建)
> ./mvnw -B clean package -Dnode.download.root=https://nodejs.org/dist/ \
>                           -Dnpm.registry.url=https://registry.npmjs.org/
>
> # ② Docker build args(已在 Dockerfile 声明 ARG)
> #    docker-compose.yml 的 build 段加:
> #      args:
> #        NODE_DOWNLOAD_ROOT: https://nodejs.org/dist/
> #        NPM_REGISTRY_URL: https://registry.npmjs.org/
>
> # ③ 直接改 pom.xml 的两个 property
> ```
>
> 想换其他镜像(如华为云 `https://mirrors.huaweicloud.com/nodejs/`)同理,
> 只要保持 `{root}v22.14.0/node-v22.14.0-linux-<arch>.tar.gz` 的路径结构。

**3. 查看实时日志(可选)**
```bash
docker compose logs -f app
```

**4. 访问服务**

打开 `http://<服务器IP>:8351`。容器会把主机的 `8351` 端口映射到容器内 `8351`。

> 💡 **「扫码登录」无需任何额外配置**:新链路(插件授权流)由服务端直接与上游交互领取凭证,
> 登录链接是官方地址,凭证不经浏览器 —— 因此不依赖本服务的对外地址,也无需 CORS 配置。

**5. 升级到新版本**
```bash
cd ARP
git pull
docker compose up -d --build
# 你的 ./Config/agentreproxy.db 不会丢(在 volume 外,但 bind mount 保留)
```

**6. 备份与迁移**

整个 `./Config/` 目录就是你所有持久化状态。要备份:
```bash
tar czf arp-backup-$(date +%Y%m%d).tar.gz Config/
```
要恢复:把 `Config/` 放回原位,`docker compose up -d` 即可。

**7. 常用维护命令**
```bash
docker compose ps              # 看容器状态
docker compose restart app     # 重启服务
docker compose down            # 停服(不删数据)
docker compose down -v         # 停服 + 清空 Config(危险,会丢数据库)
```

### 端口与目录速查

| 项 | Windows 本地 | Docker 部署 |
|---|---|---|
| 服务端口 | `8351` | `8351`(主机→容器) |
| 数据库位置 | `./agentreproxy.db` | `./Config/agentreproxy.db` |
| 模型目录 | 内存(上游实时拉取,无文件) | 内存(上游实时拉取,无文件) |
| 工作目录 | 项目根 | `/app`(容器内),`./`(主机) |

## 模型目录

ARP 的模型清单**不再依赖任何静态配置文件**,而是直接从上游真实接口拉取
(`GET copilot.tencent.com/v3/config`,以账号凭证 + 官方 CLI 伪装头调用),
过滤掉图像/视频等非聊天模型后保存在**内存快照**里。

### 目录怎么更新

| 时机 | 行为 |
|---|---|
| 服务启动 | 自动拉取一次(异步,不阻塞启动;失败仅记日志,目录留空) |
| 手动更新 | 管理面板 → 系统设置 → 模型列表 → **「更新模型列表」**按钮 |
| 其它 | **不会再自动获取** —— 没有定时器、没有 TTL,把刷新时机完全交给管理者 |

手动更新时,服务会把**全部启用账号随机洗牌后顺序尝试**,单个账号失败
(凭证过期 / 上游拒绝 / 网络错误)自动跳过换下一个,**第一个成功即止**;
全部失败时保留旧目录不动。

### 查看模型的完整能力与约束

管理面板的「查看模型列表」按钮会弹出**上游原始数据**表格 —— 包含每个模型的
计费倍率(credits)、最大输入/输出 token、推理能力档位、标签等全部字段,
展开任意一行可查看该模型的完整 JSON。目录为空时该按钮置灰不可点。

### 目录为空时的行为

- `/v1/models` 返回 **503**(提示"模型目录不可用")—— 宁可诚实报错,不返回假清单
- 下游 Key 的模型白名单与目录**严格取交集**:目录为空时交集为空,
  白名单里的残留模型名不会"救活" —— 需要先更新目录

### 从旧版(modelsConfig.json)升级

v1.5.0 起静态配置机制已整体移除,`modelsConfig.json` 不再被读取,
项目根或 `Config/` 下的旧文件可以删除。首次启动新版时会自动从上游拉取真实目录。

## API 端点

服务对外提供 **1 个 Web 管理页** + **两套兼容协议端点**（OpenAI 与 Anthropic），后两者共用同一套下游 Key 鉴权、选号与模型白名单逻辑。

### 1. Web 管理页

- **访问**:`http://localhost:8351/`(Docker 部署换成服务器 IP)
- **鉴权**:需登录(默认用户名/密码均为 `root`,**首次登录后请立即在「系统设置 → 账号管理」中修改**)
- 账号管理、签到、流量包、下游 API Key 派发、模型目录(查看/更新)、调度设置、请求文本替换都在这里

> 登录态是内存中的 token(12 小时有效期),**服务重启后需重新登录**。
> 忘记密码:删除数据库 `app_settings` 表中 `admin.credential` 这一行,重启服务会重新写入默认 `root/root`。

#### 添加上游账号

「上游账号管理 → 添加账户」提供四种方式,推荐**扫码登录**:

| 方式 | 适用场景 |
|---|---|
| **扫码登录**(推荐) | 手机微信扫码,凭证由服务端自动领取导入。无需本机客户端,最简单可靠 |
| 本机获取 | 读取本机已登录的 CodeBuddy 凭证(仅对**未加密**的旧版 info 文件有效) |
| 文件导入 | 手动选择 `workbuddy-desktop.info` 文件(同上,仅未加密文件有效) |
| 手动输入 API Key | 只有 API Key 时使用 |

**扫码登录流程**(插件授权流):点「扫码登录」→ 点「打开登录页」用手机微信扫码
→ 保持弹窗开启,服务端自动检测登录完成并领取凭证落库,弹窗随即自动关闭、账号出现在列表里。

> 原理与安全:服务端伪装官方 CLI 调用 CodeBuddy 的插件授权接口
> (`/v2/plugin/auth/*`),登录链接是官方地址,凭证由服务端直接领取、
> **不经过浏览器**(无需书签脚本、无跨域回传)。唯一需要留意的是
> **不要使用他人发来的登录链接**——只用你自己打开的本服务页面生成的链接。
>
> 凭证只保存在你自己部署的实例里(本地 SQLite),作者与任何第三方都无法访问。

#### 凭证有效期

账号列表有「凭证有效期」列,显示剩余可用时间(accessToken 约 55 天有效)。
**剩余 ≤ 7 天时橙色高亮,过期时红色**,页面顶部也会出现汇总提醒并可一键跳去重新获取 ——
避免某天账号突然静默失效却找不到原因。API Key 账号无过期概念,该列显示 `-`。

### 2. OpenAI · 流式对话

- **路径**:`POST /v1/chat/completions`
- **鉴权**:`Authorization: Bearer <API Key>`(在管理后台 → 下游 Key 里创建,形如 `ak-` + 32 字符)
- **响应**:`text/event-stream`(SSE)
- **最小请求体**:`{"model": "auto", "messages": [...至少 2 条...], "stream": true}`
- 鉴权失败/账号失效等错误按 OpenAI 风格返回(401/400 + `{error: {code, type, message}}`)

```bash
curl http://localhost:8351/v1/chat/completions \
  -H "Authorization: Bearer ak-xxxxxxxxxxxxxxxxxxxxxxxxxxxx" \
  -H "Content-Type: application/json" \
  -d '{"model":"auto","messages":[{"role":"system","content":"你是助手"},{"role":"user","content":"你好"}],"stream":true}'
```

> 用 OpenAI 官方 SDK / LangChain / cursor / Cherry Studio 等客户端时,把 `base_url` 指向 `http://localhost:8351/v1`、`api_key` 填 `ak-...` 即可,其余用法跟 OpenAI 完全一样。

### 3. OpenAI · 模型列表

- **路径**:`GET /v1/models`
- **鉴权**:同上,`Authorization: Bearer <API Key>`
- 返回该 API Key `supportedModels` 白名单内的模型(没配白名单时返回当前模型目录全集;目录为空时本端点返回 503)

```bash
curl http://localhost:8351/v1/models \
  -H "Authorization: Bearer ak-xxxxxxxxxxxxxxxxxxxxxxxxxxxx"
```

### 4. Anthropic · 对话

- **路径**:`POST /v1/messages`
- **鉴权**:`x-api-key: <API Key>`(Anthropic SDK 默认头)或 `Authorization: Bearer <API Key>`
- **响应**:`stream: true` 时返回 SSE;否则聚合为完整 Message JSON
- 错误按 Anthropic 风格返回(`{"type":"error","error":{"type","message"}}`)

```bash
# 流式
curl http://localhost:8351/v1/messages \
  -H "x-api-key: ak-xxxxxxxxxxxxxxxxxxxxxxxxxxxx" \
  -H "Content-Type: application/json" \
  -d '{"model":"deepseek-v4-flash","max_tokens":128,"stream":true,
       "messages":[{"role":"user","content":"你好"}]}'

# 非流式(服务内部聚合)
curl http://localhost:8351/v1/messages \
  -H "x-api-key: ak-xxxxxxxxxxxxxxxxxxxxxxxxxxxx" \
  -H "Content-Type: application/json" \
  -d '{"model":"deepseek-v4-flash","max_tokens":128,
       "messages":[{"role":"user","content":"你好"}]}'
```

**Claude Code 接入**:设置环境变量即可(`ANTHROPIC_BASE_URL` 指向本服务):

```bash
export ANTHROPIC_BASE_URL=http://localhost:8351
export ANTHROPIC_API_KEY=ak-xxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

Anthropic 官方 SDK(`anthropic-python` / `@anthropic-ai/sdk`)同样把 `base_url` 指向本服务、
`api_key` 填 `ak-...` 即可,流式与非流式两种用法都支持。

> **计费说明**:该端点默认走「桥接模式」——请求转换为 OpenAI 格式调上游,以便拿到上游返回的
> `credit` 计费信息。因此 `used_credits` 累加与 `credit_limit` 限制**均正常工作**。
> 如需改为直连上游 Anthropic 端点(零转换但无计费),设 `custom.anthropic.bridge-via-openai=false`。
>
> **模型名注意**:当前模型目录(上游拉取)中**没有 Claude 系列模型**,请填目录里实际存在的 model id
> (如 `deepseek-v4-flash`,可在管理面板「查看模型列表」确认)。填 `claude-*` 会撞上游 `11102` 错误。

---

## ⚠️ 免责声明

**本项目(ARP / Agent Reverse Proxy)是一个非官方的开源工具，与腾讯公司、CodeBuddy 团队及其关联公司无任何形式的关联、合作、授权或背书关系。** 项目名称、相关描述及使用场景仅用于技术说明，不代表任何官方立场。

请在使用本工具前仔细阅读以下条款:

1. **仅供学习研究使用**。本项目仅用于个人学习、协议研究、安全测试等合法用途,严禁用于商业转售、批量账号养号、刷量、绕过平台风控等违反上游服务条款的行为。
2. **服务条款风险自负**。使用本工具访问的上游服务(CodeBuddy / Tencent CodeBuddy 等)均有其用户协议与服务条款。使用本工具产生的任何账号封禁、功能限制、数据丢失、费用扣除等后果,由使用者自行承担,与本项目及作者无关。
3. **数据归属使用者**。本项目不收集、不上传任何用户数据。SQLite 数据库、API Key、账号凭证等全部保存在使用者本地或自托管的服务器上,作者无法访问。
4. **无任何保证**。本项目按"现状"提供,不承诺稳定性、可用性、安全性。使用前请自行评估风险,建议仅在隔离环境或测试账号上使用。
5. **不构成法律意见**。本免责声明不构成任何法律建议。如所在司法辖区对逆向工程、API 转发等行为有特殊规定,请咨询当地法律专业人士。

如你不同意上述任何条款,**请立即停止使用并删除本项目的所有副本**。
