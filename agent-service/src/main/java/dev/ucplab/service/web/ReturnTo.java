package dev.ucplab.service.web;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Cleans {@code return_to} (18.3 [CONTRACT]): a relative path that starts with {@code /} and not {@code //} is kept;
 * anything else becomes {@code /}, so the sign-in cannot be used as an open redirect. Stricter than the contract in
 * two ways: only a plain set of URL characters is allowed (no backslash, space or control character), and the value
 * must still pass after one more percent-decoding, so an encoded {@code %2F%2F} or {@code %5C} cannot slip through.
 */
final class ReturnTo {

    static final String HOME = "/";
    private static final int MAX_LENGTH = 512;
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9/_.~\\-?=&%]*");

    private ReturnTo() {
    }

    static String clean(Optional<String> value) {
        if (value.isEmpty()) {
            return HOME;
        }
        String candidate = value.get();
        if (!acceptable(candidate)) {
            return HOME;
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(candidate, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return HOME;
        }
        return acceptable(decoded) ? candidate : HOME;
    }

    private static boolean acceptable(String path) {
        return path.length() <= MAX_LENGTH && path.startsWith("/") && !path.startsWith("//")
                && PLAIN.matcher(path).matches();
    }
}
