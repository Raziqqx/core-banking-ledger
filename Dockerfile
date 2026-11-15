# syntax=docker/dockerfile:1.7
# Multi-stage build: Maven + JDK to build, JRE-only Alpine to run, as a non-root user.
#   docker build -t core-banking-ledger:local .

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
# POMs first so the dependency layer is reused when only sources change.
COPY pom.xml .
COPY common/pom.xml common/
COPY accounts/pom.xml accounts/
COPY ledger/pom.xml ledger/
COPY payments/pom.xml payments/
COPY notifications/pom.xml notifications/
COPY app/pom.xml app/
COPY common/src common/src
COPY accounts/src accounts/src
COPY ledger/src ledger/src
COPY payments/src payments/src
COPY notifications/src notifications/src
COPY app/src app/src
# Tests run in CI (mvn verify with Testcontainers), not inside the image build.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -Dmaven.test.skip=true package \
 && cp app/target/app-*-SNAPSHOT.jar /app.jar \
 && java -Djarmode=tools -jar /app.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre-alpine
# Fixed numeric UID so Kubernetes can verify runAsNonRoot.
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
WORKDIR /app
# Spring Boot layers, least to most frequently changed.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    LOG_FORMAT=ecs
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
