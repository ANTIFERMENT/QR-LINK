import re
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
BROWSER = ROOT / "app/src/main/java/dev/xarkon/link/FileBrowserActivity.java"
CATALOG = ROOT / "app/src/main/java/dev/xarkon/link/FileCatalog.java"

class BrowserLimitsTests(unittest.TestCase):
    def test_large_selection_and_bounded_scans(self):
        browser = BROWSER.read_text(encoding="utf-8")
        catalog = CATALOG.read_text(encoding="utf-8")
        def value(source, name):
            match = re.search(r"\\b" + name + r"\\s*=\\s*(\\d+)\\b", source)
            self.assertIsNotNone(match, name)
            return int(match.group(1))
        self.assertGreater(value(browser, "MAX_QUEUE"), 5000)
        self.assertEqual(value(browser, "MAX_QUEUE"), value(catalog, "MAX_FOLDER_FILES"))
        self.assertEqual(value(browser, "MAX_MEDIA_SCAN"), 25000)
        self.assertEqual(value(browser, "MAX_SAF_SCAN"), 50000)
        self.assertIn("++scanned>MAX_MEDIA_SCAN", browser)
        self.assertIn("++scanned>MAX_SAF_SCAN", browser)
        self.assertIn("result.size()>MAX_FOLDER_FILES", catalog)

if __name__ == "__main__":
    unittest.main()
