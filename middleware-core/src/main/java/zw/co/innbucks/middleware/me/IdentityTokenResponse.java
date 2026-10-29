package zw.co.innbucks.middleware.me;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A short-lived token that proves who the customer is and nothing more. Hand it to "
        + "a third party that needs the customer's identity; it reads GET /me/identity only.")
public record IdentityTokenResponse(

        @Schema(description = "Bearer token with the single scope identity:read. Never use it as the "
                + "app's own access token.",
                example = "eyJhbGciOiJIUzI1NiJ9.eyJzY29wZXMiOlsiaWRlbnRpdHk6cmVhZCJdfQ.c2lnbmF0dXJl")
        String identityToken,

        @Schema(description = "Seconds until it expires.", example = "120")
        long expiresIn
) {
}
