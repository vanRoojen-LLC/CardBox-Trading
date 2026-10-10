# Build from the repository root: docker build -f cloud/Dockerfile .
# The API compiles a few Swing-free classes straight from the desktop app's src/ tree.
# CI passes BASE_REGISTRY=public.ecr.aws/docker/library: GitHub-hosted runners share IPs, and Docker Hub's
# anonymous pull limit failed the image job with 429 Too Many Requests (PR #65). Same official images.
ARG BASE_REGISTRY=docker.io/library

FROM ${BASE_REGISTRY}/node:22-alpine AS web
WORKDIR /web
COPY cloud/web/package.json cloud/web/package-lock.json ./
RUN npm ci
COPY cloud/web/ ./
RUN npm run build

FROM ${BASE_REGISTRY}/maven:3.9-eclipse-temurin-21 AS api
WORKDIR /repo
COPY cloud/api/pom.xml cloud/api/pom.xml
RUN mvn -q -B -f cloud/api/pom.xml dependency:go-offline
COPY src/ src/
COPY cloud/api/src/ cloud/api/src/
COPY --from=web /web/dist/ cloud/api/src/main/resources/static/
RUN mvn -q -B -f cloud/api/pom.xml -DskipTests package

FROM ${BASE_REGISTRY}/eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=api /repo/cloud/api/target/cardbox-trading-cloud.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
