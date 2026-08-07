# ---------- Stage 1: Build ----------
FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /app

ARG APM_TYPE=elastic

COPY settings.xml /root/.m2/settings.xml

COPY pom.xml .

RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -U -B dependency:go-offline

# ---------- APM Agent ----------
RUN --mount=type=cache,target=/root/.m2/repository \
    mkdir -p /opt/apm && \
    if [ "$APM_TYPE" = "datadog" ]; then \
        echo "Installing Datadog agent"; \
        mvn -U -B dependency:get \
            -Dartifact=com.datadoghq:dd-java-agent:1.61.1 \
            -Dtransitive=false && \
        cp /root/.m2/repository/com/datadoghq/dd-java-agent/1.61.1/dd-java-agent-1.61.1.jar \
            /opt/apm/apm-agent.jar; \
    elif [ "$APM_TYPE" = "otel" ]; then \
        echo "Installing Grafana OTel Agent"; \
        curl -fsSL -L -o /opt/apm/apm-agent.jar \
            https://github.com/grafana/grafana-opentelemetry-java/releases/download/v2.27.0/grafana-opentelemetry-java.jar; \
    else \
        echo "Installing Elastic APM agent"; \
        mvn -U -B dependency:get \
            -Dartifact=co.elastic.apm:elastic-apm-agent:1.52.1 \
            -Dtransitive=false && \
        cp /root/.m2/repository/co/elastic/apm/elastic-apm-agent/1.52.1/elastic-apm-agent-1.52.1.jar \
            /opt/apm/apm-agent.jar; \
    fi

COPY . .

RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -U -B clean package -DskipTests


# ---------- Stage 2: Runtime ----------
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

COPY --from=builder /app/target/scheduler-service-0.0.1-SNAPSHOT.jar /app/app.jar
COPY --from=builder /opt/apm/ /opt/apm/

# ---------- Build-time arguments ----------
ARG SPRING_PROFILE=dev
ARG APM_TYPE=elastic

# ---------- Runtime ENV ----------
ENV SPRING_PROFILE=${SPRING_PROFILE}
ENV APM_TYPE=${APM_TYPE}
ENV JAVA_OPTS=""

# Temporal configuration (injected at runtime via K8s env vars)
ENV TEMPORAL_SERVER_TARGET="localhost:7233"

# Log directory (mount a PVC or use stdout in K8s)
ENV LOG_DIR=/app/logs

RUN mkdir -p ${LOG_DIR}

EXPOSE 8060 9090

ENTRYPOINT ["sh", "-c", "\
  echo \"Starting scheduler-service with profile=$SPRING_PROFILE\"; \
  exec java \
    -javaagent:/opt/apm/apm-agent.jar \
    $JAVA_OPTS \
    -Dlog.dir=${LOG_DIR} \
    -Dspring.temporal.connection.target=${TEMPORAL_SERVER_TARGET} \
    -jar /app/app.jar \
    --spring.profiles.active=${SPRING_PROFILE}"]