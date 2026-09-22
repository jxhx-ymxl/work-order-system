# ============================================================
# 企业工单流转平台 — 后端 Dockerfile（瘦身版部署）
# 架构：MySQL + Redis + Spring Boot，不含 MQ/XXL-Job（真实未使用）
# 多阶段构建：maven 编译 → 精简 JRE 运行
# ============================================================

# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 阿里云 maven 镜像源（国内服务器直连 Maven Central 常超时）
RUN mkdir -p /root/.m2 && cat > /root/.m2/settings.xml <<'EOF'
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <mirrors>
    <mirror>
      <id>aliyun</id>
      <mirrorOf>central</mirrorOf>
      <name>Aliyun Maven Central</name>
      <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
  </mirrors>
</settings>
EOF

# 先拷 pom 利用层缓存
COPY pom.xml .
RUN mvn dependency:go-offline -B -q || true
# 拷源码并打包（跳过测试，测试需本地 MySQL/Redis）
COPY src ./src
RUN mvn clean package -DskipTests -B

# ---- 运行阶段：用精简 JRE，控内存 ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
# 从构建产物拷 jar（boot 可执行 fat jar）
COPY --from=build /build/target/work-order-system-*.jar app.jar

# 运行内存约束：2G 服务器上 JVM 上限 256m（堆）+ 元空间
ENV JAVA_OPTS="-Xmx256m -Xms128m -XX:MaxMetaspaceSize=128m -Duser.timezone=Asia/Shanghai"
# 时区：确保容器内 LocalDateTime/日志与业务对齐
ENV TZ=Asia/Shanghai

# 端口：后端服务 9000
EXPOSE 9000
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
