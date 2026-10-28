package zw.co.innbucks.middleware.kyc;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Every KYC bean — controller, services, key ring, stores, the lifecycle job —
 * carries this. With {@code innbucks.kyc.enabled} off (the default) none of
 * them exists: the {@code /me/kyc*} routes are not mapped, so they answer the
 * same 404 {@code not_found} as any unknown path, and no KYC key is read or
 * required. A cell whose box was not prepared for KYC therefore boots and
 * takes payments exactly as before.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(prefix = "innbucks.kyc", name = "enabled", havingValue = "true")
public @interface ConditionalOnKycEnabled {
}
