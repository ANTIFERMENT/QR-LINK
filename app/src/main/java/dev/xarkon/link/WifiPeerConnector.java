package dev.xarkon.link;

import android.content.*;
import android.net.*;
import android.net.wifi.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** WifiNetworkSpecifier requires a one-time Android connection approval. */
public final class WifiPeerConnector {
    public interface Listener {void connected(String host);void failure(String reason);}
    private final ConnectivityManager manager;
    private ConnectivityManager.NetworkCallback callback;
    private long generation;
    private synchronized boolean current(long token,ConnectivityManager.NetworkCallback owner){
        return generation==token&&callback==owner;
    }
    public WifiPeerConnector(Context c){manager=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);}
    public synchronized void stop(){
        generation++; // Invalidate callbacks and background discovery from the previous attempt.
        if(callback!=null){try{manager.unregisterNetworkCallback(callback);}catch(Exception ignored){}callback=null;}
        try{manager.bindProcessToNetwork(null);}catch(Exception ignored){}
    }
    public synchronized void connect(Pairing.Info info,Listener listener){
        stop();
        final long attempt=++generation;
        try{
            WifiNetworkSpecifier.Builder spec=new WifiNetworkSpecifier.Builder().setSsid(info.ssid);
            // Android may choose WPA3; use the same mode specified by the receiver.
            if(info.security==SoftApConfiguration.SECURITY_TYPE_WPA3_SAE)spec.setWpa3Passphrase(info.password);
            else spec.setWpa2Passphrase(info.password);
            NetworkRequest request=new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec.build()).build();
            final boolean[] delivered={false};
            final boolean[] discovering={false};
            callback=new ConnectivityManager.NetworkCallback(){
                private final java.util.concurrent.atomic.AtomicBoolean failed=
                    new java.util.concurrent.atomic.AtomicBoolean(false);
                private volatile boolean bound;
                private volatile LinkProperties latestProperties;
                private boolean active(){return current(attempt,this);}
                private void fail(String reason){
                    if(!active()||!failed.compareAndSet(false,true))return;
                    WifiPeerConnector.this.stop();
                    listener.failure(reason);
                }
                @Override public void onAvailable(Network n){
                    synchronized(WifiPeerConnector.this){
                        if(!active()||failed.get())return;
                        if(!manager.bindProcessToNetwork(n)){
                            fail("Android не разрешил использовать выбранную сеть Wi-Fi");
                            return;
                        }
                        bound=true;
                    }
                    LinkProperties properties=latestProperties;
                    deliver(n,properties!=null?properties:manager.getLinkProperties(n));
                }
                @Override public void onLinkPropertiesChanged(Network n,LinkProperties lp){
                    if(!active())return;
                    latestProperties=lp;
                    // Android may report link properties before onAvailable.
                    // Do not announce a peer until this process is bound to its Wi-Fi network.
                    if(bound)deliver(n,lp);
                }
                private void deliver(Network n,LinkProperties lp){
                    if(!active()||failed.get()||!bound||lp==null)return;
                    synchronized(delivered){if(delivered[0])return;}
                    boolean hasIpv4=false;
                    for(LinkAddress local:lp.getLinkAddresses()){
                        if(local.getAddress() instanceof Inet4Address){hasIpv4=true;break;}
                    }
                    if(!hasIpv4)return;
                    String host=resolveApAddress(lp);
                    if(host.isEmpty())host=info.host==null?"":info.host;
                    if(host.isEmpty()){
                        synchronized(discovering){if(discovering[0])return;discovering[0]=true;}
                        final LinkProperties properties=lp;
                        new Thread(()->{
                            String found=discoverPeer(n,properties);
                            if(!active()||failed.get())return;
                            if(!found.isEmpty()){
                                synchronized(delivered){if(delivered[0])return;delivered[0]=true;}
                                listener.connected(found);
                            }else fail("Сеть подключена, но Android не сообщил адрес приёмника. Повторите подключение.");
                        },"qr-link-find-peer").start();
                        return;
                    }
                    if(!active()||failed.get())return;
                    synchronized(delivered){if(delivered[0])return;delivered[0]=true;}
                    listener.connected(host);
                }
                @Override public void onUnavailable(){
                    fail("Android не подключился к точке доступа. Подтвердите запрос Wi-Fi и повторите сканирование.");
                }
                @Override public void onLost(Network n){
                    fail("Соединение с точкой доступа потеряно");
                }
            };
            manager.requestNetwork(request,callback,25000);
        }catch(Exception e){stop();listener.failure("Подключение Wi-Fi: "+e.getMessage());}
    }
    /** Uses the existing authenticated receiver's UDP discovery endpoint when DHCP omits its IP. */
    private static String discoverPeer(Network network,LinkProperties links){
        try(DatagramSocket socket=new DatagramSocket(null)){
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(0));
            network.bindSocket(socket);
            socket.setBroadcast(true);socket.setSoTimeout(2500);
            byte[] data="XARKON_DISCOVER_1".getBytes(StandardCharsets.UTF_8);
            for(LinkAddress la:links.getLinkAddresses()){
                if(!(la.getAddress() instanceof Inet4Address))continue;
                byte[] ip=la.getAddress().getAddress();int prefix=la.getPrefixLength();
                if(prefix<0||prefix>32)continue;
                byte[] broadcast=new byte[4];
                for(int b=0;b<4;b++){
                    int remaining=Math.max(0,Math.min(8,prefix-b*8));
                    int mask=remaining==0?0:((255 << (8-remaining))&255);
                    broadcast[b]=(byte)((ip[b]&mask)|(~mask&255));
                }
                socket.send(new DatagramPacket(data,data.length,InetAddress.getByAddress(broadcast),Util.DISCOVERY_PORT));
            }
            // Ignore unrelated UDP packets rather than failing discovery on the first one.
            long deadline=android.os.SystemClock.elapsedRealtime()+2500L;
            while(android.os.SystemClock.elapsedRealtime()<deadline){
                long left=deadline-android.os.SystemClock.elapsedRealtime();
                socket.setSoTimeout((int)Math.max(1L,left));
                byte[] response=new byte[512];
                DatagramPacket packet=new DatagramPacket(response,response.length);
                try{socket.receive(packet);}
                catch(SocketTimeoutException timeout){break;}
                String answer=new String(packet.getData(),packet.getOffset(),packet.getLength(),StandardCharsets.UTF_8);
                if(answer.startsWith("XARKON_LINK_1|")&&answer.endsWith("|"+Util.PORT)&&
                   localAddress(packet.getAddress()))return packet.getAddress().getHostAddress();
            }
        }catch(Exception ignored){}
        return "";
    }
    /** Never send a session key to a public gateway or a spoofed public discovery IP. */
    private static boolean localAddress(InetAddress address){
        return address instanceof Inet4Address&&
            (address.isSiteLocalAddress()||address.isLinkLocalAddress());
    }
    private static String resolveApAddress(LinkProperties links){
        if(links==null)return "";
        if(android.os.Build.VERSION.SDK_INT>=30&&
            localAddress(links.getDhcpServerAddress()))
            return links.getDhcpServerAddress().getHostAddress();
        for(RouteInfo r:links.getRoutes()){
            InetAddress gateway=r.getGateway();
            if(localAddress(gateway))return gateway.getHostAddress();
        }
        // When AP does not advertise a gateway, no safe address can be deduced.
        return "";
    }
}
