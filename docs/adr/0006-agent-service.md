# ADR 0006: The agent service: Spring Boot, its classpath, and what M6 leaves for later

Status: draft (M6 C1). m6-plan.md (with its Decisions block) sets the shape; this records what C1 measured. Later M6
commits add the wiring, sessions, the run supervisor and the Compose network namespace before it is accepted.

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

## Known gaps left for later

- **21.3's rule that P3 and P5 are checked and updated in the same transaction as the intent insert is left for M7**
  (m6-plan Decisions item 4). `DefaultOrchestrator.propose` evaluates policy, then inserts, in separate repository
  calls (`PolicyUsage`'s `TODO(M3)`). In M6 the service allows one active run per user, so the agent cannot race its
  own proposals; with several instances (M7) the check must move inside the insert's write transaction.
