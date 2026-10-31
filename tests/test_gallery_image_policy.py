import subprocess
import tempfile
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
POLICY = ROOT / "app/src/main/java/dev/xarkon/link/GalleryImagePolicy.java"
SCANNER = ROOT / "app/src/main/java/dev/xarkon/link/QrScannerActivity.java"

HARNESS = r"""
import dev.xarkon.link.GalleryImagePolicy;
public final class GalleryPolicyHarness {
    private static void check(int w,int h) {
        int sample=GalleryImagePolicy.sampleSize(w,h);
        if(sample<1 || (sample&(sample-1))!=0)throw new AssertionError("Not a power of two: "+sample);
        if(Math.max(w,h)/(long)sample>GalleryImagePolicy.MAX_EDGE)throw new AssertionError("Edge too large");
        if((long)w*h/(long)sample/sample>GalleryImagePolicy.MAX_PIXELS)throw new AssertionError("Pixels too many");
    }
    public static void main(String[] args) {
        check(640,480);check(4032,3024);check(12000,9000);
        check(200000,100000);check(Integer.MAX_VALUE,Integer.MAX_VALUE);
        if(GalleryImagePolicy.sampleSize(640,480)!=1)throw new AssertionError("Small image downsampled");
        for(int[] invalid:new int[][]{{0,5},{-1,4},{10,0}}) {
            try{GalleryImagePolicy.sampleSize(invalid[0],invalid[1]);throw new AssertionError("Invalid size accepted");}
            catch(IllegalArgumentException expected){}
        }
    }
}
"""

class GalleryImagePolicyTests(unittest.TestCase):
    def test_gallery_bitmap_is_bounded(self):
        with tempfile.TemporaryDirectory() as folder:
            harness = Path(folder) / "GalleryPolicyHarness.java"
            harness.write_text(HARNESS, encoding="utf-8")
            subprocess.run(["javac", "-encoding", "UTF-8", "-d", folder, str(POLICY), str(harness)],
                           check=True, capture_output=True, text=True)
            subprocess.run(["java", "-cp", folder, "GalleryPolicyHarness"],
                           check=True, capture_output=True, text=True)

    def test_gallery_decoding_does_not_block_ui(self):
        scanner = SCANNER.read_text(encoding="utf-8")
        self.assertIn("GalleryImagePolicy.sampleSize", scanner)
        self.assertIn("decoder.execute(()->{", scanner)
        self.assertIn("decodeGalleryQr(uri)", scanner)
        self.assertIn("decodeY(new RGBLuminanceSource", scanner)
        self.assertIn("bitmap.recycle()", scanner)

if __name__ == "__main__":
    unittest.main()
