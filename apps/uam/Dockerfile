# 构建：在容器里用 Maven 打包，运行镜像只带 JRE
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY apps/uam/pom.xml ./pom.xml
RUN mvn -q -B dependency:go-offline
COPY apps/uam/src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 uam
COPY --from=build /build/target/uam.jar /app/uam.jar
USER uam
EXPOSE 8080
# 优雅停机由 server.shutdown=graceful 配置；密钥与连接串只经环境变量注入。
# 用 CMD 而不是 ENTRYPOINT：部署期命令以 `<镜像> java -jar /app/uam.jar <命令> ...` 整条替换（SPRING_PROFILES_ACTIVE=cli）
CMD ["java", "-jar", "/app/uam.jar"]
