FROM dh-mirror.gitverse.ru/gradle:9.2.0-jdk25 AS build

WORKDIR /workspace

COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY shared shared
COPY tariff-provider tariff-provider
# См. корневой Dockerfile: это исключает гонку Kaniko с временными файлами Kotlin daemon.
RUN chmod +x gradlew && gradle :tariff-provider:installDist --no-daemon -Pkotlin.compiler.execution.strategy=in-process

FROM dh-mirror.gitverse.ru/eclipse-temurin:25-jre

RUN groupadd --system provider && useradd --system --gid provider --create-home provider
WORKDIR /app

COPY --from=build --chown=provider:provider /workspace/tariff-provider/build/install/tariff-provider ./
RUN mkdir -p /data && chown provider:provider /data

USER provider
ENV PORT=8080
ENV TARIFF_SERVICE_STATE=/data/tariff-service.properties

VOLUME ["/data"]
EXPOSE 8080
ENTRYPOINT ["bin/tariff-provider"]
