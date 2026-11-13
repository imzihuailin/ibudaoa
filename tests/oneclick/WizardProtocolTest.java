import java.nio.file.Path;

public class WizardProtocolTest {
    public static void main(String[] args) throws Exception {
        OneClick app = new OneClick(Path.of("."), Path.of("."), "a".repeat(64));
        if (!app.wizard("BIND", "AA:BB").equals("continue")) throw new AssertionError("binding response");
        if (!app.wizard("KEY", "请输入高德 Android Key：").equals("1234567890abcdef1234567890abcdef")) throw new AssertionError("key response");
        if (!app.wizard("KEY", "复用").equals("")) throw new AssertionError("reuse response");
        System.out.println("Wizard protocol: PASS");
    }
}
