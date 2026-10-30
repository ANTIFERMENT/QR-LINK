package dev.xarkon.link;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.WpsInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A Wi-Fi Direct session belongs to the foreground Service, not the Activity.
 * Receiver is the group owner. Sender joins it via scanned P2P MAC, with a
 * legacy Wi-Fi client option where the OEM does not expose P2P discovery.
 */
public final class DirectWifi {
    public interface HostListener {
        void ready(String mac, String ssid, String password, String ip);
        void failure(String message);
    }
    public interface JoinListener {
        void connected(String groupOwnerAddress);
        void failure(String message);
    }
    private final Context context;
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver receiver;
    private HostListener hostListener;
    private JoinListener joinListener;
    private String desiredMac="";
    private boolean hosting, joining, closed, connectAttempted;
    private int serial;
    private final AtomicBoolean connectionDelivered=new AtomicBoolean(false);

    public DirectWifi(Context c) {
        context=c.getApplicationContext();
        manager=(WifiP2pManager)context.getSystemService(Context.WIFI_P2P_SERVICE);
        WifiP2pManager.Channel ch=null;
        if(manager!=null)try{ch=manager.initialize(context,Looper.getMainLooper(),()->{});}catch(Exception ignored){}
        channel=ch;
        receiver=new BroadcastReceiver(){@Override public void onReceive(Context ctx, Intent event){
            String action=event.getAction();
            if(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)){
                if(hosting)readGroup(serial);
                if(joining)readConnection(serial);
            }else if(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)){
                if(joining&&!connectAttempted)checkPeers(serial);
            }else if(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)){
                int state=event.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE,WifiP2pManager.WIFI_P2P_STATE_DISABLED);
                if(state==WifiP2pManager.WIFI_P2P_STATE_DISABLED){
                    if(hosting)hostFail("Wi-Fi Direct отключён системой. Включите Wi-Fi и повторите.");
                    if(joining)joinFail("Wi-Fi Direct выключен. Включите Wi-Fi и повторите.");
                }
            }
        }};
        IntentFilter filter=new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        try{
            if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED);
            else context.registerReceiver(receiver,filter);
        }catch(Exception ignored){}
    }
    private boolean permitted(){
        return context.checkSelfPermission(Build.VERSION.SDK_INT>=33?
            Manifest.permission.NEARBY_WIFI_DEVICES:Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    private String check(){
        if(manager==null||channel==null)return "Wi-Fi Direct отсутствует в прошивке телефона";
        if(!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT))
            return "Телефон не поддерживает Wi-Fi Direct";
        if(!permitted())return "Разрешите QR LINK доступ к устройствам поблизости";
        WifiManager wifi=(WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if(wifi!=null&&!wifi.isWifiEnabled())return "Включите Wi-Fi на телефоне (подключение к роутеру не требуется)";
        return "";
    }
    private static String actionError(int reason){
        if(reason==WifiP2pManager.BUSY)return "система занята (BUSY)";
        if(reason==WifiP2pManager.P2P_UNSUPPORTED)return "Wi-Fi Direct не поддерживается (P2P_UNSUPPORTED)";
        if(reason==WifiP2pManager.ERROR)return "ошибка драйвера или системного Wi-Fi (ERROR)";
        return "код "+reason;
    }
    public void host(HostListener listener){
        main.post(()->{
            final String issue=check();if(!issue.isEmpty()){listener.failure(issue);return;}
            int id=++serial;
            hosting=true;joining=false;connectAttempted=false;
            hostListener=listener;joinListener=null;
            connectionDelivered.set(false);
            try{
                // Prefer 5 GHz for higher possible throughput, but ALWAYS fall back to
                // the original automatic band selection on incompatible OEM firmware.
                WifiManager wifi=(WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                boolean prefer5=context.getSharedPreferences("settings",0).getBoolean("prefer_5ghz",true)
                    && wifi!=null && wifi.is5GHzBandSupported();
                if(prefer5){
                    try{
                        String nonce=UUID.randomUUID().toString().replace("-", "");
                        WifiP2pConfig config=new WifiP2pConfig.Builder()
                            .setNetworkName("DIRECT-QL-"+nonce.substring(0,8))
                            .setPassphrase(nonce.substring(0,16))
                            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                            .build();
                        manager.createGroup(channel,config,new WifiP2pManager.ActionListener(){
                            @Override public void onSuccess(){if(id==serial)readGroup(id);}
                            @Override public void onFailure(int reason){if(id==serial&&hosting)createAutoGroup(id);}
                        });
                    }catch(Exception unsupported){createAutoGroup(id);}
                }else createAutoGroup(id);
                scheduleGroupPoll(id,1);
                main.postDelayed(()->{if(id==serial&&hosting&&hostListener!=null)
                    hostFail("Wi-Fi Direct не создал группу за 22 секунды. Проверьте Wi-Fi и разрешения.");},22000);
            }catch(Exception e){hostFail("Ошибка Wi-Fi Direct: "+e.getMessage());}
        });
    }
    private void createAutoGroup(int id){
        if(id!=serial||!hosting)return;
        try{
            manager.createGroup(channel,new WifiP2pManager.ActionListener(){
                @Override public void onSuccess(){if(id==serial)readGroup(id);}
                @Override public void onFailure(int reason){if(id==serial)hostFail(
                    "Не удалось создать Wi-Fi Direct группу: "+actionError(reason));}
            });
        }catch(Exception e){hostFail("Wi-Fi Direct: "+e.getMessage());}
    }
    private void scheduleGroupPoll(int id,int attempt){
        if(attempt>13)return;
        main.postDelayed(()->{
            if(id!=serial||!hosting||hostListener==null)return;
            readGroup(id);
            if(hostListener!=null)scheduleGroupPoll(id,attempt+1);
        },750);
    }
    private void readGroup(int id){
        if(id!=serial||!hosting||hostListener==null)return;
        try{manager.requestGroupInfo(channel,group->{
            if(id!=serial||!hosting||hostListener==null||group==null||!group.isGroupOwner())return;
            String ssid=group.getNetworkName(),password=group.getPassphrase();
            WifiP2pDevice owner=group.getOwner();
            String mac=owner==null?"":owner.deviceAddress;
            if(ssid==null||ssid.isEmpty()||password==null||password.length()<8){
                // Group may exist but OEM has not populated its credentials yet.
                return;
            }
            String ip=localIp(group.getInterface());
            HostListener listener=hostListener;hostListener=null;
            listener.ready(mac==null?"":mac,ssid,password,ip);
        });}catch(Exception e){hostFail("Не удалось получить параметры Wi-Fi Direct: "+e.getMessage());}
    }
    private static String localIp(String iface){
        if(iface==null)return "";
        try{
            NetworkInterface n=NetworkInterface.getByName(iface);
            if(n!=null)for(Enumeration<InetAddress> e=n.getInetAddresses();e.hasMoreElements();){
                InetAddress a=e.nextElement();
                if(a instanceof Inet4Address&&a.isSiteLocalAddress())return a.getHostAddress();
            }
        }catch(Exception ignored){}
        return "";
    }
    private void hostFail(String error){
        HostListener l=hostListener;hostListener=null;
        if(l!=null){
            hosting=false;
            try{manager.removeGroup(channel,null);}catch(Exception ignored){}
            l.failure(error);
        }
    }
    /** The sender only needs one QR: random P2P MAC plus group credentials. */
    public void join(String mac,JoinListener listener){
        main.post(()->{
            String issue=check();if(!issue.isEmpty()){listener.failure(issue);return;}
            ++serial;int id=serial;
            joining=true;hosting=false;connectAttempted=false;joinListener=listener;
            connectionDelivered.set(false);
            desiredMac=mac==null?"":mac.trim();
            if(!desiredMac.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")||"02:00:00:00:00:00".equals(desiredMac)){
                joinFail("Адрес Wi-Fi Direct недоступен; требуется резервное соединение по имени сети");return;
            }
            try{
                manager.discoverPeers(channel,new WifiP2pManager.ActionListener(){
                    @Override public void onSuccess(){checkPeers(id);}
                    @Override public void onFailure(int reason){connectDirect(id);}
                });
                // P2P discovery is unreliable with certain OEMs: try direct invite as well.
                main.postDelayed(()->{if(id==serial&&joining&&!connectAttempted)connectDirect(id);},900);
                main.postDelayed(()->{if(id==serial&&joining&&joinListener!=null)
                    joinFail("Wi-Fi Direct не ответил. Переходим к резервному подключению.");},15500);
            }catch(Exception e){joinFail("Ошибка подключения Wi-Fi Direct: "+e.getMessage());}
        });
    }
    private void checkPeers(int id){
        if(id!=serial||!joining||connectAttempted)return;
        try{manager.requestPeers(channel,(WifiP2pDeviceList peers)->{
            if(id!=serial||!joining||connectAttempted)return;
            for(WifiP2pDevice d:peers.getDeviceList()){
                if(d.deviceAddress!=null&&d.deviceAddress.equalsIgnoreCase(desiredMac)){
                    connectDirect(id);return;
                }
            }
        });}catch(Exception ignored){}
    }
    private void connectDirect(int id){
        if(id!=serial||!joining||connectAttempted)return;
        connectAttempted=true;
        try{
            WifiP2pConfig config=new WifiP2pConfig();
            config.deviceAddress=desiredMac;
            config.wps=new WpsInfo();
            config.wps.setup=WpsInfo.PBC;
            manager.connect(channel,config,new WifiP2pManager.ActionListener(){
                @Override public void onSuccess(){if(id==serial)readConnection(id);}
                @Override public void onFailure(int reason){if(id==serial)joinFail("Приглашение Wi-Fi Direct отклонено: "+actionError(reason));}
            });
        }catch(Exception e){joinFail("Подключение Wi-Fi Direct: "+e.getMessage());}
    }
    private void readConnection(int id){
        if(id!=serial||!joining||joinListener==null)return;
        try{manager.requestConnectionInfo(channel,(WifiP2pInfo info)->{
            if(id!=serial||!joining||joinListener==null||info==null||!info.groupFormed||info.groupOwnerAddress==null)return;
            if(connectionDelivered.compareAndSet(false,true)){
                JoinListener l=joinListener;joinListener=null;
                l.connected(info.groupOwnerAddress.getHostAddress());
            }
        });}catch(Exception e){joinFail("Ошибка параметров P2P: "+e.getMessage());}
    }
    private void joinFail(String error){
        JoinListener l=joinListener;joinListener=null;
        if(l!=null){
            joining=false;
            try{manager.cancelConnect(channel,null);}catch(Exception ignored){}
            l.failure(error);
        }
    }
    public void close(){
        main.post(()->{
            ++serial;hostListener=null;joinListener=null;
            if((hosting||joining)&&manager!=null&&channel!=null)
                try{manager.removeGroup(channel,null);}catch(Exception ignored){}
            hosting=false;joining=false;
        });
    }
    public void destroy(){
        closed=true;close();
        try{context.unregisterReceiver(receiver);}catch(Exception ignored){}
    }
}
