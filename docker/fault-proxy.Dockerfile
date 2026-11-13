# The fault-injection proxy (section 12): the jar that `./mvnw package` builds. It binds 127.0.0.1 and needs a loopback
# upstream, so in Compose it joins lab-net's network namespace with the merchant (m6-plan section 9.1).
# Copy-only: no ARG, no secret, nothing fetched; the one RUN adds the non-root user (m6-plan section 9.2). The proxy
# generates its bodyHmac key in memory at start-up; nothing about it is in the image.
# Base: eclipse-temurin 21.0.13_9-jre-noble, pinned by its multi-arch index digest (read 2026-11-13, ADR 0006).
FROM eclipse-temurin:21.0.13_9-jre-noble@sha256:0d57a8e90c2bb844c5a8e1b2b71e11eff06dad7edcfd803cd21ed4ae203b2515

RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin ucplab

COPY fault-proxy/target/fault-proxy-*.jar /app/fault-proxy.jar

USER 10001
ENTRYPOINT ["java", "-cp", "/app/fault-proxy.jar", "dev.ucplab.faultproxy.FaultProxyMain"]
CMD ["--port", "9101", "--upstream", "http://127.0.0.1:9001"]
