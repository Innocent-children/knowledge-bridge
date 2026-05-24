# ============================================================
# Knowledge Bridge — Multi-stage Docker Build
# ============================================================

# Stage 1: Build
FROM maven:3.9-eclipse-temurin-25 AS builder

WORKDIR /build
COPY settings.xml /root/.m2/settings.xml
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn package -DskipTests -Dfile.encoding=UTF-8 -B

# Stage 2: Runtime
FROM eclipse-temurin:25-jre

LABEL maintainer="openclaw"
LABEL description="Knowledge Bridge - Knowledge orchestration layer between OpenClaw and RAGFlow"

WORKDIR /app

COPY --from=builder /build/target/*.jar app.jar

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8111/actuator/health || exit 1

EXPOSE 8111

ENTRYPOINT ["java", \
    "-Dfile.encoding=UTF-8", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "--enable-native-access=ALL-UNNAMED", \
    "-jar", "app.jar"]
