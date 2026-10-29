package dev.xarkon.link;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.content.Context;
import android.view.animation.LinearInterpolator;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.Locale;

/** QR LINK AMOLED-styled OTA sheet. Never uses the system's grey AlertDialog. */
public final class OtaProgressDialog {
    private final Activity activity;
    private final Dialog dialog;
    private final TextView heading, label, details, bytes, percent, primary, secondary;
    private final View mark;
    private final ProgressBar bar;
    private final int accent, foreground, muted, panel;
    private final boolean animate;
    private ValueAnimator numberAnim, spin;
    private long lastBytes, lastTotal;
    private boolean shown;

    /** Geometrically centered stroke; rotates without font metrics or glyph wobble. */
    private static final class CenteredRing extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds=new RectF();
        private final int color;
        CenteredRing(Context context,int tint){super(context);color=tint;}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            float density=getResources().getDisplayMetrics().density;
            float stroke=3.1f*density;
            float cx=getWidth()/2f,cy=getHeight()/2f;
            float radius=Math.min(getWidth(),getHeight())/2f-stroke*1.5f;
            if(radius<=0)return;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor((color&0x00ffffff)|0x38000000);
            canvas.drawCircle(cx,cy,radius,paint);
            paint.setColor(color);
            bounds.set(cx-radius,cy-radius,cx+radius,cy+radius);
            canvas.drawArc(bounds,-90f,270f,false,paint);
        }
    }
    private int dp(float x){ return (int)(activity.getResources().getDisplayMetrics().density*x+.5f); }
    private GradientDrawable shape(int color,int border,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));
        if(border!=0)d.setStroke(dp(1),border);
        return d;
    }
    private TextView text(String s,int size,int color,boolean bold){
        TextView v=new TextView(activity);v.setText(s);v.setTextColor(color);v.setTextSize(size);
        v.setIncludeFontPadding(false);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return v;
    }
    private static String size(long value){
        if(value<0)return "—";
        if(value<1024)return value+" Б";
        double v=value/1024d;if(v<1024)return String.format(Locale.forLanguageTag("ru"),"%.1f КБ",v);
        v/=1024;if(v<1024)return String.format(Locale.forLanguageTag("ru"),"%.1f МБ",v);
        return String.format(Locale.forLanguageTag("ru"),"%.2f ГБ",v/1024d);
    }
    public OtaProgressDialog(Activity a){
        activity=a;
        android.content.SharedPreferences p=a.getSharedPreferences("settings",0);
        String theme=p.getString("theme_mode","dark");
        boolean light="light".equals(theme),amoled="amoled".equals(theme);
        accent=p.getInt("accent",0xff55dfcb);
        panel=light?0xffffffff:amoled?0xff101013:0xff14212d;
        foreground=light?0xff182837:0xfff5f7f9;
        muted=light?0xff617689:0xffa0adb8;
        int stroke=light?0xffdce4ed:amoled?0xff303036:0xff304252;
        animate=p.getBoolean("motion",true);
        dialog=new Dialog(a);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root=new LinearLayout(a);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22),dp(22),dp(22),dp(19));root.setBackground(shape(panel,stroke,25));
        LinearLayout top=new LinearLayout(a);top.setOrientation(LinearLayout.HORIZONTAL);top.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout symbolBox=new FrameLayout(a);symbolBox.setBackground(shape((accent&0x00ffffff)|0x22000000,0,15));
        LinearLayout.LayoutParams sb=new LinearLayout.LayoutParams(dp(53),dp(53));sb.rightMargin=dp(14);top.addView(symbolBox,sb);
        mark=new CenteredRing(a,accent);
        symbolBox.addView(mark,new FrameLayout.LayoutParams(dp(33),dp(33),Gravity.CENTER));
        LinearLayout titles=new LinearLayout(a);titles.setOrientation(LinearLayout.VERTICAL);
        heading=text("Обновление QR LINK",20,foreground,true);titles.addView(heading);
        TextView sub=text("БЕЗОПАСНОЕ ОБНОВЛЕНИЕ",10,muted,true);
        LinearLayout.LayoutParams subLp=new LinearLayout.LayoutParams(-1,-2);subLp.topMargin=dp(7);titles.addView(sub,subLp);
        top.addView(titles,new LinearLayout.LayoutParams(0,-2,1));root.addView(top);
        label=text("Проверка соединения...",15,foreground,true);
        LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(-1,-2);sl.topMargin=dp(25);root.addView(label,sl);
        details=text("Подключение к серверу...",12,muted,false);
        LinearLayout.LayoutParams dl=new LinearLayout.LayoutParams(-1,-2);dl.topMargin=dp(7);root.addView(details,dl);
        LinearLayout numbers=new LinearLayout(a);numbers.setOrientation(LinearLayout.HORIZONTAL);
        numbers.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams nl=new LinearLayout.LayoutParams(-1,-2);nl.topMargin=dp(22);root.addView(numbers,nl);
        bytes=text("",13,foreground,true);numbers.addView(bytes,new LinearLayout.LayoutParams(0,-2,1));
        percent=text("",18,accent,true);numbers.addView(percent);
        bar=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);bar.setProgressTintList(ColorStateList.valueOf(accent));
        bar.setIndeterminateTintList(ColorStateList.valueOf(accent));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(light?0xffe9eef3:0xff2a3139));
        LinearLayout.LayoutParams bl=new LinearLayout.LayoutParams(-1,dp(7));bl.topMargin=dp(12);root.addView(bar,bl);
        bar.setIndeterminate(true);
        LinearLayout actions=new LinearLayout(a);actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams al=new LinearLayout.LayoutParams(-1,-2);al.topMargin=dp(24);root.addView(actions,al);
        secondary=text("СКРЫТЬ",12,muted,true);secondary.setGravity(Gravity.CENTER);
        secondary.setPadding(dp(16),dp(14),dp(16),dp(14));
        secondary.setOnClickListener(v->dismiss());
        actions.addView(secondary);
        primary=text("ПОВТОРИТЬ",12,Color.BLACK,true);primary.setGravity(Gravity.CENTER);
        primary.setBackground(shape(accent,0,13));primary.setPadding(dp(16),dp(13),dp(16),dp(13));
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-2,-2);ap.leftMargin=dp(8);
        actions.addView(primary,ap);primary.setVisibility(View.GONE);
        dialog.setContentView(root);dialog.setCancelable(true);
        dialog.setOnDismissListener(ignore->{
            if(numberAnim!=null)numberAnim.cancel();
            if(spin!=null)spin.cancel();
            shown=false;
        });
    }
    public void show(){
        if(shown)return;
        dialog.show();shown=true;
        Window window=dialog.getWindow();
        if(window!=null){
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params=window.getAttributes();
            params.dimAmount=.64f;params.gravity=Gravity.CENTER;
            params.width=Math.min(activity.getResources().getDisplayMetrics().widthPixels-dp(30),dp(450));
            params.height=WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(params);
        }
    }
    public boolean isShowing(){return shown&&dialog.isShowing();}
    public void dismiss(){if(dialog.isShowing())dialog.dismiss();}
    private void rotating(boolean enabled){
        if(enabled){
            // Progress events arrive every few milliseconds. Keep one animator alive
            // instead of restarting it (which made the icon shake and stop).
            if(spin!=null&&spin.isRunning())return;
            mark.setRotation(0f);
            spin=ValueAnimator.ofFloat(0f,360f);
            spin.setDuration(1100L);
            spin.setInterpolator(new LinearInterpolator());
            spin.setRepeatCount(ValueAnimator.INFINITE);
            spin.addUpdateListener(v->mark.setRotation((float)v.getAnimatedValue()));
            spin.start();
        }else{
            if(spin!=null){spin.cancel();spin=null;}
            mark.setRotation(0f);
        }
    }
    public void stage(String step,String hint){
        heading.setText("Обновление QR LINK");label.setText(step);details.setText(hint);
        bytes.setText("");percent.setText("");bar.setIndeterminate(true);
        primary.setVisibility(View.GONE);secondary.setText("СКРЫТЬ");rotating(true);
        if(numberAnim!=null)numberAnim.cancel();
        lastBytes=0;lastTotal=0;
    }
    public void downloading(long received,long total){
        if(received<0)return;
        rotating(true);bar.setIndeterminate(total<=0);
        label.setText("Загрузка обновления");
        details.setText("Загрузка защищённого обновления");
        primary.setVisibility(View.GONE);
        if(numberAnim!=null)numberAnim.cancel();
        final long start=total==lastTotal?lastBytes:0;
        final long end=received;lastBytes=received;lastTotal=total;
        if(animate&&end>=start){
            numberAnim=ValueAnimator.ofFloat(0f,1f);numberAnim.setDuration(285);
            numberAnim.setInterpolator(new DecelerateInterpolator());
            numberAnim.addUpdateListener(a->{
                long shownBytes=start+(long)((end-start)*(float)a.getAnimatedValue());
                renderNumbers(shownBytes,total);
            });numberAnim.start();
        }else renderNumbers(end,total);
    }
    private void renderNumbers(long loaded,long total){
        bytes.setText(size(loaded)+(total>0?" / "+size(total):""));
        if(total>0){
            int ratio=(int)Math.min(1000L,loaded*1000L/Math.max(1L,total));
            bar.setProgress(ratio);
            percent.setText((ratio/10)+"%");
        }else percent.setText("");
    }
    public void fail(String message,Runnable retry){
        if(numberAnim!=null)numberAnim.cancel();
        rotating(false);bar.setIndeterminate(false);bar.setProgress(0);
        heading.setText("Обновление не выполнено");
        label.setText("Не удалось обновить QR LINK");
        details.setText(message);bytes.setText("");percent.setText("");
        secondary.setText("ЗАКРЫТЬ");
        primary.setVisibility(retry==null?View.GONE:View.VISIBLE);
        primary.setOnClickListener(v->{if(retry!=null)retry.run();});
    }
    public void current(String version){
        if(numberAnim!=null)numberAnim.cancel();rotating(false);
        heading.setText("QR LINK обновлён");
        label.setText("Установлена актуальная версия");
        details.setText(version);
        bytes.setText("");percent.setText("");
        bar.setIndeterminate(false);bar.setProgress(1000);
        primary.setVisibility(View.GONE);secondary.setText("ЗАКРЫТЬ");
    }
    public void offer(String version,Runnable install){
        rotating(false);bar.setIndeterminate(false);bar.setProgress(0);
        heading.setText("Доступно обновление");
        label.setText("QR LINK "+version);
        details.setText("Новая версия готова к загрузке");
        bytes.setText("");percent.setText("");
        secondary.setText("ПОЗЖЕ");primary.setText("ОБНОВИТЬ");
        primary.setVisibility(View.VISIBLE);primary.setOnClickListener(v->install.run());
    }
}
