import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Duration;
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
            if (args.length != 3 || !Set.of("build", "uninstall").contains(args[0]) || !args[1].matches("[a-f0-9]{40}"))
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
        URI uri = URI.create(address);
        if (!uri.getScheme().equals("https")) throw new IOException("下载地址必须使用 HTTPS");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        Path temp = destination.resolveSibling(destination.getFileName() + ".part");
        Files.createDirectories(destination.getParent());
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(15))
                    .header("User-Agent", "ibudaoa-oneclick").build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) throw new IOException("下载失败，HTTP " + response.statusCode() + "：" + uri.getHost());
                long total = response.headers().firstValueAsLong("Content-Length").orElse(-1), count = 0, last = 0;
                try (OutputStream out = Files.newOutputStream(temp)) {
                    byte[] buffer = new byte[65536];
                    for (int n; (n = in.read(buffer)) != -1;) {
                        out.write(buffer, 0, n); count += n;
                        long now = System.nanoTime();
                        if (now - last > 1_000_000_000L) {
                            System.out.print("\r下载中…… " + (total > 0 ? count * 100 / total + "%" : count / 1048576 + " MB"));
                            last = now;
                        }
                    }
                }
                if (total >= 0 && count != total) throw new IOException("下载文件不完整");
                System.out.println("\r✓ 下载完成                         ");
            }
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
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
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).inheritIO();
        Map<String, String> env = builder.environment();
        // Do not inherit user hooks that redirect caches or inject arguments/credentials into Gradle.
        for (String key : List.of("GRADLE_OPTS", "JAVA_OPTS", "JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS")) env.remove(key);
        env.put("JAVA_HOME", javaHome.toString());
        env.put("PATH", javaHome.resolve("bin") + File.pathSeparator + env.getOrDefault("PATH", ""));
        env.put("GRADLE_USER_HOME", cache.resolve("gradle").toString());
        env.put("ANDROID_USER_HOME", cache.resolve("android-user").toString());
        env.put("ANDROID_EMULATOR_HOME", cache.resolve("android-user/emulator").toString());
        env.putAll(extra);
        Process child = builder.start();
        Thread hook = new Thread(() -> { child.descendants().forEach(ProcessHandle::destroy); child.destroy(); });
        Runtime.getRuntime().addShutdownHook(hook);
        try { return child.waitFor(); } finally { Runtime.getRuntime().removeShutdownHook(hook); }
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
            StringBuilder command = new StringBuilder("$ErrorActionPreference='Stop'; & ")
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
        Path work = cache.resolve("work");
        deleteTree(work);
        Files.createDirectories(work);
        Path zip = work.resolve("source.zip");
        download("https://codeload.github.com/imzihuailin/ibudaoa/zip/" + revision, zip);
        extract(zip, work);
        Files.delete(zip);
        Path source = work.resolve("ibudaoa-" + revision);
        if (!Files.isRegularFile(source.resolve("gradlew")) || !Files.isRegularFile(source.resolve("app/build.gradle.kts")))
            throw new IOException("源码内容不完整");
        System.out.println("✓ 源码已准备，版本：" + revision.substring(0, 12));
        return source;
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
        Path manager = sdk.resolve("cmdline-tools/19.0/bin/sdkmanager" + (WINDOWS ? ".bat" : ""));
        if (!Files.isRegularFile(manager)) {
            Path metadata = cache.resolve("sdk-repository.xml");
            download("https://dl.google.com/android/repository/repository2-1.xml", metadata);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(metadata.toFile());
            String host = WINDOWS ? "windows" : System.getProperty("os.name").startsWith("Mac") ? "macosx" : "linux";
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
            download("https://dl.google.com/android/repository/" + url, zip);
            if (!digest(zip, "SHA-1").equalsIgnoreCase(checksum)) throw new IOException("Android 工具下载校验失败");
            deleteTree(unpack); extract(zip, unpack);
            Path target = sdk.resolve("cmdline-tools/19.0");
            deleteTree(target); Files.createDirectories(target.getParent());
            Files.move(unpack.resolve("cmdline-tools"), target);
            deleteTree(unpack); Files.delete(zip);
        }
        Map<String, String> env = Map.of("ANDROID_HOME", sdk.toString(), "ANDROID_SDK_ROOT", sdk.toString());
        System.out.println("请阅读接下来显示的 Android SDK 许可。\n输入 y 表示同意，输入 N 表示拒绝。");
        mustRun(cache, env, batch(manager, "--sdk_root=" + sdk, "--licenses"));
        mustRun(cache, env, batch(manager, "--sdk_root=" + sdk, "platforms;android-36", "build-tools;35.0.0"));
        if (!sdkComplete(sdk)) throw new IOException("SDK 安装未完成或许可尚未接受");
        return sdk;
    }

    static String text(Element item, String name) throws IOException {
        NodeList list = item.getElementsByTagName(name);
        if (list.getLength() == 0) throw new IOException("Android 元数据缺少字段：" + name);
        return list.item(0).getTextContent().trim();
    }

    boolean sdkComplete(Path sdk) {
        return Files.isRegularFile(sdk.resolve("platforms/android-36/android.jar"))
                && Files.isRegularFile(sdk.resolve("build-tools/35.0.0/apksigner" + (WINDOWS ? ".bat" : "")))
                && Files.isDirectory(sdk.resolve("licenses"));
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

    String readKey() throws Exception {
        Path saved = config.resolve("amap.key");
        noLinks(saved);
        String old = Files.exists(saved) ? Files.readString(saved).trim() : "";
        while (true) {
            String prompt = old.matches("[a-fA-F0-9]{32}") ? "输入新 Key，或直接回车复用：" : "请输入高德 Android Key：";
            Console console = System.console();
            if (console == null) throw new IOException("请在交互式终端执行命令，以便隐藏输入 Key");
            char[] chars = console.readPassword("%s", prompt);
            if (chars == null) throw new IOException("输入已取消");
            String value = new String(chars).trim(); Arrays.fill(chars, '\0');
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
        step(1, "下载最新源码"); Path source = prepareSource();
        step(2, "准备编译环境"); System.out.println("✓ Java 17"); Path sdk = prepareSdk();
        step(3, "准备应用签名"); prepareSigning();
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
        mustRun(source, env, batch(source.resolve(WINDOWS ? "gradlew.bat" : "gradlew"), "--no-daemon", "--console=plain",
                "-Pkotlin.compiler.execution.strategy=in-process", "-Pandroid.builder.sdkDownload=false", ":app:assembleRelease"));
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
        String path = result.toString().replaceAll("[\\p{Cntrl}]", "?");
        String link = "\033]8;;" + result.toUri().toASCIIString() + "\033\\" + path + "\033]8;;\033\\";
        System.out.println("\n构建完成\n\nAPK：" + (System.console() != null ? link : path) + "\n\n将 APK 传到手机安装即可。");
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
