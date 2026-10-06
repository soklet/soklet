package com.soklet;

import com.soklet.annotation.GET;
import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.MicrohttpResponse;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CacheResponseMetadataTests {
    @Test
    void defaultMarshalerDoesNotInventContentTypeForAbsentBodies() {
        Request request = Request.fromPath(HttpMethod.GET, "/cache");
        ResourceMethod method = ResourceMethodResolver.fromClasses(Set.of(CacheResource.class))
                .resourceMethodForRequest(request, ServerType.HTTP).orElseThrow();
        ResponseMarshaler marshaler = ResponseMarshaler.defaultInstance();
        for (int status : List.of(200, 204, 304, 412)) {
            MarshaledResponse result = marshaler.forResourceMethod(request, Response.fromStatusCode(status), method);
            assertFalse(result.getHeaders().containsKey("Content-Type"), status + ": " + result.getHeaders());
        }
        MarshaledResponse explicit = marshaler.forResourceMethod(request, Response.withStatusCode(304)
                .headers(Map.of("Content-Type", List.of("application/example"))).build(), method);
        assertEquals(List.of("application/example"), explicit.getHeaders().get("Content-Type"));
        MarshaledResponse head = marshaler.forResourceMethod(Request.fromPath(HttpMethod.HEAD, "/cache"),
                Response.withStatusCode(200).body("representation").build(), method);
        assertEquals(List.of("text/plain; charset=UTF-8"), head.getHeaders().get("Content-Type"));
    }

    @Test
    void corsReplacesControlledFieldsCaseInsensitivelyAndPreservesVaryTokens() {
        Request request = Request.withPath(HttpMethod.GET, "/cache").headers(Map.of("Origin", List.of("https://allowed.example"))).build();
        for (List<String> vary : List.of(List.of("Accept-Encoding, Accept-Language"), List.of("accept-encoding", "origin"), List.of("*"))) {
            MarshaledResponse original = MarshaledResponse.withStatusCode(200).headers(Map.of("vary", vary,
                    "access-control-allow-origin", List.of("https://old.example"))).build();
            MarshaledResponse result = ResponseMarshaler.defaultInstance().forCorsAllowed(request, request.getCors().orElseThrow(),
                    CorsResponse.withAccessControlAllowOrigin("https://allowed.example").build(), original);
            assertEquals(List.of("https://allowed.example"), result.getHeaders().get("Access-Control-Allow-Origin"));
            if (vary.contains("*") || vary.contains("origin"))
                assertEquals(vary, result.getHeaders().get("Vary"));
            else
                assertEquals(List.of("Accept-Encoding, Accept-Language", "Origin"), result.getHeaders().get("Vary"));
            assertEquals(vary, original.getHeaders().get("Vary"));
        }
    }

    @Test
    void allNonPreflightVariantsVaryByOriginWhenAuthorizationDependsOnIt() {
        SimulatorConfig config = SimulatorConfig.builder().httpServer()
                .resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(CacheResource.class)))
                .corsAuthorizer(CorsAuthorizer.fromWhitelistedOrigins(Set.of("https://allowed.example"))).build();
        SokletSimulator.run(config, simulator -> {
            for (String path : List.of("/cache", "/missing")) {
                for (String origin : List.of("", "https://allowed.example", "https://denied.example")) {
                    Request request = Request.withPath(HttpMethod.GET, path)
                            .headers(origin.isEmpty() ? Map.of() : Map.of("Origin", List.of(origin))).build();
                    MarshaledResponse result = simulator.performHttpRequest(request).getMarshaledResponse();
                    assertTrue(result.getHeaders().getOrDefault("Vary", List.of()).contains("Origin"), result.getHeaders().toString());
                    assertEquals(origin.equals("https://allowed.example"), result.getHeaders().containsKey("Access-Control-Allow-Origin"));
                }
            }
        });
    }

    @Test
    void compressionPreservesConditionalValidatorAndVaryMetadataWithoutCallingPlanner() {
        byte[] body = "compressible representation".repeat(80).getBytes(StandardCharsets.UTF_8);
        ResponseCompressor compressor = (request, response) -> {
            assertNotEquals(304, response.getStatusCode());
            return ResponseCompressionPlan.compress(ResponseCompressionCodec.gzipInstance());
        };
        DefaultHttpServer server = (DefaultHttpServer) HttpServer.withPort(0).responseCompressor(compressor).build();
        Request request = Request.withPath(HttpMethod.GET, "/cache").headers(Map.of("Accept-Encoding", List.of("gzip"))).build();
        MarshaledResponse original = MarshaledResponse.withStatusCode(200).body(body)
                .headers(Map.of("ETag", List.of("W/\"v1\""), "Vary", List.of("Origin"))).build();
        MicrohttpResponse success = server.toMicrohttpResponse(request, null, original);
        MarshaledResponse notModified = MarshaledResponse.withStatusCode(304)
                .headers(Map.of("ETag", List.of("W/\"v1\""), "Vary", List.of("Origin"))).build();
        MicrohttpResponse conditional = server.toMicrohttpResponse(request, null, notModified);
        assertEquals(value(success, "ETag"), value(conditional, "ETag"));
        assertEquals(value(success, "Vary"), value(conditional, "Vary"));
        assertEquals(0, conditional.bodyLength());
        assertNull(value(conditional, "Content-Encoding"));
        assertEquals(List.of("W/\"v1\""), notModified.getHeaders().get("ETag"));
        assertEquals(List.of("W/\"v1\""), original.getHeaders().get("ETag"));

        DefaultHttpServer disabled = (DefaultHttpServer) HttpServer.withPort(0).build();
        assertEquals("W/\"v1\"", value(disabled.toMicrohttpResponse(request, null, notModified), "ETag"));
        assertEquals("Origin", value(disabled.toMicrohttpResponse(request, null, notModified), "Vary"));
        MarshaledResponse strong = notModified.copy().headers(Map.of("ETag", List.of("\"v1\""))).finish();
        assertEquals("\"v1\"", value(server.toMicrohttpResponse(request, null, strong), "ETag"));
    }

    private static String value(MicrohttpResponse response, String name) {
        return response.headers().stream().filter(header -> header.name().equalsIgnoreCase(name)).map(Header::value).findFirst().orElse(null);
    }

    public static class CacheResource {
        @GET("/cache")
        public String cache() { return "cached representation"; }
    }
}
