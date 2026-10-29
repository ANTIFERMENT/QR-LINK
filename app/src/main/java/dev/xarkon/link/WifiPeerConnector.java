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
    public WifiPeerConnector(Context c){manager=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);}
    public synchronized void stop(){
        if(callback!=null){try{manager.unregisterNetworkCallback(callback);}catch(Exception ignored){}callback=null;}
        try{manager.bindProcessToNetwork(null);}catch(Exception ignored){}
    }
    public synchronized void connect(Pairing.Info info,Listener listener){
        stop();
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
                @Override public void onAvailable(Network n){
                    manager.bindProcessToNetwork(n);
                    deliver(n);
                }
                @Override public void onLinkPropertiesChanged(Network n,LinkProperties lp){deliver(n);}
                private void deliver(Network n){
                    synchronized(delivered){if(delivered[0])return;}
                    LinkProperties lp=manager.getLinkProperties(n);
                    if(lp==null)return;
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
                            if(!found.isEmpty()){
                                synchronized(delivered){if(delivered[0])return;delivered[0]=true;}
                                listener.connected(found);
                            }else listener.failure("Сеть подключена, но Android не сообщил адрес приёмника. Повторите подключение.");
                        },"qr-link-find-peer").start();
                        return;
                    }
                    synchronized(delivered){if(delivered[0])return;delivered[0]=true;}
                    listener.connected(host);
                }
                @Override public void onUnavailable(){listener.failure("Android не подключился к точке доступа. Подтвердите запрос Wi-Fi и повторите сканирование.");}
                @Override public void onLost(Network n){
                    manager.bindProcessToNetwork(null);
                    listener.failure("Соединение с точкой доступа потеряно");
                }
            };
            manager.requestNetwork(request,callback,45000);
        }catch(Exception e){listener.failure("Подключение Wi-Fi: "+e.getMessage());}
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
            byte[] response=new byte[512];DatagramPacket packet=new DatagramPacket(response,response.length);
            socket.receive(packet);
            String answer=new String(packet.getData(),packet.getOffset(),packet.getLength(),StandardCharsets.UTF_8);
            if(answer.startsWith("XARKON_LINK_1|"))return packet.getAddress().getHostAddress();
        }catch(Exception ignored){}
        return "";
    }
    private static String resolveApAddress(LinkProperties links){
        if(links==null)return "";
        if(android.os.Build.VERSION.SDK_INT>=30&&links.getDhcpServerAddress()!=null)
            return links.getDhcpServerAddress().getHostAddress();
        for(RouteInfo r:links.getRoutes()){
            InetAddress gateway=r.getGateway();
            if(gateway instanceof Inet4Address&&!gateway.isAnyLocalAddress())return gateway.getHostAddress();
        }
        // When AP does not advertise a gateway, no safe address can be deduced.
        return "";
    }
}
