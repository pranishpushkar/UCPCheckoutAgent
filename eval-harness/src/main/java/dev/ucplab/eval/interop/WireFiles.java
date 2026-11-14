package dev.ucplab.eval.interop;

import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.json.LenientJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The normalised recordings of a run as files (H1 C9c): docs/interop/wire/{merchant}-{row}.json. They hold step names, relative
 * paths, statuses, JSON field paths with types and error codes, and nothing else: no value, header or token.
 */
public final class WireFiles {

    private WireFiles() {
    }

    public static void write(Path dir, String merchant, String row, List<WireStep> steps) throws IOException {
        Files.createDirectories(dir);
        List<Object> list = new ArrayList<>();
        for (WireStep s : steps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("step", s.step());
            m.put("path", s.path());
            m.put("status", (long) s.status());
            m.put("errorCode", s.errorCode());
            m.put("requestFields", new LinkedHashMap<String, Object>(s.requestFields()));
            m.put("responseFields", new LinkedHashMap<String, Object>(s.responseFields()));
            list.add(m);
        }
        Files.writeString(dir.resolve(merchant + "-" + row + ".json"), CanonicalJson.write(Map.of("steps", list)) + "\n");
    }

    @SuppressWarnings("unchecked")
    public static List<WireStep> read(Path file) throws IOException {
        Map<String, Object> root = LenientJson.readObject(Files.readString(file));
        List<WireStep> out = new ArrayList<>();
        for (Object o : (List<Object>) root.get("steps")) {
            Map<String, Object> m = (Map<String, Object>) o;
            out.add(new WireStep((String) m.get("step"), (String) m.get("path"), ((Number) m.get("status")).intValue(),
                    strings((Map<String, Object>) m.get("requestFields")), strings((Map<String, Object>) m.get("responseFields")),
                    (String) m.get("errorCode")));
        }
        return out;
    }

    private static Map<String, String> strings(Map<String, Object> m) {
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, String.valueOf(v)));
        return out;
    }
}
