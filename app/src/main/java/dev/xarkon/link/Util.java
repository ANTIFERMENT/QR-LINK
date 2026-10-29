package dev.xarkon.link;

import android.content.Context;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

public final class Util {
    public static final int PORT=45588, DISCOVERY_PORT=45589;
    public static final int CHUNK=8*1024*1024;
    private Util() {}
    public static String safeName(String s) {
        if (s==null) return "file";
        s=s.replaceAll("[\\\\/\\p{Cntrl}:*?\"<>|]", "_").trim();
        while (s.equals(".")||s.equals("..")||s.startsWith(".")) s="_"+s;
        if(s.isEmpty())s="file";
        return s.length()>150 ? s.substring(0,150) : s;
    }
    public static String safePath(String s) {
        if(s==null||s.isEmpty())return "";
        StringBuilder b=new StringBuilder();
        for(String p:s.replace('\\','/').split("/")) {
            if(p.isEmpty() || p.equals(".") || p.equals(".."))continue;
            if(b.length()>0)b.append('/');
            b.append(safeName(p));
            if(b.length()>400)break;
        }
        return b.toString();
    }
    public static String name(Context c,Uri u){
        try(Cursor cur=c.getContentResolver().query(u,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(cur!=null&&cur.moveToFirst())return safeName(cur.getString(0));
        }catch(Exception ignored){}
        return safeName(u.getLastPathSegment());
    }
    public static long size(Context c,Uri u){
        try(Cursor cur=c.getContentResolver().query(u,new String[]{OpenableColumns.SIZE},null,null,null)){
            if(cur!=null&&cur.moveToFirst()&&!cur.isNull(0))return cur.getLong(0);
        }catch(Exception ignored){}
        return -1;
    }
    public static String mime(Context c,Uri u){String t=c.getContentResolver().getType(u);return t==null?"application/octet-stream":t;}
    public static String ip(){
        String fallback="127.0.0.1";
        try{
            Enumeration<NetworkInterface> en=NetworkInterface.getNetworkInterfaces();
            while(en.hasMoreElements()){
                NetworkInterface ni=en.nextElement();
                if(!ni.isUp()||ni.isLoopback()||ni.isVirtual())continue;
                Enumeration<InetAddress> adds=ni.getInetAddresses();
                while(adds.hasMoreElements()){
                    InetAddress a=adds.nextElement();
                    if(a instanceof Inet4Address&&!a.isLoopbackAddress()&&a.isSiteLocalAddress()){
                        if(ni.getName().startsWith("wlan")||ni.getName().startsWith("ap")||ni.getName().startsWith("swlan"))return a.getHostAddress();
                        fallback=a.getHostAddress();
                    }
                }
            }
        }catch(Exception ignored){}
        return fallback;
    }
    public static String readable(long n){
        if(n<0)return "неизвестно";
        if(n<1024)return n+" Б";
        double a=n;String[]units={"Б","КБ","МБ","ГБ","ТБ"};int i=0;
        while(a>=1024&&i<4){a/=1024;i++;}
        return String.format(java.util.Locale.US,"%.1f %s",a,units[i]);
    }
    public static String readText(InputStream in,int cap)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] t=new byte[4096];int n;
        while((n=in.read(t))!=-1){if(b.size()+n>cap)throw new IOException("Слишком большой ответ");b.write(t,0,n);}
        return b.toString(StandardCharsets.UTF_8.name());
    }
    public static String http(String host,int port,String pin,String endpoint,String method,JSONObject body)throws Exception{
        URL u=new URL("http",host,port,endpoint);
        HttpURLConnection conn=(HttpURLConnection)u.openConnection();
        conn.setRequestMethod(method);conn.setConnectTimeout(4000);conn.setReadTimeout(12000);
        conn.setRequestProperty("X-Xarkon-Key",pin);
        if(body!=null){
            byte[] b=body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setDoOutput(true);conn.setFixedLengthStreamingMode(b.length);
            conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
            try(OutputStream out=conn.getOutputStream()){out.write(b);}
        }
        int status=conn.getResponseCode();
        try(InputStream in=status<400?conn.getInputStream():conn.getErrorStream()){
            String response=in==null?"":readText(in,524288);
            if(status>=400)throw new IOException("HTTP "+status+": "+response);
            return response;
        }finally{conn.disconnect();}
    }
    public static String id(){return UUID.randomUUID().toString();}
    public static long now(){return SystemClock.elapsedRealtime();}
}
