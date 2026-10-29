package dev.xarkon.link;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
public final class OtaUpdateSchedule {
    private OtaUpdateSchedule(){}
    public static final String PREF_ON_LAUNCH="ota_check_on_launch";
    public static final String PREF_BACKGROUND="ota_background_check";
    public static final String PREF_FREQUENCY="ota_background_hours";
    public static final String PREF_AVAILABLE_CODE="ota_available_version_code";
    public static final String PREF_AVAILABLE_NAME="ota_available_version_name";
    public static final long[] INTERVAL_HOURS={1,3,6,12,24,72,168};
    public static final String[] INTERVAL_NAMES={
        "Каждый час","Каждые 3 часа","Каждые 6 часов",
        "Каждые 12 часов","Каждые 24 часа","Каждые 3 дня","Каждые 7 дней"
    };
    private static final int JOB_ID=0x514C1301;
    private static final int INITIAL_JOB_ID=0x514C1302;
    public static void configure(Context context){
        Context app=context.getApplicationContext();
        SharedPreferences p=app.getSharedPreferences("settings",0);
        JobScheduler scheduler=(JobScheduler)app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(scheduler==null)return;
        if(!p.getBoolean(PREF_BACKGROUND,false)){
            scheduler.cancel(JOB_ID);scheduler.cancel(INITIAL_JOB_ID);return;
        }
        long hours=p.getLong(PREF_FREQUENCY,24L);
        boolean valid=false;
        for(long allowed:INTERVAL_HOURS)if(hours==allowed)valid=true;
        if(!valid)hours=24L;
        long interval=hours*3600000L;
        JobInfo previous=scheduler.getPendingJob(JOB_ID);
        if(previous!=null&&previous.getIntervalMillis()==interval)return;
        JobInfo info=new JobInfo.Builder(JOB_ID,new ComponentName(app,BackgroundUpdateJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(interval,Math.min(interval/4L,30L*60L*1000L))
            .build();
        if(scheduler.schedule(info)==JobScheduler.RESULT_SUCCESS){
            // A newly enabled schedule should not wait an entire day/week before
            // its first network check. Let Android dispatch this one-time job soon.
            JobInfo once=new JobInfo.Builder(INITIAL_JOB_ID,
                new ComponentName(app,BackgroundUpdateJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(15000L)
                .setOverrideDeadline(4L*60L*1000L)
                .build();
            scheduler.schedule(once);
        }else{
            p.edit().putString("ota_background_result",
                "Не удалось запланировать фоновую проверку").apply();
        }
    }
}
