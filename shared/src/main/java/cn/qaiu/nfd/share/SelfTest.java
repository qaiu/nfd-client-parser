package cn.qaiu.nfd.share;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.PrintStream;
import java.nio.charset.Charset;

/**
 * Checks that a packaged runtime can actually run the parser. jlink/jpackage images and the
 * Termux JDK can silently miss modules (charsets, EC crypto, dynalink for Nashorn), which only
 * shows up as an obscure failure on the first parse.
 */
public final class SelfTest {

    public static final String SAMPLE_LINK = "https://pan.lanzoui.com/b66477";

    private final PrintStream out;
    private boolean ok = true;

    public SelfTest(PrintStream out) {
        this.out = out;
    }

    public SelfTest check(String name, Check check) {
        boolean passed;
        String detail = "";
        try {
            passed = check.run();
        } catch (Throwable ex) {
            passed = false;
            detail = " (" + ex + ")";
        }
        out.println((passed ? "[ok]   " : "[FAIL] ") + name + detail);
        out.flush();
        ok &= passed;
        return this;
    }

    public SelfTest runtimeChecks() {
        check("java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor", "?") + ") on "
                + System.getProperty("os.name") + "/" + System.getProperty("os.arch"), () -> true);
        check("parser " + ParserRuntime.parserVersion(), () -> !ParserRuntime.parserVersion().equals("unknown"));
        check("GBK charset", () -> Charset.isSupported("GBK"));
        check("TLS with EC ciphers", () -> {
            SSLEngine engine = SSLContext.getDefault().createSSLEngine();
            for (String suite : engine.getEnabledCipherSuites()) {
                if (suite.contains("ECDHE")) {
                    return true;
                }
            }
            return false;
        });
        check("Nashorn script engine", () -> {
            ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
            return engine != null && ((Number) engine.eval("1 + 2")).intValue() == 3;
        });
        check("share link detection", () -> ShareSession.open(SAMPLE_LINK, null).type().equals("lz"));
        check("Vert.x runtime", () -> {
            ParserRuntime.start();
            return ParserRuntime.started();
        });
        return this;
    }

    public SelfTest onlineCheck() {
        return check("live listing (" + SAMPLE_LINK + ")",
                () -> !ShareSession.open(SAMPLE_LINK, null).list(null).entries().isEmpty());
    }

    public boolean passed() {
        return ok;
    }

    /** Prints the verdict and stops the parser runtime. */
    public boolean finish() {
        ParserRuntime.stop();
        out.println(ok ? "SELF-TEST OK" : "SELF-TEST FAILED");
        out.flush();
        return ok;
    }

    @FunctionalInterface
    public interface Check {
        boolean run() throws Exception;
    }
}
