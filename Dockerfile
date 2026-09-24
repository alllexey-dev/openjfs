FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build
# dependencies are cached in a separate layer and re-downloaded only when pom.xml changes
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/target/openjfs-*.jar app.jar
ENV OPENJFS_DATA_PATH=/data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
