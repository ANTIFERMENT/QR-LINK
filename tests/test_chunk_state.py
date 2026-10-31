import subprocess
import tempfile
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/dev/xarkon/link/ChunkState.java"

HARNESS = r"""
import dev.xarkon.link.ChunkState;
import java.util.BitSet;
public final class ChunkStateHarness {
    public static void main(String[] args) {
        int c = 8 * 1024 * 1024;
        if (ChunkState.count(0,c)!=1 || ChunkState.count(1,c)!=1 ||
            ChunkState.count(c,c)!=1 || ChunkState.count(c+1L,c)!=2)
            throw new AssertionError("chunk count");
        BitSet bits = new BitSet();
        bits.set(0); bits.set(2);
        if (ChunkState.bytes(bits,2L*c+7,c)!=c+7L)
            throw new AssertionError("completed bytes");
        bits.set(3);
        try { ChunkState.validate(bits,3); throw new AssertionError("out-of-range bit accepted"); }
        catch (IllegalArgumentException expected) {}
        try { ChunkState.count(-1,c); throw new AssertionError("negative size accepted"); }
        catch (IllegalArgumentException expected) {}
        if (ChunkState.bytes(new BitSet(),0,c)!=0)
            throw new AssertionError("zero-length file");
    }
}
"""

class ChunkStateTests(unittest.TestCase):
    def test_resume_bitmap_and_file_boundaries(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            harness = path / "ChunkStateHarness.java"
            harness.write_text(HARNESS, encoding="utf-8")
            subprocess.run(["javac", "-encoding", "UTF-8", "-d", tmp, str(SOURCE),
                            str(harness)], check=True)
            subprocess.run(["java", "-cp", tmp, "ChunkStateHarness"], check=True)

if __name__ == "__main__":
    unittest.main()
