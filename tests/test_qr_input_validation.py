import subprocess
import tempfile
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/dev/xarkon/link/QrInputValidation.java"

HARNESS = r"""
import dev.xarkon.link.QrInputValidation;
public final class QrValidationHarness {
    public static void main(String[] args) {
        for (String good : new String[]{"192.168.1.1", "10.0.0.1", "172.16.0.2", "169.254.2.3"})
            if (!QrInputValidation.isLocalIpv4(good)) throw new AssertionError("Rejected local: " + good);
        for (String bad : new String[]{"", "127.0.0.1", "8.8.8.8", "0.0.0.0", "255.255.255.255",
                "256.1.2.3", "192.168.1", "192.168.1.1.5", "192.168.01.1", "192.168.1.-1",
                "192.168.1.1.evil", "192.168.1.1%25eth0", "1.1.1.1", "192.168.1.999"})
            if (QrInputValidation.isLocalIpv4(bad)) throw new AssertionError("Accepted unsafe: " + bad);
        if (QrInputValidation.requirePort("45588", 45588) != 45588)
            throw new AssertionError("Valid port rejected");
        for (String bad : new String[]{ "", "45589", "045588", "abc" }) {
            try { QrInputValidation.requirePort(bad, 45588); throw new AssertionError("Accepted port " + bad); }
            catch (IllegalArgumentException expected) { }
        }
        QrInputValidation.requireMode("2", null, null);
        QrInputValidation.requireMode("3", "1", null);
        QrInputValidation.requireMode("4", "1", "1");
        for (String[] bad : new String[][]{
                {"2","1",null},{"3",null,null},{"3","0",null},{"3","1","1"},
                {"4","1",null},{"4","0","1"},{"4","1","0"}}) {
            try { QrInputValidation.requireMode(bad[0],bad[1],bad[2]);
                  throw new AssertionError("Accepted inconsistent QR mode"); }
            catch (IllegalArgumentException expected) { }
        }
        for (int i=0; i<=4; i++)
            if (QrInputValidation.security(Integer.toString(i)) != i) throw new AssertionError("Security");
        for (String bad : new String[]{"", "-1", "5", "00", "abc"}) {
            try { QrInputValidation.security(bad); throw new AssertionError("Accepted security " + bad); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
"""

class QrInputValidationTests(unittest.TestCase):
    def test_local_qr_input_is_rejected_without_dns(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            (path / "QrValidationHarness.java").write_text(HARNESS, encoding="utf-8")
            subprocess.run(["javac", "-encoding", "UTF-8", "-d", tmp, str(SOURCE),
                            str(path / "QrValidationHarness.java")], check=True)
            subprocess.run(["java", "-cp", tmp, "QrValidationHarness"], check=True)

if __name__ == "__main__":
    unittest.main()
