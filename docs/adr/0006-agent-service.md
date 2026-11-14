# ADR 0006: The agent service: Spring Boot, its classpath, and what M6 leaves for later

Status: accepted (M6, C21). m6-plan.md (with its Decisions block) sets the shape. This records what C1 measured, what the
Compose spike and the logging work found, the Compose stack as C20 built it, and what M6 leaves for later.

## Decision

- **Spring Boot 4.1.2, in agent-service only.** The parent pom holds the `spring-boot.version` property and nothing
  else from Boot. agent-service imports `spring-boot-dependencies` in its own `dependencyManagement`; no other module
  sees Boot's versions. Section 15 said "Spring Boot 3.x"; that line changes in C2 (D5).
- **agent-adk is a runtime dependency of agent-service.** The live client is reached only through
  `ModelClients.load("gemini", ...)`, as the harness does (m6-plan section 2.3), so no Gemini SDK type is on the
  service's compile classpath. agent-runtime is a compile dependency (it holds `ModelClients`).
- **Pins agent-service adds against the Boot BOM** (each proved by `ClasspathVersionsTest`):
  - **Jackson 2 `jackson-databind`, `jackson-core`, `jackson-datatype-jdk8`, `jackson-datatype-jsr310` at 2.18.11**,
    the version google-genai 1.80.0 declares and agent-adk's tests run on. The BOM's `jackson-2-bom` would give 2.21.7.
  - **`jackson-annotations` is not pinned: it resolves to 2.21 from the BOM.** Jackson 3 (`tools.jackson`, 3.1.7,
    which Boot 4's web layer uses) keeps depending on the 2.x annotations artifact and needs 2.21: with 2.18.11 the
    context failed to start with `NoClassDefFoundError: com/fasterxml/jackson/annotation/JsonSerializeAs`, a class
    added in 2.21. The jackson-bom 3.1.7 pom says so ("3.x retains dep to annotations 2.x",
    `jackson.version.annotations` 2.21). The annotations artifact is additive, so genai's databind 2.18.11 runs on
    2.21; `LiveProviderClasspathTest` maps a function call through genai on exactly this classpath. This is the one
    place the plan's "Jackson 2 pinned to 2.18.11" could not hold.
  - **`junit-bom` 6.1.3 imported again, ahead of the Boot BOM.** The parent's `junit-bom` is itself an import, and an
    import inherited from the parent does not beat this module's own imports: without the re-import Jupiter resolved
    to the BOM's 6.0.3. The m6-plan VERIFY on precedence is answered: the parent's *explicit* entries win (Flyway
    13.12.1 and sqlite-jdbc 3.54.1.0 held without any change), its *imports* do not.
- **Not pinned, from the BOM, accepted:** kotlin-stdlib 2.3.21 (and -jdk7, -jdk8) under okhttp 4.12.0, which declares
  1.8.21; `kotlin-stdlib-common` stays at 1.9.10 (okio's). The Kotlin stdlib is backward compatible by policy, and the
  stub round trip through genai's okhttp passes on it.

## Measured (2026-11-13)

**VERIFY results.**

| Fact | Result | Source, read 2026-11-13 |
|---|---|---|
| Boot 4.1.2 is on Maven Central | Yes, published 2026-10-22 (pom `Last-Modified: Thu, 22 Oct 2026 12:04:04 GMT`), 22 days old. `maven-metadata.xml` (lastUpdated 2026-11-05) lists `latest`/`release` as 4.2.0-RC2, a pre-release; 4.1.2 is the newest GA | https://repo1.maven.org/maven2/org/springframework/boot/spring-boot/maven-metadata.xml and `.../4.1.2/spring-boot-4.1.2.pom` |
| Boot 4.1 supports Java 21 | Yes: "Spring Boot 4.1.2 requires at least Java 17 and is compatible with versions up to and including Java 27." | https://docs.spring.io/spring-boot/system-requirements.html |
| Boot 3.5 OSS support | Ended 2026-06-30. spring.io's project page renders no support table without JavaScript, and its support policy page gives the rule ("Minor Releases will be supported for a minimum of 13 months") but no dates; endoflife.date lists 3.5 (released 2025-05) with end of OSS support 2026-06-30, consistent with that rule. 4.1 is supported to 2027-07-31 on the same source | https://spring.io/support-policy; https://endoflife.date/api/v1/products/spring-boot/ (a secondary source) |

**The classpath** (`./mvnw dependency:tree -pl agent-service`, saved in full as
`docs/adr/0006-agent-service-dependency-tree.txt`). The lines that matter:

```
org.flywaydb:flyway-core:jar:13.12.1:compile
org.xerial:sqlite-jdbc:jar:3.54.1.0:compile
com.fasterxml.jackson.core:jackson-databind:jar:2.18.11:runtime
com.fasterxml.jackson.core:jackson-annotations:jar:2.21:compile
com.fasterxml.jackson.core:jackson-core:jar:2.18.11:runtime
com.fasterxml.jackson.datatype:jackson-datatype-jdk8:jar:2.18.11:runtime
com.fasterxml.jackson.datatype:jackson-datatype-jsr310:jar:2.18.11:runtime
com.squareup.okhttp3:okhttp:jar:4.12.0:runtime
org.jetbrains.kotlin:kotlin-stdlib:jar:2.3.21:runtime        (-jdk7, -jdk8 2.3.21; -common 1.9.10)
tools.jackson.core:jackson-databind:jar:3.1.7:compile
org.apache.tomcat.embed:tomcat-embed-core:jar:11.0.27:compile
org.springframework:spring-webmvc:jar:7.0.10:compile
ch.qos.logback:logback-classic:jar:1.5.38:compile
org.slf4j:jul-to-slf4j:jar:2.0.20:compile
org.slf4j:slf4j-api:jar:2.0.20:compile
org.junit.jupiter:junit-jupiter:jar:6.1.3:test
```

- **Other modules are untouched:** `dependency:tree -pl eval-harness` and `-pl agent-core` are byte-identical before
  and after C1.
- **One SLF4J provider** (Logback), by `ServiceLoader` in `ClasspathVersionsTest`.
- **`System.Logger` and JUL reach Logback** (the m6-plan VERIFY on `jul-to-slf4j`). `ServiceApplicationTest` logs one
  line through each and finds them in Logback's format, with no JUL `WARNING:` line:

  ```
  2026-11-13T22:49:27.061+05:30  WARN 96561 --- [           main] dev.ucplab.core.c1probe                  : c1-system-logger-probe
  2026-11-13T22:49:27.062+05:30  WARN 96561 --- [           main] dev.ucplab.runtime.c1probe               : c1-jul-probe
  ```

  `System.Logger` with no `LoggerFinder` on the classpath goes to JUL, and Boot's `LogbackLoggingSystem` installs the
  `jul-to-slf4j` bridge, so both paths land in Logback.
- **agent-service's ArchitectureTest** (no ADK or Gemini types, no `ModelKey.reveal`) passes with agent-adk at runtime
  scope.

## Images and the Compose network namespace (C3)

- **Base image:** `eclipse-temurin:21.0.13_9-jre-noble`, pinned by its multi-arch OCI index digest
  `sha256:0d57a8e90c2bb844c5a8e1b2b71e11eff06dad7edcfd803cd21ed4ae203b2515` (`docker buildx imagetools inspect`,
  2026-11-13; built 2026-11-11 on `ubuntu:24.04`, linux/amd64 and linux/arm64). The index digest, not one platform's
  manifest, so the same line serves the Mac (arm64) and the GitHub runner (amd64). It is Temurin 21.0.13+9, the JDK
  the M4 CI runner used. It has bash, curl and wget (m6-plan risk 8 assumed curl might be missing), so healthchecks
  use curl.
- **Copy-only Dockerfiles** (`docker/merchant.Dockerfile`, `docker/fault-proxy.Dockerfile`): one `RUN` that adds the
  non-root user 10001, a `COPY` of the jar `./mvnw package` built, `USER 10001`, the entrypoint. No `ARG`, no `ENV`
  of ours (`docker image inspect` shows the base image's variables only). `.dockerignore` is an allowlist: the
  Dockerfiles and the built jars, nothing else.
- **The shared network namespace works** (m6-plan risk 2). The merchant (`MerchantServer.java:40`) and the proxy bind
  127.0.0.1 only, and merchant/src is unchanged. `lab-net` (the base image running `sleep infinity`) owns the namespace
  and publishes `127.0.0.1:8080`; `merchant-a` and `fault-proxy` join it with `network_mode: service:lab-net`.
  `scripts/compose-spike.sh`, run by hand, showed (output: `docs/adr/0006-compose-spike.txt`):
  1. discovery through the proxy works from inside the namespace, with the endpoint rewritten to `127.0.0.1:9101`,
     and the proxy log records it;
  2. the merchant's `/_lab/orders`, the proxy's admin API and the proxy itself are unreachable from the host and from
     the namespace's own Compose network address; inside it, `/_lab/orders` is served and the proxy refuses `/_lab`
     with 404 (section 12);
  3. `docker compose down -v` leaves no container or volume of the project.

  Shown in C20 (below): a `docker kill` and restart of a joined service while `lab-net` stays up (25.5 step 8). Not yet
  shown: the same on the GitHub runner's Docker, which `quickstart.yml`'s first run will tell.

## Logging and I8 (C16)

**VERIFY, 2026-11-14,** https://docs.spring.io/spring-boot/reference/features/logging.html, and the Boot 4.1.2 jar:
- `logging.structured.format.console=ecs` is as the plan wrote it (the other built-in formats are `gelf` and `logstash`).
- The customiser hook is `logging.structured.json.customizer`, naming a class that implements
  `org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer<T>`. The docs page doesn't show its
  method; the jar has one, `customize(JsonWriter.Members<T>)`, and `Members.applyingValueProcessor(ValueProcessor)` can
  rewrite every string value. `RedactingLogCustomizer` does that with `Redaction.mask`, so the message and the exception
  text of every event are masked. The plan said "a customiser that rewrites the message and the exception text"; the
  value processor covers those and every other string member, which is wider.

**A second guard the plan didn't have.** With `org.springframework.web` or the container at DEBUG or TRACE, Spring MVC
logs request and response *bodies* ("Read ... to [{message=...}]", "Writing [...]", the handler's "Arguments: [...]")
and Tomcat dumps the bytes it reads. A body printed as a Java map (`{token=...}`) has no shape for the masking to find,
and the approval token and the user's messages were in the log. `BodyLogGuard`, a Logback turbo filter installed once the
logging system is configured, drops events from those loggers at any level. `ServiceLeakTest` runs with every logger at
DEBUG and the web and container loggers at TRACE, and found this; without the guard it fails.

**What the database holds, by design.** The scan of the SQLite file allows two canaries in one column each: the CSRF token
in `user_session.csrf_token` (18.5: "the session row holds a random `csrf_token`") and the payment token in
`merchant_attempt.request_body` (section 7: the `complete` body is built once and stored, so a resend is byte-identical,
I7). The session cookie is stored only as a hash and the approval token not at all; both are scanned for in every column.

**An `Error` on a run thread** (Stage 4 review fix 1) is rethrown after the run settles, and would reach the JVM's default
handler, which prints the message and stack to stderr, outside the redacted log. `RunSupervisor`'s virtual threads have an
uncaught-exception handler that logs the class name only; `ServiceLeakTest` plants a canary in an `Error`'s message.

## The Compose stack and the key (C20)

**VERIFY, 2026-11-14,** https://docs.docker.com/compose/how-tos/environment-variables/ and its sub-pages, then measured
with `docker compose config` and real containers (fake values only), Compose v5.7.0:
- **A bare name in `environment:` is filled from `.env` as well as from the shell**, and the shell wins when both set
  it. The docs page does not say so; the test shows it. So the key must never be in `.env`: the README says so, and
  `.env.example` keeps `GEMINI_API_KEY=` empty. Unset everywhere gives no variable in the container; empty in `.env`
  gives an empty one. Either way the scripted model runs, and `AGENT_MODEL=gemini` refuses to start.
- **`.env` inline comments:** `KEY=value # note` works, but `KEY=   # note` (spaces, then a comment) gives the *value*
  `# note`. The old `.env.example` line `GEMINI_API_KEY=          # live mode only` would have set a junk key; the comment
  is on its own line now.

**agent-service in Compose** joins `lab-net`'s namespace (`network_mode: service:lab-net`), listens on 8080 there, and
keeps its SQLite file on the named volume `ucplab-data` mounted at `/data`. Its image copies the boot jar and nothing
else (the allowlist `.dockerignore` gained that one jar). `scripts/image-secret-scan.sh` exports all three images and
searches every layer, config and history for planted canaries, after a positive control.

**Recovery checks** (run by hand, 2026-11-14):
- `docker kill` of the fault proxy, then `docker compose up -d fault-proxy`: healthy again in 2.6 s, and a purchase
  completes.
- `docker kill` of agent-service straight after an approve, then `docker compose up -d --wait agent-service`: the
  intent reached COMPLETED with one order, 4 s after the kill (recovery, 7.6).
- **`docker kill` of `lab-net` is not recovered by `docker compose up -d`.** The other three containers keep running
  and report healthy, but they stay attached to the dead namespace, while the restarted `lab-net` has a new, empty
  one: the host's port 8080 reaches nothing. `docker compose restart merchant-a fault-proxy agent-service` (or
  `docker compose up -d --force-recreate`) recovers the stack. `depends_on: restart: true` does not help after an
  external kill (tried). It is left as it is: `lab-net` is `sleep infinity` under `init: true` and only an operator's
  `docker kill` stops it.

## Known gaps left for later

- **21.3's rule that P3 and P5 are checked and updated in the same transaction as the intent insert is left for M7**
  (m6-plan Decisions item 4). `DefaultOrchestrator.propose` evaluates policy, then inserts, in separate repository
  calls (`PolicyUsage`'s `TODO(M3)`). In M6 the service allows one active run per user, so the agent cannot race its
  own proposals; with several instances (M7) the check must move inside the insert's write transaction.
- **Tomcat answers an encoded slash (`%2F`) in a path with its own minimal error page before our handler runs**, so that
  one response is not the `{code, message, request_id}` JSON shape. `ErrorShapeTest.containerRejection_leaksNothing`
  proves the page leaks no exception, class name, path or server banner. Accepted for M6.
- **The stored `complete` body holds the payment credential, by design** (I7, 7.4): it is built once, when the intent
  becomes APPROVED, and every resend reads it from `merchant_attempt.request_body`, so a resend is byte-identical. That
  is fine for the lab's mock tokens (N1). A real deployment would need a single-use payment token or encryption of that
  column at rest. `ServiceLeakTest` allows the payment canary in that one column and nowhere else.
- **Lab rules are global to the one proxy until M6b's sandboxes** (T7, 24.4). Any signed-in local user's
  `PUT /api/lab/faults` changes the rules every user's purchases meet, which is why the `demo` profile still refuses to
  start. The lab controls exist in `local` only.
- **`docker kill lab-net` leaves the other containers on a dead namespace** (above): recover with
  `docker compose restart merchant-a fault-proxy agent-service`.
- **The CSRF token is stored in the clear in `user_session.csrf_token`**, as 18.5 specifies (the session cookie is stored
  only as a hash). A leaked database file therefore reveals CSRF tokens, though not sessions.
- **The hourly run limit (P5) counts turns in `agent_run_turn`**, so a person who rehearses a demo more than ten times in
  an hour on one volume is refused with 429 until the hour passes or the volume is recreated.

