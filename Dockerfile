# syntax=docker/dockerfile:1.7
FROM maven:3.9.12-eclipse-temurin-21-alpine AS build
WORKDIR /workspace

COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S wallet && adduser -S wallet -G wallet
WORKDIR /app

COPY --from=build --chown=wallet:wallet /workspace/target/wallet-service-*.jar app.jar

USER wallet
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
HEALTHCHECK --interval=30s --timeout=5s --start-period=180s --retries=3 \
  CMD wget -q -O /dev/null "http://127.0.0.1:${PORT:-8080}/healthz" || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
