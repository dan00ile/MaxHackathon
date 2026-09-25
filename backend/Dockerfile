FROM gradle:8.14.3-jdk21 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts gradle.lockfile ./
RUN gradle dependencies --no-daemon > /dev/null
COPY src ./src
RUN gradle installDist --no-daemon -x test

FROM eclipse-temurin:21-jre
COPY certs/russian_trusted_root_ca.cer /tmp/ru_root.cer
RUN keytool -importcert -noprompt -cacerts -storepass changeit -alias ru_root -file /tmp/ru_root.cer
COPY --from=build /src/build/install/backend /app
ENV FILES_DIR=/data/files
ARG GIT_SHA=dev
ENV GIT_SHA=$GIT_SHA
EXPOSE 8080
CMD ["/app/bin/backend"]
