# syntax=docker/dockerfile:1.7
# Build from repo root:
#     docker build -t innbucks-middleware .

FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY middleware-corebanking-api/pom.xml middleware-corebanking-api/
COPY middleware-core/pom.xml middleware-core/
COPY middleware-adapter-fineract/pom.xml middleware-adapter-fineract/
COPY middleware-app/pom.xml middleware-app/
RUN chmod +x mvnw

# Warm the dependency cache. Layer survives until a pom.xml changes.
RUN ./mvnw -B -DskipTests dependency:go-offline || true

COPY middleware-corebanking-api middleware-corebanking-api
COPY middleware-core middleware-core
COPY middleware-adapter-fineract middleware-adapter-fineract
COPY middleware-app middleware-app
RUN ./mvnw -B -DskipTests package \
    && cp middleware-app/target/*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine AS runtime
# Refresh the apk index and upgrade EVERY package in the branch.
#
# This used to upgrade three packages by name (libssl3/libcrypto3/openssl).
# That made the Release Trivy gate a tripwire on every future Alpine advisory:
# libexpat and p11-kit both went HIGH with fixes already published, and the
# build stayed on the vulnerable versions because they weren't on the list.
# A blanket upgrade within the pinned Alpine branch picks up security point
# releases as they ship — which is what the branch exists for — instead of
# requiring a Dockerfile edit per CVE.
#
# Defence-in-depth, two layers:
#   1) ARG ALPINE_SECURITY_REFRESH busts the Docker layer cache so this RUN
#      actually re-executes when a new Alpine point-release ships a fix. Without
#      it the layer hash is identical across builds and a cached upgrade against
#      a stale index replays forever. Bump the date string (or override via
#      --build-arg in CI) for future advisories.
#   2) `apk info -e '<pkg>>=<fixed-version>'` HARD-ASSERTS the installed version
#      after the upgrade, for CVEs we have actually been bitten by. If Alpine's
#      mirror is lagging (or the upgrade line is ever deleted in a refactor),
#      `docker build` fails immediately with a clear signal — strictly better
#      than a green build + red Trivy a few minutes later. The security
#      invariant belongs at the boundary that can't be skipped.
#
# Asserted floors — only for CVEs whose fix EXISTS in the pinned branch. An
# assertion for a version Alpine 3.23 does not carry would fail every build
# forever; see .trivyignore for the ones that need a base-image move instead.
#   CVE-2026-45447 (HIGH) — libssl3/libcrypto3/openssl PKCS#7 / S/MIME
#     signed-message handling; fixed in 3.5.7-r0.
ARG ALPINE_SECURITY_REFRESH=2026-07-31-blanket-upgrade
RUN echo "Security refresh: $ALPINE_SECURITY_REFRESH" \
 && apk update \
 && apk --no-cache upgrade \
 && apk info -e 'libssl3>=3.5.7-r0' \
 && apk info -e 'libcrypto3>=3.5.7-r0' \
 && apk info -e 'openssl>=3.5.7-r0' \
 && apk --no-cache add wget \
 && addgroup -S app && adduser -S -G app app
WORKDIR /app
USER app

COPY --from=builder --chown=app:app /workspace/app.jar /app/app.jar

# 8090 = application (mobile-facing); 9090 = management/actuator
EXPOSE 8090 9090

# Healthcheck hits the management port — split from the app port so a deluged
# 8090 doesn't block the orchestrator's liveness probe. start-period gives the
# JVM + Flyway enough time to come up before failures count against retries.
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:9090/actuator/health || exit 1

# jdk.httpclient.keepalive.timeout: the JDK HttpClient drops idle pooled
# connections after 30s by default. Money movements arrive far less often than
# that, so the write-credential pool is essentially always cold and nearly every
# transfer pays a fresh TCP + TLS handshake to Fineract — repeatedly, since one
# transfer makes several calls. 600s keeps the connection warm between
# movements. Cheap: a handful of idle sockets on the private cell network.
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-Djdk.httpclient.keepalive.timeout=600", "-jar", "/app/app.jar"]
