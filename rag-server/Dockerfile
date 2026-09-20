# ============================================================
# 多阶段构建。
#
# 阶段一用 Maven 镜像编译打包,阶段二只把打好的 jar 拷进 JRE 镜像。
# 这样最终镜像里**不含 Maven、不含源码、不含依赖缓存** ——
# 单阶段构建出来的镜像动辄 700MB+,多阶段能压到 250MB 左右。
# ============================================================

# 基础镜像的来源可以切换。
#
# 默认走 Docker Hub。但国内网络到 auth.docker.io 经常超时,
# 在 .env 里加一行就能换到别的镜像源:
#   DOCKER_REGISTRY=public.ecr.aws/docker/library
#
# AWS 的 ECR Public 镜像了 Docker Hub 的官方镜像,国内一般可以直连。
ARG REGISTRY=docker.io/library

# ---------------- 阶段一:构建 ----------------
FROM ${REGISTRY}/maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先只拷 pom 并预下载依赖。
# Maven 的依赖解析结果会被 Docker 按层缓存,只要 pom 没变,
# 后面重新构建时这一步直接命中缓存,能省掉几分钟的下载时间。
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# 再拷源码并打包。源码改动只会让这一层失效,上面的依赖缓存仍然有效。
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---------------- 阶段二:运行 ----------------
# ARG 在 FROM 之后会失效,每个阶段要用就得重新声明一次
ARG REGISTRY=docker.io/library
FROM ${REGISTRY}/eclipse-temurin:17-jre
WORKDIR /app

# 用非 root 用户运行。
# 容器里默认是 root,一旦应用被攻破,攻击者拿到的是容器内的 root 权限 ——
# 虽然还有容器隔离兜底,但少一层风险总是好的。
RUN useradd --system --create-home --shell /usr/sbin/nologin appuser

COPY --from=build /build/target/rag-server-0.0.1-SNAPSHOT.jar app.jar

# 文件存储目录。挂载卷之后属主会变,所以先建好并授权。
RUN mkdir -p /app/uploads && chown -R appuser:appuser /app
USER appuser

EXPOSE 8080

# 时区设成东八区,否则日志时间会比本地时间早 8 小时,排查问题时很别扭
ENV TZ=Asia/Shanghai

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
