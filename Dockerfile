# =====================================================================
# EmonLens 全量构建：UI(Vite) -> Server(Spring Boot fat jar) -> 运行时镜像
# 构建上下文 = 仓库根目录：  docker build -t emonlens/server:latest .
#
# 两种构建路径（默认 pkg）：
#   pkg（默认）：容器内完整构建 UI + jar，需构建机能访问 Maven/npm 仓库
#   hostjar   ：跳过容器内构建，直接装配宿主机已打好的 jar
#               docker build --build-arg BUILD_STAGE=hostjar -t emonlens/server:latest .
#               （jar 需先放到上下文根目录：make image-offline 自动完成）
# =====================================================================

ARG BUILD_STAGE=pkg

# ---------- 1. 前端构建 ----------
FROM node:20-alpine AS ui
WORKDIR /src/emonlens-ui
COPY emonlens-ui/package.json emonlens-ui/package-lock.json ./
# npm ci 偶发“Exit handler never called”但进程退出码为 0；这里强制校验 vite 已安装并重试。
RUN npm ci --no-audit --no-fund; \
    if [ ! -x node_modules/.bin/vite ]; then \
      echo "npm ci did not install dependencies; retrying with npm install" >&2; \
      npm install --no-audit --no-fund; \
    fi; \
    test -x node_modules/.bin/vite
COPY emonlens-ui/ ./
# vite outDir 指向 ../emonlens-server/src/main/resources/static
RUN npm run build

# ---------- 2. 后端打包（默认路径） ----------
FROM maven:3.9-eclipse-temurin-11 AS pkg
WORKDIR /src
COPY pom.xml ./
COPY emonlens-common/pom.xml emonlens-common/
COPY emonlens-server/pom.xml emonlens-server/
# 先拉依赖，利用层缓存加速增量构建
RUN mvn -q -B -pl emonlens-common,emonlens-server -am dependency:go-offline || true
COPY emonlens-common/ emonlens-common/
COPY emonlens-server/src/main/ emonlens-server/src/main/
COPY --from=ui /src/emonlens-server/src/main/resources/static/ emonlens-server/src/main/resources/static/
RUN mvn -q -B -DskipTests package && cp emonlens-server/target/emonlens-server-*.jar /emonlens-server.jar

# ---------- 2'. 离线路径：装配宿主机已构建 jar（上下文根目录） ----------
FROM scratch AS hostjar
COPY emonlens-server-*.jar /emonlens-server.jar

# 动态选中构建路径（ARG BUILD_STAGE，默认 pkg；离线构建传 hostjar）
FROM ${BUILD_STAGE} AS selected

# ---------- 3. 运行时 ----------
FROM eclipse-temurin:11-jre-jammy
# 不装 curl/tzdata：健康检查用 bash /dev/tcp 直连本机断言 HTTP 200；时区由 JVM -Duser.timezone 承担
# （Java 自带 tzdb，无需系统 tzdata；apt 源不可达时镜像仍可构建）
RUN adduser --system --group --home /app --shell /usr/sbin/nologin emonlens \
    && mkdir -p /app/logs && chown -R emonlens:emonlens /app
WORKDIR /app
COPY --from=selected /emonlens-server.jar /app/emonlens-server.jar
USER emonlens
EXPOSE 8300
ENV TZ=Asia/Shanghai \
    JAVA_OPTS=""
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=5 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8300 && printf 'GET /actuator/health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '200' <&3"]
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Duser.timezone=${TZ:-Asia/Shanghai} -jar /app/emonlens-server.jar"]
