package dev.xarkon.link;

/** Bounded bitmap decoding for QR photos. No Android dependencies so it can be tested on the JVM. */
public final class GalleryImagePolicy {
    public static final int MAX_EDGE=2048;
    public static final long MAX_PIXELS=3000000L;
    private GalleryImagePolicy() {}

    public static int sampleSize(int width,int height) {
        if(width<=0||height<=0)throw new IllegalArgumentException("Некорректный размер изображения");
        int sample=1;
        while(Math.max(width,height)/(long)sample>MAX_EDGE ||
              (long)width*height/(long)sample/sample>MAX_PIXELS) {
            sample*=2;
        }
        return sample;
    }
}
