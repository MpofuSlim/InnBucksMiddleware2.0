package zw.co.innbucks.middleware.otp;

import lombok.extern.slf4j.Slf4j;
import zw.co.innbucks.middleware.common.msisdn.MsisdnMasking;

/**
 * Stub SMS sender — the no-network {@link SmsSender} for dev/UAT. Selected by
 * {@code notify/NotificationConfig} when {@code innbucks.notify.provider} is
 * {@code console}; the real provider is the InnBucks notification gateway
 * ({@code innbucks-gateway}), which is the go-live requirement.
 *
 * <p>It deliberately does <strong>not</strong> log the message body. The body
 * carries the OTP code (and for other purposes may carry other secrets), so
 * emitting it would turn the application log into a store of live one-time
 * codes if this stub were ever active in a non-dev deployment. We log only a
 * masked MSISDN plus the body length as a "would-have-sent" breadcrumb. In dev
 * the OTP is pinned to a fixed code via {@code innbucks.otp.fixed-code}, so
 * there is nothing to read from the console anyway.
 */
@Slf4j
public class ConsoleSmsSender implements SmsSender {

    @Override
    public void send(String e164Msisdn, String body) {
        log.warn("SMS stub — would send {} chars to {} (no real SMS provider configured)",
                body == null ? 0 : body.length(), MsisdnMasking.mask(e164Msisdn));
    }
}
