package dev.ucplab.service.lab;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Calls {@link LabControls#sweep} once a second, so the 503 window ends on time. Local profile only. */
@Component
@Profile("local")
public class LabSweeper {

    private static final System.Logger LOG = System.getLogger(LabSweeper.class.getName());

    private final LabControls controls;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lab-sweeper");
        t.setDaemon(true);
        return t;
    });

    public LabSweeper(LabControls controls) {
        this.controls = Objects.requireNonNull(controls, "controls");
    }

    @PostConstruct
    void start() {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                controls.sweep();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "lab sweep failed: {0}", e.getClass().getSimpleName());
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }
}
