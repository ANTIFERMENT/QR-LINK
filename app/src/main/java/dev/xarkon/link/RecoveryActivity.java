package dev.xarkon.link;

import android.app.Activity;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Build;
import android.view.WindowInsets;
import android.view.*;
import android.widget.*;

/** Small independent launcher. It stays usable even if MainActivity's rich UI fails. */
public final class RecoveryActivity extends Activity {
    private final int bg=0xff000000, panel=0xff15191d, text=0xffedf6f7, muted=0xffa8b8c0,accent=0xff58dfce;
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable shape(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(17));return d;}
    private TextView label(String s,int size,int color,boolean bold){
        TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;
    }
    private void add(LinearLayout parent,View v,int bottom){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(bottom);parent.addView(v,p);
    }
    private void button(LinearLayout parent,String name,boolean primary,Runnable click){
        TextView v=label(name,15,primary?0xff091619:text,true);v.setGravity(Gravity.CENTER);
        v.setPadding(dp(14),dp(17),dp(14),dp(17));v.setBackground(shape(primary?accent:panel));
        add(parent,v,11);v.setOnClickListener(x->click.run());
    }
    @Override public void onCreate(Bundle state){super.onCreate(state);
        getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg);
        CrashReport report=(CrashReport)getApplication();
        String crash=report.read();
        if(crash.isEmpty()&&!getIntent().getBooleanExtra("show",false)){open(false);return;}
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(bg);
        if(Build.VERSION.SDK_INT>=30){
            getWindow().setDecorFitsSystemWindows(false);
            scroll.setOnApplyWindowInsetsListener((view,insets)->{
                android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.statusBars()
                    |WindowInsets.Type.navigationBars()|WindowInsets.Type.displayCutout());
                view.setPadding(safe.left,safe.top,safe.right,safe.bottom);
                return insets;
            });
        }else scroll.setFitsSystemWindows(true);
        LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(21),dp(25),dp(21),dp(25));scroll.addView(c);setContentView(scroll);
        scroll.post(scroll::requestApplyInsets);
        add(c,label("QR LINK",28,accent,true),8);
        add(c,label(crash.isEmpty()?"Диагностика приложения":"Обнаружен сбой предыдущего запуска",17,text,true),12);
        add(c,label(crash.isEmpty()?"Аварийных отчётов пока нет.":
             "Причина ошибки сохранена на устройстве. Можно скопировать отчёт и запустить QR LINK в безопасном режиме.",14,muted,false),23);
        button(c,"Открыть QR LINK",true,()->open(false));
        button(c,"Безопасный запуск (тема без анимаций)",false,()->open(true));
        button(c,"Скопировать отчёт",false,()->{
            if(crash.isEmpty()){Toast.makeText(this,"Отчёта пока нет",Toast.LENGTH_SHORT).show();return;}
            ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("QR LINK crash",crash));
            Toast.makeText(this,"Отчёт скопирован",Toast.LENGTH_SHORT).show();
        });
        button(c,"Поделиться отчётом",false,()->{
            if(crash.isEmpty()){Toast.makeText(this,"Отчёта пока нет",Toast.LENGTH_SHORT).show();return;}
            Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,crash);
            startActivity(Intent.createChooser(i,"Отправить отчёт QR LINK"));
        });
        button(c,"Очистить аварийный отчёт",false,()->{report.clear();recreate();});
        if(!crash.isEmpty()){
            add(c,label("ДЕТАЛИ ОШИБКИ",11,accent,true),9);
            TextView details=label(crash,11,text,false);
            details.setTypeface(Typeface.MONOSPACE);details.setTextIsSelectable(true);
            details.setPadding(dp(13),dp(14),dp(13),dp(14));details.setBackground(shape(panel));
            add(c,details,16);
        }
    }
    private void open(boolean safe){
        if(safe)getSharedPreferences("settings",0).edit().putString("theme_mode","dark")
            .putBoolean("motion",false).putBoolean("haptics",false).apply();
        startActivity(new Intent(this,MainActivity.class));finish();
    }
}
