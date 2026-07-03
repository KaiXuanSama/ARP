# ========== Stage 1: Maven 构建（使用 Maven Wrapper） ==========
FROM eclipse-temurin:17-jdk AS builder

WORKDIR /build

# 先复制构建描述文件和 Maven Wrapper，利用缓存层下载依赖
COPY pom.xml ./
COPY .mvn ./.mvn
COPY mvnw mvnw
COPY mvnw.cmd mvnw.cmd

# 前端 package.json（用于 frontend-maven-plugin 缓存）
COPY frontend/package.json ./frontend/package.json

# 下载依赖（这一层会被缓存，只要 pom.xml 不变就不会重新下载）
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

# 复制源码和前端代码并构建（frontend-maven-plugin 会自动构建前端）
COPY src ./src
COPY frontend ./frontend
RUN ./mvnw -B -q clean package -DskipTests


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
