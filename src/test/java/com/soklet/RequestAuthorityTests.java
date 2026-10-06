package com.soklet;

import com.soklet.exception.IllegalRequestException;
import com.soklet.internal.microhttp.Header;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import static com.soklet.EffectiveOriginResolver.TrustPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class RequestAuthorityTests {
    @Test
    void absoluteAuthorityReplacesHostForBothHeaderRepresentations() {
        for (boolean physical : List.of(false, true)) {
            Request.RawBuilder builder = Request.withRawUrl(HttpMethod.GET, "https://catalog.example:8443/a%20b?key=value");
            if (physical)
                builder.microhttpHeaders(List.of(new Header("hOsT", "other.example"), new Header("X-Note", "a,b")));
            else
                builder.headers(Map.of("hOsT", List.of("other.example"), "X-Note", List.of("a,b")));
            Request request = builder.build();
            assertEquals("catalog.example:8443", request.getHeader("host").orElseThrow());
            assertEquals(List.of("catalog.example:8443"), request.getHeaders().get("HOST"));
            assertEquals(List.of("a,b"), request.getHeaders().get("X-Note"));
            assertEquals("/a%20b", request.getRawPath());
            assertEquals("/a b", request.getPath());
            assertEquals("value", request.getQueryParameter("key").orElseThrow());
            assertEquals("https://catalog.example:8443", EffectiveOriginResolver.withRequest(request, TRUST_NONE).resolve().orElseThrow());
            assertEquals(EffectiveOriginResolver.withRequest(request, TRUST_NONE).resolve(),
                    EffectiveOriginResolver.withRequest(request.copy().finish(), TRUST_NONE).resolve());
        }
    }

    @Test
    void authoritySupportsIpv6ExplicitDefaultPortsAndEmptyPaths() {
        for (String target : List.of("http://catalog.example:80", "https://[::1]:443", "https://[2001:db8::1]:8443/")) {
            Request request = Request.fromRawUrl(HttpMethod.GET, target);
            assertEquals("/", request.getRawPath());
            assertEquals(target.endsWith("/") ? target.substring(0, target.length() - 1) : target,
                    EffectiveOriginResolver.withRequest(request, TRUST_NONE).resolve().orElseThrow());
        }
    }

    @Test
    void malformedAbsoluteTargetsFailWithoutDisclosingInput() {
        for (String target : List.of("https:///secret", "https://user:secret@catalog.example/", "ftp://catalog.example/secret",
                "https://catalog.example:99999/secret", "https://[broken]/secret", "https://catalog.example/secret#fragment",
                "https://catalog.example/secret value", "secret")) {
            IllegalRequestException exception = assertThrows(IllegalRequestException.class,
                    () -> Request.fromRawUrl(HttpMethod.GET, target));
            assertEquals("Invalid absolute request URL.", exception.getMessage());
            assertNull(exception.getCause());
        }
    }

    @Test
    void originTargetsRetainSuppliedHostAndForwardingRequiresTrust() {
        Map<String, List<String>> headers = Map.of("Host", List.of("origin.example"), "Forwarded",
                List.of("for=192.0.2.1;host=public.example;proto=http"));
        Request origin = Request.withRawUrl(HttpMethod.GET, "//segment/path").headers(headers).build();
        assertEquals("origin.example", origin.getHeader("Host").orElseThrow());
        Request absolute = Request.withRawUrl(HttpMethod.GET, "https://target.example/path").headers(headers)
                .remoteAddress(new InetSocketAddress("127.0.0.1", 1234)).build();
        assertEquals("https://target.example", EffectiveOriginResolver.withRequest(absolute, TRUST_NONE).resolve().orElseThrow());
        assertEquals("https://target.example", EffectiveOriginResolver.withRequest(absolute, TRUST_PROXY_ALLOWLIST)
                .trustedProxyPredicate(address -> false).resolve().orElseThrow());
        assertEquals("http://public.example", EffectiveOriginResolver.withRequest(absolute, TRUST_PROXY_ALLOWLIST)
                .trustedProxyPredicate(address -> true).resolve().orElseThrow());
    }
}
