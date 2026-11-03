import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class OneClickTest {
    static int checks;
    static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    interface Action { void run() throws Exception; }
    static void rejects(Action action, String message) throws Exception {
        try { action.run(); } catch (IOException expected) { checks++; return; }
        throw new AssertionError(message);
    }
    static OneClick app(Path root, String answer) throws Exception {
        System.setIn(new ByteArrayInputStream(answer.getBytes(StandardCharsets.UTF_8)));
        OneClick app = new OneClick(root.resolve("cache"), root.resolve("config"), "a".repeat(40));
        app.own(app.cache); app.own(app.config); return app;
    }
    static void recordOutput(OneClick app, Path output, boolean created) throws Exception {
        Files.createDirectories(output);
        Files.writeString(app.config.resolve("output.path"), output.toString());
        if (created) Files.writeString(app.config.resolve("output.created"), output.toString());
        Files.writeString(output.resolve("ibudaoa.apk"), "our apk");
        Files.writeString(app.config.resolve("output.sha256"), OneClick.digest(output.resolve("ibudaoa.apk"), "SHA-256"));
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("ibudaoa 测试 space ");
        try {
            if (args.length > 0 && args[0].equals("key")) {
                OneClick app = new OneClick(root.resolve("cache"), root.resolve("config"), "a".repeat(40));
                app.own(app.config); app.readKey();
                check(Files.readString(app.config.resolve("amap.key")).equals("a".repeat(32)), "key saved");
                System.out.println("KEY_PASS"); return;
            }
            OneClick cancel = app(root.resolve("cancel"), "\n");
            recordOutput(cancel, root.resolve("cancel-output"), true);
            check(!cancel.uninstall(), "default cancels");
            check(Files.exists(cancel.config.resolve("output.path")), "cancel preserves config");
            check(Files.exists(root.resolve("cancel-output/ibudaoa.apk")), "cancel preserves apk");

            OneClick clean = app(root.resolve("clean"), "y\n");
            Path output = root.resolve("用户 下载 space"); recordOutput(clean, output, true);
            Files.writeString(output.resolve("用户文件.txt"), "keep");
            check(clean.uninstall(), "approve cleanup");
            check(!Files.exists(clean.config), "config removed");
            check(!Files.exists(output.resolve("ibudaoa.apk")), "managed apk removed");
            check(Files.readString(output.resolve("用户文件.txt")).equals("keep"), "other files preserved");
            check(Files.exists(clean.cache), "JVM cache left for shell");

            OneClick empty = app(root.resolve("empty"), "y\n");
            Path emptyOutput = root.resolve("empty-output"); recordOutput(empty, emptyOutput, true);
            check(empty.uninstall() && !Files.exists(emptyOutput), "remove owned empty output");

            OneClick replaced = app(root.resolve("replaced"), "y\n");
            Path replacedOutput = root.resolve("replaced-output"); recordOutput(replaced, replacedOutput, true);
            Files.writeString(replacedOutput.resolve("ibudaoa.apk"), "someone else's apk");
            rejects(replaced::uninstall, "replaced apk must be kept");
            check(Files.exists(replaced.config), "retain records after refusal");

            OneClick foreign = app(root.resolve("foreign"), "y\n");
            Path foreignOutput = root.resolve("foreign-output"); Files.createDirectories(foreignOutput);
            Files.writeString(foreignOutput.resolve("ibudaoa.apk"), "existing");
            // Simulate an existing default destination without giving the tool ownership.
            OneClick override = new OneClick(foreign.cache, foreign.config, "a".repeat(40)) {
                Path defaultOutput() { return foreignOutput; }
            };
            rejects(override::output, "do not overwrite unowned apk");

            Path unowned = root.resolve("not-owned"); Files.createDirectories(unowned);
            rejects(() -> foreign.own(unowned), "do not claim existing directory");
            Path link = root.resolve("link"); Files.createSymbolicLink(link, output);
            rejects(() -> OneClick.noLinks(link.resolve("x")), "reject link ancestors");
            Path tree = root.resolve("tree"); Files.createDirectories(tree); Files.createSymbolicLink(tree.resolve("outside"), output);
            OneClick.deleteTree(tree);
            check(Files.exists(output.resolve("用户文件.txt")), "recursive removal never follows links");

            Path zip = root.resolve("bad.zip");
            try (ZipOutputStream stream = new ZipOutputStream(Files.newOutputStream(zip))) {
                stream.putNextEntry(new ZipEntry("../escape")); stream.write(1); stream.closeEntry();
            }
            rejects(() -> OneClick.extract(zip, root.resolve("extract")), "reject zip traversal");
            check(!Files.exists(root.resolve("escape")), "no escaped file");
            Path privateFile = root.resolve("private"); OneClick.atomicWrite(privateFile, "secret".getBytes(StandardCharsets.UTF_8));
            if (!OneClick.WINDOWS) check(Files.getPosixFilePermissions(privateFile).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")), "private file permissions");
            check(foreign.batch(root.resolve("space test/run"), "space arg")[2].equals("space arg"), "argument boundaries");
            OneClick signing = app(root.resolve("signing"), "\n");
            signing.prepareSigning();
            String firstSignature = Files.readString(signing.config.resolve("signing.sha1"));
            signing.prepareSigning();
            check(Files.readString(signing.config.resolve("signing.sha1")).equals(firstSignature), "reuse signature");
            Files.delete(signing.config.resolve("signing.keystore"));
            rejects(signing::prepareSigning, "never replace missing signature");
            Path sdk = root.resolve("partial-sdk");
            Files.createDirectories(sdk.resolve("platforms/android-36"));
            Files.writeString(sdk.resolve("platforms/android-36/android.jar"), "interrupted download");
            check(!foreign.platformComplete(sdk), "incomplete platform must be repaired");
            Path tools = sdk.resolve("build-tools/35.0.0"); Files.createDirectories(tools);
            Files.writeString(tools.resolve("apksigner" + (OneClick.WINDOWS ? ".bat" : "")), "partial extraction");
            check(!foreign.buildToolsComplete(sdk), "launcher alone is not an installed toolchain");
            check(!foreign.sdkComplete(sdk), "partial SDK must never be reused");
            System.out.println("PASS " + checks + " checks");
        } finally { OneClick.deleteTree(root); }
    }
}
