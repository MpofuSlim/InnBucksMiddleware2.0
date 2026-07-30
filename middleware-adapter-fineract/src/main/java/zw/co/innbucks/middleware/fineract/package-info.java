/**
 * CoreBankingPort adapter for Apache Fineract. Lands in the next slice:
 *
 * <ul>
 *   <li>RestClient with Basic auth (two least-privilege AppUsers:
 *       {@code innbucks-mw-read} / {@code innbucks-mw-write}), trimmed
 *       credentials, correlation-ID propagation, typed exception mapping to
 *       the core-neutral taxonomy, read-retry/write-never-retry resilience.</li>
 *   <li>{@code Fineract-Platform-TenantId} pinned per deployment.</li>
 *   <li>{@code Idempotency-Key} propagated on every mutating call — never
 *       rely on Fineract's auto-generated key for money movement.</li>
 *   <li>Client creation with externalId = middleware customer UUID; savings
 *       onboarding saga create → approve → activate with per-leg idempotency
 *       keys; deposit/withdrawal/transfer by external-id endpoints.</li>
 *   <li>Standalone-WireMock contract tests are the definition of done, one
 *       test per observed response shape plus a connect-refused case.</li>
 * </ul>
 */
package zw.co.innbucks.middleware.fineract;
