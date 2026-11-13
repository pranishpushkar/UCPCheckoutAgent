package dev.ucplab.service;

import dev.ucplab.service.config.Environment;
import dev.ucplab.service.config.ProfileGuard;
import dev.ucplab.service.config.ServiceProperties;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The agent service (section 5.1, 23): the REST API and the lab console. The settings are read and checked before
 * Spring starts ({@link ServiceProperties}), the one Spring profile for {@code AGENT_PROFILE} is added, and once the
 * environment is prepared {@link ProfileGuard} refuses any other agent profile beside it.
 */
@SpringBootApplication
public class ServiceApplication {

    public static void main(String[] args) {
        start(Environment.system(), List.of(), args);
    }

    /**
     * Starts the service on {@code env}.
     *
     * @param initializers run before the context refreshes; a test registers a fake clock or other beans here
     */
    public static ConfigurableApplicationContext start(
            Environment env, List<ApplicationContextInitializer<ConfigurableApplicationContext>> initializers,
            String... args) {
        Objects.requireNonNull(env, "env");
        ServiceProperties properties = ServiceProperties.from(env);
        SpringApplication app = new SpringApplication(ServiceApplication.class);
        app.setAdditionalProfiles(properties.springProfile());
        app.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event ->
                ProfileGuard.check(properties.profile(), event.getEnvironment().getActiveProfiles()));
        app.addInitializers(context -> {
            context.getBeanFactory().registerSingleton("serviceEnvironment", env);
            context.getBeanFactory().registerSingleton("serviceProperties", properties);
        });
        initializers.forEach(app::addInitializers);
        return app.run(args);
    }
}
