FROM maven:3.9-eclipse-temurin-26-alpine@sha256:4c85d516dd8c7e50d49a403d604e565e0dbb09a790186fe3245725d313ae7cb9 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline
COPY src src
RUN mvn -B -q -DskipTests package
FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555
RUN apk add --no-cache curl && addgroup -S gateflow && adduser -S -G gateflow -u 10001 gateflow
WORKDIR /app
COPY --from=build /workspace/target/gateflow-backend-*.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
