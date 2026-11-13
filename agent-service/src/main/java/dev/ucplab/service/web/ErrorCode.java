package dev.ucplab.service.web;

/**
 * The API's error codes (23.5 [CONTRACT]), each with its status and a fixed message. A message never carries input,
 * an exception's text or a class name (risk 9, I8).
 */
public enum ErrorCode {
    UNAUTHENTICATED("unauthenticated", 401, "sign in first"),
    REAUTH_REQUIRED("reauth_required", 401, "sign in again to approve"),
    CSRF_FAILED("csrf_failed", 403, "missing or wrong CSRF token"),
    NOT_FOUND("not_found", 404, "not found"),
    APPROVAL_NOT_PENDING("approval_not_pending", 409, "this approval is no longer pending"),
    INTENT_BUSY("intent_busy", 409, "the intent is busy; retry later"),
    RUN_NOT_WAITING("run_not_waiting", 409, "the run is not waiting for a message"),
    VALIDATION_FAILED("validation_failed", 422, "the request is not valid"),
    RATE_LIMITED("rate_limited", 429, "too many requests"),
    INTERNAL("internal", 500, "internal error"),
    MODEL_UNAVAILABLE("model_unavailable", 503, "the model is unavailable");

    private final String code;
    private final int status;
    private final String message;

    ErrorCode(String code, int status, String message) {
        this.code = code;
        this.status = status;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public String message() {
        return message;
    }
}
