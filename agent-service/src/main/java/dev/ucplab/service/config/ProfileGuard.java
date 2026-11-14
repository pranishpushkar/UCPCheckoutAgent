package dev.ucplab.service.config;

import dev.ucplab.core.config.AgentProfile;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Maps {@code AGENT_PROFILE} to exactly one Spring profile (m6-plan section 4, 18.8). {@code demo} is refused in M6
 * (D10), and no other agent profile may be active beside the chosen one: in particular {@code local} with {@code prod}
 * refuses to start (18), since {@code local} brings the dev sign-in. Messages are fixed and never carry a value.
 */
public final class ProfileGuard {

    static final String DEMO_REFUSED = "AGENT_PROFILE=demo is not available in M6";
    static final String MIXED_REFUSED = "the local and prod profiles cannot both be active";
    static final String OTHER_REFUSED = "a Spring profile other than AGENT_PROFILE's is active";

    private static final List<String> AGENT_PROFILES = Arrays.stream(AgentProfile.values())
            .map(p -> p.name().toLowerCase(Locale.ROOT)).toList();

    private ProfileGuard() {
    }

    /** The Spring profile for {@code profile}. */
    public static String springProfile(AgentProfile profile) {
        Objects.requireNonNull(profile, "profile");
        if (profile == AgentProfile.DEMO) {
            throw new IllegalStateException(DEMO_REFUSED);
        }
        return profile.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Checks the Spring profiles active once the environment is prepared: the one for {@code profile} must be among
     * them, and no other agent profile may be.
     */
    public static void check(AgentProfile profile, String[] active) {
        String expected = springProfile(profile);
        List<String> agentActive = Arrays.stream(active).filter(AGENT_PROFILES::contains).distinct().toList();
        if (agentActive.contains("local") && agentActive.contains("prod")) {
            throw new IllegalStateException(MIXED_REFUSED);
        }
        if (!agentActive.equals(List.of(expected))) {
            throw new IllegalStateException(OTHER_REFUSED);
        }
    }
}
