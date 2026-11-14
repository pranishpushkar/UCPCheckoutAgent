package dev.ucplab.eval.interop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares two recordings of the same row, step by step (H1 C9b). A difference is a step on one side only, a different HTTP
 * status, a different endpoint path, a JSON field path on one side only or of another type (request and response), or a
 * different error code. Its {@code id} is computed from the row, step, kind and field path, never from a value; its
 * {@code matchText} is what a {@code match} pattern of differences.md is tested against.
 */
public final class WireDiff {

    /** One raw wire difference. */
    public record Diff(String id, String row, String step, String kind, String detail, String matchText) {
    }

    private WireDiff() {
    }

    public static List<Diff> diff(String row, List<WireStep> lab, List<WireStep> sample) {
        Map<String, WireStep> a = byStep(lab);
        Map<String, WireStep> b = byStep(sample);
        Set<String> names = new TreeSet<>(a.keySet());
        names.addAll(b.keySet());
        List<Diff> out = new ArrayList<>();
        for (String step : names) {
            WireStep l = a.get(step);
            WireStep s = b.get(step);
            if (l == null || s == null) {
                out.add(make(row, step, l == null ? "step-only-sample" : "step-only-lab", ""));
                continue;
            }
            if (l.status() != s.status()) {
                out.add(make(row, step, "status", l.status() + "|" + s.status()));
            }
            if (!l.path().equals(s.path())) {
                out.add(make(row, step, "path", l.path() + "|" + s.path()));
            }
            if (!l.errorCode().equals(s.errorCode())) {
                out.add(make(row, step, "error-code", l.errorCode() + "|" + s.errorCode()));
            }
            fieldDiffs(out, row, step, "req:", l.requestFields(), s.requestFields());
            fieldDiffs(out, row, step, "resp:", l.responseFields(), s.responseFields());
        }
        return out;
    }

    private static void fieldDiffs(List<Diff> out, String row, String step, String side, Map<String, String> l, Map<String, String> s) {
        Set<String> paths = new TreeSet<>(l.keySet());
        paths.addAll(s.keySet());
        for (String p : paths) {
            if (!s.containsKey(p)) {
                out.add(make(row, step, "field-only-lab", side + p));
            } else if (!l.containsKey(p)) {
                out.add(make(row, step, "field-only-sample", side + p));
            } else if (!l.get(p).equals(s.get(p))) {
                out.add(make(row, step, "type", side + p));
            }
        }
    }

    private static Diff make(String row, String step, String kind, String detail) {
        boolean field = kind.startsWith("field-") || kind.equals("type");
        String idDetail = field ? detail : "";
        return new Diff(row + "/" + step + "/" + kind + (idDetail.isEmpty() ? "" : ":" + idDetail), row, step, kind, detail,
                row + "/" + step + "/" + kind + ":" + detail);
    }

    private static Map<String, WireStep> byStep(List<WireStep> steps) {
        Map<String, WireStep> m = new LinkedHashMap<>();
        steps.forEach(s -> m.putIfAbsent(s.step(), s));
        return m;
    }
}
