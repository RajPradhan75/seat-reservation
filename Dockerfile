FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends haproxy tini \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system seats && useradd --system --gid seats seats
WORKDIR /app
COPY --from=build --chown=seats:seats /build/target/seat-reservation-0.0.1-SNAPSHOT.jar app.jar
COPY --chown=seats:seats docker/haproxy.cfg docker/start.sh /app/
RUN chmod +x /app/start.sh
USER seats
EXPOSE 8080
ENTRYPOINT ["/usr/bin/tini", "--", "/app/start.sh"]
