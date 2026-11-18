package dev.ucplab.service.config;

import dev.ucplab.core.agent.ModelClient;
import dev.ucplab.core.agent.ModelClientSettings;
import dev.ucplab.core.id.IdGenerator;
import dev.ucplab.core.merchant.CatalogGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.orchestrator.DefaultOrchestrator;
import dev.ucplab.persistence.SqlIntentRepository;
import dev.ucplab.persistence.SqlRunRepository;
import dev.ucplab.runtime.AgentBudget;
import dev.ucplab.runtime.AgentRuntime;
import dev.ucplab.runtime.ModelClients;
import dev.ucplab.runtime.ToolPorts;
import dev.ucplab.service.config.StackConfiguration.ConfiguredGateways;
import dev.ucplab.service.run.DemoModel;
import dev.ucplab.service.run.RunSupervisor;
import dev.ucplab.service.run.RunSupervisorConfig;
import dev.ucplab.ucp.CircuitBreakers;
import dev.ucplab.ucp.registry.ConfiguredMerchantRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The agent half of the stack (C14): the model {@code AGENT_MODEL} names, the tools' catalog, the runtime and the run
 * supervisor. The live client is built only for {@code gemini}, with the key from {@link ServiceProperties}; a missing
 * key or provider refuses to start (the key check is in {@code ServiceProperties}, the provider check here).
 */
@Configuration(proxyBeanMethods = false)
public class RunConfiguration {

    static final String MODEL_UNAVAILABLE = "AGENT_MODEL=gemini needs the gemini provider, which is not on the classpath";
    /** One model call may take this long; the run's own wall clock stays 30 s (6.3, m6-plan section 3.2). */
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);

    /** The model for {@code AGENT_MODEL}; a test may register its own. */
    @Bean
    @ConditionalOnMissingBean
    ModelClient modelClient(ServiceProperties properties) {
        return switch (properties.agentModel()) {
            case SCRIPTED -> new DemoModel();
            case GEMINI -> {
                if (!ModelClients.available("gemini")) {
                    throw new IllegalStateException(MODEL_UNAVAILABLE);
                }
                ModelClientSettings settings = ModelClientSettings.defaults().withCallTimeout(CALL_TIMEOUT);
                if (properties.modelBaseUrl().isPresent()) {
                    settings = settings.withBaseUrl(properties.modelBaseUrl().get());
                }
                yield ModelClients.load("gemini", properties.modelKey().orElseThrow(), settings);
            }
        };
    }

    /** One catalog client per configured merchant, over the same gateway and the one {@link CircuitBreakers} (8.3, 19). */
    @Bean
    CatalogClients catalogClients(ConfiguredGateways gateways, MerchantWiring.GatewayParts parts) {
        Map<MerchantId, CatalogGateway> byId = new LinkedHashMap<>();
        gateways.byId().forEach((id, gateway) -> byId.put(id, MerchantWiring.catalog(gateway, parts)));
        return new CatalogClients(Map.copyOf(byId));
    }

    /** The first merchant's catalog client: M6's one client, which a test or a single-merchant caller can still ask for. */
    @Bean
    CatalogGateway catalogClient(ServiceProperties properties, CatalogClients clients) {
        return clients.byId().get(properties.merchant().id());
    }

    @Bean
    AgentRuntime agentRuntime(ModelClient model, ServiceProperties properties, ConfiguredMerchantRegistry registry,
                              CatalogClients catalogs, DefaultOrchestrator orchestrator, SqlRunRepository runs,
                              Clock clock, RunSupervisorConfig supervisorConfig) {
        String name = switch (properties.agentModel()) {
            case SCRIPTED -> "scripted";
            case GEMINI -> "gemini";
        };
        ToolPorts ports = new ToolPorts(registry, new LinkedHashMap<MerchantId, CatalogGateway>(catalogs.byId()), orchestrator);
        return new AgentRuntime(model, name, ports, runs, AgentBudget.DEFAULT, supervisorConfig.askTimeout(), clock);
    }

    /** The catalog client of each configured merchant, SUSPENDED ones included (19). */
    public record CatalogClients(Map<MerchantId, CatalogGateway> byId) {
        public CatalogClients {
            byId = Map.copyOf(Objects.requireNonNull(byId, "byId"));
        }
    }

    /** One config drives both the runtime's {@code ask_user} wait and the supervisor's orphan rule, so they agree. */
    @Bean
    @ConditionalOnMissingBean
    RunSupervisorConfig runSupervisorConfig() {
        return RunSupervisorConfig.defaults();
    }

    /** Closed by {@link StackLifecycle} before the database, never by Spring's own destroy step. */
    @Bean(destroyMethod = "")
    RunSupervisor runSupervisor(AgentRuntime runtime, SqlRunRepository runs, SqlIntentRepository intents, Clock clock,
                                IdGenerator ids, RunSupervisorConfig config) {
        return new RunSupervisor(runtime, runs, intents, clock, ids, config);
    }
}
