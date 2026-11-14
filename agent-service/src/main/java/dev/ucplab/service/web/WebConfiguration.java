package dev.ucplab.service.web;

import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.SessionRepository;
import dev.ucplab.core.identity.UserRepository;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The session and CSRF interceptors on {@code /api/**} and {@code POST /auth/logout} (m6-plan section 3.1), and the
 * argument resolver that hands a controller the caller {@link SessionInterceptor} found, as a {@link SignedIn} or an
 * {@link AuthContext}. No controller reads a user id from anywhere else (T1).
 */
@Configuration(proxyBeanMethods = false)
public class WebConfiguration implements WebMvcConfigurer {

    private static final String[] SIGNED_IN_PATHS = {"/api/**", "/auth/logout"};

    private final SessionRepository sessions;
    private final UserRepository users;
    private final Clock clock;

    public WebConfiguration(SessionRepository sessions, UserRepository users, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.users = Objects.requireNonNull(users, "users");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SessionInterceptor(sessions, users, clock)).addPathPatterns(SIGNED_IN_PATHS)
                .order(0);
        registry.addInterceptor(new CsrfInterceptor()).addPathPatterns(SIGNED_IN_PATHS).order(1);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CallerResolver());
    }

    /** Resolves a {@link SignedIn} or {@link AuthContext} parameter from the interceptor's request attribute. */
    private static final class CallerResolver implements HandlerMethodArgumentResolver {
        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            Class<?> type = parameter.getParameterType();
            return type == SignedIn.class || type == AuthContext.class;
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                      WebDataBinderFactory binders) {
            if (!(request.getAttribute(SignedIn.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST)
                    instanceof SignedIn signedIn)) {
                throw ApiException.of(ErrorCode.UNAUTHENTICATED);
            }
            return parameter.getParameterType() == SignedIn.class ? signedIn : signedIn.auth();
        }
    }
}
