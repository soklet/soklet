package com.soklet;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import static com.soklet.EffectiveOriginResolver.TrustPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class ForwardedOriginTrustTests {
    @Test
    void allowlistUsesNearestProxyOriginAcrossPhysicalAndCommaSeparatedFields() {
        for (List<String> forwarded : List.of(
                List.of("for=192.0.2.1;host=spoof.example;proto=http, for=198.51.100.1;host=public.example:8443;proto=https"),
                List.of("for=192.0.2.1;host=spoof.example;proto=http", "for=198.51.100.1;host=public.example:8443;proto=https"))) {
            Map<String, List<String>> headers = Map.of("Host", List.of("internal.example"), "Forwarded", forwarded);
            assertEquals("https://public.example:8443", resolver(headers, true).resolve().orElseThrow());
            assertEquals("http://spoof.example", EffectiveOriginResolver.withHeaders(headers, TRUST_ALL).resolve().orElseThrow());
            assertTrue(resolver(headers, false).resolve().isEmpty());
        }
    }

    @Test
    void allowlistDoesNotLookPastMissingOrInvalidNearestOriginMetadata() {
        for (String last : List.of("for=198.51.100.1", "for=198.51.100.1;host=bad@host;proto=https", "")) {
            Map<String, List<String>> headers = Map.of("Host", List.of("internal.example"), "Forwarded",
                    List.of("for=192.0.2.1;host=spoof.example;proto=http", last));
            String origin = resolver(headers, true).resolve().orElse("");
            assertFalse(origin.contains("spoof"), origin);
            if (!origin.isEmpty())
                assertEquals("https://internal.example", origin);
        }
    }

    @Test
    void xForwardedOriginFieldsUseNearestValuesTogether() {
        for (boolean physical : List.of(false, true)) {
            Map<String, List<String>> headers = Map.of("Host", List.of("internal.example"),
                    "X-Forwarded-Host", physical ? List.of("spoof.example", "public.example") : List.of("spoof.example, public.example"),
                    "X-Forwarded-Proto", physical ? List.of("http", "https") : List.of("http, https"),
                    "X-Forwarded-Port", physical ? List.of("80", "8443") : List.of("80, 8443"));
            assertEquals("https://public.example:8443", resolver(headers, true).resolve().orElseThrow());
            assertTrue(resolver(headers, false).resolve().isEmpty());
        }
    }

    private static EffectiveOriginResolver resolver(Map<String, List<String>> headers, boolean trusted) {
        return EffectiveOriginResolver.withHeaders(headers, TRUST_PROXY_ALLOWLIST)
                .remoteAddress(new InetSocketAddress("127.0.0.1", 9000)).trustedProxyPredicate(address -> trusted);
    }
}
