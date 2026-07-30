package zw.co.innbucks.middleware.auth.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import zw.co.innbucks.middleware.auth.exception.AccountLockedException;
import zw.co.innbucks.middleware.auth.exception.BackoffActiveException;
import zw.co.innbucks.middleware.auth.exception.InvalidCredentialsException;
import zw.co.innbucks.middleware.auth.exception.MalformedRequestException;
import zw.co.innbucks.middleware.auth.exception.PinNotSetException;
import zw.co.innbucks.middleware.auth.exception.PinSetNotAllowedException;
import zw.co.innbucks.middleware.auth.exception.RefreshTokenInvalidException;
import zw.co.innbucks.middleware.auth.exception.RefreshTokenReplayException;
import zw.co.innbucks.middleware.common.msisdn.InvalidMsisdnException;
import zw.co.innbucks.middleware.notify.NotificationDeliveryException;
import zw.co.innbucks.middleware.otp.VerificationTokenInvalidException;
import zw.co.innbucks.middleware.ratelimit.RateLimitExceededException;

import java.net.URI;

@Slf4j
@RestControllerAdvice
public class AuthExceptionHandler {

    private static final URI PROBLEM_TYPE = URI.create("about:blank");

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ProblemDetail> invalidCredentials(InvalidCredentialsException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setType(PROBLEM_TYPE);
        problem.setTitle("Sign-in failed");
        problem.setDetail("The phone number or PIN you entered is incorrect. Please try again.");
        problem.setProperty("errorCode", "invalid_credentials");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler(VerificationTokenInvalidException.class)
    public ResponseEntity<ProblemDetail> verificationTokenInvalid(VerificationTokenInvalidException ex) {
        log.warn("Verification token rejected: {}", ex.getMessage());
        // WRONG_ISSUER and REPLAYED both fold into the generic invalid code on
        // purpose: never tell a caller the token was minted for another deployment
        // or has already been used — that leaks information to an attacker probing
        // with captured/foreign tokens. Same opaque surface as a bad signature.
        String errorCode = switch (ex.getReason()) {
            case MALFORMED, BAD_SIGNATURE, WRONG_AUDIENCE, WRONG_ISSUER, REPLAYED -> "verification_token_invalid";
            case EXPIRED -> "verification_token_expired";
            case PURPOSE_MISMATCH -> "verification_token_purpose_mismatch";
            case TXN_MISMATCH -> "verification_token_txn_mismatch";
        };
        String detail = switch (ex.getReason()) {
            case EXPIRED -> "Your verification session has expired. Please request a new code.";
            case PURPOSE_MISMATCH -> "That verification doesn't match this action. Please start over.";
            case TXN_MISMATCH -> "That approval doesn't match this transaction. Please approve it again.";
            case MALFORMED, BAD_SIGNATURE, WRONG_AUDIENCE, WRONG_ISSUER, REPLAYED ->
                    "We couldn't verify your session. Please request a new code and try again.";
        };
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        body.setType(PROBLEM_TYPE);
        body.setTitle("Verification failed");
        body.setDetail(detail);
        body.setProperty("errorCode", errorCode);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    @ExceptionHandler(PinSetNotAllowedException.class)
    public ResponseEntity<ProblemDetail> pinSetNotAllowed(PinSetNotAllowedException ex) {
        log.warn("PIN-set rejected: {}", ex.getMessage());
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        body.setType(PROBLEM_TYPE);
        body.setTitle("Can't change your PIN right now");
        body.setDetail("Your account isn't in a state where the PIN can be changed. Please contact support for help.");
        body.setProperty("errorCode", "pin_set_not_allowed");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(PinNotSetException.class)
    public ResponseEntity<ProblemDetail> pinNotSet(PinNotSetException ex) {
        // 403 — the customer's identity is known, but they have no PIN yet.
        // Mobile app should route them to the OTP-+-PIN-setup flow.
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(PROBLEM_TYPE);
        problem.setTitle("Set up your PIN to continue");
        problem.setDetail("You haven't set a PIN yet. Please complete PIN setup before signing in.");
        problem.setProperty("errorCode", "pin_not_set");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(InvalidMsisdnException.class)
    public ResponseEntity<ProblemDetail> invalidMsisdn(InvalidMsisdnException ex) {
        // Same surface as invalid credentials to avoid leaking whether the MSISDN
        // is recognised by the system (account enumeration defence).
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setType(PROBLEM_TYPE);
        problem.setTitle("Sign-in failed");
        problem.setDetail("The phone number or PIN you entered is incorrect. Please try again.");
        problem.setProperty("errorCode", "invalid_credentials");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ProblemDetail> locked(AccountLockedException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.LOCKED);
        problem.setType(PROBLEM_TYPE);
        problem.setTitle("Account temporarily locked");
        problem.setDetail("Too many failed sign-in attempts. Your account is locked for a short while — please try again later.");
        problem.setProperty("errorCode", "account_locked");
        problem.setProperty("lockedUntil", ex.getLockedUntil().toString());
        return ResponseEntity.status(HttpStatus.LOCKED).body(problem);
    }

    @ExceptionHandler(BackoffActiveException.class)
    public ResponseEntity<ProblemDetail> backoff(BackoffActiveException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        problem.setType(PROBLEM_TYPE);
        problem.setTitle("Please wait before trying again");
        problem.setDetail("We've paused sign-in for a few seconds after several failed attempts. Please try again shortly.");
        problem.setProperty("errorCode", "backoff_active");
        problem.setProperty("retryAfterSeconds", ex.getRetryAfterSeconds());

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()));
        return new ResponseEntity<>(problem, headers, HttpStatus.TOO_MANY_REQUESTS);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> rateLimited(RateLimitExceededException ex) {
        // Distinct errorCode from `backoff_active` (per-account login backoff):
        // this is the per-IP / per-MSISDN token-bucket limit on the auth surface.
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        problem.setType(PROBLEM_TYPE);
        problem.setTitle("Too many requests");
        problem.setDetail("You've made too many requests in a short time. Please wait a moment and try again.");
        problem.setProperty("errorCode", "rate_limited");
        problem.setProperty("retryAfterSeconds", ex.getRetryAfterSeconds());

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()));
        return new ResponseEntity<>(problem, headers, HttpStatus.TOO_MANY_REQUESTS);
    }

    @ExceptionHandler(NotificationDeliveryException.class)
    public ResponseEntity<ProblemDetail> smsDeliveryFailed(NotificationDeliveryException ex) {
        // The SMS gateway rejected the send or was unreachable. The OTP tx has
        // rolled back (no challenge row without a dispatched SMS), so a retry
        // is safe and starts clean. 503 is honest AND enumeration-safe: the
        // failure is provider-wide, not account-shaped. Never echo ex.getMessage()
        // to the caller — it can carry upstream detail; it's already logged.
        log.warn("SMS delivery failed: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setType(PROBLEM_TYPE);
        problem.setTitle("We couldn't send the SMS");
        problem.setDetail("We couldn't send you a text message right now. Please try again in a few minutes.");
        problem.setProperty("errorCode", "sms_delivery_failed");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    @ExceptionHandler(RefreshTokenInvalidException.class)
    public ResponseEntity<ProblemDetail> refreshInvalid(RefreshTokenInvalidException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setType(PROBLEM_TYPE);
        problem.setTitle("Please sign in again");
        problem.setDetail("Your session has expired. Please sign in again to continue.");
        problem.setProperty("errorCode", "refresh_invalid");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler(RefreshTokenReplayException.class)
    public ResponseEntity<ProblemDetail> refreshReplay(RefreshTokenReplayException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setType(PROBLEM_TYPE);
        problem.setTitle("Please sign in again");
        problem.setDetail("Your session is no longer valid. Please sign in again to continue.");
        problem.setProperty("errorCode", "refresh_replay_detected");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler(MalformedRequestException.class)
    public ResponseEntity<ProblemDetail> malformedRequest(MalformedRequestException ex) {
        // Typed exception thrown explicitly by controllers when they parse
        // a caller-supplied string that's already passed @Valid but can't be
        // turned into its target type (today: refresh-token JTI to UUID).
        //
        // Why typed instead of catching bare IllegalArgumentException? A
        // blanket IAE→400 handler swallowed every other IAE in the codebase —
        // PinHasher.hash(null), failed internal preconditions, etc. — and
        // dressed them up as 400s, hiding genuine programmer bugs from
        // error-rate dashboards. Genuine IAEs now bubble to Spring's default
        // 500 handler (the right observable behaviour for an internal bug).
        log.warn("MalformedRequestException -> 400 bad_request: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(PROBLEM_TYPE);
        problem.setTitle("Invalid request");
        problem.setDetail("Some details in your request weren't valid. Please check and try again.");
        problem.setProperty("errorCode", "bad_request");
        return ResponseEntity.badRequest().body(problem);
    }
}
