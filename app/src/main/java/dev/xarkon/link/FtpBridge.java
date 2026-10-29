package dev.xarkon.link;

import android.content.*;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** ShareMe's documented 'Connect to computer' FTP mode: download-only bridge. */
public final class FtpBridge implements AutoCloseable {
    public static final class Entry {
        public final String name; public final boolean directory;public final long size;
        Entry(String n,boolean d,long s){name=n;directory=d;size=s;}
        public String toString(){return (directory?"[ПАПКА] ":"[ФАЙЛ] ")+name+(size>=0?" · "+Util.readable(size):"");}
    }
    private final Socket control;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final Context context;
    private final String host;
    private int port;
    public FtpBridge(Context ctx,String ftp)throws Exception{
        this.context=ctx.getApplicationContext();
        URI uri=new URI(ftp.trim());if(!"ftp".equalsIgnoreCase(uri.getScheme()))throw new IOException("Введите ftp://IP:порт/");
        host=uri.getHost();if(host==null)throw new IOException("Нет FTP-адреса");
        port=uri.getPort()>0?uri.getPort():21;
        control=new Socket();control.connect(new InetSocketAddress(host,port),5000);control.setSoTimeout(20000);
        reader=new BufferedReader(new InputStreamReader(control.getInputStream(),StandardCharsets.UTF_8));
        writer=new BufferedWriter(new OutputStreamWriter(control.getOutputStream(),StandardCharsets.UTF_8));
        expect(220);
        String user="anonymous",pass="anonymous@";
        if(uri.getUserInfo()!=null){String[]parts=uri.getUserInfo().split(":",2);user=parts[0];if(parts.length>1)pass=parts[1];}
        int code=send("USER "+user);
        if(code==331){code=send("PASS "+pass);}
        if(code!=230&&code!=202)throw new IOException("FTP: доступ не разрешён ("+code+")");
        expectResult(send("TYPE I"));
    }
    private String last;
    private int response()throws IOException{
        String first=reader.readLine();if(first==null||first.length()<3)throw new EOFException("FTP отключился");
        int code=Integer.parseInt(first.substring(0,3));String line=first;
        if(first.length()>3&&first.charAt(3)=='-'){
            while((line=reader.readLine())!=null){if(line.startsWith(first.substring(0,3)+" "))break;}
            if(line==null)throw new EOFException();
        }
        last=line;return code;
    }
    private int send(String cmd)throws IOException{if(cmd.contains("\n")||cmd.contains("\r"))throw new IOException("Неверный FTP-путь");writer.write(cmd+"\r\n");writer.flush();return response();}
    private void expect(int expect)throws IOException{int n=response();if(n!=expect)throw new IOException("FTP "+n+": "+last);}
    private void expectResult(int code)throws IOException{if(code<200||code>=300)throw new IOException("FTP "+code+": "+last);}
    private Socket passive()throws IOException{
        int code=send("EPSV");String ip=host;int dataport;
        if(code==229){
            int a=last.indexOf('('),b=last.indexOf(')',a+1);
            if(a<0||b<=a)throw new IOException("Некорректный EPSV");
            String body=last.substring(a+1,b),delimiter=body.substring(0,1);
            String[]v=body.split(java.util.regex.Pattern.quote(delimiter));
            dataport=Integer.parseInt(v[v.length-1]);
        }else{
            code=send("PASV");if(code!=227)throw new IOException("FTP не поддерживает пассивный режим");
            int a=last.indexOf('('),b=last.indexOf(')',a+1);
            if(a<0||b<=a)throw new IOException("Некорректный PASV");
            String[]nums=last.substring(a+1,b).split(",");
            if(nums.length!=6)throw new IOException("Некорректный PASV");
            dataport=Integer.parseInt(nums[4])*256+Integer.parseInt(nums[5]);
            // Use control-connection address instead of the untrusted PASV IP.
        }
        if(dataport<1||dataport>65535)throw new IOException("Неверный порт данных");
        Socket s=new Socket();s.connect(new InetSocketAddress(ip,dataport),5000);s.setSoTimeout(30000);return s;
    }
    private void navigate(String path)throws IOException{
        int res=send("CWD /");expectResult(res);
        if(path==null||path.isEmpty()||path.equals("/"))return;
        for(String segment:path.replace('\\','/').split("/")){
            if(segment.isEmpty())continue;
            if(segment.equals(".")||segment.equals(".."))throw new IOException("Некорректный путь");
            expectResult(send("CWD "+segment));
        }
    }
    public List<Entry> list(String dir)throws Exception{
        navigate(dir);List<Entry> result=new ArrayList<>();
        try(Socket data=passive()){
            int code=send("LIST");if(code!=125&&code!=150)throw new IOException("FTP LIST: "+code);
            try(BufferedReader r=new BufferedReader(new InputStreamReader(data.getInputStream(),StandardCharsets.UTF_8))){
                String line;
                while((line=r.readLine())!=null){
                    if(line.startsWith("total "))continue;
                    // Unix LIST: permissions links owner group size month day time name
                    String[]fields=line.trim().split("\\s+",9);
                    if(fields.length>=9){
                        boolean isDir=fields[0].charAt(0)=='d';long size=-1;
                        try{size=Long.parseLong(fields[4]);}catch(Exception ignored){}
                        String name=fields[8];if(name.equals(".")||name.equals(".."))continue;
                        result.add(new Entry(name,isDir,size));
                    }else{
                        // Some ShareMe servers support minimal LIST without Unix attributes.
                        if(!line.trim().isEmpty())result.add(new Entry(line.trim(),false,-1));
                    }
                }
            }
        }
        expectResult(response());
        result.sort((a,b)->a.directory==b.directory?a.name.compareToIgnoreCase(b.name):(a.directory?-1:1));
        return result;
    }
    public Uri download(String dir,String file,TransferEngine.Events events)throws Exception{
        navigate(dir);
        if(file.contains("/")||file.contains("\\")||file.contains("\r")||file.contains("\n"))throw new IOException("Неверное имя");
        String name=Util.safeName(file);
        ContentValues cv=new ContentValues();cv.put(MediaStore.Downloads.DISPLAY_NAME,name);
        cv.put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream");
        cv.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/QR LINK/ShareMe/");
        cv.put(MediaStore.Downloads.IS_PENDING,1);
        Uri dst=context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cv);
        if(dst==null)throw new IOException("Не удалось создать файл");
        boolean success=false;
        long start=Util.now(),total=0;
        try(Socket data=passive()){
            int code=send("RETR "+file);
            if(code!=125&&code!=150)throw new IOException("FTP RETR: "+code+" "+last);
            try(InputStream in=data.getInputStream();OutputStream out=context.getContentResolver().openOutputStream(dst,"w")){
                if(out==null)throw new IOException("Нет доступа к загрузкам");
                byte[]b=new byte[256*1024];int n;long lastUpdate=0;
                while((n=in.read(b))!=-1){out.write(b,0,n);total+=n;
                    if(Util.now()-lastUpdate>500){lastUpdate=Util.now();events.progress("ShareMe FTP: "+name,total,-1,total/Math.max(.001,(Util.now()-start)/1000.0));}
                }
                out.flush();
            }
            expectResult(response());success=true;
        }finally{
            if(success){ContentValues publish=new ContentValues();publish.put(MediaStore.Downloads.IS_PENDING,0);
                context.getContentResolver().update(dst,publish,null,null);events.note("FTP получено: "+name+" ("+Util.readable(total)+")");}
            else context.getContentResolver().delete(dst,null,null);
        }
        return dst;
    }
    @Override public void close(){try{send("QUIT");}catch(Exception ignored){}try{control.close();}catch(Exception ignored){}}
}
