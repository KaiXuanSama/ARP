# ========== Stage 1: Maven 构建（使用 Maven Wrapper） ==========
FROM eclipse-temurin:17-jdk AS builder

# 前端构建的下载源覆盖入口
#   默认留空 —— 此时 mvn 使用 pom.xml properties 里的值（国内镜像）。
#   默认值只定义在 pom.xml 一处，避免两处默认值发生漂移。
#   需要换回官方源时，用 docker-compose 的 build.args 传入：
#     args:
#       NODE_DOWNLOAD_ROOT: https://nodejs.org/dist/
#       NPM_REGISTRY_URL: https://registry.npmjs.org/
ARG NODE_DOWNLOAD_ROOT=
ARG NPM_REGISTRY_URL=

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
#   去掉 -q:依赖解析/下载/前端 npm install 的进度都要能看见
#   Docker 缓存层只缓存 RUN 的最终文件系统变化,日志详细不会破坏缓存
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

# 复制源码和前端代码并构建（frontend-maven-plugin 会自动构建前端）
#   同上,带 INFO 日志输出;前端构建(vite 编译)进度会通过 maven 日志流出
#
#   下载源用 ${VAR:+...} 按需注入：ARG 为空时该段展开为空字符串，
#   于是 mvn 回退到 pom.xml properties 里的默认值（国内镜像）。
#   这样默认值只存在于 pom.xml 一处，不会与 Dockerfile 重复维护。
COPY src ./src
COPY frontend ./frontend
RUN ./mvnw -B clean package -DskipTests \
      ${NODE_DOWNLOAD_ROOT:+-Dnode.download.root=$NODE_DOWNLOAD_ROOT} \
      ${NPM_REGISTRY_URL:+-Dnpm.registry.url=$NPM_REGISTRY_URL}


# ========== Stage 2: JRE 运行 ==========
FROM eclipse-temurin:17-jre

WORKDIR /app

# SQLite 数据文件 + 模型配置文件的挂载目录
RUN mkdir -p /data

COPY --from=builder /build/target/*.jar app.jar

EXPOSE 8351

# Spring 配置:
#   - SQLite 文件落到 /data/agentreproxy.db
#   - 模型配置走 /data/modelsConfig.json(可被用户随时替换)
#   - 工作目录设为 /data,确保 SQLite 的相对路径(若有人传 ./xxx.db)落到这里
#   - 激活 docker profile,读取 application-docker.yml
ENTRYPOINT ["java", \
    "-jar", "app.jar", \
    "--spring.profiles.active=docker", \
    "--spring.datasource.url=jdbc:sqlite:/data/agentreproxy.db", \
    "--models.config.path=/data/modelsConfig.json"]
