package dev.ucplab.service.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

/**
 * Drops, at any level, the few loggers that write request or response <em>bodies</em> when an operator turns DEBUG or
 * TRACE on: Spring MVC's argument and return-value processors ("Read ... to [...]", "Writing [...]"), the handler
 * method's "Arguments: [...]", and Tomcat's dump of the bytes it reads. Bodies carry the approval token and the user's
 * messages, and are never logged (23.2, I8). The redaction customiser is the second layer; this is the first, because
 * a body printed as a Java map ({@code {token=...}}) has no pattern to mask.
 */
public final class BodyLogGuard extends TurboFilter {

    /** Logger names, or prefixes ending in a dot, whose events are dropped. */
    static final List<String> DROPPED = List.of("org.springframework.web.servlet.mvc.method.annotation.",
            "org.springframework.web.method.HandlerMethod", "org.apache.coyote.http11.Http11InputBuffer");

    private BodyLogGuard() {
    }

    /** Adds the guard to the logging system once; a second call finds it and changes nothing. */
    public static void install() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            throw new IllegalStateException("the logging system is not Logback, so bodies cannot be kept out of it");
        }
        boolean present = context.getTurboFilterList().stream().anyMatch(f -> f instanceof BodyLogGuard);
        if (!present) {
            BodyLogGuard guard = new BodyLogGuard();
            guard.start();
            context.addTurboFilter(guard);
        }
    }

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params, Throwable t) {
        String name = logger.getName();
        for (String dropped : DROPPED) {
            if (dropped.endsWith(".") ? name.startsWith(dropped) : name.equals(dropped)) {
                return FilterReply.DENY;
            }
        }
        return FilterReply.NEUTRAL;
    }
}
