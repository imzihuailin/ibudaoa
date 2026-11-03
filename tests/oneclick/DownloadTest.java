import com.sun.net.httpserver.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real TLS transport tests, using only an isolated local server and its own test certificate. */
public class DownloadTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    interface Action { void run() throws Exception; }
    static void rejects(Action action) throws Exception {
        try { action.run(); } catch (IOException expected) { checks++; return; }
        throw new AssertionError("Expected download failure");
    }
    static void respond(HttpExchange exchange, int code, byte[] body) throws IOException {
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]), storeFile = Path.of(args[1]);
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(storeFile)) { keys.load(in, "changeit".toCharArray()); }
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        km.init(keys, "changeit".toCharArray()); tm.init(keys);
        SSLContext ssl = SSLContext.getInstance("TLS"); ssl.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        // Only this test JVM trusts its generated localhost certificate; production TLS is unchanged.
        HttpsURLConnection.setDefaultSSLSocketFactory(ssl.getSocketFactory());
        HttpsServer server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        ExecutorService executor = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
        server.setExecutor(executor);
        byte[] good = "verified payload".getBytes(StandardCharsets.UTF_8);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(good));
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/good", e -> { requests.incrementAndGet(); respond(e, 200, good); });
        server.createContext("/bad", e -> respond(e, 200, "wrong payload".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/missing", e -> respond(e, 404, new byte[]{1}));
        server.createContext("/redirect", e -> {
            e.getResponseHeaders().set("Location", "/good");
            e.sendResponseHeaders(302, -1); e.close();
        });
        server.createContext("/short", e -> {
            e.sendResponseHeaders(200, 10000);
            try (OutputStream out = e.getResponseBody()) { out.write(1); } catch (IOException ignored) {} finally { e.close(); }
        });
        server.createContext("/stall", e -> {
            e.sendResponseHeaders(200, 10000); e.getResponseBody().flush();
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {} finally { e.close(); }
        });
        server.createContext("/insecure", e -> {
            e.getResponseHeaders().set("Location", "http://localhost/forbidden");
            e.sendResponseHeaders(302, -1); e.close();
        });
        server.start();
        String base = "https://localhost:" + server.getAddress().getPort();
        try {
            Path destination = root.resolve("下载 space.bin");
            OneClick.downloadFrom(List.of(base + "/missing", base + "/bad", base + "/redirect"), destination, checksum);
            check(Arrays.equals(Files.readAllBytes(destination), good), "fallback must verify the complete file");
            Files.writeString(destination, "old file");
            rejects(() -> OneClick.downloadFrom(List.of(base + "/bad", base + "/missing"), destination, checksum));
            check(Files.readString(destination).equals("old file"), "failed attempts preserve previous destination");
            check(!Files.exists(destination.resolveSibling(destination.getFileName() + ".part")), "remove partial files");
            long start = System.nanoTime();
            rejects(() -> OneClick.downloadAttempt(base + "/stall", root.resolve("stall"), 1000, 150));
            check((System.nanoTime() - start) < 1_500_000_000L, "stalled read must time out");
            rejects(() -> OneClick.downloadAttempt(base + "/short", root.resolve("short"), 1000, 150));
            rejects(() -> OneClick.downloadAttempt(base + "/insecure", root.resolve("redirect"), 1000, 150));

            OneClick app = new OneClick(root.resolve("cache"), root.resolve("config"), "a".repeat(40));
            Path source = root.resolve("source space"); Files.createDirectories(source.resolve("scripts"));
            Files.createDirectories(source.resolve("gradle/wrapper"));
            Properties manifest = new Properties(), wrapper = new Properties();
            manifest.setProperty("gradle.file", "gradle-8.13-bin.zip"); manifest.setProperty("gradle.sha256", checksum);
            manifest.setProperty("gradle.mirror", base + "/bad"); manifest.setProperty("gradle.official", base + "/good");
            try (OutputStream out = Files.newOutputStream(source.resolve("scripts/download-manifest.properties"))) { manifest.store(out, "fixture"); }
            wrapper.setProperty("distributionUrl", "https://services.gradle.org/distributions/gradle-8.13-bin.zip");
            wrapper.setProperty("distributionSha256Sum", checksum);
            Path properties = source.resolve("gradle/wrapper/gradle-wrapper.properties");
            try (OutputStream out = Files.newOutputStream(properties)) { wrapper.store(out, "fixture"); }
            app.prepareGradle(source);
            try (InputStream in = Files.newInputStream(properties)) { wrapper.load(in); }
            check(wrapper.getProperty("distributionUrl").startsWith("file:"), "Wrapper uses verified local ZIP");
            check(wrapper.getProperty("distributionSha256Sum").equals(checksum), "Wrapper checksum retained");
            int before = requests.get(); app.prepareGradle(source);
            check(before == requests.get(), "complete verified Gradle cache must be reused");
            wrapper.setProperty("distributionSha256Sum", "0".repeat(64));
            try (OutputStream out = Files.newOutputStream(properties)) { wrapper.store(out, "mismatch"); }
            rejects(() -> app.prepareGradle(source));
            check(!OneClick.dependencyNetworkFailure("Compilation error: Unresolved reference: example"), "compile errors do not trigger official retry");
            check(OneClick.dependencyNetworkFailure("Could not GET artifact: Read timed out"), "network errors trigger official retry");
            Path launcher = source.resolve("gradlew");
            Files.writeString(launcher, "#!/usr/bin/env bash\nprintf '%s\\n' \"$*\" >> \"$0.calls\"\n"
                    + "case \"$*\" in *-Pibudaoa.useMirrors=true*) echo 'Could not GET artifact'; exit 1;; esac\necho 'OK'; exit 0\n");
            app.buildApk(source, Map.of());
            Path calls = source.resolve("gradlew.calls");
            List<String> commands = Files.readAllLines(calls);
            check(commands.size() == 2 && commands.get(0).contains("-Pibudaoa.useMirrors=true")
                    && commands.get(1).contains("-Pibudaoa.useMirrors=false"), "dependency failures retry official repositories once");
            Files.delete(calls);
            Files.writeString(launcher, "#!/usr/bin/env bash\nprintf '%s\\n' \"$*\" >> \"$0.calls\"\necho 'Compilation error: Unresolved reference'; exit 1\n");
            rejects(() -> app.buildApk(source, Map.of()));
            check(Files.readAllLines(calls).size() == 1, "compiler errors are not retried");
            if (args.length > 2) {
                String fingerprint = HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-1").digest(keys.getCertificate("test").getEncoded()));
                ProcessBuilder ps = new ProcessBuilder(args[2], "-NoProfile", "-File", args[3], base, checksum, root.toString(), fingerprint);
                ps.inheritIO();
                ps.environment().put("XDG_CACHE_HOME", root.resolve("ps-cache").toString());
                ps.environment().put("XDG_CONFIG_HOME", root.resolve("ps-config").toString());
                ps.environment().put("XDG_DATA_HOME", root.resolve("ps-data").toString());
                ps.environment().put("XDG_RUNTIME_DIR", root.resolve("ps-runtime").toString());
                if (ps.start().waitFor() != 0) throw new AssertionError("PowerShell HTTPS transport tests failed");
            }
            System.out.println("TLS transport + Gradle integration: PASS " + checks + " checks");
        } finally { server.stop(0); executor.shutdownNow(); }
    }
}
