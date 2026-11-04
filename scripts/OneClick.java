import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Shared Java 17 engine. Shell entrypoints own the lock and final cache deletion. */
public class OneClick {
    static final String OWNER = "ibudaoa-oneclick-v1";
    static final String REPO = "https://github.com/imzihuailin/ibudaoa";
    static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");
    final Path cache, config, javaHome;
    final String revision;
    final BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String stage = "初始化";

    OneClick(Path cache, Path config, String revision) {
        this.cache = cache.toAbsolutePath().normalize();
        this.config = config.toAbsolutePath().normalize();
        this.revision = revision;
        javaHome = Path.of(System.getProperty("java.home"));
    }

    public static void main(String[] args) {
        try {
            if (!WINDOWS && !System.getProperty("os.name").equals("Linux")) throw new IOException("仅支持 Windows 和 Linux");
            if (args.length != 3 || !Set.of("build", "uninstall").contains(args[0]) || !args[1].matches("[a-f0-9]{64}"))
                throw new IOException("入口参数无效");
            Path base = WINDOWS ? Path.of(requireEnv("LOCALAPPDATA"), "ibudaoa")
                    : Path.of(System.getProperty("user.home"), ".local", "share", "ibudaoa");
            OneClick app = new OneClick(Path.of(args[2]), base.resolve("config"), args[1]);
            app.requireOwned(app.cache);
            if (args[0].equals("uninstall")) {
                if (app.uninstall()) System.exit(10);
            } else {
                try { app.build(); }
                catch (Exception e) { throw new IOException("失败阶段：" + app.stage + "。" + e.getMessage(), e); }
            }
        } catch (Exception e) {
            System.err.println("操作失败：" + e.getMessage());
            System.err.println("请处理上述问题后重新执行同一命令。未报告构建成功的旧 APK 不代表本次结果。");
            System.exit(1);
        }
    }

    static String requireEnv(String key) throws IOException {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IOException("缺少系统目录：" + key);
        return value;
    }

    void step(int n, String message) { stage = message; System.out.println("\n[" + n + "/5] " + message); }

    static void noLinks(Path path) throws IOException {
        for (Path p = path.toAbsolutePath(); p != null; p = p.getParent())
            if (Files.isSymbolicLink(p) || (WINDOWS && Files.exists(p, LinkOption.NOFOLLOW_LINKS)
                    && Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther()))
                throw new IOException("路径不能经过符号链接或连接点：" + p);
    }

