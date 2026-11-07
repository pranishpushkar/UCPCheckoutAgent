package dev.ucplab.core.merchant;

import java.util.Objects;
import java.util.Optional;

/** A message in a merchant response (Appendix B). Untrusted merchant data. */
public record MerchantMessage(String type, String code, Optional<String> path, String content, Optional<String> severity) {

    public MerchantMessage {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(severity, "severity");
    }
}
