package dev.xarkon.link;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import java.net.*;
import java.util.*;

/** Keeps Android LocalOnlyHotspot alive through the foreground transfer service. */
public final class HotspotSupport {
    public interface Listener { void ready(String ssid,String pass,int security,String host);void failure(String reason); }
    private final Context context;
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private boolean requesting;
    private long generation;
    public HotspotSupport(Context context){this.context=context.getApplicationContext();}
    public synchronized boolean active(){return reservation!=null;}
    public synchronized boolean requesting(){return requesting;}
    public static boolean hasWifiNetwork(Context c){
        ConnectivityManager cm=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm==null)return false;
        for(Network n:cm.getAllNetworks()){
            NetworkCapabilities caps=cm.getNetworkCapabilities(n);
            if(caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return true;
        }
        return false;
    }
    public void start(Listener listener){
        if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)!=PackageManager.PERMISSION_GRANTED){
            listener.failure("Разрешите доступ к устройствам поблизости для создания точки Wi-Fi");return;
        }
        if(Build.VERSION.SDK_INT<33&&context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            listener.failure("Разрешите геолокацию для создания точки Wi-Fi на этой версии Android");return;
        }
        final long requestGeneration;
        synchronized(this){if(requesting||reservation!=null)return;requesting=true;requestGeneration=++generation;}
        try{
            WifiManager manager=(WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if(manager==null){synchronized(this){requesting=false;}listener.failure("Система не предоставляет Wi-Fi API");return;}
            manager.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback(){
                @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation r){
                    synchronized(HotspotSupport.this){
                        if(requestGeneration!=generation){r.close();return;}
                        reservation=r;requesting=false;
                    }
                    try{
                        String ssid,pass;int security=0;
                        if(Build.VERSION.SDK_INT>=30){
                            SoftApConfiguration cfg=r.getSoftApConfiguration();
                            ssid=cfg.getSsid();pass=cfg.getPassphrase();security=cfg.getSecurityType();
                        }else{
                            WifiConfiguration cfg=r.getWifiConfiguration();
                            if(cfg==null)throw new IllegalStateException("Система не вернула параметры Wi-Fi");
                            ssid=cfg.SSID;pass=cfg.preSharedKey;
                        }
                        if(ssid==null||pass==null)throw new IllegalStateException("Система не вернула пароль Wi-Fi");
                        listener.ready(stripQuotes(ssid),stripQuotes(pass),security,hotspotIp());
                    }catch(Exception e){stop();listener.failure("Не удалось прочитать параметры точки: "+e.getMessage());}
                }
                @Override public void onStopped(){
                    boolean unexpected;
                    synchronized(HotspotSupport.this){unexpected=reservation!=null;reservation=null;requesting=false;}
                    if(unexpected)listener.failure("Локальная точка доступа остановлена системой");
                }
                @Override public void onFailed(int reason){
                    synchronized(HotspotSupport.this){requesting=false;reservation=null;}
                    listener.failure("Android не запустил точку доступа (код "+reason+"). Проверьте настройки Wi-Fi");
                }
            },new Handler(Looper.getMainLooper()));
        }catch(Exception e){synchronized(this){requesting=false;}listener.failure("Точка доступа: "+e.getMessage());}
    }
    public synchronized void stop(){
        generation++;
        WifiManager.LocalOnlyHotspotReservation previous=reservation;reservation=null;requesting=false;
        if(previous!=null)try{previous.close();}catch(Exception ignored){}
    }
    private static String stripQuotes(String s){return s!=null&&s.length()>1&&s.charAt(0)=='\"'&&s.endsWith("\"")?s.substring(1,s.length()-1):s;}
    /** An unknown IP is deliberately left blank, never guessed as 192.168.43.1. */
    public static String hotspotIp(){
        try{
            Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
            while(interfaces.hasMoreElements()){
                NetworkInterface ni=interfaces.nextElement();
                if(!ni.isUp()||ni.isLoopback())continue;
                String name=ni.getName().toLowerCase(Locale.ROOT);
                if(!(name.startsWith("ap")||name.startsWith("swlan")||name.startsWith("wlan")||name.startsWith("softap")))continue;
                Enumeration<InetAddress> addresses=ni.getInetAddresses();
                while(addresses.hasMoreElements()){
                    InetAddress a=addresses.nextElement();
                    if(a instanceof Inet4Address&&a.isSiteLocalAddress())return a.getHostAddress();
                }
            }
        }catch(Exception ignored){}
        return "";
    }
}
