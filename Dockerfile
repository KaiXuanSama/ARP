# 基础镜像仓库前缀
#   留空 = Docker Hub 官方（eclipse-temurin:17-jdk / 17-jre）
#   用了私有仓库 / 镜像加速时传入前缀（⚠️ 结尾必须带斜杠）：
#     args:
#       IMAGE_REGISTRY: docker.kaixuan.cloud:33577/
#
#   为什么做成参数而不是直接写死：写死会让 Dockerfile 变成「需要本地魔改」的文件，
#   每次 git pull 都产生冲突。做成参数后，私有仓库地址只存在于 docker-compose.yml
#   （端口本来就在那儿），一次配置长期生效。
#
#   ⚠️ 必须声明在第一个 FROM 之前 —— ARG 参与 FROM 替换时只能放在这个位置。
ARG IMAGE_REGISTRY=

# ========== Stage 1: Maven 构建（使用 Maven Wrapper） ==========
FROM ${IMAGE_REGISTRY}eclipse-temurin:17-jdk AS builder

# 构建期下载源覆盖入口
#   默认值均为国内镜像 —— 国内服务器/容器内实测 repo.maven.apache.org 与
#   nodejs.org 均反复出现 "Temporary failure in name resolution"。
#
#   MAVEN_MIRROR_URL   : Maven 依赖与插件的中央仓库镜像（见 .mvn/docker-settings.xml）
#   MVNW_REPOURL       : Maven 自身发行包的下载源（mvnw 用它重写 distributionUrl）
#   NODE_DOWNLOAD_ROOT : Node.js 二进制下载源（留空则用 pom.xml properties 里的默认值）
#   NPM_REGISTRY_URL   : npm 依赖下载源（同上，留空则用 pom.xml 的默认值）
#
#   需要换回官方源时，用 docker-compose 的 build.args 覆盖任意一项即可：
#     args:
#       MAVEN_MIRROR_URL: https://repo.maven.apache.org/maven2
#       MVNW_REPOURL: https://repo.maven.apache.org/maven2
#
#   ⚠️ 四个 ARG 的默认值故意留空，真正的默认值写在下面的 ENV 里用
#      ${VAR:-默认值} 回退。原因：docker-compose 的 args 用 ${VAR:-} 插值时，
#      未配置的项会传「空字符串」而不是「不传」，若把默认值写在 ARG 上
#      会被空串覆盖掉。写成回退表达式后，空串与未设置行为一致。
ARG MAVEN_MIRROR_URL=
ARG MVNW_REPOURL=
ARG NODE_DOWNLOAD_ROOT=
ARG NPM_REGISTRY_URL=

# 全部转成 ENV —— 因为这几个值都需要被「子进程自己」读取，而不是仅在
# Dockerfile 内做字符串替换：
#   - mvnw 脚本读 MVNW_REPOURL 重写 Maven 发行包地址
#   - Maven 读 MAVEN_MIRROR_URL 做 settings.xml 的 ${env.*} 插值
#   - 后两个在下面的 RUN 里以 -D 传给 frontend-maven-plugin
# ARG 不会以环境变量形式出现在 RUN 启动的进程里，故必须过一道 ENV。
# （ENV 只存在于 builder 阶段，最终镜像从 jre 阶段重新开始，不会带进产物）
ENV MAVEN_MIRROR_URL=${MAVEN_MIRROR_URL:-https://maven.aliyun.com/repository/public}
ENV MVNW_REPOURL=${MVNW_REPOURL:-https://maven.aliyun.com/repository/public}
ENV NODE_DOWNLOAD_ROOT=${NODE_DOWNLOAD_ROOT:-https://registry.npmmirror.com/-/binary/node/}
ENV NPM_REGISTRY_URL=${NPM_REGISTRY_URL:-https://registry.npmmirror.com}

WORKDIR /build

# 先复制构建描述文件和 Maven Wrapper，利用缓存层下载依赖
COPY pom.xml ./
COPY .mvn ./.mvn
COPY mvnw mvnw
COPY mvnw.cmd mvnw.cmd

# 前端 package.json（用于 frontend-maven-plugin 缓存）
COPY frontend/package.json ./frontend/package.json

# 下载依赖（这一层会被缓存，只要 pom.xml 不变就不会重新下载）
#   -B (--batch-mode): 非交互式,日志去 ANSI 颜色(避免 docker build 把日志显示成乱码)
#   -s .mvn/docker-settings.xml: 显式指定镜像 settings（本机构建不会读到它）
#   去掉 -q:依赖解析/下载/前端 npm install 的进度都要能看见
#   Docker 缓存层只缓存 RUN 的最终文件系统变化,日志详细不会破坏缓存
RUN chmod +x mvnw && ./mvnw -B -s .mvn/docker-settings.xml dependency:go-offline

# 复制源码和前端代码并构建（frontend-maven-plugin 会自动构建前端）
#   同上,带 INFO 日志输出;前端构建(vite 编译)进度会通过 maven 日志流出
#
#   Node / npm 的下载源直接把 ENV 传给 maven（pom.xml 的 properties 也读得到）：
#   显式传 -D 而非依赖 pom 默认值，因为 .env 里可能配置了官方源需要生效。
COPY src ./src
COPY frontend ./frontend
RUN ./mvnw -B -s .mvn/docker-settings.xml clean package -DskipTests \
      -Dnode.download.root="$NODE_DOWNLOAD_ROOT" \
      -Dnpm.registry.url="$NPM_REGISTRY_URL"


# ========== Stage 2: JRE 运行 ==========
FROM ${IMAGE_REGISTRY}eclipse-temurin:17-jre

WORKDIR /app

# SQLite 数据文件 + 模型配置文件的挂载目录
RUN mkdir -p /data

COPY --from=builder /build/target/*.jar app.jar

EXPOSE 8351

# Spring 配置:
#   - SQLite 文件落到 /data/agentreproxy.db
#   - 模型目录为内存快照(上游 /v3/config),无静态文件配置
#   - 工作目录设为 /data,确保 SQLite 的相对路径(若有人传 ./xxx.db)落到这里
#   - 激活 docker profile,读取 application-docker.yml
ENTRYPOINT ["java", \
    "-jar", "app.jar", \
    "--spring.profiles.active=docker", \
    "--spring.datasource.url=jdbc:sqlite:/data/agentreproxy.db"]
