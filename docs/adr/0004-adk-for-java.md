# ADR 0004: ADK for Java, and what the live model client is built on

Status: proposed (M5, C1). The version and the dependency are in place for the spike. **The choice between ADK
single-turn and `google-genai` single-call is open**, and is decided at the gate after C1.

## Decision so far

- `com.google.adk:google-adk` **1.13.0**, pinned in the parent pom (`google-adk.version`) and added to `agent-adk` as
  an ordinary compile dependency. agent-adk is the only module that imports ADK or Gemini SDK types (5.2). Every other
  module's ArchUnit test forbids `com.google.adk..` and `com.google.genai..`; agent-core's JDK-only rule already did.
- **Why 1.13.0:** the versions on Maven Central (`maven-metadata.xml`, read 2026-11-13) are 1.0.0-rc.1, 1.0.0
  (2026-03-30), then 1.1.0 to 1.14.0. 1.14.0 (2026-11-02) was under two weeks old, so the newest release older than
  that, 1.13.0, is pinned.
- **Our loop owns every tool call** (6.3, I6). ADK's agents, runner, sessions and flows are not used.

## The dependency tree

`./mvnw dependency:tree -pl agent-adk` (kept outside the repo):

| Item | Result |
| --- | --- |
| Size | **216 jars, about 198 MB** on agent-adk's runtime classpath. `google-genai` alone is 38 jars, about 21 MB |
| ADK's own compile dependencies | 33, among them `anthropic-java` and `anthropic-java-vertex`, `google-cloud-aiplatform`, `-speech`, `-storage` and `-bigquery`, `docker-java`, `mcp`, `okhttp` 5, Jackson 2.20, Guava 33, protobuf 4, RxJava 3, Reactor, OpenTelemetry API, SDK and OTLP exporter, Apache Arrow with `arrow-memory-netty`, and `google-genai` 1.77.0 |
| Largest groups by size | `com.google` 73 MB, `io.grpc` 24 MB, `org.eclipse` 13 MB, BouncyCastle 10.5 MB, Anthropic 9.7 MB, ByteBuddy 9.3 MB |
| Version clash inside ADK's tree | Maven resolves **`jackson-databind` 2.20.2 with `jackson-core` 2.18.2**. `google-cloud-storage` declares `jackson-core` 2.18.2 one level nearer than databind's own 2.20.2, and the nearest declaration wins. Jackson does not support a databind newer than its core. The spike's tests parse real Gemini JSON through it without error, but a newer code path could fail with `NoSuchMethodError`. Not fixed in C1 |
| Clash with what is already on the classpath | None. `flyway-core` 13.12.1 and `sqlite-jdbc` 3.54.1.0 bring no transitive dependencies, so there is no Jackson, Guava, Netty or OpenTelemetry to clash with. agent-service, which depends on both agent-persistence and agent-adk, now inherits the whole tree, with Jackson 2.17 to 2.20 versions omitted for conflict |
| Build | Compiles and tests on Java 21; the enforcer rule (JDK 21) passes. No exclusion was added |

## Items 1 to 6 (m5-plan section 2)

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

`AdkLiveSpikeIT` runs only under `-Plive-spike` with `GEMINI_API_KEY` set:

```
GEMINI_API_KEY=... ./mvnw -B -ntp -pl agent-adk verify -Plive-spike
```

- **Model:** `gemini-3.8-flash`, the stable Flash model listed at https://aistudio.google.com/docs/models (where
  https://ai.google.dev/gemini-api/docs/models redirects) on 2026-11-13. `-Dspike.model` overrides it. The agent's
  model is pinned in C15.
- **What it does:** our loop, not ADK's runner, sends the tool result back.
- **What it prints:** turns, the tool call with its id, input and output tokens, and the answer's length. Never the key,
  a URL or a header.

Status: not run yet (no key in the build environment).

## Recommendation

**`google-genai` single-call**, with ADK removed from the build before C15. The reasons:

- **ADK's model class adds nothing we use.** `Gemini.generateContent` is a thin wrapper around genai's
  `models.generateContent`. What it adds is request preparation: stripping `adk-` call ids, making sure the last content
  is the user's, and tracking headers. None of that is needed when our loop builds the request.
- **The cost of ADK is the other 178 jars and 177 MB.** They include a Docker client, the Anthropic SDK, BigQuery,
  Arrow with Netty and an OpenTelemetry SDK, on agent-adk's and agent-service's classpaths. They bring a Jackson
  databind/core mismatch we would have to manage, and a release roughly every two weeks to track.
- **6.5 already allows it:** "If the ADK API blocks progress, implement `ModelClient` directly on the Gemini API.
  Nothing else changes."
  - ADK does not block progress (item 1 works), so taking the fallback is a judgement on weight and risk, not
    necessity. It needs your decision and a 6.5 wording change (D8 would name `google-genai` instead).

**If ADK stays**, C15:
- builds `GeminiModelClient` on `new Gemini(model, client)` with an explicit genai `Client`;
- manages `jackson-core` to databind's version in agent-adk, with a test;
- documents the classpath weight in agent-service.

## Consequences

- agent-service inherits ADK's whole tree while ADK is a compile dependency of agent-adk.
- Every module's ArchUnit test forbids `com.google.genai..` as well as `com.google.adk..`.
- C15's `GeminiModelClient` must, whichever library is chosen:
  - set `vertexAI(false)`, `baseUrl` and `retryOptions(attempts 1)` explicitly;
  - set a per-call `timeout` from the remaining budget;
  - refuse to start if `GOOGLE_GENAI_CLIENT_MODE` is set;
  - replace every exception message with a fixed reason.
