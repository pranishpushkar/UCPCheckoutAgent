package dev.ucplab.eval.interop;

import java.util.Map;

/**
 * One recorded merchant call, normalised (H1 C9b): the step name ({@code discover}, {@code create}, {@code update},
 * {@code update#2}, {@code complete}, {@code get}, {@code cancel}), the endpoint path with ids replaced, the status, the JSON
 * field paths and their types on each side (never their values, so no token or address can be in a recording), and the error code.
 */
public record WireStep(String step, String path, int status, Map<String, String> requestFields,
                       Map<String, String> responseFields, String errorCode) {
}
