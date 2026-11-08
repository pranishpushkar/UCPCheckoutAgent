package dev.ucplab.core.orchestrator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A generated run: entries executed in order by {@link ScheduleExecutor} (section 14, level 2). */
record Schedule(List<ScheduleEntry> entries) {

    Schedule {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    int size() {
        return entries.size();
    }

    /** This schedule with entry {@code index} removed: the only shrinking move. */
    Schedule without(int index) {
        List<ScheduleEntry> fewer = new ArrayList<>(entries);
        fewer.remove(index);
        return new Schedule(fewer);
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            out.append(String.format("  %2d  %s%n", i, entries.get(i)));
        }
        return out.toString();
    }
}
