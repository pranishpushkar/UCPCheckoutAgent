# The lab merchant (Appendix A), unchanged: the jar that `./mvnw package` builds. It binds 127.0.0.1 only, so in
# Compose it joins lab-net's network namespace (m6-plan section 9.1, DESIGN 25.3 M6).
# Copy-only: no ARG, no secret, nothing fetched; the one RUN adds the non-root user (m6-plan section 9.2).
# Base: eclipse-temurin 21.0.13_9-jre-noble, pinned by its multi-arch index digest (read 2026-11-13, ADR 0006).
FROM eclipse-temurin:21.0.13_9-jre-noble@sha256:0d57a8e90c2bb844c5a8e1b2b71e11eff06dad7edcfd803cd21ed4ae203b2515

RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin ucplab

COPY merchant/target/merchant-*.jar /app/merchant.jar

USER 10001
ENTRYPOINT ["java", "-cp", "/app/merchant.jar", "dev.ucplab.merchant.MerchantServer"]
CMD ["9001"]
