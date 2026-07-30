package zw.co.innbucks.middleware.common.correlation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.common.country.CountryProperties;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter(new CountryProperties(Country.KE));

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void usesIncomingCorrelationIdAndEchoesItOnResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationContext.HEADER, "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationContext.HEADER)).isEqualTo("abc-123");
        assertThat(MDC.get(CorrelationContext.MDC_KEY)).isNull();
        assertThat(MDC.get(CorrelationContext.COUNTRY_MDC_KEY)).isNull();
    }

    @Test
    void generatesCorrelationIdWhenMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationContext.HEADER)).isNotBlank();
    }

    @Test
    void rejectsCorrelationIdWithNewline_substitutesFreshUuid() throws Exception {
        // Log-injection attempt — \n would split a single log line into two.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationContext.HEADER, "abc\nINJECTED");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String echoed = response.getHeader(CorrelationContext.HEADER);
        assertThat(echoed).isNotBlank();
        assertThat(echoed).doesNotContain("\n");
        assertThat(echoed).doesNotContain("INJECTED");
    }

    @Test
    void rejectsOversizedCorrelationId_substitutesFreshUuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationContext.HEADER, "a".repeat(2000));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String echoed = response.getHeader(CorrelationContext.HEADER);
        assertThat(echoed).isNotBlank();
        assertThat(echoed.length()).isLessThanOrEqualTo(64);
    }

    @Test
    void rejectsCorrelationIdWithDisallowedCharacter_substitutesFreshUuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // Space isn't in the allowed character class.
        request.addHeader(CorrelationContext.HEADER, "abc def");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String echoed = response.getHeader(CorrelationContext.HEADER);
        assertThat(echoed).isNotBlank();
        assertThat(echoed).doesNotContain(" ");
    }

    @Test
    void populatesCountryInMdcDuringFilterExecution() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain captureChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse resp) {
                request.setAttribute("country-during-chain", MDC.get(CorrelationContext.COUNTRY_MDC_KEY));
            }
        };

        filter.doFilter(request, response, captureChain);

        assertThat(request.getAttribute("country-during-chain")).isEqualTo("KE");
        assertThat(MDC.get(CorrelationContext.COUNTRY_MDC_KEY)).isNull();
    }
}
