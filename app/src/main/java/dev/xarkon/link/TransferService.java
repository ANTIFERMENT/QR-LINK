package dev.xarkon.link;

import android.app.*;
import android.content.*;
import android.os.*;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import org.json.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TransferService extends Service implements TransferEngine.Events {
    public static final String RECEIVE="dev.xarkon.link.RECEIVE",STOP="dev.xarkon.link.STOP",SEND="dev.xarkon.link.SEND";
    public static final String CONNECT="dev.xarkon.link.CONNECT";
    public static final String BENCH="dev.xarkon.link.BENCH",FTP="dev.xarkon.link.FTP",ROTATE="dev.xarkon.link.ROTATE";
    public static volatile String status="Служба выключена",detail="",receiveKey="";
    public static volatile long progress=0,total=0;
    public static volatile double bytesPerSecond=0;
    public static volatile String activeFile="";
    public static volatile String transferDirection="";
    private long sampleAt=0,sampleBytes=0;
    private String sampledFile="";
    public static volatile boolean isReceiving=false,isBusy=false;
    private static final String CHANNEL="xarkon_transfer";
    private static final int NOTIFICATION=118;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private TransferEngine engine;
    private PowerManager.WakeLock wake;
    private long lastNotify;
    private HotspotSupport hotspot;
    private WifiPeerConnector wifiPeer;
    private DirectWifi directWifi;
    private WifiManager.WifiLock wifiLock;
    public static volatile boolean hotspotReady=false, hotspotStarting=false;
    public static volatile String hotspotSsid="", hotspotPassword="", hotspotHost="";
    public static volatile int hotspotSecurity=0;
    public static volatile String peerState="";
    public static volatile String systemApHost="";
    public static volatile boolean directReady=false,directStarting=false;
    public static volatile String directMac="",directSsid="",directPass="",directHost="";
    @Override public void onCreate(){super.onCreate();
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(
            new NotificationChannel(CHANNEL,"Передача файлов",NotificationManager.IMPORTANCE_LOW));
        engine=new TransferEngine(this,this);
        hotspot=new HotspotSupport(this);
        wifiPeer=new WifiPeerConnector(this);
        directWifi=new DirectWifi(this);
    }
    private Notification notification(){
        Intent intent=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stopIntent=new Intent(this,TransferService.class).setAction(STOP);
        PendingIntent stopPi=PendingIntent.getService(this,1,stopIntent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        String notice=activeFile.isEmpty()?status:(activeFile+" · "+Util.readable(progress)+" / "+Util.readable(total));
        Notification.Builder b=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("QR LINK · фоновая передача").setContentText(notice).setContentIntent(pi).setOngoing(isReceiving||isBusy||!peerState.isEmpty())
            .setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"Остановить",stopPi).build());
        if(total>0)b.setProgress(100,(int)Math.min(100,progress*100/Math.max(1,total)),false);
        return b.build();
    }
    private void foreground(){
        if(android.os.Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTIFICATION,notification());
    }
    private void refresh(){MainActivity.changed();
        if(Util.now()-lastNotify>750){lastNotify=Util.now();
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION,notification());}
    }
    private void lock(){if(wake==null){PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        wake=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"XarkonLink:FileTransfer");wake.setReferenceCounted(false);}
        if(!wake.isHeld())wake.acquire();
        if(wifiLock==null){
            WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
            if(wm!=null){wifiLock=wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"QRLink:Transfer");wifiLock.setReferenceCounted(false);}
        }
        if(wifiLock!=null&&!wifiLock.isHeld())wifiLock.acquire();
    }
    private void unlock(){if(wake!=null&&wake.isHeld())wake.release();
        if(wifiLock!=null&&wifiLock.isHeld())wifiLock.release();}
    // An established QR pairing is not an active transfer. Release the CPU and
    // high-performance Wi-Fi locks when idle; SEND acquires them again.
    // Receiving keeps its locks while the server is listening in the background.
    private void unlockIfIdle(){if(!isReceiving&&!isBusy)unlock();}
    @Override public int onStartCommand(Intent in,int flags,int startId){
        if(in==null)return START_NOT_STICKY;
        String action=in.getAction();
        if(STOP.equals(action)){
            engine.stop();hotspot.stop();wifiPeer.stop();directWifi.close();resetHotspot();resetDirect();systemApHost="";isReceiving=false;receiveKey="";peerState="";activeFile="";transferDirection="";progress=0;total=0;status="Служба остановлена";
            unlock();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();MainActivity.changed();return START_NOT_STICKY;
        }
        foreground();
        if(RECEIVE.equals(action)||ROTATE.equals(action)){
            try{
                if(!WifiState.localTransferReady(this)){
                    note("Wi-Fi выключен, точка доступа не обнаружена. Включите Wi-Fi.");
                    return START_NOT_STICKY;
                }
                if(ROTATE.equals(action)){engine.stop();isReceiving=false;receiveKey="";}
                if(!engine.receiving()){
                    byte[] secret=new byte[32];new SecureRandom().nextBytes(secret);
                    StringBuilder hex=new StringBuilder(64);for(byte b:secret)hex.append(String.format(Locale.US,"%02x",b&255));
                    receiveKey=hex.toString();engine.start(receiveKey);isReceiving=true;
                }
                lock();
                if(directReady){systemApHost="";status="Wi-Fi Direct · ожидание отправителя";refresh();}
                else if(!WifiState.hostedApAddress(this).isEmpty()){
                    systemApHost=WifiState.hostedApAddress(this);
                    status="Системная точка доступа · "+systemApHost+":"+Util.PORT;refresh();
                }else if(!WifiState.clientConnected(this)){
                    systemApHost="";
                    status="Создаём Wi-Fi Direct группу...";refresh();
                    if(!directStarting){
                        directStarting=true;directWifi.host(new DirectWifi.HostListener(){
                            @Override public void ready(String mac,String ssid,String pass,String host){
                                directReady=true;directStarting=false;directMac=mac;directSsid=ssid;
                                directPass=pass;directHost=host;
                                status="Wi-Fi Direct активен · покажите QR";refresh();
                            }
                            @Override public void failure(String reason){
                                resetDirect();note(reason);
                            }
                        });
                    }
                }else{
                    systemApHost="";
                    status="Приём по общей Wi-Fi сети · "+Util.ip()+":"+Util.PORT;refresh();
                }
            }catch(Exception e){note("Не удалось запустить приём: "+e.getMessage());}
        }else if(CONNECT.equals(action)){
            if(!WifiState.localTransferReady(this)){
                note("Нет локального Wi-Fi. Включите его и повторно отсканируйте QR.");
                return START_NOT_STICKY;
            }
            String raw=in.getStringExtra("qr");
            try{
                Pairing.Info info=Pairing.parse(raw);
                if(!info.hotspot){note("Это QR общей Wi-Fi сети, используйте стандартное подключение");}
                else{
                    getSharedPreferences("settings",0).edit().remove("host").remove("key").apply();
                    lock();
                    if(info.direct){
                        peerState="Ищем Wi-Fi Direct приёмник...";refresh();
                        directWifi.join(info.mac,new DirectWifi.JoinListener(){
                            @Override public void connected(String host){
                                verifyReceiver(host,info,true);
                            }
                            @Override public void failure(String reason){
                                peerState="Wi-Fi Direct: "+reason+". Пробуем подключение по имени сети...";refresh();
                                fallbackWifi(info);
                            }
                        });
                    }else fallbackWifi(info);
                }
            }catch(Exception e){note("Ошибка QR: "+e.getMessage());}
        }else if(SEND.equals(action))runJob(()->{
            if(!WifiState.localTransferReady(this))throw new java.io.IOException(
                "Локальная Wi-Fi сеть недоступна.");
            android.content.SharedPreferences p=getSharedPreferences("settings",0);
            String host=p.getString("host","").trim();int port=p.getInt("port",Util.PORT);
            String pin=p.getString("key","");int lanes=p.getInt("lanes",4);
            List<FileCatalog.Item> list=FileCatalog.load(this);List<FileCatalog.Item>todo=new ArrayList<>();
            for(FileCatalog.Item it:list)if(!"Передан".equals(it.status))todo.add(it);
            if(todo.isEmpty()){note("Нет файлов для отправки");return;}
            if(host.isEmpty()||pin.isEmpty())throw new java.io.IOException(
                "Нет подключённого получателя. Отсканируйте QR заново.");
            engine.send(host,port,pin,todo,lanes);
        });
        else if(BENCH.equals(action))runJob(()->{
            if(!WifiState.localTransferReady(this))throw new java.io.IOException(
                "Локальная Wi-Fi сеть недоступна.");
            android.content.SharedPreferences p=getSharedPreferences("settings",0);
            String host=p.getString("host","");int port=p.getInt("port",Util.PORT);
            String pin=p.getString("key","");int lanes=p.getInt("lanes",4);
            note("Тест сети: 512 МиБ данных, "+lanes+" поток(а/ов)");
            double rate=TransferEngine.benchmark(host,port,pin,lanes);
            note(String.format(Locale.US,"Тест завершён: %.1f МиБ/с (%.0f Мбит/с)",rate/1048576.0,rate*8/1000000.0));
        });
        else if(FTP.equals(action))runJob(()->{
            String url=in.getStringExtra("ftp_url"),dir=in.getStringExtra("ftp_dir"),name=in.getStringExtra("ftp_name");
            if(url==null||name==null)return;
            try(FtpBridge ftp=new FtpBridge(this,url)){ftp.download(dir,name,this);}
        });
        return START_NOT_STICKY;
    }
    private void fallbackWifi(Pairing.Info info){
        peerState="Подключение к сети приёмника. Может потребоваться подтверждение Android";refresh();
        wifiPeer.connect(info,new WifiPeerConnector.Listener(){
            @Override public void connected(String host){verifyReceiver(host,info,false);}
            @Override public void failure(String reason){
                peerState="Не удалось подключиться: "+reason;
                note(peerState);
                unlockIfIdle();
            }
        });
    }
    private void verifyReceiver(String host,Pairing.Info info,boolean p2pPath){
        peerState="Проверяем защищённый сеанс QR LINK...";refresh();
        work.execute(()->{
            try{
                org.json.JSONObject hello=new org.json.JSONObject(Util.http(host,info.port,info.key,"/hello","GET",null));
                if(!"XARKON LINK".equals(hello.optString("app")))throw new Exception("Несовместимый приёмник");
                getSharedPreferences("settings",0).edit().putString("host",host).putInt("port",info.port)
                    .putString("key",info.key).apply();
                peerState="Подключено по QR · готово к отправке";status=peerState;
                unlockIfIdle();refresh();
            }catch(Exception e){
                if(info.direct&&p2pPath){
                    peerState="Wi-Fi Direct подключён, но приёмник не отвечает. Пробуем резервный маршрут";refresh();
                    fallbackWifi(info);
                }else{peerState="Ошибка проверки приёмника: "+e.getMessage();note(peerState);
                    unlockIfIdle();}
            }
        });
    }
    private interface Work{void run()throws Exception;}
    private void runJob(Work job){
        if(!busy.compareAndSet(false,true)){note("Другая операция ещё выполняется");return;}
        isBusy=true;status="Подготовка...";lock();refresh();
        work.submit(()->{
            try{job.run();}catch(Exception e){note("Ошибка: "+e.getMessage());}
            finally{isBusy=false;busy.set(false);progress=0;total=0;bytesPerSecond=0;activeFile="";transferDirection="";
                status=isReceiving?(directReady?"Wi-Fi Direct активен": "Приём включён · "+Util.ip()+":"+Util.PORT):
                    (!peerState.isEmpty()?peerState:"Готов к работе");
                unlockIfIdle();refresh();}
        });
    }
    @Override public void note(String message){
        detail=message;status=message;
        android.content.SharedPreferences prefs=getSharedPreferences("history",0);
        String old=prefs.getString("log","");
        String next=new java.text.SimpleDateFormat("dd.MM HH:mm:ss",Locale.getDefault()).format(new Date())+"  "+message+"\n"+old;
        if(next.length()>20000)next=next.substring(0,20000);
        prefs.edit().putString("log",next).apply();refresh();
    }
    @Override public synchronized void progress(String label,long received,long max,double speed){
        status=label;
        String file=label;
        String direction="";
        int sep=label.indexOf(": ");
        if(sep>=0){direction=label.substring(0,sep);file=label.substring(sep+2);}
        else if(label.startsWith("Готово: ")){direction="Отправка";file=label.substring(8);}
        if(!file.equals(sampledFile)||received<sampleBytes){
            sampledFile=file;sampleAt=Util.now();sampleBytes=received;
            bytesPerSecond=0;
        }
        long now=Util.now();
        // Both devices use the same moving-window payload metric. The old sender
        // used average-from-start while the receiver used an instant estimate.
        if(now-sampleAt>=300){
            long delta=Math.max(0L,received-sampleBytes);
            double instant=delta*1000d/Math.max(1L,now-sampleAt);
            bytesPerSecond=bytesPerSecond<=0?instant:
                (bytesPerSecond*.72d+instant*.28d);
            sampleAt=now;sampleBytes=received;
        }
        transferDirection=direction;activeFile=file;progress=received;total=max;refresh();
    }
    @Override public void onTimeout(int startId,int foregroundServiceType){
        note("Достигнуто системное ограничение фоновой службы. Продолжите позднее.");
        stopSelf();
    }
    private void resetDirect(){directReady=false;directStarting=false;directMac="";directSsid="";
        directPass="";directHost="";MainActivity.changed();}
    private void resetHotspot(){hotspotReady=false;hotspotStarting=false;hotspotSsid="";hotspotPassword="";hotspotHost="";hotspotSecurity=0;MainActivity.changed();}
    @Override public void onDestroy(){engine.shutdown();hotspot.stop();wifiPeer.stop();directWifi.destroy();resetHotspot();resetDirect();systemApHost="";work.shutdownNow();unlock();
        isReceiving=false;isBusy=false;receiveKey="";activeFile="";transferDirection="";progress=0;total=0;status="Служба выключена";MainActivity.changed();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
