package dev.xarkon.link;

import java.util.BitSet;

/** Pure-Java bounds checks for resumable transfer metadata. */
public final class ChunkState {
    private ChunkState() {}
    public static int count(long size,int chunkSize) {
        if(size<0||chunkSize<=0)throw new IllegalArgumentException("Некорректный размер файла или блока");
        long blocks=size==0?1:1+(size-1)/chunkSize;
        if(blocks>Integer.MAX_VALUE)throw new IllegalArgumentException("Слишком много блоков");
        return (int)blocks;
    }
    public static void validate(BitSet done,int blocks) {
        if(done==null||blocks<1||done.length()>blocks)
            throw new IllegalArgumentException("Повреждена карта полученных блоков");
    }
    public static long bytes(BitSet done,long size,int chunkSize) {
        int blocks=count(size,chunkSize);
        validate(done,blocks);
        long result=0;
        for(int i=done.nextSetBit(0);i>=0;i=done.nextSetBit(i+1))
            result+=Math.min((long)chunkSize,size-(long)i*chunkSize);
        return result;
    }
}
