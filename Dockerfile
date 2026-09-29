FROM dh-mirror.gitverse.ru/gradle:9.2.0-jdk25 AS build

WORKDIR /workspace

COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts шаблон.xlsx ./
RUN chmod +x gradlew

COPY shared shared
COPY tariff-provider tariff-provider
COPY src src
# Kotlin daemon оставляет временный файл, который Kaniko в Amvera иногда успевает
# увидеть уже удалённым при создании слоя. Компилируем в процессе Gradle.
RUN gradle installDist --no-daemon -Pkotlin.compiler.execution.strategy=in-process

FROM dh-mirror.gitverse.ru/eclipse-temurin:25-jre

RUN groupadd --system bot && useradd --system --gid bot --create-home bot
WORKDIR /app

COPY --from=build --chown=bot:bot /workspace/build/install/tg-bot-count-electric ./
COPY --from=build --chown=bot:bot /workspace/шаблон.xlsx ./шаблон.xlsx
RUN mkdir -p /app/data && chown bot:bot /app/data

USER bot
ENV ELECTRICITY_DB=/app/data/electricity.db

VOLUME ["/app/data"]
ENTRYPOINT ["bin/tg-bot-count-electric"]

