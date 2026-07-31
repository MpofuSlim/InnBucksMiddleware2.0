package zw.co.innbucks.middleware.fineract;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.TransactionDirection;
import zw.co.innbucks.middleware.corebanking.value.TransactionHistoryQuery;
import zw.co.innbucks.middleware.corebanking.value.TransactionPage;

import java.time.LocalDate;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static zw.co.innbucks.middleware.fineract.FineractContractTestSupport.*;

/**
 * The statement wire contract.
 *
 * <p>The live cell's {@code /transactions/search} returns a Spring Data page
 * keyed {@code total} / {@code content} (captured 2026-07-31); other Fineract
 * endpoints use the legacy {@code totalFilteredRecords} / {@code pageItems}
 * wrapper, and a version bump could hand us Spring's default
 * {@code totalElements}. All three are accepted and pinned below, because an
 * unrecognised envelope means every customer silently gets an empty statement.
 *
 * <p>Dates arrive from the legacy Gson serializer as {@code [yyyy,m,d]}
 * ARRAYS, with the ISO-string form also accepted.
 */
class FineractStatementContractTest {

    private static WireMockServer wireMock;
    private static FineractAdapter adapter;

    private static final String ACCT = "cust-1:wallet";
    private static final String PATH = "/v1/savingsaccounts/external-id/cust-1%3Awallet/transactions/search";

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        FineractProperties properties = properties(wireMock.port());
        adapter = new FineractAdapter(client(properties), properties);
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    private static TransactionHistoryQuery query(LocalDate from, LocalDate to, int offset, int limit) {
        return new TransactionHistoryQuery(new AccountRef(ACCT), from, to, offset, limit);
    }

