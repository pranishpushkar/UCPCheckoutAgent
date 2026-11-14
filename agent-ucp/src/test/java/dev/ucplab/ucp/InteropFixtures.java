package dev.ucplab.ucp;

import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.json.LenientJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** The wire records of the official sample merchant (docs/interop/fixtures, copied onto the test classpath by the pom). */
final class InteropFixtures {

    /** The port the tests give the sample's base URL; the fixtures carry {@code <port>} where the real one was. */
    static final int PORT = 8081;

    private InteropFixtures() {
    }

    /** The recorded fixture {@code name} (for example {@code 26-stock-complete-sold-out}), as a map. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> read(String name) {
        try (InputStream in = InteropFixtures.class.getResourceAsStream("/interop/" + name + ".json")) {
            if (in == null) {
                throw new IllegalStateException("no fixture " + name);
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("<port>", Integer.toString(PORT));
            return LenientJson.readObject(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> response(String name) {
        return (Map<String, Object>) read(name).get("response");
    }

    /** The recorded response as the transport would deliver it. */
    @SuppressWarnings("unchecked")
    static HttpReply reply(String name) {
        Map<String, Object> response = response(name);
        Object body = response.get("body");
        String json = body == null ? "" : CanonicalJson.write(body);
        return new HttpReply(((Number) response.get("status")).intValue(), Map.of(), json.getBytes(StandardCharsets.UTF_8));
    }

    /** The recorded request's header {@code header}. */
    @SuppressWarnings("unchecked")
    static String requestHeader(String name, String header) {
        Map<String, Object> request = (Map<String, Object>) read(name).get("request");
        return (String) ((Map<String, Object>) request.get("headers")).get(header);
    }

    static List<String> names(String... n) {
        return List.of(n);
    }
}
