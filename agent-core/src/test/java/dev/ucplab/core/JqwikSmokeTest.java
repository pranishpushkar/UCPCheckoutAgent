package dev.ucplab.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

class JqwikSmokeTest {

    // Covers: section 14, level 2 (tooling only: proves the jqwik engine runs under ./mvnw verify)
    @Property
    void list_reversedTwice_equalsOriginal(@ForAll List<Integer> original) {
        List<Integer> copy = new ArrayList<>(original);
        Collections.reverse(copy);
        Collections.reverse(copy);
        assertThat(copy).isEqualTo(original);
    }
}
