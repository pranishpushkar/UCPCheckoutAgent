package dev.ucplab.faultproxy;

import java.util.List;
import java.util.Objects;

/**
 * A path glob (§12 Matching): {@code *} matches exactly one non-empty path segment, {@code **} any number of segments
 * (zero included), and every other segment matches itself literally. A pattern starts with {@code /}, and a literal
 * segment may not contain {@code *}. Matching is against the raw (still percent-encoded) path, without the query.
 */
public record PathPattern(String pattern) {

    /** @throws IllegalArgumentException if the pattern does not start with {@code /} or has a partial wildcard */
    public PathPattern {
        Objects.requireNonNull(pattern, "pattern");
        if (!pattern.startsWith("/")) {
            throw new IllegalArgumentException("pathPattern must start with '/': " + pattern);
        }
        for (String segment : split(pattern)) {
            if (segment.contains("*") && !segment.equals("*") && !segment.equals("**")) {
                throw new IllegalArgumentException("'*' must be a whole segment: " + pattern);
            }
        }
    }

    public boolean matches(String rawPath) {
        Objects.requireNonNull(rawPath, "rawPath");
        if (!rawPath.startsWith("/")) {
            return false;
        }
        return matches(split(pattern), 0, split(rawPath), 0);
    }

    private static boolean matches(List<String> pattern, int p, List<String> path, int i) {
        if (p == pattern.size()) {
            return i == path.size();
        }
        String segment = pattern.get(p);
        if (segment.equals("**")) {
            for (int j = i; j <= path.size(); j++) {
                if (matches(pattern, p + 1, path, j)) {
                    return true;
                }
            }
            return false;
        }
        if (i == path.size()) {
            return false;
        }
        boolean fits = segment.equals("*") ? !path.get(i).isEmpty() : segment.equals(path.get(i));
        return fits && matches(pattern, p + 1, path, i + 1);
    }

    private static List<String> split(String path) {
        return List.of(path.substring(1).split("/", -1));
    }
}
