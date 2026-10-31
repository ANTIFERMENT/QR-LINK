package dev.xarkon.link;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Strict, DNS-free validation of untrusted QR pairing fields. Android-independent for JVM tests. */
public final class QrInputValidation {
    private QrInputValidation() {}

    public static boolean isLocalIpv4(String host) {
        if(host==null||host.isEmpty()||host.length()>15)return false;
        String[] parts=host.split("\\.",-1);
        if(parts.length!=4)return false;
        byte[] address=new byte[4];
        for(int i=0;i<4;i++){
            String part=parts[i];
            if(part.isEmpty()||part.length()>3||(part.length()>1&&part.charAt(0)=='0'))
                return false;
            int octet=0;
            for(int j=0;j<part.length();j++){
                char c=part.charAt(j);
                if(c<'0'||c>'9')return false;
                octet=octet*10+(c-'0');
            }
            if(octet>255)return false;
            address[i]=(byte)octet;
        }
        try{
            InetAddress ip=InetAddress.getByAddress(address);
            return ip.isSiteLocalAddress()||ip.isLinkLocalAddress();
        }catch(UnknownHostException impossible){return false;}
    }

    public static int requirePort(String raw,int expected) {
        if(raw==null||!raw.equals(Integer.toString(expected)))
            throw new IllegalArgumentException("Несовместимый или отсутствующий порт QR");
        return expected;
    }

    /** Version and transport flags must agree; never silently downgrade a QR invitation. */
    public static void requireMode(String version,String ap,String p2p) {
        boolean valid=("2".equals(version)&&ap==null&&p2p==null) ||
            ("3".equals(version)&&"1".equals(ap)&&p2p==null) ||
            ("4".equals(version)&&"1".equals(ap)&&"1".equals(p2p));
        if(!valid)throw new IllegalArgumentException("Несогласованные параметры подключения в QR");
    }

    public static int security(String raw) {
        // Android SoftApConfiguration currently defines security modes 0..4.
        if(raw==null||raw.length()!=1||raw.charAt(0)<'0'||raw.charAt(0)>'4')
            throw new IllegalArgumentException("Некорректный тип защиты точки Wi-Fi");
        return raw.charAt(0)-'0';
    }
}
