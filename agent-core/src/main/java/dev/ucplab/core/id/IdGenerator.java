package dev.ucplab.core.id;

/** Source of new identifiers (Appendix B). UUIDv7 in production, sequential in tests; core never calls UUID.randomUUID (7.8). */
public interface IdGenerator {

    String next();
}
