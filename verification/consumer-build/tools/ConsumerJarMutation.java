package consumerbuild;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Produces isolated consumer-only negative fixtures; never modifies the core JAR. */
public final class ConsumerJarMutation {
  private static final String HTTP_INDEX = "META-INF/soklet/resource-method-lookup-table";
  private static final String MCP_INDEX = "META-INF/soklet/mcp-endpoint-descriptor-providers";
  private static final long MAXIMUM_ARCHIVE_BYTES = 64L * 1024 * 1024;
  private static final int MAXIMUM_ENTRY_BYTES = 16 * 1024 * 1024;
  private static final int MAXIMUM_ENTRIES = 1024;
  private static final byte[] CORRUPT_INDEX =
      "GET|%%%|%%%|%%%||false\n".getBytes(StandardCharsets.UTF_8);

  public static void main(String[] args) throws Exception {
    if (args.length != 3)
      throw new IllegalArgumentException("Expected input JAR, new output JAR, and mutation name");
    Path input = Path.of(args[0]);
    Path output = Path.of(args[1]);
    String mode = args[2];
    if (!mode.equals("missing-http-index") && !mode.equals("corrupt-http-index"))
      throw new IllegalArgumentException("Unknown mutation: " + mode);
    Map<String, byte[]> original = readEntries(input);
    if (!original.containsKey(HTTP_INDEX) || !original.containsKey(MCP_INDEX))
      throw new IllegalArgumentException("Input consumer JAR must contain both generated indexes");
    Map<String, byte[]> mutated = new LinkedHashMap<>(original);
    if (mode.equals("missing-http-index"))
      mutated.remove(HTTP_INDEX);
    else
      mutated.put(HTTP_INDEX, CORRUPT_INDEX);

    try (ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(output,
        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
      for (Map.Entry<String, byte[]> entry : mutated.entrySet()) {
        ZipEntry target = new ZipEntry(entry.getKey());
        target.setTime(0);
        archive.putNextEntry(target);
        archive.write(entry.getValue());
        archive.closeEntry();
      }
    }

    Map<String, byte[]> actual = readEntries(output);
    if (!actual.keySet().equals(mutated.keySet()))
      throw new IllegalStateException("Mutation changed unexpected archive entries");
    for (Map.Entry<String, byte[]> entry : original.entrySet()) {
      if (!entry.getKey().equals(HTTP_INDEX)
          && !Arrays.equals(entry.getValue(), actual.get(entry.getKey())))
        throw new IllegalStateException("Mutation changed unrelated entry: " + entry.getKey());
    }
    if (mode.equals("missing-http-index") ? actual.containsKey(HTTP_INDEX)
        : !Arrays.equals(CORRUPT_INDEX, actual.get(HTTP_INDEX)))
      throw new IllegalStateException("Requested HTTP index mutation was not applied");
    String mcpHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(actual.get(MCP_INDEX)));
    System.out.println("{\"mode\":\"" + mode + "\",\"mcpIndexSha256\":\"" + mcpHash
        + "\",\"unchangedEntryCount\":" + (original.size() - 1) + "}");
  }

  private static Map<String, byte[]> readEntries(Path path) throws Exception {
    if (Files.size(path) > MAXIMUM_ARCHIVE_BYTES)
      throw new IllegalArgumentException("Consumer JAR is larger than the fixture's archive bound");
    Map<String, byte[]> entries = new LinkedHashMap<>();
    long totalBytes = 0;
    try (ZipFile archive = new ZipFile(path.toFile())) {
      var enumeration = archive.entries();
      while (enumeration.hasMoreElements()) {
        ZipEntry entry = enumeration.nextElement();
        if (entries.size() >= MAXIMUM_ENTRIES || entry.getSize() > MAXIMUM_ENTRY_BYTES)
          throw new IllegalArgumentException("Consumer JAR exceeds the fixture's entry bound");
        byte[] bytes;
        try (InputStream stream = archive.getInputStream(entry)) {
          bytes = stream.readNBytes(MAXIMUM_ENTRY_BYTES + 1);
        }
        totalBytes += bytes.length;
        if (bytes.length > MAXIMUM_ENTRY_BYTES || totalBytes > MAXIMUM_ARCHIVE_BYTES)
          throw new IllegalArgumentException("Consumer JAR exceeds the fixture's expanded-byte bound");
        if (entries.putIfAbsent(entry.getName(), bytes) != null)
          throw new IllegalArgumentException("Consumer JAR has duplicate entries: " + entry.getName());
      }
    }
    return entries;
  }
}
