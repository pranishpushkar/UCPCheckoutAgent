package dev.ucplab.service.web;

import dev.ucplab.core.intent.IntentId;

/** Builds an {@link IntentId} from a path segment: a blank or malformed id is 404 like a missing one (T6). */
final class IntentIds {

    private IntentIds() {
    }

    static IntentId parse(String raw) {
        try {
            return new IntentId(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.of(ErrorCode.NOT_FOUND);
        }
    }
}
