package dev.ucplab.service.web;

import dev.ucplab.core.id.IdGenerator;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Every error as {@code {code, message, request_id}} (23.5, m6-plan section 3.1, risk 9), from a controller, an
 * interceptor, Spring MVC itself, or the container ({@code /error}). The message is the code's fixed text: never a
 * stack trace, an exception's message or a class name, and never a Whitelabel page. {@code request_id} is the
 * request's {@code X-Request-Id}.
 *
 * <p>Codes outside 23.5's table are not invented: a method a route does not have is 404 {@code not_found}, and a body or
 * content type the route cannot read is 422 {@code validation_failed}, like any other bad input.
 */
@RestControllerAdvice
@RestController
public final class ApiErrors implements ErrorController {

    private static final System.Logger LOG = System.getLogger(ApiErrors.class.getName());

    private final IdGenerator ids;

    public ApiErrors(IdGenerator ids) {
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> api(ApiException e, HttpServletRequest request, HttpServletResponse response) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.code().status());
        e.retryAfter().ifPresent(d -> builder.header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds(d))));
        return builder.contentType(MediaType.APPLICATION_JSON)
                .body(body(e.code(), e.publicMessage(), RequestIds.idOf(request, response, ids)));
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class,
            HttpRequestMethodNotSupportedException.class})
    ResponseEntity<Map<String, String>> notFound(Exception e, HttpServletRequest request,
                                                 HttpServletResponse response) {
        return render(ErrorCode.NOT_FOUND, request, response);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class, ServletRequestBindingException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> badRequest(Exception e, HttpServletRequest request,
                                                   HttpServletResponse response) {
        return render(ErrorCode.VALIDATION_FAILED, request, response);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> internal(Exception e, HttpServletRequest request,
                                                 HttpServletResponse response) {
        // The class name only: a message can carry a request body or a credential (I8).
        LOG.log(System.Logger.Level.ERROR, "internal error: {0}", e.getClass().getSimpleName());
        return render(ErrorCode.INTERNAL, request, response);
    }

    /** The container's error page, for errors that never reached a controller (a malformed request, a filter). */
    @RequestMapping("/error")
    ResponseEntity<Map<String, String>> error(HttpServletRequest request, HttpServletResponse response) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int code = status instanceof Integer i ? i : 500;
        ErrorCode mapped = switch (code) {
            case 401 -> ErrorCode.UNAUTHENTICATED;
            case 403 -> ErrorCode.CSRF_FAILED;
            case 404, 405 -> ErrorCode.NOT_FOUND;
            case 429 -> ErrorCode.RATE_LIMITED;
            default -> code >= 400 && code < 500 ? ErrorCode.VALIDATION_FAILED : ErrorCode.INTERNAL;
        };
        return render(mapped, request, response);
    }

    private ResponseEntity<Map<String, String>> render(ErrorCode code, HttpServletRequest request,
                                                       HttpServletResponse response) {
        return ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_JSON)
                .body(body(code, code.message(), RequestIds.idOf(request, response, ids)));
    }

    private static Map<String, String> body(ErrorCode code, String message, String requestId) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", code.code());
        body.put("message", message);
        body.put("request_id", requestId);
        return body;
    }

    /** {@code Retry-After} in whole seconds, rounded up (23.5 {@code intent_busy}). */
    static long retryAfterSeconds(Duration d) {
        long seconds = d.getSeconds();
        return d.getNano() > 0 ? seconds + 1 : seconds;
    }
}
