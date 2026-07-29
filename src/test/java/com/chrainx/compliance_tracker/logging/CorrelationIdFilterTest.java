package com.chrainx.compliance_tracker.logging;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Plain unit test - MockHttpServletRequest/Response + a capturing FilterChain, no Spring context
// needed to prove this filter's own logic.
class CorrelationIdFilterTest {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void generatesAFreshCorrelationId_whenNoHeaderIsSent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] mdcValueDuringChain = new String[1];

        filter.doFilter(request, response, (req, res) -> mdcValueDuringChain[0] = MDC.get(CorrelationIdFilter.MDC_KEY));

        assertTrue(UUID_PATTERN.matcher(mdcValueDuringChain[0]).matches());
        assertEquals(mdcValueDuringChain[0], response.getHeader("X-Correlation-Id"));
    }

    @Test
    void reusesTheIncomingHeaderValue_ratherThanGeneratingANewOne() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", "caller-supplied-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] mdcValueDuringChain = new String[1];

        filter.doFilter(request, response, (req, res) -> mdcValueDuringChain[0] = MDC.get(CorrelationIdFilter.MDC_KEY));

        assertEquals("caller-supplied-id", mdcValueDuringChain[0]);
        assertEquals("caller-supplied-id", response.getHeader("X-Correlation-Id"));
    }

    @Test
    void ignoresABlankHeaderValue_generatingAFreshIdInstead() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] mdcValueDuringChain = new String[1];

        filter.doFilter(request, response, (req, res) -> mdcValueDuringChain[0] = MDC.get(CorrelationIdFilter.MDC_KEY));

        assertTrue(UUID_PATTERN.matcher(mdcValueDuringChain[0]).matches());
    }

    @Test
    void clearsTheMdcEntry_afterTheRequestCompletes() throws Exception {
        // The actual bug this guards against: Tomcat reuses worker threads across requests, so
        // a correlation ID left in MDC after this request would leak into whatever unrelated
        // request that same thread handles next.
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void clearsTheMdcEntry_evenWhenTheRestOfTheChainThrows() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, (req, res) -> {
                throw new RuntimeException("simulated downstream failure");
            });
        } catch (RuntimeException expected) {
            // The point of this test - the exception propagating is expected, not the failure.
        }

        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}
