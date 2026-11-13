# ADR 0004: ADK for Java, and what the live model client is built on

Status: accepted (M5). C1 evaluated ADK for Java; the gate after C1 (2026-11-13) chose `google-genai` single-call, and
the first commit of Stage 2 removed ADK. The ADK findings below are kept as the record of that evaluation.

## Decision

- **`GeminiModelClient` is built directly on the Gemini API with `com.google.genai:google-genai`, single call**
  (`client.models.generateContent(model, contents, config)`). ADK for Java was evaluated and is not used.
- **Why:** ADK can make one model turn outside its runner (item 1 below), but its `Gemini` model class is a thin wrapper
  over genai's `models.generateContent`. It would cost about 178 extra jars, including a Docker client, the Anthropic
  SDK, BigQuery, Arrow with Netty and an OpenTelemetry SDK, and a Jackson databind/core mismatch. Our loop owns every
  tool call (6.3, I6), so ADK's agents, runner, sessions and flows would never be used. 6.5 allows building directly on
  the Gemini API.
- **Version: `google-genai` 1.80.0** (2026-10-29), pinned in the parent pom (`google-genai.version`). It is the newest
  release at least two weeks old on 2026-11-13: `maven-metadata.xml` lists 1.77.0 (2026-10-13), 1.78.0, 1.79.0, 1.80.0,
  then 2.0.0 (2026-11-06) to 2.3.0 (2026-11-12). The 2.x line is a new major, newer than two weeks; it is not used yet.
- **The module keeps the name `agent-adk`.** It is the only module that imports Gemini SDK (or ADK) types (5.2).
  Every other module's ArchUnit test forbids `com.google.adk..` and `com.google.genai..`. agent-adk's own test allows
  the JDK, core and `com.google.genai..` only.
- **The spike tests run on genai:**
  - `GeminiSpikeTest` (offline, loopback stub) shows one call returning the function call unexecuted with usage; the
    base URL override and the function response by name; the key in the `x-goog-api-key` header only; and a 503 sent
    once with one attempt, with no key in any exception message;
  - `GeminiLiveSpikeIT` runs under `-Plive-spike` with `GEMINI_API_KEY` set.

### The dependency tree after the swap

| Item | ADK 1.13.0 (C1) | google-genai 1.80.0 |
| --- | --- | --- |
| agent-adk runtime classpath | 216 jars, about 198 MB | **51 jars, about 37 MB** |
| Jackson | databind 2.20.2 with core 2.18.2 (mismatch) | databind, core, annotations and both datatypes all 2.18.11; **no mismatch** |
| Other notable dependencies | see below | OkHttp 4.12 with the Kotlin standard library, Guava 33.4 (android), google-auth |
| Conflicts omitted by Maven | many (Jackson 2.17 to 2.20) | none |

The genai findings in items 2 to 6 were read in the 1.77.0 sources and re-checked in the 1.80.0 sources: the key
header, the eager environment read, `GOOGLE_GEMINI_BASE_URL`, `GOOGLE_GENAI_CLIENT_MODE` and the 5-attempt retry
default are unchanged. `GeminiSpikeTest` passes on 1.80.0.

## The ADK evaluation (C1, kept as the record)

- `com.google.adk:google-adk` **1.13.0** was pinned for the spike. Maven Central had 1.0.0-rc.1, 1.0.0 (2026-03-30),
  then 1.1.0 to 1.14.0; 1.14.0 (2026-11-02) was under two weeks old.
- The spike tests were `AdkSpikeTest` and `AdkLiveSpikeIT`, now `GeminiSpikeTest` and `GeminiLiveSpikeIT` on genai.

### ADK's dependency tree

`./mvnw dependency:tree -pl agent-adk` (kept outside the repo):

