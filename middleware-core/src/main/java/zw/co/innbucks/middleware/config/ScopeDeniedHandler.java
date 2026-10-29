package zw.co.innbucks.middleware.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.net.URI;

/**
 * A 403 decided at the security FILTER (a token with no customer scope on
 * any path but {@code GET /me/identity}). Answers the same body the
 * method-security 403 gets from {@link GlobalExceptionHandler}, so a client
 * sees one {@code insufficient_scope} shape wherever the check ran. Spring's
 * default here is an empty body.
 */
class ScopeDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    ScopeDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException ex) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create("about:blank"));
        problem.setTitle("Forbidden");
        problem.setDetail("Your access token doesn't grant permission for this action.");
        problem.setProperty("errorCode", "insufficient_scope");
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