    @Test
    void mapsThePageEnvelopeAndTheArrayDateForm() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""
                {
                  "totalFilteredRecords": 3,
                  "pageItems": [
                    {"id":15,"externalId":"ref-abc","entryType":"DEBIT",
                     "transactionType":{"id":2,"code":"savingsAccountTransactionType.withdrawal",
                                        "value":"Withdrawal","deposit":false,"withdrawal":true},
                     "amount":600.00,"runningBalance":15.00,"reversed":false,"date":[2026,7,31]},
                    {"id":12,"externalId":"","entryType":"CREDIT",
                     "transactionType":{"id":1,"value":"Deposit","deposit":true,"withdrawal":false},
                     "amount":25.00,"runningBalance":25.00,"reversed":false,"date":[2026,7,30]}
                  ]
                }""")));

        TransactionPage page = adapter.listTransactions(query(null, null, 0, 20));

        assertThat(page.totalCount()).isEqualTo(3);
        assertThat(page.entries()).hasSize(2);

        var first = page.entries().get(0);
        assertThat(first.coreId()).isEqualTo("15");
        assertThat(first.externalRef()).isEqualTo("ref-abc");
        assertThat(first.direction()).isEqualTo(TransactionDirection.DEBIT);
        assertThat(first.narrative()).isEqualTo("Withdrawal");
        // 600.00 USD major -> 60000 minor. The conversion point that must not drift.
        assertThat(first.amount().amount()).isEqualTo(60000);
        assertThat(first.runningBalance().amount()).isEqualTo(1500);
        assertThat(first.valueDate()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(first.reversed()).isFalse();

        // Fineract returns "" — not null — for an unset externalId.
        assertThat(page.entries().get(1).externalRef()).isNull();
        assertThat(page.entries().get(1).direction()).isEqualTo(TransactionDirection.CREDIT);
    }

    @Test
    void sendsPagingOrderingAndTheDateFiltersOnTheWire() {
        wireMock.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(okJson("{\"totalFilteredRecords\":0,\"pageItems\":[]}")));

        adapter.listTransactions(query(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), 40, 20));

        wireMock.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("offset", equalTo("40"))
                .withQueryParam("limit", equalTo("20"))
                // Same-day entries tie on date alone; id breaks the tie so page
                // boundaries are stable between requests.
                .withQueryParam("orderBy", equalTo("transactionDate,id"))
                .withQueryParam("sortOrder", equalTo("DESC"))
                .withQueryParam("fromDate", equalTo("2026-07-01"))
                .withQueryParam("toDate", equalTo("2026-07-31"))
                // Without these the date filters don't parse upstream.
                .withQueryParam("locale", equalTo("en"))
                .withQueryParam("dateFormat", equalTo("yyyy-MM-dd")));
    }

    @Test
    void omitsDateFiltersEntirelyWhenTheRangeIsOpenEnded() {
        wireMock.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(okJson("{\"totalFilteredRecords\":0,\"pageItems\":[]}")));

        adapter.listTransactions(query(null, null, 0, 20));

        wireMock.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withoutQueryParam("fromDate")
                .withoutQueryParam("toDate"));
    }

    @Test
    void anEmptyStatementIsAnAnswerNotAFailure() {
        wireMock.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(okJson("{\"totalFilteredRecords\":0,\"pageItems\":[]}")));

        TransactionPage page = adapter.listTransactions(query(null, null, 0, 20));

        assertThat(page.entries()).isEmpty();
        assertThat(page.totalCount()).isZero();
    }

    /**
     * Observed on the live cell (2026-07-31): a full page of transactions came
     * back with NO count field, and against a primitive {@code long} Jackson
     * rejected the entire document — the customer's statement 500'd while the
     * data was sitting right there in the body. Unknown total must degrade to
     * null, never to a parse failure and never to zero.
     */
    @Test
    void aPageWithNoTotalStillYieldsItsTransactions() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""
                {"pageItems":[
                   {"id":15,"externalId":"ref-abc","entryType":"DEBIT",
                    "transactionType":{"id":2,"value":"Withdrawal","deposit":false,"withdrawal":true},
                    "amount":600.00,"runningBalance":15.00,"reversed":false,"date":[2026,7,31]}
                 ]}""")));

        TransactionPage page = adapter.listTransactions(query(null, null, 0, 20));

        assertThat(page.entries()).hasSize(1);
        assertThat(page.entries().get(0).coreId()).isEqualTo("15");
        // Unknown, NOT zero — zero would tell a customer they have no history.
        assertThat(page.totalCount()).isNull();
    }

    /**
     * THE SHAPE THE LIVE CELL ACTUALLY RETURNS, captured verbatim from
     * `/transactions/search` on 2026-07-31 — a Spring Data page whose count
     * key is {@code total}, not Fineract's legacy {@code totalFilteredRecords}
     * and not Spring's own {@code totalElements}:
     *
     * <pre>{"total":0,"content":[],"pageable":{"sort":{...},"pageNumber":0,"pageSize":3}}</pre>
     *
     * Every other stub in this class was written against an assumed envelope,
     * which is exactly why the suite was green while production 500'd. This
     * one is transcribed, not imagined.
     */
    @Test
    void mapsTheEnvelopeTheLiveCellActuallyReturns() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""
                {"total":42,
                 "content":[
                   {"id":15,"externalId":"ref-abc","entryType":"DEBIT",
                    "transactionType":{"id":2,"value":"Withdrawal","deposit":false,"withdrawal":true},
                    "amount":600.00,"runningBalance":15.00,"reversed":false,"date":[2026,7,31]}
                 ],
                 "pageable":{"sort":{"orders":[{"direction":"DESC","property":"transaction_date",
                                                "ignoreCase":false,"nullHandling":"NATIVE"}]},
                             "pageNumber":0,"pageSize":3}}""")));

        TransactionPage page = adapter.listTransactions(query(null, null, 0, 20));

        assertThat(page.entries()).hasSize(1);
        assertThat(page.entries().get(0).coreId()).isEqualTo("15");
        assertThat(page.totalCount()).isEqualTo(42);
    }

    /** The live cell's empty page, verbatim — `pageable` and all. */
    @Test
    void anEmptyLiveCellPageIsAnEmptyStatementNotAFailure() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""
                {"total":0,"content":[],"pageable":{"sort":{"orders":[
                   {"direction":"DESC","property":"transaction_date","ignoreCase":false,"nullHandling":"NATIVE"},
                   {"direction":"DESC","property":"created_on_utc","ignoreCase":false,"nullHandling":"NATIVE"},
                   {"direction":"DESC","property":"id","ignoreCase":false,"nullHandling":"NATIVE"}]},
                 "pageNumber":0,"pageSize":3}}""")));

        TransactionPage page = adapter.listTransactions(query(null, null, 0, 3));

        assertThat(page.entries()).isEmpty();
        assertThat(page.totalCount()).isZero();
    }

    /**
     * Spring's own default count key, in case a Fineract upgrade drops the
     * custom {@code total} alias.
     */
    @Test
    void acceptsSpringsOwnTotalElementsKeyToo() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""
                {"totalElements":42,
                 "content":[
                   {"id":15,"externalId":"ref-abc","entryType":"DEBIT",
                    "transactionType":{"id":2,"value":"Withdrawal","deposit":false,"withdrawal":true},
                    "amount":600.00,"runningBalance":15.00,"reversed":false,"date":[2026,7,31]}
                 ]}""")));

        TransactionPage page = adapter.listTransactions(query(null, null, 0, 20));

        assertThat(page.entries()).hasSize(1);
        assertThat(page.totalCount()).isEqualTo(42);
    }

    @Test
    void acceptsAnIsoStringDateSoAnUpstreamSerializerChangeDoesNotBreakStatements() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""
                {"totalFilteredRecords":1,
                 "pageItems":[{"id":9,"entryType":"CREDIT","amount":1.00,
                               "transactionType":{"value":"Deposit","deposit":true},
                               "reversed":false,"date":"2026-07-15"}]}""")));

        TransactionPage page = adapter.listTransactions(query(null, null, 0, 20));

        assertThat(page.entries().get(0).valueDate()).isEqualTo(LocalDate.of(2026, 7, 15));
    }

    @Test
    void reversedEntriesStayOnTheStatementFlaggedRatherThanBeingDropped() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""
                {"totalFilteredRecords":1,
                 "pageItems":[{"id":7,"entryType":"DEBIT","amount":10.00,
                               "transactionType":{"value":"Withdrawal","withdrawal":true},
                               "reversed":true,"date":[2026,7,20]}]}""")));

        TransactionPage page = adapter.listTransactions(query(null, null, 0, 20));

        assertThat(page.entries()).hasSize(1);
        assertThat(page.entries().get(0).reversed()).isTrue();
    }

    @Test
    void anEntryWithNoRunningBalanceIsAllowed() {
        // Not every entry type carries one; a null must not become a zero,
        // which would read as "balance went to nil" on the statement.
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""
                {"totalFilteredRecords":1,
                 "pageItems":[{"id":5,"entryType":"CREDIT","amount":2.50,
                               "transactionType":{"value":"Interest Posting"},
                               "reversed":false,"date":[2026,7,1]}]}""")));

        TransactionPage page = adapter.listTransactions(query(null, null, 0, 20));

        assertThat(page.entries().get(0).runningBalance()).isNull();
        assertThat(page.entries().get(0).narrative()).isEqualTo("Interest Posting");
        // No entryType booleans and no explicit deposit flag on some entry
        // types — CREDIT here comes from entryType, which is authoritative.
        assertThat(page.entries().get(0).direction()).isEqualTo(TransactionDirection.CREDIT);
    }
}
