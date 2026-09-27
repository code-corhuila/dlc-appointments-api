FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml .
COPY appointments-core/pom.xml appointments-core/pom.xml
COPY appointments-adapters/pom.xml appointments-adapters/pom.xml
COPY appointments-app/pom.xml appointments-app/pom.xml

RUN mvn -B dependency:go-offline

COPY appointments-core appointments-core
COPY appointments-adapters appointments-adapters
COPY appointments-app appointments-app

RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build \
    /workspace/appointments-app/target/appointments-app-0.1.0-SNAPSHOT.jar \
    app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]