| Item | Result |
| --- | --- |
| Size | **216 jars, about 198 MB** on agent-adk's runtime classpath. `google-genai` alone is 38 jars, about 21 MB |
| ADK's own compile dependencies | 33, among them `anthropic-java` and `anthropic-java-vertex`, `google-cloud-aiplatform`, `-speech`, `-storage` and `-bigquery`, `docker-java`, `mcp`, `okhttp` 5, Jackson 2.20, Guava 33, protobuf 4, RxJava 3, Reactor, OpenTelemetry API, SDK and OTLP exporter, Apache Arrow with `arrow-memory-netty`, and `google-genai` 1.77.0 |
| Largest groups by size | `com.google` 73 MB, `io.grpc` 24 MB, `org.eclipse` 13 MB, BouncyCastle 10.5 MB, Anthropic 9.7 MB, ByteBuddy 9.3 MB |
| Version clash inside ADK's tree | Maven resolves **`jackson-databind` 2.20.2 with `jackson-core` 2.18.2**. `google-cloud-storage` declares `jackson-core` 2.18.2 one level nearer than databind's own 2.20.2, and the nearest declaration wins. Jackson does not support a databind newer than its core. The spike's tests parse real Gemini JSON through it without error, but a newer code path could fail with `NoSuchMethodError`. Not fixed in C1 |
| Clash with what is already on the classpath | None. `flyway-core` 13.12.1 and `sqlite-jdbc` 3.54.1.0 bring no transitive dependencies, so there is no Jackson, Guava, Netty or OpenTelemetry to clash with. agent-service, which depends on both agent-persistence and agent-adk, now inherits the whole tree, with Jackson 2.17 to 2.20 versions omitted for conflict |
| Build | Compiles and tests on Java 21; the enforcer rule (JDK 21) passes. No exclusion was added |

### Items 1 to 6 (m5-plan section 2)

Sources: the ADK 1.13.0 sources jar (`google-adk-1.13.0-sources.jar` from Maven Central) and the `google-genai`
1.77.0 sources jar, both read 2026-11-13, and `AdkSpikeTest`, which runs both against a loopback stub.

1. **One model turn outside ADK's runner: yes.**
   - `com.google.adk.models.BaseLlm` declares `Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean
     stream)`. `com.google.adk.models.Gemini` extends it and has a public constructor `Gemini(String modelName, Client
     apiClient)` taking a genai `Client`.
   - With `stream = false`, `Gemini.generateContent` calls `GeminiUtil.prepareGenenerateContentRequest` and then
     `apiClient.async.models.generateContent(model, contents, config)` once, returning `LlmResponse.create(response)`.
   - Function calls come back as parts of `LlmResponse.content()`, and nothing executes them: tool execution lives in
     ADK's flows and runner, which we do not use. `AdkSpikeTest.oneTurn_functionDeclaration_returnsTheCallUnexecutedWithUsage`
     proves it: one request, one function call returned.
   - Nothing in `com.google.adk.models` calls ADK's telemetry.
   - genai's automatic function calling applies only to Java `Method` tools (`AfcUtil.hasCallableTool`). We pass only
     `FunctionDeclaration`s, so it never runs. `AutomaticFunctionCallingConfig.builder().disable(true)` exists to say so
     explicitly.
2. **ToolSpec to function declaration, and the response.**
   - `FunctionDeclaration.builder().name(..).description(..).parametersJsonSchema(Object)` takes a JSON schema as a
     plain `Map`, so a `ToolSpec` schema maps directly. It is grouped as `Tool.builder().functionDeclarations(List)`
     and set with `GenerateContentConfig.builder().tools(List<Tool>)`.
   - The model's call is a `FunctionCall` with `Optional` `id()`, `name()` and `args()` (`Map<String, Object>`).
   - The answer goes back as `Part.fromFunctionResponse(String name, Map<String, Object> response)` in a user
     `Content`, so it is **matched by name**. `FunctionResponse` also has an optional `id()`.
   - ADK strips client-made call ids, those with the prefix `adk-` (`FunctionCallIds`,
     `GeminiUtil.removeClientFunctionCallId`), before sending.
   - **VERIFY in the live spike:** whether the Gemini API returns a call id. The spike prints `id (none)` if not.
     Either way the loop pairs results with calls by order and name.
3. **Token counts.**
   - `LlmResponse.usageMetadata()` and the genai `GenerateContentResponse.usageMetadata()` are
     `Optional<GenerateContentResponseUsageMetadata>`, with `promptTokenCount()` (input), `candidatesTokenCount()`
     (output), `thoughtsTokenCount()` and `totalTokenCount()`, each `Optional<Integer>`.
   - Asserted in `AdkSpikeTest`. `GenerateContentConfig.builder().maxOutputTokens(Integer)` caps output per call (6.3).
   - **Open for C15:** whether thinking tokens (`thoughtsTokenCount`) count against our budget and the price table.
