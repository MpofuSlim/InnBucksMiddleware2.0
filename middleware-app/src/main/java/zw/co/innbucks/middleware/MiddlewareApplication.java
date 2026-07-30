package zw.co.innbucks.middleware;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling activates Spring's TaskScheduler so beans with @Scheduled
// run on their own thread pool: the refresh-token, idempotency-record and
// otp_challenge pruners, plus the nightly AuditIntegrityVerifier.
@SpringBootApplication
@EnableScheduling
public class MiddlewareApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiddlewareApplication.class, args);
    }

}
