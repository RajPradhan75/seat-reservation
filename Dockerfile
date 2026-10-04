FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
RUN groupadd --system seats && useradd --system --gid seats seats
WORKDIR /app
COPY --from=build --chown=seats:seats /build/target/seat-reservation-0.0.1-SNAPSHOT.jar app.jar
USER seats
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "app.jar"]