4. **The API key.**
   - genai sends it as the **`x-goog-api-key` header** (`ApiClient.setHeaders`), never in the URL.
     `AdkSpikeTest.apiKey_travelsInTheHeaderOnly_neverInTheUrlOrBody` asserts that against the stub.
   - It can be given explicitly with `Client.builder().apiKey(..)`. The explicit key wins, but genai still reads the
     environment: the constructor evaluates `apiKey.orElse(getApiKeyFromEnv())` eagerly, reading `GOOGLE_API_KEY` and
     `GEMINI_API_KEY`, and logs a warning (without the value) if both are set.
   - Other environment variables can change behaviour silently unless set explicitly:
     - `GOOGLE_GENAI_USE_VERTEXAI` and `GOOGLE_GENAI_USE_ENTERPRISE` switch the backend, so set `vertexAI(false)`;
     - `GOOGLE_GEMINI_BASE_URL` changes the host, so set `HttpOptions.baseUrl`;
     - `GOOGLE_GENAI_CLIENT_MODE`, `_REPLAY_ID` and `_REPLAYS_DIRECTORY` are read by `DebugConfig`, whose builder
       setter is package-private.
   - **C15 must set the first two explicitly, and refuse to start if a `GOOGLE_GENAI_CLIENT_MODE` variable is set.**
5. **Base URL override: yes.** `HttpOptions.builder().baseUrl(String)` on the client, as used by every `AdkSpikeTest`
   test against `http://127.0.0.1:<port>`. The request path is `/v1beta/models/<model>:generateContent`.
6. **HTTP logging and telemetry.**
   - genai installs no HTTP logging interceptor; its only interceptor is `RetryInterceptor`. It logs through
     `java.util.logging`, warnings only on the call path, without headers or the key.
   - ADK's `Gemini` logs the request contents and config through SLF4J at `trace`. agent-adk has no SLF4J binding, so
     that goes nowhere. agent-service must keep `com.google.adk` below `trace` when it adds logging (M6). The key is not
     in the contents or the config in any case.
   - ADK's telemetry (`com.google.adk.telemetry.Tracing`, `Metrics`) uses `GlobalOpenTelemetry`, which is a no-op
     unless an SDK is registered globally; the model path does not call it.
   - **Retries are on by default:** `RetryInterceptor` retries 408, 429 and 5xx up to 5 attempts, with delays up to
     60 s. That would spend the 30 s budget out of sight, so the client sets
     `HttpOptions.retryOptions(HttpRetryOptions.builder().attempts(1))`.
     `AdkSpikeTest.serverError_oneAttempt_sentOnceAndTheErrorHoldsNoKey` asserts one request on a 503, and that no
     exception message in the chain holds the key.
   - `HttpOptions.timeout(Integer)` sets OkHttp's call timeout in milliseconds, which gives the per-call timeout of the
     wall-clock budget.

**The fallback, `com.google.genai:google-genai` 1.77.0** (the version ADK 1.13.0 depends on), is ready. Items 2 to 6
are the same, because they are genai's own behaviour, and item 1 becomes `client.models.generateContent(model,
contents, config)`: one call, no runner, no automatic function calling for declarations.

## The live round trip

`GeminiLiveSpikeIT` runs only under `-Plive-spike` with `GEMINI_API_KEY` set:

```
GEMINI_API_KEY=... ./mvnw -B -ntp -pl agent-adk verify -Plive-spike
```

- **Model:** `gemini-3.8-flash`, the stable Flash model listed at https://aistudio.google.com/docs/models (where
  https://ai.google.dev/gemini-api/docs/models redirects) on 2026-11-13. `-Dspike.model` overrides it. The agent's
  model is pinned in C15.
- **What it does:** our loop sends the tool result back.
- **What it prints:** turns, the tool call with its id, input, output and thinking tokens, and the answer's length.
  Never the key, a URL or a header.

Status: not run yet (no key in the build environment).

## Recommendation at the gate (C1, kept as the record)

The C1 recommendation was `google-genai` single-call, for the reasons now under Decision, with this caveat. ADK did not
block progress (item 1 works), so taking 6.5's fallback was a judgement on weight and risk, not necessity. The gate
accepted it.

## Consequences

- agent-adk and agent-service no longer carry ADK's tree. agent-adk carries genai's 51 jars, and agent-service inherits
  them through agent-adk.
- Every module's ArchUnit test forbids `com.google.genai..` as well as `com.google.adk..`.
- C15's `GeminiModelClient` must:
  - set `vertexAI(false)`, `baseUrl` and `retryOptions(attempts 1)` explicitly;
  - set a per-call `timeout` from the remaining budget;
  - refuse to start if `GOOGLE_GENAI_CLIENT_MODE` is set;
  - replace every exception message with a fixed reason;
  - decide whether thinking tokens (`thoughtsTokenCount`) count against the budget and the price table.
