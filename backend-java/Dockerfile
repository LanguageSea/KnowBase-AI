FROM maven:3.9.4-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY backend-java/pom.xml backend-java/pom.xml
COPY backend-java/src backend-java/src
COPY database/migrations database/migrations
RUN mvn -B -f backend-java/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre

RUN groupadd --system knowbase \
    && useradd --system --gid knowbase --home-dir /app --shell /usr/sbin/nologin knowbase \
    && mkdir -p /app/data/uploads \
    && chown -R knowbase:knowbase /app

WORKDIR /app
COPY --from=build --chown=knowbase:knowbase \
    /workspace/backend-java/target/knowbase-backend-0.1.0-SNAPSHOT.jar /app/app.jar

USER knowbase
EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
