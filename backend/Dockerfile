FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline
COPY src src
RUN mvn -B -q -DskipTests package
FROM eclipse-temurin:21.0.6_7-jre-alpine
RUN apk add --no-cache curl && addgroup -S gateflow && adduser -S -G gateflow -u 10001 gateflow
WORKDIR /app
COPY --from=build /workspace/target/gateflow-backend-*.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
