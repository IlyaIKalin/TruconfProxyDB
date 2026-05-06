# syntax=docker/dockerfile:1

FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system truconf \
    && useradd --system --gid truconf --home-dir /app --shell /usr/sbin/nologin truconf \
    && mkdir -p /app /var/lib/truconf-proxydb/files \
    && chown -R truconf:truconf /app /var/lib/truconf-proxydb

WORKDIR /app
COPY --from=build /workspace/target/*.jar /app/app.jar

USER truconf
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
