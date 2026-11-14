# The agent service (section 5.1, 23): Boot's repackaged jar, the one `./mvnw package` builds. In Compose it joins
# lab-net's network namespace, so it reaches the loopback-bound proxy and merchant and listens on 8080 there
# (m6-plan section 9.1).
# Copy-only: no ARG, no secret, nothing fetched; the one RUN adds the non-root user and /data, which it owns, so a
# named volume mounted there starts out writable (the SQLite file lives on it). The model key is never here: Compose
# passes it from the shell at run time (m6-plan section 9.3, I8).
# Base: eclipse-temurin 21.0.13_9-jre-noble, pinned by its multi-arch index digest (read 2026-11-13, ADR 0006).
FROM eclipse-temurin:21.0.13_9-jre-noble@sha256:0d57a8e90c2bb844c5a8e1b2b71e11eff06dad7edcfd803cd21ed4ae203b2515

RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin ucplab \
    && mkdir /data && chown 10001:10001 /data

COPY agent-service/target/agent-service-*.jar /app/agent-service.jar

USER 10001
WORKDIR /data
ENTRYPOINT ["java", "-jar", "/app/agent-service.jar"]
