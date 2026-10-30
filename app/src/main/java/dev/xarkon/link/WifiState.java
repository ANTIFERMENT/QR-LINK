package dev.xarkon.link;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * A Wi-Fi interface is not necessarily a connected station:
 * P2P group owners, hotspot networks, and VPN-backed networks also exist.
 * Never decide to use a LAN QR merely because TRANSPORT_WIFI is present.
 */
public final class WifiState {
    private WifiState(){}
    public static boolean radioOn(Context c){
        WifiManager wm=(WifiManager)c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        return wm!=null&&wm.isWifiEnabled();
    }
    public static boolean clientConnected(Context c){
        if(!radioOn(c))return false;
        ConnectivityManager cm=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm==null)return false;
        try{
            for(Network network:cm.getAllNetworks()){
                NetworkCapabilities caps=cm.getNetworkCapabilities(network);
                if(caps==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))continue;
                if(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_WIFI_P2P))continue;
                // Group owners and Android tethering advertise local addresses
                // but are NOT clients of an existing access point.
                if(Build.VERSION.SDK_INT>=35&&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK))continue;
                LinkProperties links=cm.getLinkProperties(network);
                if(links==null)continue;
                boolean address=false, gateway=false;
                for(android.net.LinkAddress la:links.getLinkAddresses()){
                    InetAddress addr=la.getAddress();
                    if(addr instanceof Inet4Address&&!addr.isLoopbackAddress()&&
                        !addr.isLinkLocalAddress())address=true;
                }
                for(RouteInfo route:links.getRoutes()){
                    InetAddress gw=route.getGateway();
                    if(gw instanceof Inet4Address&&!gw.isAnyLocalAddress()&&
                        !gw.isLoopbackAddress())gateway=true;
                }
                if(address&&gateway)return true;
            }
        }catch(SecurityException ignored){}
        return false;
    }
    /** Android hotspot hosting is not a Wi-Fi client connection. */
    public static String hostedApAddress(Context c){
        if(clientConnected(c))return "";
        ConnectivityManager cm=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm!=null&&Build.VERSION.SDK_INT>=35)try{
            for(Network n:cm.getAllNetworks()){
                NetworkCapabilities cap=cm.getNetworkCapabilities(n);
                if(cap==null||!cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||
                    !cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK))continue;
                LinkProperties lp=cm.getLinkProperties(n);
                if(lp==null)continue;
                for(android.net.LinkAddress la:lp.getLinkAddresses()){
                    InetAddress addr=la.getAddress();
                    if(addr instanceof Inet4Address&&addr.isSiteLocalAddress())return addr.getHostAddress();
                }
            }
        }catch(Exception ignored){}
        // Many tethering devices leave AP active while STA Wi-Fi is disabled.
        if(!radioOn(c)){
            String ip=HotspotSupport.hotspotIp();
            if(!ip.isEmpty()&&!"127.0.0.1".equals(ip))return ip;
        }
        return "";
    }
    public static boolean localTransferReady(Context c){
        return radioOn(c)||!hostedApAddress(c).isEmpty();
    }
    public static String receiveHint(Context c){
        if(!radioOn(c))return "Wi-Fi выключен. Включите его в настройках телефона.";
        if(clientConnected(c))return "Подключение через общую сеть Wi-Fi.";
        return "Нет подключения к роутеру. Используем Wi-Fi Direct.";
    }
}
