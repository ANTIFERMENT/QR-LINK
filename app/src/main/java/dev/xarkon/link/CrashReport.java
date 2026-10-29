package dev.xarkon.link;

import android.app.Application;
import android.os.Build;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/** Persistent, local-only crash diagnostic. Does not read personal files or send telemetry. */
public final class CrashReport extends Application {
    private static final String FILE="qr_link_crash_report.txt";
    private Thread.UncaughtExceptionHandler previous;
    private volatile boolean recording;

    @Override public void onCreate(){
        super.onCreate();
        previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, problem)->{
            try{if(!recording){recording=true;save("UNCAUGHT ["+thread.getName()+"]",problem);}}
            catch(Throwable ignored){}
            finally{
                if(previous!=null)previous.uncaughtException(thread,problem);
                else{android.os.Process.killProcess(android.os.Process.myPid());System.exit(10);}
            }
        });
    }
    public synchronized void save(String stage,Throwable problem){
        try{
            StringWriter sw=new StringWriter();
            PrintWriter pw=new PrintWriter(sw);
            problem.printStackTrace(pw);pw.flush();
            String appVersion="неизвестна";
            try{appVersion=getPackageManager().getPackageInfo(getPackageName(),0).versionName;}
            catch(Exception ignored){}
            String build="QR LINK "+appVersion+" / Android "+Build.VERSION.RELEASE+" (API "+Build.VERSION.SDK_INT+")"
                +" / "+Build.MANUFACTURER+" "+Build.MODEL;
            String date=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.US).format(new Date());
            String report=build+"\nTime: "+date+"\nStage: "+stage+"\n\n"+sw;
            try(FileOutputStream out=openFileOutput(FILE,MODE_PRIVATE)){
                out.write(report.getBytes(StandardCharsets.UTF_8));out.flush();out.getFD().sync();
            }
        }catch(Throwable ignored){}
    }
    public String read(){
        try(FileInputStream in=openFileInput(FILE);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[]buf=new byte[4096];int n;
            while((n=in.read(buf))>0&&out.size()<128*1024)out.write(buf,0,n);
            return out.toString("UTF-8");
        }catch(Exception ignored){return "";}
    }
    public void clear(){deleteFile(FILE);}
}
