/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet;

import com.soklet.internal.mcp.generated.McpGeneratedEndpointProviderIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Real JAR URL reads, including archive replacement and physical handle retirement. */
@Timeout(30)
class JarIndexReaderCacheTests {
    private static final String HTTP_INDEX = "META-INF/soklet/resource-method-lookup-table";
    private static final String MCP_INDEX = McpGeneratedEndpointProviderIndex.RESOURCE_PATH;
    @TempDir Path temporary;

    @Test
    void httpIndexUsesTheReplacementAtTheSameJarPath() throws Exception {
        assertReplacement(Family.HTTP);
    }

    @Test
    void mcpIndexUsesTheReplacementAtTheSameJarPath() throws Exception {
        assertReplacement(Family.MCP);
    }

    @Test
    void bothReadersCloseUncachedJarHandlesAfterSuccessfulReads() throws Exception {
        assertClosedHandles(false);
    }

    @Test
    void bothReadersCloseUncachedJarHandlesAfterMalformedIndexes() throws Exception {
        assertClosedHandles(true);
    }

    private void assertReplacement(Family family) throws Exception {
        Path archive = temporary.resolve("same-path.jar");
        write(archive, "v1", false);
        assertEquals("/v1", read(family, loader(family, jarUrl(archive, family))));
        write(archive, "v2", false);
        assertEquals("/v2", read(family, loader(family, jarUrl(archive, family))),
                "A new application loader must read the replacement archive's index.");
    }

    private void assertClosedHandles(boolean malformed) throws Exception {
        Path archive = temporary.resolve("tracked.jar");
        write(archive, "v1", malformed);
        for (Family family : Family.values()) {
            List<TrackedConnection> opened = new ArrayList<>();
            URL original = jarUrl(archive, family);
            URL tracked = new URL(null, "tracked:" + original.toExternalForm(), new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL url) {
                    TrackedConnection connection = new TrackedConnection(url, original);
                    opened.add(connection);
                    return connection;
                }
            });
            ClassLoader loader = loader(family, tracked);
            if (malformed) assertThrows(RuntimeException.class, () -> read(family, loader));
            else assertEquals("/v1", read(family, loader));
            assertEquals(1, opened.size());
            TrackedConnection connection = opened.getFirst();
            assertFalse(connection.getUseCaches(), "Only this lookup's connection must bypass the global JAR cache.");
            assertTrue(connection.closed, "The reader did not close its stream.");
            assertNotNull(connection.jar);
            assertThrows(IllegalStateException.class, connection.jar::size,
                    "The real JarFile must be closed, independently of operating-system handle inspection.");
        }
    }

    private static String read(Family family, ClassLoader loader) throws Exception {
        Class<?> type = Class.forName(family == Family.HTTP
                ? "com.soklet.DefaultResourceMethodResolver$ResourceMethodDeclarationLoader"
                : "com.soklet.internal.mcp.generated.McpGeneratedEndpointProviderLoader");
        Method load = type.getDeclaredMethod(family == Family.HTTP ? "loadAll" : "readEntries", ClassLoader.class);
        load.setAccessible(true);
        Object result;
        try { result = load.invoke(null, loader); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        }
        Object row = family == Family.HTTP ? ((List<?>) result).getFirst()
                : ((Map<?, ?>) result).values().iterator().next();
        Method path = row.getClass().getDeclaredMethod(family == Family.HTTP ? "path" : "endpointPath");
        path.setAccessible(true);
        return (String) path.invoke(row);
    }

    private static ClassLoader loader(Family family, URL resource) {
        return new ClassLoader(JarIndexReaderCacheTests.class.getClassLoader()) {
            @Override public Enumeration<URL> getResources(String name) {
                return name.equals(family.path) ? Collections.enumeration(List.of(resource)) : Collections.emptyEnumeration();
            }
        };
    }

    private static URL jarUrl(Path archive, Family family) throws IOException {
        return new URL("jar:" + archive.toUri() + "!/" + family.path);
    }

    private static void write(Path archive, String version, boolean malformed) throws IOException {
        Path replacement = archive.resolveSibling("replacement.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(replacement))) {
            out.putNextEntry(new JarEntry(HTTP_INDEX));
            out.write((malformed ? "bad row\n" : "GET|" + b64("/" + version) + "|" + b64("example.Endpoint" + version)
                    + "|" + b64("get") + "||false\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry(MCP_INDEX));
            out.write((malformed ? "bad row\n" : McpGeneratedEndpointProviderIndex.formatLine(
                    "example.Endpoint" + version, "example.Provider" + version,
                    "example.Endpoint" + version, "/" + version) + "\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Files.move(replacement, archive, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private enum Family {
        HTTP(HTTP_INDEX), MCP(MCP_INDEX);
        private final String path;
        Family(String path) { this.path = path; }
    }

    private static final class TrackedConnection extends URLConnection {
        private final URL original;
        private JarFile jar;
        private boolean closed;
        private TrackedConnection(URL url, URL original) { super(url); this.original = original; }
        @Override public void connect() { }
        @Override public InputStream getInputStream() throws IOException {
            JarURLConnection delegate = (JarURLConnection) original.openConnection();
            delegate.setUseCaches(getUseCaches());
            InputStream stream = delegate.getInputStream();
            this.jar = delegate.getJarFile();
            return new FilterInputStream(stream) {
                @Override public void close() throws IOException {
                    try { super.close(); }
                    finally { closed = true; }
                }
            };
        }
    }
}
