# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system truconf \
    && useradd --system --gid truconf --home-dir /app --shell /usr/sbin/nologin truconf \
    && mkdir -p /app /var/lib/truconf-proxydb/files /tmp/app-src \
    && chown -R truconf:truconf /app /var/lib/truconf-proxydb /tmp/app-src

WORKDIR /app

COPY . /tmp/app-src/

RUN set -eux; \
    jar_path="$(find /tmp/app-src/target -maxdepth 1 -type f -name '*.jar' ! -name '*.jar.original' 2>/dev/null | head -n 1)"; \
    if [ -z "$jar_path" ]; then \
        echo "Build artifact not found under target. Run 'mvn clean package -DskipTests' before 'docker compose up --build'." >&2; \
        exit 1; \
    fi; \
    cp "$jar_path" /app/app.jar; \
    rm -rf /tmp/app-src; \
    chown truconf:truconf /app/app.jar

USER truconf
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
