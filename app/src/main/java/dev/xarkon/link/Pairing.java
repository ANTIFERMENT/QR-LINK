package dev.xarkon.link;

import android.net.Uri;
import java.net.InetAddress;

/** The QR code is an ephemeral invitation for the currently running receiver. */
public final class Pairing {
    private Pairing() {}
    public static String build(String host, String key) {
        return new Uri.Builder().scheme("xarkonlink").authority("pair")
            .appendQueryParameter("v","2")
            .appendQueryParameter("host",host)
            .appendQueryParameter("port",String.valueOf(Util.PORT))
            .appendQueryParameter("key",key).build().toString();
    }
    /** Single QR contains a session invitation and hotspot credentials, never logged. */
    public static String buildHotspot(String host,String key,String ssid,String password,int security) {
        return new Uri.Builder().scheme("xarkonlink").authority("pair")
            .appendQueryParameter("v","3").appendQueryParameter("host",host)
            .appendQueryParameter("port",String.valueOf(Util.PORT))
            .appendQueryParameter("key",key).appendQueryParameter("ap","1")
            .appendQueryParameter("ssid",ssid).appendQueryParameter("pwd",password)
            .appendQueryParameter("sec",String.valueOf(security)).build().toString();
    }
    /** Wi-Fi Direct invitation: MAC identifies the group owner for P2P pairing;
     * SSID/password allow fallback as a legacy Wi-Fi client if OEM P2P fails. */
    public static String buildDirect(String host,String key,String mac,String ssid,String password){
        return new Uri.Builder().scheme("xarkonlink").authority("pair")
            .appendQueryParameter("v","4").appendQueryParameter("host",host)
            .appendQueryParameter("port",String.valueOf(Util.PORT))
            .appendQueryParameter("key",key).appendQueryParameter("ap","1")
            .appendQueryParameter("p2p","1").appendQueryParameter("mac",mac)
            .appendQueryParameter("ssid",ssid).appendQueryParameter("pwd",password)
            .appendQueryParameter("sec","0").build().toString();
    }
    public static final class Info {
        public final String host,key,ssid,password,mac;
        public final int port,security;
        public final boolean hotspot,direct;
        Info(String h,int p,String k,String s,String pw,int sec,boolean ap,boolean dr,String m){host=h;port=p;key=k;ssid=s;password=pw;security=sec;hotspot=ap;direct=dr;mac=m;}
    }
    public static Info parse(String value) throws Exception {
        Uri u=Uri.parse(value==null?"":value.trim());
        if(!"xarkonlink".equals(u.getScheme())||!"pair".equals(u.getAuthority())
                ||!("2".equals(u.getQueryParameter("v"))||"3".equals(u.getQueryParameter("v"))||"4".equals(u.getQueryParameter("v"))))throw new Exception("Неподдерживаемый QR-код QR LINK");
        String host=u.getQueryParameter("host"),key=u.getQueryParameter("key");
        boolean direct="4".equals(u.getQueryParameter("v"))&&"1".equals(u.getQueryParameter("p2p"));
        boolean ap=("3".equals(u.getQueryParameter("v"))||direct)&&"1".equals(u.getQueryParameter("ap"));
        if(host==null)host="";
        if(!host.isEmpty()){
            if(!host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))throw new Exception("Неверный IP в QR");
            InetAddress addr=InetAddress.getByName(host);
            if(!addr.isSiteLocalAddress()&&!addr.isLinkLocalAddress())throw new Exception("QR указывает не на локальный адрес");
        }else if(!ap)throw new Exception("В QR отсутствует IP");
        if(key==null||!key.matches("[0-9a-f]{64}"))throw new Exception("Неверный ключ QR");
        int port=Integer.parseInt(u.getQueryParameter("port"));
        if(port!=Util.PORT)throw new Exception("Несовместимый порт");
        String ssid=ap?u.getQueryParameter("ssid"):"", password=ap?u.getQueryParameter("pwd"):"";
        int security=ap?Integer.parseInt(u.getQueryParameter("sec")):0;
        if(ap&&(ssid==null||ssid.isEmpty()||ssid.length()>64||password==null||password.length()<8||password.length()>63))
            throw new Exception("Некорректные параметры точки доступа");
        String mac=direct?u.getQueryParameter("mac"):"";
        if(mac==null)mac="";
        if(direct&&mac.length()>0&&!mac.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))throw new Exception("Некорректный адрес устройства P2P");
        return new Info(host,port,key,ssid,password,security,ap,direct,mac);
    }
}
