package dev.xarkon.link;
import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
public final class BackgroundUpdateJob extends JobService {
    private volatile Thread worker;
    @Override public boolean onStartJob(JobParameters params){
        SharedPreferences prefs=getSharedPreferences("settings",0);
        if(!prefs.getBoolean(OtaUpdateSchedule.PREF_BACKGROUND,false))return false;
        worker=new Thread(()->{
            prefs.edit().putLong("ota_last_background_attempt",System.currentTimeMillis())
                .putString("ota_background_result","Проверка соединения...").apply();
            try{
                UpdateManager.Release release=UpdateManager.fetchLatest(getApplicationContext());
                long installed=getPackageManager().getPackageInfo(getPackageName(),0).getLongVersionCode();
                prefs.edit().putLong("ota_last_background_check",System.currentTimeMillis())
                    .putString("ota_background_result",release.versionCode>installed
                        ?"Доступна версия "+release.versionName:"Установлена актуальная версия")
                    .apply();
                if(release.versionCode>installed){
                    prefs.edit().putLong(OtaUpdateSchedule.PREF_AVAILABLE_CODE,release.versionCode)
                        .putString(OtaUpdateSchedule.PREF_AVAILABLE_NAME,release.versionName).apply();
                    long previous=prefs.getLong("ota_last_notified_code",0L);
                    if(release.versionCode>previous){
                        if(notifyUpdate(release.versionName))
                            prefs.edit().putLong("ota_last_notified_code",release.versionCode).apply();
                        else prefs.edit().putString("ota_background_result",
                            "Обновление найдено, но уведомления отключены").apply();
                    }
                }
            }catch(Exception error){
                String message=error.getMessage();
                if(message==null||message.trim().isEmpty())message="Ошибка соединения";
                prefs.edit().putString("ota_background_result",message).apply();
            }finally{jobFinished(params,false);}
        },"qr-link-background-ota");
        worker.start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params){
        Thread active=worker;
        if(active!=null)active.interrupt();
        return true;
    }
    private boolean notifyUpdate(String version){
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            !=PackageManager.PERMISSION_GRANTED)return false;
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(manager==null)return false;
        String channel="qr_link_updates";
        if(Build.VERSION.SDK_INT>=26)
            manager.createNotificationChannel(new NotificationChannel(channel,"Обновления QR LINK",
                NotificationManager.IMPORTANCE_DEFAULT));
        Intent open=new Intent(this,MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.putExtra("ota_open_from_notification",true);
        PendingIntent pending=PendingIntent.getActivity(this,1501,open,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification.Builder n=Build.VERSION.SDK_INT>=26
            ?new android.app.Notification.Builder(this,channel)
            :new android.app.Notification.Builder(this);
        n.setSmallIcon(R.drawable.ic_link)
            .setContentTitle("Доступно обновление QR LINK")
            .setContentText("Версия "+version+" готова к установке")
            .setAutoCancel(true).setContentIntent(pending).setOnlyAlertOnce(true);
        manager.notify(1501,n.build());
        return true;
    }
}