    void requireOwned(Path path) throws IOException {
        noLinks(path);
        Path marker = path.resolve(".owner");
        if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker)
                || !Files.readString(marker).equals(OWNER)) throw new IOException("不是本工具创建的目录：" + path);
    }

    void own(Path path) throws IOException {
        noLinks(path);
        if (Files.exists(path)) { requireOwned(path); return; }
        Files.createDirectories(path);
        privateFile(path, true);
        Files.writeString(path.resolve(".owner"), OWNER, StandardOpenOption.CREATE_NEW);
    }

    static void privateFile(Path path, boolean directory) throws IOException {
        if (!WINDOWS) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        // Windows inherits the current user's private LocalAppData ACL.
    }

    static void atomicWrite(Path destination, byte[] data) throws IOException {
        noLinks(destination);
        Path temp = Files.createTempFile(destination.getParent(), ".oneclick-", ".tmp");
        try {
            privateFile(temp, false);
            Files.write(temp, data);
            try { Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    static void deleteTree(Path path) throws IOException {
        // walkFileTree never follows links. A link inside the owned tree is deleted, not traversed.
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }

    void deleteOwnedTree(Path path) throws IOException {
        requireOwned(path);
        try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
            for (Path child : children) if (!child.getFileName().toString().equals(".owner")) deleteTree(child);
        }
        // Keep ownership evidence until all potentially failing child deletions finish, allowing retries.
        Files.delete(path.resolve(".owner"));
        Files.delete(path);
    }

    static void download(String address, Path destination) throws Exception {
        downloadFrom(List.of(address), destination, null);
    }

    static void downloadFrom(List<String> sources, Path destination, String checksum) throws Exception {
        if (checksum != null && !checksum.matches("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}"))
            throw new IOException("下载校验值无效");
        IOException last = null;
        Path partial = destination.resolveSibling(destination.getFileName() + ".part");
        Files.createDirectories(destination.getParent());
        System.out.println("下载文件：" + destination.getFileName());
        for (String source : sources) {
            for (int attempt = 1; attempt <= 2; attempt++) {
                System.out.println("下载来源：" + URI.create(source).getHost() + "（尝试 " + attempt + "/2）");
                try {
                    downloadAttempt(source, partial, 15000, 30000);
                    if (checksum != null && !digest(partial, checksum.length() == 40 ? "SHA-1" : "SHA-256").equalsIgnoreCase(checksum))
                        throw new IOException("文件校验失败");
                    try { Files.move(partial, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                    catch (AtomicMoveNotSupportedException e) { Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING); }
                    System.out.println("\r✓ 下载完成                                          ");
                    return;
                } catch (IOException e) {
                    last = e;
                    System.out.println("\n下载失败或长时间无进展：" + e.getMessage() + "。正在重试或切换来源。");
                } finally { Files.deleteIfExists(partial); }
            }
        }
        throw new IOException("所有下载来源均失败：" + (last == null ? "没有下载地址" : last.getMessage()), last);
    }

    static void downloadAttempt(String address, Path destination, int connectTimeout, int readTimeout) throws IOException {
        URI uri = URI.create(address);
        HttpURLConnection connection = null;
        try {
            for (int redirect = 0; redirect <= 5; redirect++) {
                if (!"https".equalsIgnoreCase(uri.getScheme())) throw new IOException("下载地址及跳转必须使用 HTTPS");
                connection = (HttpURLConnection) uri.toURL().openConnection();
                connection.setConnectTimeout(connectTimeout);
                connection.setReadTimeout(readTimeout);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "ibudaoa-oneclick");
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String next = connection.getHeaderField("Location");
                    connection.disconnect(); connection = null;
                    if (next == null) throw new IOException("下载跳转地址缺失");
                    uri = uri.resolve(next);
                    continue;
                }
                if (status != 200) throw new IOException("下载失败，HTTP " + status);
                long total = connection.getContentLengthLong(), count = 0, lastTime = System.nanoTime(), lastBytes = 0;
                try (InputStream in = connection.getInputStream(); OutputStream out = Files.newOutputStream(destination)) {
                    byte[] buffer = new byte[65536];
                    for (int n; (n = in.read(buffer)) != -1;) {
                        out.write(buffer, 0, n); count += n;
                        long now = System.nanoTime();
                        if (now - lastTime >= 1_000_000_000L) {
                            double speed = (count - lastBytes) / 1024.0 / ((now - lastTime) / 1_000_000_000.0);
                            System.out.printf(Locale.ROOT, "\r下载中…… %.1f MB  %s  %.0f KB/s    ", count / 1048576.0,
                                    total > 0 ? count * 100 / total + "%" : "大小未知", speed);
                            lastTime = now; lastBytes = count;
                        }
                    }
                }
                if (total >= 0 && count != total) throw new IOException("下载文件不完整");
                return;
            }
            throw new IOException("下载跳转次数过多");
        } finally { if (connection != null) connection.disconnect(); }
    }

    static void extract(Path archive, Path directory) throws IOException {
        Files.createDirectories(directory);
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                Path target = directory.resolve(entry.getName()).normalize();
                if (!target.startsWith(directory) || entry.getName().contains("\\")) throw new IOException("压缩包路径异常");
                if (entry.isDirectory()) Files.createDirectories(target);
                else { Files.createDirectories(target.getParent()); Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING); }
            }
        }
    }

    int process(Path directory, Map<String, String> extra, String... command) throws Exception {
        return processLogged(directory, extra, null, command);
    }

    int processLogged(Path directory, Map<String, String> extra, Path log, String... command) throws Exception {
        return processLogged(directory, extra, log, false, command);
    }

    int processLogged(Path directory, Map<String, String> extra, Path log, boolean acceptLicenses, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).inheritIO();
        if (acceptLicenses) builder.redirectInput(ProcessBuilder.Redirect.PIPE);
        if (log != null) builder.redirectOutput(ProcessBuilder.Redirect.PIPE).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        // Do not inherit user hooks that redirect caches or inject arguments/credentials into Gradle.
        for (String key : List.of("GRADLE_OPTS", "JAVA_OPTS", "JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS")) env.remove(key);
        env.put("JAVA_HOME", javaHome.toString());
        String path = javaHome.resolve("bin") + File.pathSeparator + env.getOrDefault("PATH", "");
        if (WINDOWS) {
            String systemRoot = env.get("SystemRoot");
            if (systemRoot == null || systemRoot.isBlank()) throw new IOException("Windows 系统目录不可用，无法运行 SDK 工具");
            Path system32 = Path.of(systemRoot, "System32");
            if (!Files.isRegularFile(system32.resolve("findstr.exe")))
                throw new IOException("Windows 系统工具 findstr.exe 不存在，请修复系统后重试");
            path = javaHome.resolve("bin") + File.pathSeparator + system32 + File.pathSeparator + env.getOrDefault("PATH", "");
        }
        env.put("PATH", path);
        env.put("GRADLE_USER_HOME", cache.resolve("gradle").toString());
        env.put("ANDROID_USER_HOME", cache.resolve("android-user").toString());
        env.put("ANDROID_EMULATOR_HOME", cache.resolve("android-user/emulator").toString());
        env.putAll(extra);
        Process child = builder.start();
        Thread hook = new Thread(() -> { child.descendants().forEach(ProcessHandle::destroy); child.destroy(); });
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            if (acceptLicenses) {
                // Consent was obtained once by this tool. Only SDK commands receive these answers.
                try (OutputStream answers = child.getOutputStream()) {
                    answers.write("y\n".repeat(256).getBytes(StandardCharsets.US_ASCII));
                } catch (IOException ignored) {
                    // An already licensed SDK may close stdin immediately; its exit code decides success.
                }
            }
            if (log != null) {
                try (InputStream in = child.getInputStream(); OutputStream out = Files.newOutputStream(log)) {
                    byte[] buffer = new byte[8192];
                    for (int n; (n = in.read(buffer)) != -1;) { System.out.write(buffer, 0, n); out.write(buffer, 0, n); }
                }
            }
            return child.waitFor();
        } finally {
            if (child.isAlive()) { child.descendants().forEach(ProcessHandle::destroy); child.destroy(); }
            Runtime.getRuntime().removeShutdownHook(hook);
        }
    }

    void mustRun(Path directory, Map<String, String> env, String... command) throws Exception {
        if (process(directory, env, command) != 0) throw new IOException("命令执行失败，请查看上方工具提示");
    }

    String executable(String name) { return name + (WINDOWS ? ".exe" : ""); }

    String[] batch(Path script, String... args) throws IOException {
        List<String> result = new ArrayList<>();
        if (WINDOWS) {
            // .bat files ultimately use cmd, which expands percent variables even in quotes.
            if (script.toString().matches(".*[%!\r\n\"].*")) throw new IOException("Windows 构建路径包含不支持的字符");
            for (String arg : args) if (arg.matches(".*[%!\r\n\"].*")) throw new IOException("Windows 参数含不支持的字符");
            // Let PowerShell quote batch paths/arguments, rather than cmd /c's fragile nested quotes.
            StringBuilder command = new StringBuilder("$ErrorActionPreference='Stop'; [Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); $OutputEncoding=[Console]::OutputEncoding; & ")
                    .append(psLiteral(script.toString()));
            for (String arg : args) command.append(' ').append(psLiteral(arg));
            command.append("; exit $LASTEXITCODE");
            return new String[]{"powershell.exe", "-NoProfile", "-EncodedCommand",
                    Base64.getEncoder().encodeToString(command.toString().getBytes(StandardCharsets.UTF_16LE))};
        }
        result.add("bash");
        result.add(script.toString());
        result.addAll(Arrays.asList(args));
        return result.toArray(String[]::new);
    }

    static String psLiteral(String value) { return "'" + value.replace("'", "''") + "'"; }

    Path prepareSource() throws Exception {
        Path source = cache.resolve("work/ibudaoa-main");
        noLinks(source);
        Path snapshot = cache.resolve("work/source.sha256");
        noLinks(snapshot);
        if (!Files.isRegularFile(snapshot) || !Files.readString(snapshot).equals(revision)
                || !Files.isRegularFile(source.resolve("gradlew"))
                || !Files.isRegularFile(source.resolve("app/build.gradle.kts"))
                || !Files.isRegularFile(source.resolve("scripts/download-manifest.properties")))
            throw new IOException("本次源码快照不完整，请重新运行入口");
        System.out.println("✓ 源码已准备，快照 SHA-256：" + revision.substring(0, 12));
        return source;
    }

    void prepareGradle(Path source) throws Exception {
        Properties manifest = new Properties(), wrapper = new Properties();
        try (InputStream in = Files.newInputStream(source.resolve("scripts/download-manifest.properties"))) { manifest.load(in); }
        Path properties = source.resolve("gradle/wrapper/gradle-wrapper.properties");
        try (InputStream in = Files.newInputStream(properties)) { wrapper.load(in); }
        String name = manifest.getProperty("gradle.file", ""), checksum = manifest.getProperty("gradle.sha256", "");
        if (!name.matches("gradle-[0-9.]+-bin\\.zip") || !checksum.matches("[a-f0-9]{64}")
                || !checksum.equals(wrapper.getProperty("distributionSha256Sum"))
                || !wrapper.getProperty("distributionUrl", "").endsWith("/" + name))
            throw new IOException("下载清单与 Gradle Wrapper 版本或校验值不一致");
        Path zip = cache.resolve("tools/" + name);
        noLinks(zip);
        if (!Files.isRegularFile(zip) || !digest(zip, "SHA-256").equals(checksum)) {
            downloadFrom(List.of(manifest.getProperty("gradle.mirror"), manifest.getProperty("gradle.official")), zip, checksum);
        } else System.out.println("✓ Gradle 下载缓存校验通过");
        wrapper.setProperty("distributionUrl", zip.toUri().toASCIIString());
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        wrapper.store(data, "Managed one-click build: verified local distribution");
        atomicWrite(properties, data.toByteArray());
    }

    static boolean dependencyNetworkFailure(String log) {
        return Pattern.compile("(?i)could not (?:GET|HEAD|resolve)|could not transfer|(?:connect|read|connection|socket) timed out|remote host terminated the handshake|PKIX path building failed")
                .matcher(log).find();
    }

    void buildApk(Path source, Map<String, String> env) throws Exception {
        Path log = cache.resolve("gradle-build.log");
        for (boolean mirrors : new boolean[]{true, false}) {
            System.out.println(mirrors ? "依赖来源：阿里云镜像优先" : "正在使用官方依赖仓库重试……");
            int code = processLogged(source, env, log, batch(source.resolve(WINDOWS ? "gradlew.bat" : "gradlew"),
                    "--no-daemon", "--console=plain", "-Pkotlin.compiler.execution.strategy=in-process",
                    "-Pandroid.builder.sdkDownload=false", "-Pibudaoa.useMirrors=" + mirrors,
                    "-Dorg.gradle.internal.http.connectionTimeout=15000", "-Dorg.gradle.internal.http.socketTimeout=30000", ":app:assembleRelease"));
            if (code == 0) return;
            if (!mirrors || !dependencyNetworkFailure(new String(Files.readAllBytes(log), StandardCharsets.UTF_8)))
                throw new IOException("编译失败，请查看上方错误");
            System.out.println("镜像依赖下载失败，保留已有缓存并切换官方源。");
        }
    }

    Path prepareSdk() throws Exception {
        for (String variable : List.of("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            String existing = System.getenv(variable);
            if (existing != null && sdkComplete(Path.of(existing))) {
                System.out.println("✓ 复用现有 Android SDK"); return Path.of(existing).toAbsolutePath();
            }
        }
        Path sdk = cache.resolve("tools/android-sdk");
        if (sdkComplete(sdk)) { System.out.println("✓ Android SDK"); return sdk; }
        System.out.println("Android SDK 尚无已验证完整的国内镜像，本步骤使用 Google 官方源。");
        Path manager = sdk.resolve("cmdline-tools/19.0/bin/sdkmanager" + (WINDOWS ? ".bat" : ""));
        Path toolsReady = manager.getParent().getParent().resolve(".oneclick-complete");
        if (!Files.isRegularFile(manager) || !Files.isRegularFile(toolsReady)) {
            Path metadata = cache.resolve("sdk-repository.xml");
            download("https://dl.google.com/android/repository/repository2-1.xml", metadata);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(metadata.toFile());
            String host = WINDOWS ? "windows" : "linux";
            Element selected = null;
            NodeList packages = document.getElementsByTagName("remotePackage");
            for (int i = 0; i < packages.getLength(); i++) {
                Element item = (Element) packages.item(i);
                if (!item.getAttribute("path").equals("cmdline-tools;19.0")) continue;
                NodeList archives = item.getElementsByTagName("archive");
                for (int j = 0; j < archives.getLength(); j++) {
                    Element candidate = (Element) archives.item(j);
                    if (text(candidate, "host-os").equals(host)) selected = candidate;
                }
            }
            if (selected == null) throw new IOException("未找到适用的 Android 命令行工具 19.0");
            String url = text(selected, "url"), checksum = text(selected, "checksum");
            if (!url.matches("commandlinetools-[A-Za-z0-9_.-]+\\.zip") || !checksum.matches("[a-fA-F0-9]{40}"))
                throw new IOException("Android 工具下载元数据异常");
            Path zip = cache.resolve("sdk-tools.zip"), unpack = cache.resolve("sdk-unpack");
            downloadFrom(List.of("https://dl.google.com/android/repository/" + url), zip, checksum);
            deleteTree(unpack); extract(zip, unpack);
            Path target = sdk.resolve("cmdline-tools/19.0");
            deleteTree(target); Files.createDirectories(target.getParent());
            Files.move(unpack.resolve("cmdline-tools"), target);
            atomicWrite(toolsReady, "19.0".getBytes(StandardCharsets.UTF_8));
            deleteTree(unpack); Files.delete(zip);
        }
        // sdkmanager can leave package directories after interruption. Only repair our own SDK.
        if (!platformComplete(sdk)) deleteTree(sdk.resolve("platforms/android-36"));
        if (!buildToolsComplete(sdk)) deleteTree(sdk.resolve("build-tools/35.0.0"));
        Map<String, String> env = Map.of("ANDROID_HOME", sdk.toString(), "ANDROID_SDK_ROOT", sdk.toString());
        confirmSdkLicenses();
        for (String[] command : List.of(
                batch(manager, "--sdk_root=" + sdk, "--licenses"),
                batch(manager, "--sdk_root=" + sdk, "platforms;android-36", "build-tools;35.0.0"))) {
            if (processLogged(cache, env, null, true, command) != 0)
                throw new IOException("SDK 许可接受或安装失败，请查看上方提示");
        }
        if (!sdkComplete(sdk)) throw new IOException("SDK 安装未完成或许可尚未接受");
        return sdk;
    }

    static String text(Element item, String name) throws IOException {
        NodeList list = item.getElementsByTagName(name);
        if (list.getLength() == 0) throw new IOException("Android 元数据缺少字段：" + name);
        return list.item(0).getTextContent().trim();
    }

    boolean sdkComplete(Path sdk) {
        return platformComplete(sdk) && buildToolsComplete(sdk)
                && Files.isDirectory(sdk.resolve("licenses"));
    }

    boolean platformComplete(Path sdk) {
        Path platform = sdk.resolve("platforms/android-36");
        return Files.isRegularFile(platform.resolve("package.xml")) && validJar(platform.resolve("android.jar"));
    }

    boolean buildToolsComplete(Path sdk) {
        Path tools = sdk.resolve("build-tools/35.0.0");
        return Files.isRegularFile(tools.resolve("package.xml"))
                && Files.isRegularFile(tools.resolve("apksigner" + (WINDOWS ? ".bat" : "")))
                && Files.isRegularFile(tools.resolve(executable("aapt"))) && validJar(tools.resolve("lib/apksigner.jar"));
    }

    static boolean validJar(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) { return zip.size() > 0; }
        catch (IOException e) { return false; }
    }

    static String digest(Path file, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    void prepareSigning() throws Exception {
        own(config);
        Path key = config.resolve("signing.keystore");
        if (!Files.exists(key)) {
            // A missing key after first initialization must never silently change the identity.
            if (Files.exists(config.resolve("signing.sha1"))) throw new IOException("原签名文件缺失，请恢复备份");
            Path temp = config.resolve("signing-new.keystore");
            Files.deleteIfExists(temp);
            mustRun(config, Map.of(), javaHome.resolve("bin/" + executable("keytool")).toString(),
                    "-genkeypair", "-keystore", temp.toString(), "-storetype", "JKS", "-alias", "androiddebugkey",
                    "-storepass", "android", "-keypass", "android", "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "10000", "-dname", "CN=ibudaoa local build", "-noprompt");
            privateFile(temp, false);
            Files.move(temp, key);
        }
        noLinks(key);
        KeyStore store = KeyStore.getInstance("JKS");
        try (InputStream in = Files.newInputStream(key)) { store.load(in, "android".toCharArray()); }
        if (!store.isKeyEntry("androiddebugkey")) throw new IOException("签名别名不匹配");
        X509Certificate cert = (X509Certificate) store.getCertificate("androiddebugkey");
        String sha = HexFormat.ofDelimiter(":").withUpperCase().formatHex(MessageDigest.getInstance("SHA-1").digest(cert.getEncoded()));
        Path identity = config.resolve("signing.sha1");
        if (Files.exists(identity) && !Files.readString(identity).equals(sha)) throw new IOException("签名与保存的身份不匹配，请恢复备份");
        atomicWrite(identity, sha.getBytes(StandardCharsets.UTF_8));
        System.out.println("✓ 签名已保存\n包名：io.github.ibudaoa\nSHA-1：" + sha);
        System.out.println("请保留签名，后续覆盖升级会自动复用。\n备份目录：" + config);
    }

    void confirmSdkLicenses() throws IOException {
        System.out.println("Android SDK 许可：https://developer.android.com/studio/terms\n"
                + "本次同意涵盖安装所需的 Android SDK 许可，后续逐项询问由程序自动确认。");
        System.out.print("是否同意本次安装所需的 Android SDK 许可？[y/N]：");
        String answer = input.readLine();
        if (answer == null || !answer.trim().equalsIgnoreCase("y"))
            throw new IOException("未同意 Android SDK 许可，已停止安装");
    }

    static String terminalLink(Path path) {
        return terminalLink(path.toUri().toASCIIString(), path.toString());
    }

    static String terminalLink(String url, String label) {
        label = label.replaceAll("[\\p{Cntrl}]", "?");
        return System.console() == null ? label
                : "\033]8;;" + url + "\033\\" + label + "\033]8;;\033\\";
    }

    void confirmMapBinding() throws IOException {
        String url = "https://console.amap.com/dev/key/app";
        System.out.println("\n请打开高德控制台，将以上 SHA-1 填入所用 Android Key 的“发布版安全码 SHA1”，"
                + "并确认包名与上方一致。\n" + terminalLink(url, url));
        System.out.print("确认已完成绑定后，按回车继续：");
        if (input.readLine() == null) throw new IOException("绑定确认已取消");
    }

    String readMaskedKey(String prompt) throws Exception {
        if (System.console() == null) throw new IOException("请在交互式终端执行命令，以便显示星号输入 Key");
        String[] command;
        if (WINDOWS) {
            String script = """
                    $ErrorActionPreference='Stop'
                    [Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
                    $value=[Text.StringBuilder]::new()
                    [Console]::Error.Write($env:IBUDAOA_KEY_PROMPT)
                    while($true) {
                        $key=[Console]::ReadKey($true)
                        if($key.Key -eq 'Enter') { break }
                        if(($key.Modifiers -band [ConsoleModifiers]::Control) -and $key.Key -in @('C','D','Z')) { exit 130 }
                        if($key.Key -eq 'Backspace') {
                            if($value.Length -gt 0) { $value.Length--; [Console]::Error.Write("`b `b") }
                        } elseif(![char]::IsControl($key.KeyChar) -and $value.Length -lt 256) {
                            [void]$value.Append($key.KeyChar); [Console]::Error.Write('*')
                        }
                    }
                    [Console]::Error.WriteLine()
                    [Console]::Write($value.ToString())
                    """;
            command = new String[]{"powershell.exe", "-NoProfile", "-EncodedCommand",
                    Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE))};
        } else {
            command = new String[]{"bash", "-c", """
                    saved=$(stty -g) || exit 1
                    trap 'stty "$saved"' EXIT
                    trap 'exit 130' INT TERM
                    stty -echo -icanon min 1 time 0 || exit 1
                    printf '%s' "$IBUDAOA_KEY_PROMPT" >&2
                    value=''
                    while IFS= read -r -s -n 1 ch; do
                        case "$ch" in
                            '') printf '\\n' >&2; printf '%s' "$value"; exit 0 ;;
                            $'\\177'|$'\\b') if [ -n "$value" ]; then value=${value%?}; printf '\\b \\b' >&2; fi ;;
                            $'\\004'|$'\\032') exit 130 ;;
                            *) if [[ "$ch" != [[:cntrl:]] && ${#value} -lt 256 ]]; then value+=$ch; printf '*' >&2; fi ;;
                        esac
                    done
                    exit 130
                    """};
        }
        ProcessBuilder builder = new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT);
        builder.environment().put("IBUDAOA_KEY_PROMPT", prompt);
        Process child = builder.start();
        try {
            String value = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (child.waitFor() != 0) throw new IOException("Key 输入已取消");
            return value;
        } finally { if (child.isAlive()) child.destroy(); }
    }

    String readKey() throws Exception {
        Path saved = config.resolve("amap.key");
        noLinks(saved);
        String old = Files.exists(saved) ? Files.readString(saved).trim() : "";
        while (true) {
            String prompt = old.matches("[a-fA-F0-9]{32}") ? "输入新 Key，或直接回车复用：" : "请输入高德 Android Key：";
            String value = readMaskedKey(prompt).trim();
            if (value.isEmpty()) value = old;
            if (!value.matches("[a-fA-F0-9]{32}")) { System.out.println("Key 格式不正确，应为 32 位十六进制字符。请重新输入。"); continue; }
            atomicWrite(saved, value.getBytes(StandardCharsets.UTF_8));
            System.out.println("✓ Key 已保存"); return value;
        }
    }

    Path defaultOutput() throws Exception {
        String home = System.getProperty("user.home");
        if (WINDOWS) {
            // Use explicit UTF-8 instead of reg.exe's locale-dependent output for Chinese user paths.
            String query = "[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); "
                    + "$p=(Get-ItemProperty -LiteralPath 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders').'{374DE290-123F-4565-9164-39C4925E467B}'; "
                    + "if($p){[Console]::Write([Environment]::ExpandEnvironmentVariables($p))}";
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", query)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() == 0) {
                String path = result.trim();
                if (!path.isEmpty() && !path.contains("%") && Path.of(path).isAbsolute()) return Path.of(path).resolve("ibudaoa");
            }
        } else {
            Path xdg = Path.of(home, ".config/user-dirs.dirs");
            if (Files.isRegularFile(xdg)) {
                Matcher match = Pattern.compile("(?m)^XDG_DOWNLOAD_DIR=\"([^\"]+)\"$").matcher(Files.readString(xdg));
                if (match.find()) {
                    String path = match.group(1).replace("$HOME", home);
                    if (!path.contains("$") && Path.of(path).isAbsolute()) return Path.of(path).resolve("ibudaoa");
                }
            }
            Path downloads = Path.of(home, "Downloads");
            if (Files.isDirectory(downloads)) return downloads.resolve("ibudaoa");
        }
        return Path.of(home, "ibudaoa-output");
    }

    Path output() throws Exception {
        Path record = config.resolve("output.path");
        noLinks(record);
        Path output = Files.exists(record) ? Path.of(Files.readString(record)) : defaultOutput();
        output = output.toAbsolutePath().normalize();
        noLinks(output);
        Path apk = output.resolve("ibudaoa.apk");
        if (!Files.exists(record)) {
            if (Files.exists(apk)) throw new IOException("输出位置已有非本工具创建的 APK，请先移走：" + apk);
            if (!Files.exists(output)) {
                Files.createDirectories(output);
                atomicWrite(config.resolve("output.created"), output.toString().getBytes(StandardCharsets.UTF_8));
            }
            atomicWrite(record, output.toString().getBytes(StandardCharsets.UTF_8));
        }
        Files.createDirectories(output);
        return output;
    }

    void build() throws Exception {
        stage = "下载最新源码"; Path source = prepareSource();
        step(2, "准备编译环境"); System.out.println("✓ Java 17"); prepareGradle(source); Path sdk = prepareSdk();
        step(3, "准备应用签名"); prepareSigning(); confirmMapBinding();
        step(4, "配置 Key"); String key = readKey();
        Path signing = source.resolve(".local/signing"); Files.createDirectories(signing);
        Files.copy(config.resolve("signing.keystore"), signing.resolve("debug.keystore"), StandardCopyOption.REPLACE_EXISTING);
        privateFile(signing.resolve("debug.keystore"), false);
        Properties properties = new Properties();
        properties.setProperty("sdk.dir", sdk.toString()); properties.setProperty("amap.android.key", key);
        properties.setProperty("project.repository.url", REPO);
        ByteArrayOutputStream data = new ByteArrayOutputStream(); properties.store(data, "Managed local build configuration");
        atomicWrite(source.resolve("local.properties"), data.toByteArray());
        Path target = output();
        step(5, "编译 APK"); System.out.println("首次编译可能需要几分钟……");
        Map<String, String> env = Map.of("ANDROID_HOME", sdk.toString(), "ANDROID_SDK_ROOT", sdk.toString());
        buildApk(source, env);
        Path apk = source.resolve("app/build/outputs/apk/release/app-release.apk");
        if (!Files.isRegularFile(apk)) throw new IOException("未生成 APK");
        mustRun(source, env, batch(sdk.resolve("build-tools/35.0.0/apksigner" + (WINDOWS ? ".bat" : "")), "verify", "--verbose", apk.toString()));
        // aapt's manifest output verifies package and debuggable flag without installing on a phone.
        Path aapt = sdk.resolve("build-tools/35.0.0/" + executable("aapt"));
        Process check = new ProcessBuilder(aapt.toString(), "dump", "badging", apk.toString()).redirectErrorStream(true).start();
        String badging = new String(check.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (check.waitFor() != 0 || !badging.contains("package: name='io.github.ibudaoa'") || badging.contains("application-debuggable"))
            throw new IOException("APK 包名或 release 属性验证失败");
        Path result = target.resolve("ibudaoa.apk");
        atomicWrite(result, Files.readAllBytes(apk));
        atomicWrite(config.resolve("output.sha256"), digest(result, "SHA-256").getBytes(StandardCharsets.UTF_8));
        System.out.println("\n构建完成\n\n输出文件夹：" + terminalLink(target)
                + "\nAPK 文件名：ibudaoa.apk\n\n将 APK 传到手机安装即可。");
    }

    boolean uninstall() throws Exception {
        stage = "清理";
        if (Files.exists(config)) requireOwned(config);
        System.out.println("\n将删除本工具保存的源码、工具、缓存、Key、签名及输出 APK。\n缓存目录：" + cache + "\n配置目录：" + config);
        Path output = null;
        if (Files.isRegularFile(config.resolve("output.path"))) {
            output = Path.of(Files.readString(config.resolve("output.path"))).toAbsolutePath().normalize();
            noLinks(output);
            System.out.println("输出 APK：" + output.resolve("ibudaoa.apk"));
        }
        System.out.println("删除签名后，将无法用新签名覆盖升级原来安装的应用。\n如需保留升级能力，请先备份整个配置目录：" + config);
        System.out.print("确认全部删除？[y/N]：");
        String answer = input.readLine();
        if (answer == null || !answer.trim().equalsIgnoreCase("y")) { System.out.println("已取消，未删除文件。"); return false; }
        if (output != null) {
            Path apk = output.resolve("ibudaoa.apk");
            if (Files.exists(apk, LinkOption.NOFOLLOW_LINKS)) {
                noLinks(apk);
                Path fingerprint = config.resolve("output.sha256");
                if (!Files.isRegularFile(fingerprint) || !digest(apk, "SHA-256").equals(Files.readString(fingerprint)))
                    throw new IOException("输出 APK 已被替换，保留该文件并停止清理：" + apk);
                Files.delete(apk);
            }
            Path created = config.resolve("output.created");
            if (Files.isRegularFile(created) && Files.readString(created).equals(output.toString())) {
                try { Files.deleteIfExists(output); } catch (DirectoryNotEmptyException ignored) { /* Keep user files. */ }
            }
        }
        // Builds use --no-daemon; only a short-lived daemon is ever used and Gradle waits for it on completion.
        if (Files.exists(config)) deleteOwnedTree(config);
        System.out.println("✓ 配置、签名及本工具的输出 APK 已清理\n正在清理工具与缓存……");
        return true; // Shell removes cached JVM after this JVM exits.
    }
}
