package dev.xarkon.link;

import android.app.Activity;
import android.content.*;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;

/** Secure user-initiated update checker for an HTTPS manifest and a single APK. */
public final class UpdateManager {
    private UpdateManager(){}
    /** This is the only official OTA channel. No editable source or alternate endpoint. */
    public static final String MANIFEST_URL="https://github.com/ANTIFERMENT/QR-LINK/releases/latest/download/update.json";
    public static final String TRUSTED_CERT_SHA256="2651D90F5C57F2D4A121E2D8B0899680343DC9E87E515ED5E100FC3D62C6D626";
    private static final String RELEASE_PREFIX="/ANTIFERMENT/QR-LINK/releases/download/";
    public static final String APK_FILE="qr_link_update.apk";
    public interface Callback {
        void status(String message);
        void release(Release info, boolean newer);
        void downloaded(File file);
        void error(String message);
    }
    public static final class Release {
        public final long versionCode;
        public final String versionName,apkUrl,sha256,notes,packageName;
        public final long sizeBytes;
        Release(JSONObject j)throws Exception {
            if(j.has("schema") && j.getInt("schema")!=1)throw new IOException("Неподдерживаемый формат OTA");
            packageName=j.optString("packageName","dev.xarkon.link");
            if(!"dev.xarkon.link".equals(packageName))throw new IOException("Ошибка идентификатора приложения");
            versionCode=j.getLong("versionCode");
            versionName=j.optString("versionName","Новая версия");
            apkUrl=j.getString("apkUrl");
            sha256=j.getString("sha256").toLowerCase(Locale.US);
            notes=j.optString("notes","");
            sizeBytes=j.optLong("sizeBytes",-1L);
            if(versionCode<1||!sha256.matches("[0-9a-f]{64}"))throw new IOException("Недействительные метаданные OTA");
            checkHttps(apkUrl);
            URL apk=new URL(apkUrl);
            if(!"github.com".equalsIgnoreCase(apk.getHost())
                || !apk.getPath().startsWith(RELEASE_PREFIX)
                || !apk.getPath().endsWith("/QR-LINK.apk"))
                throw new IOException("APK размещён вне официального канала");
        }
    }
    private static void checkHttps(String url)throws IOException{
        URL u=new URL(url);
        if(!"https".equalsIgnoreCase(u.getProtocol())||u.getHost()==null||u.getHost().isEmpty())
            throw new IOException("Нужна прямая HTTPS-ссылка");
    }
    private static HttpsURLConnection connection(String url)throws IOException{
        checkHttps(url);
        URLConnection c=new URL(url).openConnection();
        if(!(c instanceof HttpsURLConnection))throw new IOException("Не HTTPS");
        HttpsURLConnection h=(HttpsURLConnection)c;
        h.setConnectTimeout(12000);h.setReadTimeout(25000);
        h.setRequestProperty("User-Agent","QR-LINK-Updater/1.0");
        return h;
    }
    public static void check(Activity activity,String manifestUrl,Callback cb){
        new Thread(()->{
            try{
                if(!MANIFEST_URL.equals(manifestUrl))throw new IOException("Нельзя сменить официальный OTA-источник");
                HttpsURLConnection c=connection(MANIFEST_URL);
                JSONObject json;
                try{
                    if(c.getResponseCode()!=200)throw new IOException("Сервер обновлений: HTTP "+c.getResponseCode());
                    ByteArrayOutputStream memory=new ByteArrayOutputStream();
                    try(InputStream in=c.getInputStream()){
                        byte[] b=new byte[8192];int n;
                        while((n=in.read(b))!=-1){if(memory.size()+n>64*1024)throw new IOException("Слишком большой манифест");memory.write(b,0,n);}
                    }
                    json=new JSONObject(memory.toString("UTF-8"));
                }finally{c.disconnect();}
                Release info=new Release(json);
                long installed=activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).getLongVersionCode();
                activity.runOnUiThread(()->cb.release(info,info.versionCode>installed));
            }catch(Exception e){activity.runOnUiThread(()->cb.error("Проверка OTA: "+e.getMessage()));}
        },"qr-link-update-check").start();
    }
    public static void download(Activity activity,Release r,Callback cb){
        new Thread(()->{
            File temp=new File(activity.getCacheDir(),APK_FILE+".part");
            File apk=new File(activity.getCacheDir(),APK_FILE);
            try{
                checkHttps(r.apkUrl);
                HttpsURLConnection c=connection(r.apkUrl);
                long read=0;long size;
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                try{
                    if(c.getResponseCode()!=200)throw new IOException("Скачивание APK: HTTP "+c.getResponseCode());
                    size=c.getContentLengthLong();
                    if(size>512L*1024*1024)throw new IOException("APK слишком большой");
                    if(r.sizeBytes>0 && size>0 && size!=r.sizeBytes)throw new IOException("Размер APK не соответствует манифесту");
                    try(InputStream in=new BufferedInputStream(c.getInputStream(),256*1024);
                        FileOutputStream out=new FileOutputStream(temp)){
                        byte[] buf=new byte[256*1024];int n;long lastReport=0;
                        while((n=in.read(buf))!=-1){
                            read+=n;if(read>512L*1024*1024)throw new IOException("Превышен предел 512 МиБ");
                            out.write(buf,0,n);digest.update(buf,0,n);
                            long now=System.currentTimeMillis();
                            if(now-lastReport>400){lastReport=now;
                                String msg="Загружено "+Util.readable(read)+(size>0?" / "+Util.readable(size):"");
                                activity.runOnUiThread(()->cb.status(msg));
                            }
                        }
                        out.getFD().sync();
                    }
                }finally{c.disconnect();}
                if(r.sizeBytes>0 && read!=r.sizeBytes)throw new IOException("Скачанный APK имеет неверный размер");
                StringBuilder hash=new StringBuilder(64);
                for(byte b:digest.digest())hash.append(String.format(Locale.US,"%02x",b&255));
                if(!hash.toString().equals(r.sha256))throw new IOException("SHA-256 не совпадает, APK отклонён");
                if(apk.exists()&&!apk.delete())throw new IOException("Не удалось заменить старый APK");
                if(!temp.renameTo(apk))throw new IOException("Не удалось сохранить APK");
                try{verifyApk(activity,apk,r.versionCode);}catch(Exception invalid){apk.delete();throw invalid;}
                activity.runOnUiThread(()->cb.downloaded(apk));
            }catch(Exception e){temp.delete();activity.runOnUiThread(()->cb.error("OTA: "+e.getMessage()));}
        },"qr-link-update-download").start();
    }
    /** An archive is accepted only if Android will recognize it as THIS installed app. */
    public static void verifyApk(Context c,File file,long expectedCode)throws Exception{
        PackageManager pm=c.getPackageManager();
        PackageInfo archive=pm.getPackageArchiveInfo(file.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES);
        if(archive==null)throw new IOException("Не удалось прочитать пакет APK");
        if(!c.getPackageName().equals(archive.packageName))throw new IOException("APK принадлежит другому приложению");
        if(archive.getLongVersionCode()!=expectedCode)throw new IOException("Версия APK не соответствует манифесту");
        long current=pm.getPackageInfo(c.getPackageName(),0).getLongVersionCode();
        if(archive.getLongVersionCode()<=current)throw new IOException("Версия уже установлена или устарела");
        PackageInfo installed=pm.getPackageInfo(c.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
        if(installed.signingInfo==null||archive.signingInfo==null)throw new IOException("Не удалось проверить подпись APK");
        Signature[] oldSigners=installed.signingInfo.getApkContentsSigners();
        Signature[] newSigners=archive.signingInfo.getApkContentsSigners();
        if(oldSigners==null||newSigners==null||oldSigners.length!=1||newSigners.length!=1)
            throw new IOException("Неподдерживаемое количество сертификатов подписи");
        if(!TRUSTED_CERT_SHA256.equals(sha256OfSignature(oldSigners[0]))
            || !TRUSTED_CERT_SHA256.equals(sha256OfSignature(newSigners[0])))
            throw new IOException("Неверная подпись APK. Установите официальную версию QR LINK");
        if(!Arrays.equals(oldSigners,newSigners))
            throw new IOException("Подписи установленного приложения и обновления различаются");
    }
    private static String sha256OfSignature(Signature signer)throws Exception{
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(signer.toByteArray());
        StringBuilder hex=new StringBuilder(64);
        for(byte b:digest)hex.append(String.format(Locale.US,"%02X",b&255));
        return hex.toString();
    }
    public static void install(Activity a){
        try{
            File apk=new File(a.getCacheDir(),APK_FILE);
            if(!apk.isFile())throw new IOException("Сначала загрузите обновление");
            PackageManager pm=a.getPackageManager();
            if(Build.VERSION.SDK_INT>=26&&!pm.canRequestPackageInstalls()){
                Intent settings=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:"+a.getPackageName()));
                a.startActivity(settings);
                android.widget.Toast.makeText(a,"Разрешите установку и вернитесь в QR LINK",android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            // Both APK identity and signing certificate are checked again before installation.
            PackageInfo archive=pm.getPackageArchiveInfo(apk.getAbsolutePath(),0);
            if(archive==null)throw new IOException("Не удалось открыть APK");
            verifyApk(a,apk,archive.getLongVersionCode());
            Uri content=Uri.parse("content://"+a.getPackageName()+".updates/apk");
            Intent view=new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(content,"application/vnd.android.package-archive");
            view.setClipData(ClipData.newRawUri("QR LINK",content));
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(view);
        }catch(Exception e){
            new android.app.AlertDialog.Builder(a).setTitle("Не удалось установить").setMessage(e.getMessage())
                .setPositiveButton("Закрыть",null).show();
        }
    }
}
