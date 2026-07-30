package zw.co.innbucks.middleware.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.core.MethodParameter;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unhandledThrowableMapsTo500WithFriendlyCopyAndNeverLeaksMessage() {
        // An internal exception's message must not reach the customer — only a
        // friendly generic apology + errorCode=internal_error.
        Throwable secretLeak = new IllegalStateException(
                "DB password '/very/secret/path' invalid");

        ResponseEntity<ProblemDetail> response = handler.unexpected(secretLeak);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getTitle()).isEqualTo("Something went wrong on our side");
        assertThat(body.getProperties()).containsEntry("errorCode", "internal_error");
        assertThat(body.getDetail()).doesNotContain("secret");
        assertThat(body.getDetail()).doesNotContain("password");
        assertThat(body.getDetail()).doesNotContain("DB");
    }

    @Test
    void unreadableJsonMapsTo400WithFriendlyCopyAndNeverLeaksParserDetail() {
        HttpInputMessage stubInput = new HttpInputMessage() {
            @Override public java.io.InputStream getBody() { return java.io.InputStream.nullInputStream(); }
            @Override public org.springframework.http.HttpHeaders getHeaders() {
                return new org.springframework.http.HttpHeaders();
            }
        };
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: Unexpected character ('}' (code 125)) at line 1 col 7",
                stubInput);

        ResponseEntity<ProblemDetail> response = handler.unreadable(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ProblemDetail body = response.getBody();
        assertThat(body.getProperties()).containsEntry("errorCode", "malformed_request");
        assertThat(body.getTitle()).isEqualTo("Invalid request");
        assertThat(body.getDetail()).doesNotContainIgnoringCase("json parse");
        assertThat(body.getDetail()).doesNotContain("line 1");
    }

    @Test
    void missingHeaderMapsTo400WithFriendlyCopyAndNeverLeaksHeaderName() throws Exception {
        Method m = String.class.getMethod("length");
        MethodParameter param = new MethodParameter(m, -1);
        MissingRequestHeaderException ex =
                new MissingRequestHeaderException("X-Internal-Token", param);

        ResponseEntity<ProblemDetail> response = handler.missingHeader(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ProblemDetail body = response.getBody();
        assertThat(body.getProperties()).containsEntry("errorCode", "missing_header");
        // The specific header name is operational info — don't tell the
        // customer which security header is missing.
        assertThat(body.getDetail()).doesNotContain("X-Internal-Token");
    }

    @Test
    void missingParamMapsTo400WithFriendlyCopy() {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("amount", "Long");

        ResponseEntity<ProblemDetail> response = handler.missingParam(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getProperties())
                .containsEntry("errorCode", "missing_parameter");
        assertThat(response.getBody().getDetail()).doesNotContain("amount");
    }

    @Test
    void wrongContentTypeMapsTo415WithFriendlyCopy() {
        HttpMediaTypeNotSupportedException ex =
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_XML, java.util.List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<ProblemDetail> response = handler.wrongContentType(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody().getProperties())
                .containsEntry("errorCode", "unsupported_media_type");
    }

    @Test
    void wrongMethodMapsTo405WithFriendlyCopy() {
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("PATCH");

        ResponseEntity<ProblemDetail> response = handler.wrongMethod(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody().getProperties())
                .containsEntry("errorCode", "method_not_allowed");
    }
}
