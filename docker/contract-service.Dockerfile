# syntax=docker/dockerfile:1.7
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw mvnw
COPY pom.xml pom.xml
COPY contract-core/pom.xml contract-core/pom.xml
COPY contract-cli/pom.xml contract-cli/pom.xml
COPY contract-service/pom.xml contract-service/pom.xml
COPY contract-validation-spring-boot-starter/pom.xml contract-validation-spring-boot-starter/pom.xml
COPY contract-sdk/pom.xml contract-sdk/pom.xml
COPY examples/dcg-spring-boot-realworld-demo/pom.xml examples/dcg-spring-boot-realworld-demo/pom.xml

RUN chmod +x mvnw
COPY contract-core/src contract-core/src
COPY contract-service/src contract-service/src
COPY contracts contracts

RUN --mount=type=cache,target=/root/.m2 set -eux; \
    for attempt in 1 2 3; do \
      MAVEN_CONFIG="" ./mvnw \
        -pl contract-service \
        -am \
        -DskipTests \
        -Dmaven.wagon.http.retryHandler.count=5 \
        -Dmaven.wagon.http.retryHandler.requestSentEnabled=true \
        package && break; \
      if [ "$attempt" -eq 3 ]; then \
        echo "Maven package failed after ${attempt} attempts"; \
        exit 1; \
      fi; \
      echo "Retrying Maven package (attempt $((attempt + 1)) of 3) after transient failure..."; \
      sleep 5; \
    done

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system dcg && useradd --system --gid dcg --create-home dcg
WORKDIR /app

COPY --from=build /workspace/contract-service/target/contract-service-0.1.0-SNAPSHOT.jar /app/contract-service.jar
COPY --from=build /workspace/contracts /app/contracts

RUN mkdir -p /var/lib/dcg \
    && chown -R dcg:dcg /app /var/lib/dcg

USER dcg
EXPOSE 8080

ENV JAVA_OPTS=""
ENV SPRING_PROFILES_ACTIVE=prod
ENV CONTRACTS_ROOT=/app/contracts

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/contract-service.jar"]
