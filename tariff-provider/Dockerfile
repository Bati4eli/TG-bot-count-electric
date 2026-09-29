# Этап сборки самостоятельного сервиса: Gradle и JDK нужны только для компиляции.
FROM dh-mirror.gitverse.ru/gradle:9.2.0-jdk25 AS build

WORKDIR /workspace

COPY settings.gradle.kts build.gradle.kts ./
COPY src src
# Компилируем в процессе Gradle: так Kaniko не сталкивается с временными файлами Kotlin daemon.
RUN gradle installDist --no-daemon -Pkotlin.compiler.execution.strategy=in-process

# Финальный этап: лёгкий образ с JRE и собранным дистрибутивом из этапа build.
# Gradle, JDK и исходный код в итоговый образ не попадают.
FROM dh-mirror.gitverse.ru/eclipse-temurin:25-jre

RUN groupadd --system provider && useradd --system --gid provider --create-home provider && \
    mkdir -p /data && chown provider:provider /data
WORKDIR /app

COPY --from=build --chown=provider:provider /workspace/build/install/tariff-provider ./
RUN printf '195.82.140.164 www.mosenergosbyt.ru\n' > /app/java-hosts

USER provider
ENV PORT=8080
ENV TARIFF_CACHE_PATH=/data/tariff-cache.properties
ENV JAVA_TOOL_OPTIONS=-Djdk.net.hosts.file=/app/java-hosts

VOLUME ["/data"]
EXPOSE 8080
ENTRYPOINT ["bin/tariff-provider"]
