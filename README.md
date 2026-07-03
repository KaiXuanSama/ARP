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

打开 <http://localhost:8351>。所有数据(SQLite 数据库 `agentreproxy.db`、模型配置 `modelsConfig.json`)默认落在项目根目录。

> **小贴士**
> - PowerShell 里务必用 `.\mvnw.cmd` 而不是 `mvnw`,直接打 `mvnw` 在 PowerShell 下不会走 `.cmd` 后缀
> - 端口冲突?改 `src\main\resources\application.yml` 里的 `server.port`
> - 想用本地 CodeBuddy `.info` 文件自动导入账号?确认你的 Windows `%LOCALAPPDATA%\CodeBuddyExtension\Data\Public\Auth\workbuddy-desktop.info` 存在

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

默认配置:数据全部落到 `./Config/`,包含 SQLite 数据库和模型配置文件。
```bash
docker compose up -d --build
```
首次构建会从源码编译 Spring Boot jar(包含前端 Vue 构建),约 5-10 分钟;后续 `docker compose up -d` 直接复用镜像缓存,秒级启动。

**3. 查看实时日志(可选)**
```bash
docker compose logs -f app
```

**4. 访问服务**

打开 `http://<服务器IP>:8351`。容器会把主机的 `8351` 端口映射到容器内 `8351`。

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
| 模型配置 | `./modelsConfig.json` | `./Config/modelsConfig.json` |
| 工作目录 | 项目根 | `/app`(容器内),`./`(主机) |
| 修改后生效 | 重启进程 | 改 `Config/*.json` 需 `docker compose restart app` |

## 模型配置

ARP 把"对消费者暴露哪些模型"做成了一份**外部 JSON 配置**。这样你可以:

- 增删模型不用重新编译
- 不同部署(本地 / 服务器)用不同模型清单
- 内置一份兜底,第一次启动就有内容

### 配置文件在哪

| 部署形态 | 路径 | 说明 |
|---|---|---|
| Windows 本地 | `<项目根>\modelsConfig.json` | Spring 工作目录下的相对路径,默认存在一份(由 classpath 模板生成) |
| Docker 部署 | `./Config/modelsConfig.json` | 通过 `docker-compose.yml` 的 volume 挂载到容器内 `/data/modelsConfig.json` |

> **首次启动会怎样?** 如果上面两个路径都没有 `modelsConfig.json`,服务会回退到 classpath 内置的 [src/main/resources/models-config.default.json](src/main/resources/models-config.default.json),保证 `/v1/models` 至少返回一份默认清单。

### 文件格式

```json
{
  "_comment": "随便写,这行会被忽略",
  "models": [
    { "id": "auto",            "family": "virtual", "contextLength": 172032 },
    { "id": "deepseek-v4-pro", "family": "deepseek", "contextLength": 1048576 }
  ]
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | ✅ | 模型 ID,会出现在 `/v1/models` 和 `/v1/chat/completions` 的 `model` 字段 |
| `family` | ❌ | 厂商/家族名(仅作分组提示,不影响路由) |
| `contextLength` | ❌ | 上下文窗口(token 数),给客户端参考 |

顶层允许任意字段(Jackson 默认忽略未知字段),所以 `_comment` 之类的注释字段不会报错。

### 怎么自定义模型清单

**方式 A:直接编辑配置文件(推荐)**

- Windows:编辑 `<项目根>\modelsConfig.json`,然后重启服务(`Ctrl+C` 停掉再 `.\mvnw.cmd spring-boot:run`)
- Docker:编辑 `./Config/modelsConfig.json`,然后 `docker compose restart app`

**方式 B:以模板为基础**

仓库自带一份完整的内置模板,你可以复制它作为起点:

- 路径:[src/main/resources/models-config.default.json](src/main/resources/models-config.default.json)
- 这份文件随 jar 一起发布,改它不会影响正在运行的服务(只对"没有外部配置"时生效)
- 改完的副本**另存为** `modelsConfig.json` 才生效(外部配置优先于内置)

**方式 C:用环境变量 / 命令行指向别处的配置**

不一定要把配置放项目根,可以把多个部署的模型清单集中放:

```powershell
# Windows PowerShell:指向 D:\conf\modelsConfig.json
$env:MODELS_CONFIG_PATH = "D:\conf\modelsConfig.json"
.\mvnw.cmd spring-boot:run

# Linux:指向 /etc/agentreproxy/modelsConfig.json
MODELS_CONFIG_PATH=/etc/agentreproxy/modelsConfig.json docker compose up -d
```

或命令行:

```bash
java -jar app.jar --models.config.path=/etc/agentreproxy/modelsConfig.json
```

> **加载时机**:Spring 启动时一次性读入内存,运行期改文件不会自动重载。改了之后**必须重启**服务才生效。

### 验证配置已生效

启动后访问 `<服务地址>/v1/models`,返回的 JSON 里 `data[].id` 应该跟你配置文件里的一致。如果不一致:

1. 检查路径是否对(Windows 大小写不敏感,L/Docker 大小写敏感,见 [AGENTS.md](AGENTS.md) 中"Models config file-name casing trap"提示)
2. 看启动日志里 `ModelsConfigService` 输出的"加载了 N 个模型"
3. JSON 写错会**让服务启动失败**(故意如此,避免线上偷偷 200 但缺模型),看 `docker compose logs app` 找原因

## API 端点

服务中常用的 3 个端点:1 个 Web 管理页 + 2 个 OpenAI 兼容端点(下发给 B 端消费者)。

### 1. Web 管理页

- **访问**:`http://localhost:8351/`(Docker 部署换成服务器 IP)
- **鉴权**:无(本地/内网使用)
- 账号管理、签到、流量包、下游 API Key 派发、模型配置、调度设置都在这里

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
- 返回该 API Key `supportedModels` 白名单内的模型(没配白名单时返回 `modelsConfig.json` 里的全集)

```bash
curl http://localhost:8351/v1/models \
  -H "Authorization: Bearer ak-xxxxxxxxxxxxxxxxxxxxxxxxxxxx"
```

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
