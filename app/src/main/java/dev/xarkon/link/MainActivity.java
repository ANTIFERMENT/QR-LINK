package dev.xarkon.link;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.animation.*;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.net.Uri;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import java.lang.ref.WeakReference;
import java.util.*;

/** QR LINK 0.3.19: smooth live transfer and explicit Wi-Fi / P2P state checks. */
public final class MainActivity extends Activity {
    private static final int FILES=41,FOLDER=42,SCAN=43,CAMERA_PERMISSION=44,WIFI_PERMISSION=45,WIFI_SEND_PERMISSION=46,BROWSER=47;
    private static final int HOME=0,SEND=1,RECEIVE=2,SETTINGS=3,HISTORY=4,FTP=5;
    private static final int[] ACCENTS={
        0xff55dfcb,0xff43baf5,0xff658aff,0xffac8dff,0xffdb91ff,0xffff77ce,
        0xffff7085,0xffff936d,0xffffba63,0xffffd45a,0xffd0e46d,0xff90df79,
        0xff57cda3,0xff4ad5de,0xff35a6de,0xff8773e4,0xffba6ef6,0xffed77a3,
        0xffe75f5f,0xffd39c78,0xffa3bbc8,0xffe8edf5,0xff76a4ab,0xff9a86ab
    };
    private static final String[] COLOR_NAMES={
        "Мята","Лазурь","Сапфир","Лаванда","Сирень","Фуксия",
        "Коралл","Персик","Янтарь","Лимон","Лайм","Весна",
        "Изумруд","Бирюза","Океан","Ирис","Аметист","Пион",
        "Рубин","Бронза","Сталь","Лёд","Шалфей","Слива"
    };
    private static WeakReference<MainActivity> current=new WeakReference<>(null);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable ticker=new Runnable(){@Override public void run(){
        try{
            updateDynamic();
            boolean transferring=TransferService.total>0&&
                TransferService.progress<TransferService.total;
            handler.postDelayed(this,transferring?55L:300L);
        }
        catch(RuntimeException | LinkageError failure){
            ((CrashReport)getApplication()).save("MainActivity.ticker",failure);
            handler.removeCallbacks(this);
            showEmergency(failure);
        }
    }};
    private int page=HOME,accent,bg,card,fg,muted,stroke,buttonBg,soft,subtle;
    private boolean navigating,emergency,suppressEntranceAnimations;
    private ScrollView scroller;
    private FrameLayout screenHost;
    private FrameLayout statusBarShield;
    private int statusShieldHeight;
    private boolean screenTransition;
    private int screenDirection=1;
    private boolean updateNotificationLaunch;
    private TextView backgroundCheckStatus;
    private LinearLayout homeUpdatePanel;
    private TextView homeUpdateVersion;
    private final Runnable startupUpdateTask=()->{
        if(!isFinishing()&&!isDestroyed())checkOtaQuietly();
    };
    private LinearLayout content,root;
    private TextView status,progressText,queueLabel,pairLabel,receiveHint,receiveCaption,networkBadge;
    private TextView transferName,transferPercent,transferSize,transferRate;
    private long visibleBytes;
    private String visibleFile="";
    private double visibleRate;
    private long lastQueueUpdate=0;
    private ImageView qrImage;
    private ProgressBar progress;
    private String lastQr="";
    private String pendingHotspotQr="";
    private boolean resumeReceiveAfterWifi;
    private EditText ftpAddress;
    private String ftpDir="/";
    private final ArrayList<FtpBridge.Entry> ftpEntries=new ArrayList<>();
    private TextView ftpListLabel;
    private long lastQueueFingerprint=-1;
    // Section state is only kept while editing Settings; opening Settings starts fully collapsed.
    private final Set<String> expandedSections = new HashSet<>();
    public static void changed(){MainActivity a=current.get();if(a!=null)a.runOnUiThread(a::updateDynamic);}
    private android.content.SharedPreferences prefs(){return getSharedPreferences("settings",0);}
    private int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    private String mode(){
        String saved=prefs().getString("theme_mode", "");
        if(saved.isEmpty())return prefs().getBoolean("light",false)?"light":"dark";
        return saved;
    }
    private boolean light(){return "light".equals(mode());}
    private boolean amoled(){return "amoled".equals(mode());}
    private boolean motion(){return prefs().getBoolean("motion",true);}
    private int themeStyle(){return amoled()?R.style.AppThemeAmoled:light()?R.style.AppThemeLight:R.style.AppTheme;}
    private void applyMode(String value){
        if(mode().equals(value))return;
        prefs().edit().putString("theme_mode",value).putBoolean("light","light".equals(value)).apply();
        // A full Activity recreate produces the black launch-frame flash and resets
        // scroll while changing the theme. Replace the runtime Resources.Theme
        // instead: every page element is already colored programmatically.
        android.content.res.Resources.Theme fresh=getResources().newTheme();
        fresh.applyStyle(themeStyle(),true);
        getTheme().setTo(fresh);
        redrawAtPosition();
    }
    private int accentIndex(int value){
        for(int i=0;i<ACCENTS.length;i++)if(ACCENTS[i]==value)return i;
        return -1;
    }
    /** Match the system bar exactly to the selected page background. */
    private int statusShieldColor(){
        return bg;
    }
    private int initialStatusHeight(){
        int id=getResources().getIdentifier("status_bar_height","dimen","android");
        if(id!=0)try{return getResources().getDimensionPixelSize(id);}
        catch(android.content.res.Resources.NotFoundException ignored){}
        return dp(29);
    }
    private void setupStatusBarShield(){
        if(screenHost==null)return;
        if(statusBarShield==null){
            // Opaque same-color overlay sits under system icons to keep content
            // out of the cutout, without drawing a separate band or divider.
            statusBarShield=new FrameLayout(this);
            statusBarShield.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        statusBarShield.setBackgroundColor(statusShieldColor());
        if(statusBarShield.getParent() instanceof ViewGroup)
            ((ViewGroup)statusBarShield.getParent()).removeView(statusBarShield);
        if(statusShieldHeight<=0)statusShieldHeight=initialStatusHeight();
        screenHost.addView(statusBarShield,new FrameLayout.LayoutParams(
            -1,statusShieldHeight,Gravity.TOP));
        statusBarShield.setElevation(dp(15));
        screenHost.setOnApplyWindowInsetsListener((v,insets)->{
            int top;
            if(Build.VERSION.SDK_INT>=30){
                android.graphics.Insets bar=insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.statusBars());
                android.graphics.Insets cutout=insets.getInsets(
                    WindowInsets.Type.displayCutout());
                top=Math.max(bar.top,cutout.top);
            }else{
                top=insets.getSystemWindowInsetTop();
            }
            top=Math.max(initialStatusHeight(),top);
            if(statusBarShield!=null&&top!=statusShieldHeight){
                statusShieldHeight=top;
                FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)
                    statusBarShield.getLayoutParams();
                if(lp!=null){lp.height=top;statusBarShield.setLayoutParams(lp);}
            }
            return insets;
        });
        screenHost.post(screenHost::requestApplyInsets);
    }
    private int onAccent(){
        double r=Color.red(accent)/255.0,g=Color.green(accent)/255.0,b=Color.blue(accent)/255.0;
        r=r<=.04045?r/12.92:Math.pow((r+.055)/1.055,2.4);
        g=g<=.04045?g/12.92:Math.pow((g+.055)/1.055,2.4);
        b=b<=.04045?b/12.92:Math.pow((b+.055)/1.055,2.4);
        return .2126*r+.7152*g+.0722*b>.24?0xff071219:0xffffffff;
    }
    private void theme(){
        accent=prefs().getInt("accent",ACCENTS[0]);
        if(accentIndex(accent)<0){
            accent=ACCENTS[0];
            prefs().edit().putInt("accent",accent).apply();
        }
        if(amoled()){
            bg=0xff000000;card=0xff0b0b0d;fg=0xfff5f7f9;
            muted=0xff9ba8b1;stroke=0xff27282e;buttonBg=0xff191b20;
            soft=0xff101315;subtle=0xff0d0e10;
        }else if(light()){
            bg=0xfff5f7fb;card=0xffffffff;fg=0xff152536;
            muted=0xff607587;stroke=0xffdce5ed;buttonBg=0xffe8eef5;
            soft=0xffedf2f7;subtle=0xfff9fbfd;
        }else{
            bg=0xff0a111b;card=0xff121e2a;fg=0xfff1f6fb;
            muted=0xff9caebd;stroke=0xff283947;buttonBg=0xff203140;
            soft=0xff101924;subtle=0xff0d1722;
        }
        getWindow().setStatusBarColor(statusShieldColor());
        getWindow().setNavigationBarColor(bg);
    }
        @Override public void onCreate(Bundle saved){setTheme(themeStyle());super.onCreate(saved);current=new WeakReference<>(this);
        if(saved!=null){
            page=saved.getInt("page",HOME);
            ArrayList<String> prior=saved.getStringArrayList("expanded_sections");
            if(prior!=null)expandedSections.addAll(prior);
        }
        // Do not interrupt Activity startup with a permission dialog.
        show();
        if(saved!=null&&scroller!=null){
            int y=saved.getInt("scroll_y",0);
            if(y>0)scroller.post(()->scroller.scrollTo(0,y));
        }
        incomingShare(getIntent());
        handlePairLink(getIntent());
        OtaUpdateSchedule.configure(this);
        consumeUpdateNotification(getIntent());
    }
    @Override protected void onSaveInstanceState(Bundle state){
        state.putInt("page",page);
        state.putStringArrayList("expanded_sections",new ArrayList<>(expandedSections));
        state.putInt("scroll_y",scroller==null?0:scroller.getScrollY());
        super.onSaveInstanceState(state);
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);
        setIntent(intent);
        // The installer may reopen an existing launcher task after replacing
        // the package. Always make this explicit MAIN/LAUNCHER request visible.
        if(intent!=null&&Intent.ACTION_MAIN.equals(intent.getAction())
            &&intent.hasCategory(Intent.CATEGORY_LAUNCHER)
            &&page!=HOME&&!navigating){
            navigate(HOME);
        }
        incomingShare(intent);
        handlePairLink(intent);
        consumeUpdateNotification(intent);
    }
    @Override protected void onResume(){
        super.onResume();current=new WeakReference<>(this);
        handler.removeCallbacks(ticker);if(!emergency)handler.post(ticker);
        updateBackgroundCheckStatus();
        refreshHomeUpdateCard(false);
        handler.removeCallbacks(startupUpdateTask);
        if(updateNotificationLaunch){
            updateNotificationLaunch=false;
        }else if(prefs().getBoolean(OtaUpdateSchedule.PREF_ON_LAUNCH,true)){
            handler.postDelayed(startupUpdateTask,1600L);
        }else{
            handler.postDelayed(this::offerStoredBackgroundUpdate,700L);
        }
        if(resumeReceiveAfterWifi&&page==RECEIVE){
            android.net.wifi.WifiManager wifi=(android.net.wifi.WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
            if(wifi!=null&&wifi.isWifiEnabled()){
                resumeReceiveAfterWifi=false;
                handler.postDelayed(this::beginReceive,350);
            }
        }
    }
    @Override protected void onPause(){
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(startupUpdateTask);
        super.onPause();
    }
    @Override protected void onDestroy(){if(current.get()==this)current.clear();super.onDestroy();}
    @Override public void onBackPressed(){if(page==HOME){super.onBackPressed();}else if(page==HISTORY||page==FTP){navigate(SETTINGS);}else navigate(HOME);}
    private GradientDrawable shape(int color,int line,int round){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(round));if(line!=0)d.setStroke(dp(1),line);return d;}
    private TextView text(CharSequence value,int size,int color,boolean heavy){TextView t=new TextView(this);
        t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);
        if(heavy)t.setTypeface(android.graphics.Typeface.DEFAULT,1);return t;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private void space(LinearLayout c,int px){View v=new View(this);c.addView(v,new LinearLayout.LayoutParams(1,dp(px)));}
    private void margin(LinearLayout c,View v,int after){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(after));c.addView(v,lp);}
    private View.OnClickListener click(Runnable r){return v->{
        if(prefs().getBoolean("haptics",true))v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        r.run();
    };}
    private void interactive(View v,Runnable action){
        v.setClickable(true);v.setOnClickListener(click(action));
        if(motion())v.setOnTouchListener((view,event)->{
            if(event.getAction()==MotionEvent.ACTION_DOWN){view.animate().cancel();view.animate().scaleX(.985f).scaleY(.985f).setDuration(80).start();}
            if(event.getAction()==MotionEvent.ACTION_UP||event.getAction()==MotionEvent.ACTION_CANCEL)
                view.animate().scaleX(1).scaleY(1).setDuration(135).setInterpolator(new OvershootInterpolator(.35f)).start();
            return false;
        });
    }
    private android.graphics.drawable.Drawable ripple(int color,int border,int radius){
        GradientDrawable base=shape(color,border,radius);
        return new RippleDrawable(ColorStateList.valueOf((accent&0x00ffffff)|0x34000000),base,shape(0xffffffff,0,radius));
    }
    private void animateIn(View v,int index){
        // Palette/theme changes are a state refresh, not a new screen navigation.
        // Restarting entrance animations made the entire settings panel blink.
        if(suppressEntranceAnimations||!motion())return;
        v.setAlpha(0f);v.setTranslationY(dp(13));
        v.animate().alpha(1f).translationY(0f).setDuration(265).setStartDelay(Math.min(index,8)*28L)
            .setInterpolator(new DecelerateInterpolator(1.5f)).start();
    }
    private void redrawAtPosition(){
        final ScrollView previous=scroller;
        final int scroll=previous==null?0:previous.getScrollY();
        // All state (expanded sections, scroll and palette selection) survives.
        // The new view tree must be fully visible in its very first frame.
        suppressEntranceAnimations=true;
        try{show();}finally{suppressEntranceAnimations=false;}
        if(scroller!=null&&scroller!=previous){
            final ScrollView refreshed=scroller;
            // Restore the exact scroll position BEFORE first draw. post(scrollTo)
            // caused a visible jump to the top for one frame on fast devices.
            refreshed.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener(){
                @Override public boolean onPreDraw(){
                    if(refreshed.getViewTreeObserver().isAlive())
                        refreshed.getViewTreeObserver().removeOnPreDrawListener(this);
                    refreshed.scrollTo(0,scroll);
                    return true;
                }
            });
        }
    }
    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setGravity(Gravity.CENTER_VERTICAL);return r;}
    private UiIcon icon(String s,int color,int px){UiIcon i=new UiIcon(this,s,color);i.setLayoutParams(new LinearLayout.LayoutParams(dp(px),dp(px)));return i;}
    private void button(LinearLayout parent,String glyph,String label,boolean highlight,Runnable action){
        LinearLayout b=row();b.setGravity(Gravity.CENTER);b.setMinimumHeight(dp(53));
        b.setPadding(dp(17),dp(14),dp(17),dp(14));
        int txt=highlight?onAccent():fg;
        b.setBackground(ripple(highlight?accent:buttonBg,highlight?0:stroke,16));
        UiIcon i=icon(glyph,txt,23);b.addView(i);
        View gap=new View(this);b.addView(gap,new LinearLayout.LayoutParams(dp(11),1));
        TextView caption=text(label,14,txt,true);caption.setLetterSpacing(.015f);b.addView(caption);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(10);parent.addView(b,lp);
        interactive(b,action);
    }
        private void outlineButton(LinearLayout parent,String glyph,String label,Runnable action){button(parent,glyph,label,false,action);}
    private LinearLayout panel(String title,String subtitle,String glyph){
        LinearLayout p=column();p.setPadding(dp(18),dp(19),dp(18),dp(12));p.setBackground(shape(card,stroke,22));
        margin(content,p,15);animateIn(p,content.getChildCount()-1);
        LinearLayout heading=row();
        FrameLayout capsule=new FrameLayout(this);capsule.setBackground(shape(buttonBg,0,14));
        UiIcon ic=icon(glyph,accent,25);FrameLayout.LayoutParams ilp=new FrameLayout.LayoutParams(dp(30),dp(30),Gravity.CENTER);
        capsule.addView(ic,ilp);heading.addView(capsule,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout l=column();l.setPadding(dp(13),0,0,0);l.addView(text(title,18,fg,true));
        if(subtitle!=null&&!subtitle.isEmpty()){space(l,5);l.addView(text(subtitle,13,muted,false));}
        heading.addView(l,new LinearLayout.LayoutParams(0,-2,1));p.addView(heading);space(p,17);return p;
    }
    private LinearLayout section(String key,String title,CharSequence subtitle,String glyph,boolean unusedDefaultOpen){
        LinearLayout shell=column();shell.setBackground(shape(card,stroke,22));
        shell.setClipChildren(true);shell.setClipToPadding(true);
        margin(content,shell,13);animateIn(shell,content.getChildCount()-1);
        LinearLayout head=row();head.setMinimumHeight(dp(82));head.setPadding(dp(16),dp(15),dp(15),dp(15));
        UiIcon left=icon(glyph,accent,29);head.addView(left);
        LinearLayout titles=column();titles.setPadding(dp(13),0,dp(8),0);
        titles.addView(text(title,17,fg,true));space(titles,5);
        titles.addView(text(subtitle,12,muted,false));head.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        UiIcon chevron=icon("chevron",muted,22);head.addView(chevron);
        shell.addView(head);View line=new View(this);line.setBackgroundColor(stroke);
        LinearLayout.LayoutParams sep=new LinearLayout.LayoutParams(-1,dp(1));
        sep.setMargins(dp(16),0,dp(16),0);shell.addView(line,sep);
        LinearLayout body=column();body.setPadding(dp(16),dp(17),dp(16),dp(8));
        body.setClipChildren(true);body.setClipToPadding(true);shell.addView(body);
        final boolean[] isOpen={expandedSections.contains(key)};
        final int[] transitionId={0};
        final ValueAnimator[] running={null};
        body.setVisibility(isOpen[0]?View.VISIBLE:View.GONE);
        line.setVisibility(isOpen[0]?View.VISIBLE:View.GONE);
        chevron.setRotation(isOpen[0]?180f:0f);
        head.setBackground(ripple(0x00000000,0,22));
        head.setContentDescription(title+". "+(isOpen[0]?"Свернуть":"Развернуть"));
        interactive(head,()->{
            // Do not rebuild the Activity to collapse: animate the measured height to zero first.
            boolean opening=!isOpen[0];
            isOpen[0]=opening;
            if(opening)expandedSections.add(key);else expandedSections.remove(key);
            head.setContentDescription(title+". "+(opening?"Свернуть":"Развернуть"));
            int id=++transitionId[0];
            if(running[0]!=null){running[0].cancel();running[0]=null;}
            chevron.animate().cancel();
            if(!motion()){
                line.setVisibility(opening?View.VISIBLE:View.GONE);
                body.setVisibility(opening?View.VISIBLE:View.GONE);
                LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)body.getLayoutParams();
                lp.height=ViewGroup.LayoutParams.WRAP_CONTENT;body.setLayoutParams(lp);
                body.setAlpha(1f);chevron.setRotation(opening?180f:0f);
                return;
            }
            int firstHeight=Math.max(0,body.getHeight());
            int finalHeight;
            if(opening){
                // Obtain a real height even while the section is initially GONE.
                LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)body.getLayoutParams();
                lp.height=ViewGroup.LayoutParams.WRAP_CONTENT;body.setLayoutParams(lp);
                body.setVisibility(View.VISIBLE);
                int available=Math.max(1,shell.getWidth()-shell.getPaddingLeft()-shell.getPaddingRight());
                body.measure(View.MeasureSpec.makeMeasureSpec(available,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                finalHeight=Math.max(1,body.getMeasuredHeight());
                line.setVisibility(View.VISIBLE);
            }else{
                finalHeight=0;
            }
            final int startHeight=firstHeight;
            ValueAnimator animator=ValueAnimator.ofInt(startHeight,finalHeight);
            running[0]=animator;
            animator.setDuration(opening?245:210);
            animator.setInterpolator(new DecelerateInterpolator(1.4f));
            animator.addUpdateListener(a->{
                if(id!=transitionId[0])return;
                LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)body.getLayoutParams();
                lp.height=(Integer)a.getAnimatedValue();body.setLayoutParams(lp);
                float fraction=a.getAnimatedFraction();
                body.setAlpha(opening?fraction:1f-fraction);
            });
            animator.addListener(new AnimatorListenerAdapter(){
                @Override public void onAnimationEnd(Animator a){
                    if(id!=transitionId[0])return;
                    running[0]=null;
                    LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)body.getLayoutParams();
                    lp.height=ViewGroup.LayoutParams.WRAP_CONTENT;body.setLayoutParams(lp);
                    body.setAlpha(1f);
                    body.setVisibility(opening?View.VISIBLE:View.GONE);
                    line.setVisibility(opening?View.VISIBLE:View.GONE);
                }
            });
            chevron.animate().rotation(opening?180f:0f).setDuration(opening?220:190)
                .setInterpolator(new DecelerateInterpolator()).start();
            animator.start();
        });
        return body;
    }
    private void settingToggle(LinearLayout parent,String glyph,String label,String description,String pref,boolean fallback){
        LinearLayout r=row();r.setPadding(dp(8),dp(9),dp(6),dp(9));
        r.addView(icon(glyph,accent,25));
        LinearLayout labels=column();labels.setPadding(dp(12),0,dp(9),0);
        labels.addView(text(label,14,fg,true));space(labels,4);
        labels.addView(text(description,12,muted,false));r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        Switch control=new Switch(this);control.setChecked(prefs().getBoolean(pref,fallback));
        control.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{accent,muted}));
        r.addView(control);margin(parent,r,12);
        control.setOnCheckedChangeListener((button,isChecked)->prefs().edit().putBoolean(pref,isChecked).apply());
        r.setOnClickListener(click(()->control.setChecked(!control.isChecked())));
    }
    private void colorPalette(LinearLayout parent){
        margin(parent,text("АКЦЕНТНЫЙ ЦВЕТ  ·  "+ACCENTS.length+" ВАРИАНТА",11,muted,true),12);
        LinearLayout selection=row();selection.setPadding(dp(9),dp(9),dp(10),dp(9));
        selection.setBackground(shape(soft,stroke,13));margin(parent,selection,14);
        View dot=new View(this);dot.setBackground(shape(accent,0,12));
        selection.addView(dot,new LinearLayout.LayoutParams(dp(21),dp(21)));
        TextView prefix=text("   Выбран: ",12,fg,true);selection.addView(prefix);
        int selected=accentIndex(accent);
        TextView currentColor=text(selected<0?COLOR_NAMES[0]:COLOR_NAMES[selected],13,accent,true);
        selection.addView(currentColor);
        int columns=4;
        for(int start=0;start<ACCENTS.length;start+=columns){
            LinearLayout line=row();margin(parent,line,9);
            for(int n=start;n<Math.min(start+columns,ACCENTS.length);n++){
                int index=n;LinearLayout tile=column();tile.setGravity(Gravity.CENTER);
                tile.setPadding(dp(3),dp(11),dp(3),dp(9));
                int back=accent==ACCENTS[n]?soft:card;
                tile.setBackground(ripple(back,accent==ACCENTS[n]?accent:stroke,13));
                FrameLayout swatch=new FrameLayout(this);swatch.setBackground(shape(ACCENTS[n],0,25));
                if(accent==ACCENTS[n]){
                    UiIcon tick=icon("check",onAccent(),21);
                    swatch.addView(tick,new FrameLayout.LayoutParams(dp(26),dp(26),Gravity.CENTER));
                }
                LinearLayout.LayoutParams dl=new LinearLayout.LayoutParams(dp(29),dp(29));dl.gravity=Gravity.CENTER;tile.addView(swatch,dl);
                space(tile,8);TextView caption=text(COLOR_NAMES[n],11,fg,accent==ACCENTS[n]);caption.setGravity(Gravity.CENTER);
                caption.setMaxLines(1);caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tile.addView(caption,new LinearLayout.LayoutParams(-1,-2));
                LinearLayout.LayoutParams tl=new LinearLayout.LayoutParams(0,dp(79),1);tl.setMargins(dp(3),0,dp(3),0);
                line.addView(tile,tl);
                tile.setContentDescription("Акцент: "+COLOR_NAMES[n]+(accent==ACCENTS[n]?", выбран":""));
                interactive(tile,()->{
                    if(accent==ACCENTS[index])return;
                    prefs().edit().putInt("accent",ACCENTS[index]).apply();
                    redrawAtPosition();
                });
            }
        }
    }
    private void themeChoice(LinearLayout parent,String id,String name,String glyph){
        LinearLayout tile=column();tile.setGravity(Gravity.CENTER);
        boolean selected=mode().equals(id);
        tile.setPadding(dp(5),dp(13),dp(5),dp(12));tile.setMinimumHeight(dp(87));
        tile.setBackground(ripple(selected?soft:buttonBg,selected?accent:stroke,16));
        tile.addView(icon(glyph,selected?accent:muted,28));space(tile,8);
        TextView nameText=text(name,12,selected?accent:fg,selected);nameText.setGravity(Gravity.CENTER);tile.addView(nameText);
        LinearLayout.LayoutParams param=new LinearLayout.LayoutParams(0,-2,1);param.setMargins(dp(3),0,dp(3),0);
        parent.addView(tile,param);tile.setContentDescription("Тема: "+name+(selected?", выбрана":""));
        interactive(tile,()->applyMode(id));
    }

        private void show(){
        try{renderScreen();}
        catch(RuntimeException | LinkageError failure){
            ((CrashReport)getApplication()).save("MainActivity.renderScreen [page="+page+"]",failure);
            showEmergency(failure);
        }
    }
    private void showEmergency(Throwable error){
        // Deliberately uses no custom icons, animation or third-party widgets.
        emergency=true;
        try{
            page=HOME;handler.removeCallbacks(ticker);
            LinearLayout fallback=new LinearLayout(this);fallback.setOrientation(LinearLayout.VERTICAL);
            fallback.setPadding(dp(22),dp(44),dp(22),dp(22));fallback.setBackgroundColor(0xff000000);
            TextView header=new TextView(this);header.setText("QR LINK · Восстановление");
            header.setTextColor(0xff58dfce);header.setTextSize(22);fallback.addView(header);
            TextView info=new TextView(this);info.setText("Не удалось открыть интерфейс. Отчёт сохранён. "
                +"Откройте диагностику, чтобы скопировать причину ошибки.\n\n"+error);
            info.setTextColor(0xffffffff);info.setTextSize(14);info.setPadding(0,dp(22),0,dp(22));fallback.addView(info);
            Button diag=new Button(this);diag.setText("Открыть диагностику");
            diag.setOnClickListener(v->{Intent i=new Intent(this,RecoveryActivity.class);
                i.putExtra("show",true);startActivity(i);finish();});fallback.addView(diag);
            screenHost=null;
            statusBarShield=null;
            setContentView(fallback);
        }catch(Throwable fail){throw new RuntimeException("QR LINK recovery screen failed",fail);}
    }
    private void renderScreen(){
        theme();lastQr="";status=null;progress=null;progressText=null;transferName=null;transferPercent=null;transferSize=null;transferRate=null;pairLabel=null;queueLabel=null;
        qrImage=null;receiveHint=null;receiveCaption=null;networkBadge=null;
        backgroundCheckStatus=null;
        homeUpdatePanel=null;homeUpdateVersion=null;
        scroller=new ScrollView(this);scroller.setFillViewport(true);scroller.setClipToPadding(false);
        scroller.setBackgroundColor(bg);scroller.setVerticalScrollBarEnabled(false);
        // Keep the window background identical to the screen during navigation and
        // while Android 17 applies edge-to-edge system insets.
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bg));
        UiMotion.applySafeInsets(this,scroller,dp(12),dp(7));
        root=column();root.setPadding(dp(19),dp(16),dp(19),dp(25));scroller.addView(root);
        if(screenHost==null){
            screenHost=new FrameLayout(this);
            screenHost.setBackgroundColor(bg);
            setContentView(screenHost);
        }else screenHost.setBackgroundColor(bg);
        // Keep the shield above all page layers. It must not be mistaken
        // for the outgoing page during a crossfade.
        if(statusBarShield!=null&&statusBarShield.getParent()==screenHost)
            screenHost.removeView(statusBarShield);
        final View outgoing=screenHost.getChildCount()>0
            ?screenHost.getChildAt(screenHost.getChildCount()-1):null;
        final boolean swap=screenTransition&&outgoing!=null&&motion()&&!suppressEntranceAnimations;
        screenTransition=false;
        if(!swap)screenHost.removeAllViews();
        screenHost.addView(scroller,new FrameLayout.LayoutParams(-1,-1));
        setupStatusBarShield();
        UiMotion.systemBars(this,light());
        // The initial call may be too early; repeat when the view is attached.
        scroller.post(scroller::requestApplyInsets);
        LinearLayout top=row();top.setMinimumHeight(dp(56));root.addView(top);
        if(page!=HOME){
            FrameLayout backBox=new FrameLayout(this);backBox.setBackground(ripple(buttonBg,stroke,15));
            UiIcon back=icon("back",fg,28);backBox.addView(back,new FrameLayout.LayoutParams(dp(29),dp(29),Gravity.CENTER));
            top.addView(backBox,new LinearLayout.LayoutParams(dp(47),dp(47)));
            interactive(backBox,()->onBackPressed());
            View gap=new View(this);top.addView(gap,new LinearLayout.LayoutParams(dp(13),1));
        }
        LinearLayout title=column();TextView brand=text("QR LINK",27,fg,true);brand.setLetterSpacing(.07f);
        android.text.SpannableString brandColor=new android.text.SpannableString("QR LINK");
        brandColor.setSpan(new android.text.style.ForegroundColorSpan(accent),0,2,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        brand.setText(brandColor);
        title.addView(brand);space(title,5);
        TextView caption=text(pageTitle(),10,muted,true);caption.setLetterSpacing(.09f);title.addView(caption);
        top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        if(page!=SETTINGS){
            FrameLayout gearBox=new FrameLayout(this);gearBox.setBackground(ripple(buttonBg,stroke,16));
            gearBox.addView(icon("settings",accent,29),new FrameLayout.LayoutParams(dp(29),dp(29),Gravity.CENTER));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(48),dp(48));top.addView(gearBox,lp);
            gearBox.setContentDescription("Настройки");interactive(gearBox,()->navigate(SETTINGS));
        }
        animateIn(top,0);space(root,21);
        content=column();root.addView(content);
        if(page==HOME)home();else if(page==SEND)sendPage();else if(page==RECEIVE)receivePage();
        else if(page==SETTINGS)settingsPage();else if(page==HISTORY)historyPage();else ftpPage();
        updateDynamic();
        emergency=false;
        if(swap){
            final ScrollView incoming=scroller;
            final int direction=screenDirection;
            incoming.setAlpha(0f);
            incoming.setTranslationX(dp(22)*direction);
            incoming.setScaleX(.985f);incoming.setScaleY(.985f);
            outgoing.animate().cancel();
            outgoing.animate().alpha(.45f).translationX(-dp(16)*direction)
                .setDuration(235).setInterpolator(new DecelerateInterpolator(1.3f)).start();
            incoming.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
                .setDuration(280).setInterpolator(new DecelerateInterpolator(1.5f))
                .withEndAction(()->{
                    if(screenHost!=null&&outgoing.getParent()==screenHost)
                        screenHost.removeView(outgoing);
                    navigating=false;
                }).start();
        }else navigating=false;
    }
        private String pageTitle(){switch(page){case SEND:return "ОТПРАВКА ФАЙЛОВ";case RECEIVE:return "ПОЛУЧЕНИЕ ФАЙЛОВ";
        case SETTINGS:return "НАСТРОЙКИ И ВНЕШНИЕ ПРОТОКОЛЫ";case HISTORY:return "ЖУРНАЛ ОПЕРАЦИЙ";
        case FTP:return "SHAREME FTP";default:return "ПЕРЕДАЧА БЕЗ ОБЛАКА И РЕКЛАМЫ";}}
    private void navigate(int value){
        if(navigating||page==value)return;
        if(value==SETTINGS)expandedSections.clear();
        final int previous=page;
        screenDirection=(value==HOME||value==SETTINGS&&previous!=HOME)?-1:1;
        screenTransition=motion()&&screenHost!=null;
        navigating=true;
        page=value;
        suppressEntranceAnimations=screenTransition;
        try{show();}finally{suppressEntranceAnimations=false;}
    }
    private void home(){
        LinearLayout hero=column();hero.setPadding(dp(18),dp(20),dp(18),dp(18));
        hero.setBackground(shape(card,stroke,24));margin(content,hero,19);animateIn(hero,0);
        LinearLayout heading=row();
        QrPulseView pulse=new QrPulseView(this,accent,motion());
        heading.addView(pulse,new LinearLayout.LayoutParams(dp(82),dp(82)));
        LinearLayout intro=column();intro.setPadding(dp(15),0,0,0);
        intro.addView(text("Мгновенный обмен",20,fg,true));space(intro,7);
        intro.addView(text("Никаких проводов, облака и лишних действий",12,muted,false));
        heading.addView(intro,new LinearLayout.LayoutParams(0,-2,1));hero.addView(heading);
        space(hero,15);
        TextView tag=text("●  ПРЯМОЕ ПОДКЛЮЧЕНИЕ ПО QR",11,accent,true);
        tag.setLetterSpacing(.05f);hero.addView(tag);
        space(hero,7);hero.addView(text("Выберите действие, остальное QR LINK сделает сам.",14,muted,false));
        homeUpdatePanel=panel("Доступно обновление","Новая версия QR LINK готова к установке.","receive");
        homeUpdateVersion=text("",14,accent,true);
        margin(homeUpdatePanel,homeUpdateVersion,12);
        button(homeUpdatePanel,"receive","ОБНОВИТЬ QR LINK",true,this::checkForUpdates);
        refreshHomeUpdateCard(false);
        LinearLayout send=panel("Отправить файлы","Сканируйте QR получателя и выберите файлы или папки.","send");
        button(send,"send","ОТПРАВИТЬ",true,()->navigate(SEND));
        LinearLayout recv=panel("Получить файлы","Покажите свой QR. Устройство готово принимать файлы.","receive");
        button(recv,"receive","ПОЛУЧИТЬ",true,()->{
            navigate(RECEIVE);if(!TransferService.isReceiving)beginReceive();
        });
    }
    /**
     * The available release is cached after a successful startup/background check.
     * Update the home card in place, without rebuilding the current screen
     * or interfering with Send/Receive views and their running transfers.
     */
    private void refreshHomeUpdateCard(boolean animate){
        if(homeUpdatePanel==null||homeUpdateVersion==null)return;
        long available=prefs().getLong(OtaUpdateSchedule.PREF_AVAILABLE_CODE,0L);
        long installed;
        try{
            installed=getPackageManager().getPackageInfo(getPackageName(),0).getLongVersionCode();
        }catch(Exception ignored){return;}
        if(available<=installed){
            homeUpdatePanel.animate().cancel();
            homeUpdatePanel.setVisibility(View.GONE);
            return;
        }
        String name=prefs().getString(OtaUpdateSchedule.PREF_AVAILABLE_NAME,"");
        homeUpdateVersion.setText(name.isEmpty()?"Новая версия":("Версия "+name));
        if(homeUpdatePanel.getVisibility()!=View.VISIBLE){
            homeUpdatePanel.animate().cancel();
            homeUpdatePanel.setVisibility(View.VISIBLE);
            if(animate&&motion()){
                homeUpdatePanel.setAlpha(0f);
                homeUpdatePanel.setTranslationY(dp(8));
                homeUpdatePanel.animate().alpha(1f).translationY(0f)
                    .setDuration(235L)
                    .setInterpolator(new DecelerateInterpolator(1.5f)).start();
            }else{
                homeUpdatePanel.setAlpha(1f);
                homeUpdatePanel.setTranslationY(0f);
            }
        }
    }
    /** One premium progress panel shared by Send and Receive screens. */
    private void liveStatus(){
        LinearLayout box=column();box.setPadding(dp(18),dp(18),dp(18),dp(17));
        box.setBackground(shape(card,stroke,22));margin(content,box,17);
        LinearLayout top=row();top.setGravity(Gravity.CENTER_VERTICAL);
        UiIcon glyph=icon("file",accent,25);top.addView(glyph);
        LinearLayout nameBlock=column();nameBlock.setPadding(dp(12),0,dp(8),0);
        transferName=text("Ожидание передачи",16,fg,true);
        transferName.setSingleLine(true);transferName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        nameBlock.addView(transferName);
        top.addView(nameBlock,new LinearLayout.LayoutParams(0,-2,1));
        transferPercent=text("0%",20,accent,true);
        transferPercent.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.BOLD));
        top.addView(transferPercent);box.addView(top);space(box,14);
        visibleBytes=0;visibleRate=0;visibleFile="";
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);progress.setProgressTintList(ColorStateList.valueOf(accent));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(buttonBg));
        box.addView(progress,new LinearLayout.LayoutParams(-1,dp(7)));space(box,13);
        LinearLayout stats=row();
        transferSize=text("0 Б / 0 Б",14,fg,true);
        stats.addView(transferSize,new LinearLayout.LayoutParams(0,-2,1));
        transferRate=text("",12,accent,true);
        stats.addView(transferRate);box.addView(stats);space(box,10);
        status=text("Готово к соединению",12,muted,false);
        status.setMaxLines(2);box.addView(status);
        // Kept for compatibility with the existing update tick.
        progressText=text("",11,muted,false);
        progressText.setVisibility(View.GONE);box.addView(progressText);
    }
    private boolean paired(){return prefs().getString("key","").matches("[0-9a-f]{64}")&&!prefs().getString("host","").isEmpty();}
    private void sendPage(){
        LinearLayout connect=panel("Подключение по QR","Никаких адресов или PIN: наведи камеру на QR-код получателя.","scan");
        pairLabel=text("Ожидаем сканирование QR-кода",14,paired()?accent:muted,true);margin(connect,pairLabel,13);
        button(connect,"scan",paired()?"СКАНИРОВАТЬ ДРУГОЙ QR":"СКАНИРОВАТЬ QR-КОД",true,this::startScanner);
        if(paired())outlineButton(connect,"check","Проверить подключение",this::validatePaired);
        liveStatus();
        LinearLayout queue=panel("Файлы и папки","Собственный проводник: картинки, видео, музыка, приложения и файлы.","folder");
        button(queue,"file","+ ОТКРЫТЬ ПРОВОДНИК",true,this::pickFilesOrFolder);
        queueLabel=text("Очередь пуста",13,muted,false);margin(queue,queueLabel,14);
        button(queue,"send","НАЧАТЬ ПЕРЕДАЧУ",true,()->{
            if(!ensureWifiEnabled())return;
            if(!paired()){toast("Сначала отсканируйте QR-код получателя");return;}
            if(FileCatalog.load(this).isEmpty()){toast("Добавьте файлы или папку");return;}
            start(TransferService.SEND);
        });
        outlineButton(queue,"trash","Очистить очередь",()->new AlertDialog.Builder(this).setTitle("Очистить очередь?")
            .setMessage("Исходные файлы не удалятся.").setNegativeButton("Отмена",null)
            .setPositiveButton("Очистить",(d,w)->{FileCatalog.save(this,new ArrayList<>());updateQueue();}).show());
        LinearLayout extra=panel("Дополнительно","Совместимость с установленными SHAREit и ShareMe через стандартное Android-меню «Поделиться».","share");
        outlineButton(extra,"share","Передать через SHAREit / ShareMe",this::shareExternal);
    }
    private void receivePage(){
        LinearLayout connection=panel("Соединение","Общая сеть Wi-Fi или прямое соединение Wi-Fi Direct без роутера.","wifi");
        LinearLayout state=row();state.setGravity(Gravity.CENTER_VERTICAL);
        state.setPadding(dp(14),dp(13),dp(14),dp(13));state.setBackground(shape(soft,stroke,16));
        UiIcon wifiGlyph=new UiIcon(this,"wifi",accent);
        state.addView(wifiGlyph,new LinearLayout.LayoutParams(dp(25),dp(25)));
        networkBadge=text("Определяем подключение...",13,accent,true);
        networkBadge.setPadding(dp(13),0,0,0);
        state.addView(networkBadge,new LinearLayout.LayoutParams(0,-2,1));
        margin(connection,state,3);
        LinearLayout qrPanel=panel("Мой QR-код","Без общей сети QR LINK создаёт Wi-Fi Direct группу. Отправителю достаточно сканировать QR.","scan");
        receiveHint=text("Создаём защищённый ключ подключения...",15,muted,true);margin(qrPanel,receiveHint,12);
        FrameLayout wrapper=new FrameLayout(this);wrapper.setBackground(shape(0xffffffff,0,16));
        qrImage=new ImageView(this);qrImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int sz=Math.min(dp(286),getResources().getDisplayMetrics().widthPixels-dp(90));FrameLayout.LayoutParams img=new FrameLayout.LayoutParams(-1,-1);img.setMargins(dp(14),dp(14),dp(14),dp(14));wrapper.addView(qrImage,img);
        LinearLayout.LayoutParams wrap=new LinearLayout.LayoutParams(sz,sz);wrap.gravity=Gravity.CENTER;wrap.bottomMargin=dp(14);
        qrPanel.addView(wrapper,wrap);
        receiveCaption=text("Ожидание подключения...",13,muted,false);margin(qrPanel,receiveCaption,15);
        outlineButton(qrPanel,"scan","ОБНОВИТЬ QR-КОД",()->{lastQr="";start(TransferService.ROTATE);});
        outlineButton(qrPanel,"trash","ОСТАНОВИТЬ ПРИЁМ",()->{start(TransferService.STOP);navigate(HOME);});
        liveStatus();
        LinearLayout folder=panel("Фоновая передача","Загрузки / QR LINK · Приём продолжится при блокировке экрана благодаря постоянному уведомлению и защите Wi-Fi от сна. В энергосбережении производитель может ограничивать работу.","folder");
        outlineButton(folder,"settings","Настройки энергосбережения",()->{
            try{Intent i=new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);startActivity(i);}
            catch(Exception e){toast("Откройте системные настройки батареи для QR LINK");}
        });
        outlineButton(folder,"folder","Открыть Загрузки",()->{try{startActivity(new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS));}catch(Exception e){toast(e.getMessage());}});
        outlineButton(folder,"wifi","Настройки Wi-Fi",()->{try{startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));}catch(Exception e){toast(e.getMessage());}});
    }
    /** Four discrete stops: actual worker counts are 1, 2, 4 and 8. */
    private void streamSlider(LinearLayout parent){
        final int[] counts={1,2,4,8};
        final String[] names={"Стабильный","Экономичный","Оптимальный","Максимальный"};
        final String[] descriptions={
            "1 поток · минимальная нагрузка, для слабого Wi-Fi и старых устройств.",
            "2 потока · умеренная нагрузка и экономия ресурсов.",
            "4 потока · рекомендовано: баланс скорости и стабильности.",
            "8 потоков · экспериментально: может быть быстрее или медленнее в зависимости от сети."
        };
        int saved=prefs().getInt("lanes",4);
        int selected=2;for(int i=0;i<counts.length;i++)if(counts[i]==saved)selected=i;
        margin(parent,text("ПАРАЛЛЕЛЬНЫЕ ПОТОКИ",11,muted,true),14);
        LinearLayout modeCard=row();modeCard.setPadding(dp(14),dp(13),dp(12),dp(13));
        modeCard.setBackground(shape(soft,stroke,16));
        UiIcon speedIcon=icon("speed",accent,28);modeCard.addView(speedIcon);
        LinearLayout modeText=column();modeText.setPadding(dp(12),0,0,0);
        TextView modeName=text("",16,accent,true);modeText.addView(modeName);space(modeText,5);
        TextView modeDescription=text("",12,muted,false);modeText.addView(modeDescription);
        modeCard.addView(modeText,new LinearLayout.LayoutParams(0,-2,1));
        SeekBar picker=new SeekBar(this);
        picker.setMax(counts.length-1);picker.setProgress(selected);
        picker.setProgressTintList(ColorStateList.valueOf(accent));
        picker.setThumbTintList(ColorStateList.valueOf(accent));
        picker.setProgressBackgroundTintList(ColorStateList.valueOf(stroke));
        picker.setTickMarkTintList(ColorStateList.valueOf(accent));
        picker.setSplitTrack(false);
        picker.setContentDescription("Количество параллельных потоков");
        LinearLayout.LayoutParams track=new LinearLayout.LayoutParams(-1,dp(45));
        track.setMargins(dp(3),0,dp(3),0);parent.addView(picker,track);
        LinearLayout marks=row();marks.setGravity(Gravity.CENTER_VERTICAL);margin(parent,marks,13);
        TextView[] steps=new TextView[counts.length];
        for(int i=0;i<counts.length;i++){
            final int step=i;
            TextView mark=text(counts[i]+"",13,fg,true);
            mark.setGravity(Gravity.CENTER);mark.setPadding(0,dp(4),0,dp(4));
            marks.addView(mark,new LinearLayout.LayoutParams(0,dp(34),1));
            mark.setOnClickListener(click(()->{
                picker.setProgress(step);
                prefs().edit().putInt("lanes",counts[step]).apply();
            }));
            steps[i]=mark;
        }
        margin(parent,modeCard,16); // The live mode description belongs BELOW the slider.
        Runnable refresh=()->{
            int idx=Math.max(0,Math.min(counts.length-1,picker.getProgress()));
            modeName.setText(counts[idx]+" "+(counts[idx]==1?"поток":counts[idx]==2||counts[idx]==4?"потока":"потоков")+" · "+names[idx]);
            modeDescription.setText(descriptions[idx]);
            for(int i=0;i<steps.length;i++){
                steps[i].setTextColor(i==idx?accent:muted);
                steps[i].setBackground(i==idx?shape(buttonBg,stroke,10):null);
            }
        };
        refresh.run();
        picker.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int value,boolean fromUser){
                refresh.run();
                if(fromUser)prefs().edit().putInt("lanes",counts[value]).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){
                int value=bar.getProgress();prefs().edit().putInt("lanes",counts[value]).apply();
                if(prefs().getBoolean("haptics",true))bar.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
        });
    }
    /** Retain bold/accent span in the settings section subtitle. */
    private CharSequence updatesSubtitle(){
        android.text.SpannableStringBuilder line=
            new android.text.SpannableStringBuilder("Проверка обновлений ");
        int start=line.length();
        line.append("QR LINK");
        line.setSpan(new android.text.style.ForegroundColorSpan(accent),start,line.length(),
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            start,line.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return line;
    }
    private void settingsPage(){
        LinearLayout summary=column();summary.setPadding(dp(7),dp(2),dp(7),dp(14));
        margin(content,summary,6);
        summary.addView(text("Персонализация",23,fg,true));space(summary,7);
        summary.addView(text("Настройте приложение под себя. Все разделы изначально свёрнуты.",13,muted,false));
        LinearLayout appearance=section("appearance","Оформление","Тема экрана и акцентная палитра","theme",false);
        margin(appearance,text("ТЕМА ИНТЕРФЕЙСА",11,muted,true),13);
        LinearLayout themes=row();margin(appearance,themes,19);
        themeChoice(themes,"dark","Тёмная","moon");
        themeChoice(themes,"amoled","AMOLED","amoled");
        themeChoice(themes,"light","Светлая","sun");
        colorPalette(appearance);
        LinearLayout animations=section("animations","Анимация и отклик",
            "Скорость переходов и тактильная обратная связь","sparkles",false);
        settingToggle(animations,"sparkles","Плавные анимации",
            "Быстрые переходы и анимированные разделы","motion",true);
        settingToggle(animations,"flash","Тактильный отклик",
            "Короткая вибрация при нажатии","haptics",true);
        LinearLayout performance=section("performance","Передача и скорость",
            "Потоки и замер реальной скорости","speed",false);
        streamSlider(performance);
        settingToggle(performance,"wifi","Предпочитать Wi-Fi Direct 5 ГГц",
            "Высокая скорость при поддержке обоих телефонов. При отказе используется автоматический диапазон.",
            "prefer_5ghz",true);
        outlineButton(performance,"speed","Проверка скорости сети",()->{
            if(!paired()){toast("Сначала подключитесь с помощью QR-кода");navigate(SEND);}else start(TransferService.BENCH);
        });
        LinearLayout utilities=section("tools","Инструменты","ShareMe FTP, журнал и системные средства","link",false);
        outlineButton(utilities,"folder","ShareMe FTP-клиент",()->navigate(FTP));
        outlineButton(utilities,"history","Журнал операций",()->navigate(HISTORY));
        outlineButton(utilities,"info","Диагностика и аварийные отчёты",()->{
            Intent i=new Intent(this,RecoveryActivity.class);i.putExtra("show",true);startActivity(i);
        });
        outlineButton(utilities,"wifi","Настройки Wi-Fi",()->{
            try{startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));}catch(Exception e){toast(e.getMessage());}
        });
        LinearLayout updater=section("updates","Обновления",
            updatesSubtitle(),"receive",false);
        outlineButton(updater,"check","Проверить обновление",this::checkForUpdates);
        updatePreferences(updater);
        LinearLayout about=section("about","Информация и безопасность","Версия, протокол, хранение файлов","info",false);
        about.addView(text("QR LINK "+getAppVersion(),16,fg,true));space(about,9);
        about.addView(text("Android 10+  ·  Wi-Fi / Wi-Fi Direct  ·  соединение по QR",13,muted,false));space(about,13);
        about.addView(text("Данные передаются по HTTP без шифрования. Случайный ключ QR защищает от случайного подключения, но не скрывает содержимое передачи. Используйте доверенную локальную сеть.",13,fg,false));
        space(about,11);
        about.addView(text("Режим ShareMe FTP и Android «Поделиться» доступны отдельно. Нативные протоколы SHAREit и ShareMe не эмулируются.",12,muted,false));
        space(about,8);
    }
    private Switch otaSwitch(LinearLayout parent,String glyph,String title,String detail,
                             boolean on,java.util.function.Consumer<Boolean> changed){
        LinearLayout item=row();item.setPadding(dp(7),dp(11),dp(6),dp(11));
        item.addView(icon(glyph,accent,24));
        LinearLayout labels=column();labels.setPadding(dp(12),0,dp(8),0);
        labels.addView(text(title,14,fg,true));space(labels,5);
        labels.addView(text(detail,12,muted,false));
        item.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        Switch control=new Switch(this);control.setChecked(on);
        control.setThumbTintList(new ColorStateList(new int[][]{
            new int[]{android.R.attr.state_checked},new int[]{}},new int[]{accent,muted}));
        item.addView(control);margin(parent,item,4);
        control.setOnCheckedChangeListener((button,enabled)->changed.accept(enabled));
        item.setOnClickListener(click(()->control.setChecked(!control.isChecked())));
        return control;
    }
    private void updatePreferences(LinearLayout parent){
        otaSwitch(parent,"check","Проверка обновлений при запуске",
            "При открытии QR LINK",
            prefs().getBoolean(OtaUpdateSchedule.PREF_ON_LAUNCH,true),
            enabled->prefs().edit().putBoolean(OtaUpdateSchedule.PREF_ON_LAUNCH,enabled).apply());
        final LinearLayout frequency=column();
        otaSwitch(parent,"history","Проверка обновлений в фоне",
            "Когда приложение не открыто",
            prefs().getBoolean(OtaUpdateSchedule.PREF_BACKGROUND,false),enabled->{
                prefs().edit().putBoolean(OtaUpdateSchedule.PREF_BACKGROUND,enabled).apply();
                OtaUpdateSchedule.configure(this);
                updateBackgroundCheckStatus();
                frequency.animate().cancel();
                frequency.animate().alpha(enabled?1f:.45f)
                    .setDuration(175).setInterpolator(new DecelerateInterpolator()).start();
                setEnabledChildren(frequency,enabled);
                if(enabled&&android.os.Build.VERSION.SDK_INT>=33&&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        !=PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},1007);
            });
        space(parent,7);margin(parent,frequency,8);
        final String[] labels=OtaUpdateSchedule.INTERVAL_NAMES;
        final long[] hours=OtaUpdateSchedule.INTERVAL_HOURS;
        int initial=4;
        long configured=prefs().getLong(OtaUpdateSchedule.PREF_FREQUENCY,24L);
        for(int i=0;i<hours.length;i++)if(hours[i]==configured)initial=i;
        margin(frequency,text("ЧАСТОТА ПРОВЕРКИ",11,muted,true),12);
        LinearLayout sliderCard=column();
        sliderCard.setPadding(dp(9),dp(15),dp(9),dp(12));
        sliderCard.setBackground(shape(soft,stroke,15));
        margin(frequency,sliderCard,7);
        SeekBar slider=new SeekBar(this);
        slider.setMax(hours.length-1);
        slider.setProgressTintList(ColorStateList.valueOf(accent));
        slider.setThumbTintList(ColorStateList.valueOf(accent));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(stroke));
        slider.setSplitTrack(false);
        slider.setProgress(initial);
        TextView selectedLabel=text(labels[initial],15,accent,true);
        selectedLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams captionParams=new LinearLayout.LayoutParams(-1,-2);
        captionParams.setMargins(0,dp(3),0,dp(6));
        sliderCard.addView(selectedLabel,captionParams);
        sliderCard.addView(slider,new LinearLayout.LayoutParams(-1,dp(46)));
        slider.setContentDescription("Частота проверки обновлений");
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            private int last=slider.getProgress();
            @Override public void onProgressChanged(SeekBar seek,int value,boolean fromUser){
                if(value==last)return;
                last=value;
                selectedLabel.animate().cancel();
                selectedLabel.setText(labels[value]);
                selectedLabel.setAlpha(.72f);
                selectedLabel.setScaleX(.98f);selectedLabel.setScaleY(.98f);
                selectedLabel.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(145).setInterpolator(new DecelerateInterpolator()).start();
                if(fromUser&&prefs().getBoolean("haptics",true))
                    seek.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
            @Override public void onStartTrackingTouch(SeekBar seek){}
            @Override public void onStopTrackingTouch(SeekBar seek){
                long newHours=hours[seek.getProgress()];
                prefs().edit().putLong(OtaUpdateSchedule.PREF_FREQUENCY,newHours).apply();
                OtaUpdateSchedule.configure(MainActivity.this);
            }
        });
        boolean enabled=prefs().getBoolean(OtaUpdateSchedule.PREF_BACKGROUND,false);
        frequency.setAlpha(enabled?1f:.45f);
        setEnabledChildren(frequency,enabled);
        backgroundCheckStatus=text("",12,muted,false);
        backgroundCheckStatus.setPadding(dp(7),dp(7),dp(7),dp(10));
        margin(parent,backgroundCheckStatus,5);
        updateBackgroundCheckStatus();
    }
    private void setEnabledChildren(View v,boolean enabled){
        v.setEnabled(enabled);
        if(v instanceof ViewGroup){
            ViewGroup group=(ViewGroup)v;
            for(int i=0;i<group.getChildCount();i++)
                setEnabledChildren(group.getChildAt(i),enabled);
        }
    }
    private String getAppVersion(){
        try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}
        catch(Exception e){return "—";}
    }
    private void consumeUpdateNotification(Intent intent){
        if(intent==null||!intent.getBooleanExtra("ota_open_from_notification",false))return;
        intent.removeExtra("ota_open_from_notification");
        updateNotificationLaunch=true;
        handler.postDelayed(()->{
            if(!isFinishing()&&!isDestroyed())checkForUpdates();
        },380L);
    }
    private void updateBackgroundCheckStatus(){
        if(backgroundCheckStatus==null)return;
        if(!prefs().getBoolean(OtaUpdateSchedule.PREF_BACKGROUND,false)){
            backgroundCheckStatus.setText("Фоновая проверка выключена");
            return;
        }
        String result=prefs().getString("ota_background_result","");
        long last=prefs().getLong("ota_last_background_attempt",0L);
        String lastText=last>0?android.text.format.DateFormat.format("dd.MM HH:mm",last).toString():"ещё не выполнялась";
        if(android.os.Build.VERSION.SDK_INT>=33&&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=
                PackageManager.PERMISSION_GRANTED){
            backgroundCheckStatus.setText("Последняя проверка: "+lastText+
                "\nУведомления запрещены в настройках Android");
        }else if(!result.isEmpty()){
            backgroundCheckStatus.setText("Последняя проверка: "+lastText+"\n"+result);
        }else{
            backgroundCheckStatus.setText("Ожидается первая проверка по расписанию");
        }
    }
    private void checkOtaQuietly(){
        if(isFinishing()||isDestroyed()||
            !prefs().getBoolean(OtaUpdateSchedule.PREF_ON_LAUNCH,true))return;
        long now=System.currentTimeMillis();
        long last=prefs().getLong("ota_last_startup_check",0L);
        if(last>0L&&now>=last&&now-last<60L*1000L)return;
        prefs().edit().putLong("ota_last_startup_check",now).apply();
        UpdateManager.check(this,UpdateManager.MANIFEST_URL,new UpdateManager.Callback(){
            @Override public void status(String message){}
            @Override public void release(UpdateManager.Release r,boolean newer){
                if(isFinishing()||isDestroyed())return;
                if(!newer){refreshHomeUpdateCard(false);offerStoredBackgroundUpdate();return;}
                prefs().edit().putLong(OtaUpdateSchedule.PREF_AVAILABLE_CODE,r.versionCode)
                    .putString(OtaUpdateSchedule.PREF_AVAILABLE_NAME,r.versionName).apply();
                refreshHomeUpdateCard(true);
                OtaProgressDialog offer=new OtaProgressDialog(MainActivity.this);
                offer.show();offer.offer(r.versionName,()->downloadAndInstallUpdate(r,offer));
            }
            @Override public void downloaded(java.io.File file){}
            @Override public void error(String message){}
        });
    }
    private void offerStoredBackgroundUpdate(){
        if(isFinishing()||isDestroyed())return;
        long code=prefs().getLong(OtaUpdateSchedule.PREF_AVAILABLE_CODE,0L);
        long prompted=prefs().getLong("ota_last_prompted_code",0L);
        long installed;
        try{installed=getPackageManager().getPackageInfo(getPackageName(),0).getLongVersionCode();}
        catch(Exception ignored){return;}
        if(code<=installed||code<=prompted)return;
        prefs().edit().putLong("ota_last_prompted_code",code).apply();
        String name=prefs().getString(OtaUpdateSchedule.PREF_AVAILABLE_NAME,"новая версия");
        OtaProgressDialog offer=new OtaProgressDialog(this);
        offer.show();offer.offer(name,()->{
            offer.dismiss();checkForUpdates();
        });
    }
    /** One-tap secure OTA, with a single themed dialog through all stages. */
    private void checkForUpdates(){
        if(isFinishing()||isDestroyed())return;
        OtaProgressDialog ui=new OtaProgressDialog(this);
        ui.show();
        beginUpdateCheck(ui);
    }
    private void beginUpdateCheck(OtaProgressDialog ui){
        ui.stage("Проверка соединения...","Подключение к серверу...");
        UpdateManager.check(this,UpdateManager.MANIFEST_URL,new UpdateManager.Callback(){
            @Override public void status(String message){
                if(!isFinishing()&&!isDestroyed())ui.stage(message,"Подключение к серверу...");
            }
            @Override public void release(UpdateManager.Release r,boolean newer){
                if(isFinishing()||isDestroyed())return;
                if(newer)downloadAndInstallUpdate(r,ui);
                else ui.current("Версия "+getAppVersion());
            }
            @Override public void downloaded(java.io.File file){}
            @Override public void error(String message){
                if(isFinishing()||isDestroyed())return;
                ui.fail(message,()->beginUpdateCheck(ui));
            }
        });
    }
    private void downloadAndInstallUpdate(UpdateManager.Release r){
        if(isFinishing()||isDestroyed())return;
        OtaProgressDialog ui=new OtaProgressDialog(this);
        ui.show();downloadAndInstallUpdate(r,ui);
    }
    private void downloadAndInstallUpdate(UpdateManager.Release r,OtaProgressDialog ui){
        ui.stage("Подготавливаем загрузку…","QR LINK "+r.versionName);
        UpdateManager.download(this,r,new UpdateManager.Callback(){
            @Override public void status(String message){
                if(!isFinishing()&&!isDestroyed())
                    ui.stage(message,"Проверяем безопасность обновления");
            }
            @Override public void progress(long bytes,long total){
                if(!isFinishing()&&!isDestroyed())ui.downloading(bytes,total);
            }
            @Override public void release(UpdateManager.Release ignore,boolean newer){}
            @Override public void downloaded(java.io.File file){
                if(isFinishing()||isDestroyed())return;
                ui.dismiss();
                UpdateManager.install(MainActivity.this);
            }
            @Override public void error(String message){
                if(isFinishing()||isDestroyed())return;
                ui.fail(message,()->downloadAndInstallUpdate(r,ui));
            }
        });
    }
        private void historyPage(){LinearLayout h=panel("Журнал передач","Локальная история. Не отправляется в интернет.","history");
        String log=getSharedPreferences("history",0).getString("log","");
        TextView t=text(log.isEmpty()?"Записей пока нет":log,12,fg,false);t.setTextIsSelectable(true);margin(h,t,18);
        outlineButton(h,"trash","Очистить журнал",()->{
            getSharedPreferences("history",0).edit().remove("log").apply();show();});
    }
    private void ftpPage(){
        LinearLayout c=panel("ShareMe · FTP","Дополнительный режим для ShareMe «Подключение к компьютеру». Адрес FTP выдаёт само ShareMe.","folder");
        ftpAddress=new EditText(this);ftpAddress.setSingleLine(true);ftpAddress.setHint("ftp://192.168.1.10:2121/");
        ftpAddress.setText(prefs().getString("ftp", ""));ftpAddress.setTextSize(14);ftpAddress.setTextColor(fg);ftpAddress.setHintTextColor(muted);
        ftpAddress.setBackground(shape(bg,stroke,12));ftpAddress.setPadding(dp(11),dp(12),dp(11),dp(12));margin(c,ftpAddress,12);
        button(c,"folder","ОТКРЫТЬ FTP",true,()->browseFtp("/"));
        ftpListLabel=text("Выберите папку или файл",13,muted,false);margin(c,ftpListLabel,9);
        outlineButton(c,"back","Вверх по каталогу",()->{String d=ftpDir;int at=d.lastIndexOf('/');browseFtp(at<=0?"/":d.substring(0,at));});
        LinearLayout list=column();c.addView(list);populateFtp(list);
    }
    private void populateFtp(LinearLayout list){list.removeAllViews();
        for(FtpBridge.Entry e:new ArrayList<>(ftpEntries)){
            outlineButton(list,e.directory?"folder":"file",e.name+(e.directory?"  /":"  ·  "+Util.readable(e.size)),()->{
                if(e.directory)browseFtp((ftpDir.equals("/")?"":ftpDir)+"/"+e.name);
                else new AlertDialog.Builder(this).setTitle("Скачать из ShareMe?").setMessage(e.name)
                    .setNegativeButton("Отмена",null).setPositiveButton("Скачать",(d,w)->{
                        Intent i=new Intent(this,TransferService.class);i.setAction(TransferService.FTP);
                        i.putExtra("ftp_url",prefs().getString("ftp",""));i.putExtra("ftp_dir",ftpDir);i.putExtra("ftp_name",e.name);launchService(i);
                    }).show();
            });
        }
    }
    private void browseFtp(String dir){String address=ftpAddress==null?prefs().getString("ftp",""):ftpAddress.getText().toString().trim();
        if(!address.startsWith("ftp://")){toast("Укажите FTP-адрес из ShareMe");return;}
        prefs().edit().putString("ftp",address).apply();
        if(ftpListLabel!=null)ftpListLabel.setText("Загрузка списка файлов...");
        new Thread(()->{try(FtpBridge client=new FtpBridge(this,address)){
            ArrayList<FtpBridge.Entry> entries=new ArrayList<>(client.list(dir));runOnUiThread(()->{
                ftpDir=dir;ftpEntries.clear();ftpEntries.addAll(entries);show();
            });
        }catch(Exception e){runOnUiThread(()->{if(ftpListLabel!=null)ftpListLabel.setText("Ошибка: "+e.getMessage());});}},"ftp-list").start();
    }
    private void updateQueue(){if(queueLabel==null||page!=SEND)return;
        ArrayList<FileCatalog.Item> jobs=FileCatalog.load(this);long size=0;int finished=0,failed=0;
        for(FileCatalog.Item f:jobs){size+=Math.max(0,f.size);if("Передан".equals(f.status))finished++;
            if(f.status.startsWith("Ошибка"))failed++;}
        queueLabel.setText(jobs.isEmpty()?"Очередь пуста":
            "Файлов: "+jobs.size()+"  ·  "+Util.readable(size)+"\nГотово: "+finished+"   Ошибки: "+failed);
    }
    private void updateDynamic(){
        if(status!=null){
            long done=Math.max(0,TransferService.progress), size=Math.max(0,TransferService.total);
            String active=TransferService.activeFile;
            String phase=TransferService.transferDirection;
            if(!active.equals(visibleFile)||size<=0||done<visibleBytes){
                visibleFile=active;visibleBytes=done;visibleRate=0;
            }else if(motion()&&done>visibleBytes){
                long gap=done-visibleBytes;
                visibleBytes+=Math.min(gap,Math.max(128L*1024L,(long)(gap*.33)));
            }else visibleBytes=done;
            long shown=Math.min(size,visibleBytes);
            status.setText(size>0&&done>=size?"Файл передан · соединение активно":
                size>0?("Приём".equals(phase)?"Получение в фоне · экран можно заблокировать":
                    "Отправка в фоне · экран можно заблокировать"):TransferService.status);
            int permille=size>0?(int)Math.min(1000,shown*1000d/size):0;
            if(progress!=null)progress.setProgress(permille);
            if(transferName!=null)
                transferName.setText(active.isEmpty()?"Ожидание передачи":active);
            if(transferPercent!=null)
                transferPercent.setText(size>0?(permille/10)+"%":"0%");
            if(transferSize!=null)
                transferSize.setText(Util.readable(shown)+" / "+Util.readable(size));
            if(transferRate!=null){
                double actualRate=Math.max(0,TransferService.bytesPerSecond);
                visibleRate=visibleRate<=0?actualRate:visibleRate*.78+actualRate*.22;
                if(visibleRate>0&&done<size){
                    long sec=(long)Math.ceil((size-done)/visibleRate);
                    transferRate.setText(Util.readable((long)visibleRate)+"/с"+
                        (sec>0&&sec<360000?" · "+(sec<60?sec+" с":(sec/60)+" мин"):""));
                }else transferRate.setText(size>0&&done>=size?"ГОТОВО":"");
            }
            if(progressText!=null)progressText.setText(TransferService.detail);
        }
        if(page==SEND){
            if(pairLabel!=null)pairLabel.setText(!TransferService.peerState.isEmpty()?TransferService.peerState:
                (paired()?"QR отсканирован · получатель найден":"Ожидаем сканирование QR-кода"));
            long now=Util.now();
            if(now-lastQueueUpdate>=1000){lastQueueUpdate=now;updateQueue();}
        }
        if(page==RECEIVE)renderQr();
    }
    private void renderQr(){if(qrImage==null||receiveHint==null)return;
        if(networkBadge!=null){
            networkBadge.setText(!WifiState.localTransferReady(this)?"WI-FI ВЫКЛЮЧЕН":
                TransferService.hotspotReady?"ТОЧКА ДОСТУПА ГОТОВА":
                TransferService.hotspotStarting?"СОЗДАЁМ ТОЧКУ ДОСТУПА":
                TransferService.directReady?"WI-FI DIRECT АКТИВЕН":
                !TransferService.systemApHost.isEmpty()?"СИСТЕМНАЯ ТОЧКА ДОСТУПА":
                !WifiState.radioOn(this)?"WI-FI ВЫКЛЮЧЕН":
                TransferService.directStarting?"СОЗДАЁМ WI-FI DIRECT...":
                WifiState.clientConnected(this)?"ОБЩАЯ СЕТЬ WI-FI":"ОЖИДАЕМ WI-FI DIRECT");
        }
        String key=TransferService.receiveKey;
        if(!WifiState.localTransferReady(this)){
            receiveHint.setText("Wi-Fi выключен");
            qrImage.setImageDrawable(null);lastQr="";
            receiveCaption.setText("Включите Wi-Fi. QR появится после создания соединения.");
            return;
        }
        if(!TransferService.isReceiving||key.isEmpty()){
            receiveHint.setText("Приём не запущен");qrImage.setImageDrawable(null);lastQr="";
            if(receiveCaption!=null)receiveCaption.setText("Нажмите «Получить» ещё раз. "+TransferService.detail);
            return;
        }
        boolean direct=TransferService.directReady;
        if(TransferService.directStarting||TransferService.hotspotStarting){
            receiveHint.setText(TransferService.directStarting?"Создаём Wi-Fi Direct...":"Создаём точку доступа...");
            qrImage.setImageDrawable(null);lastQr="";
            receiveCaption.setText("QR появится после подтверждения готовности сети.");return;
        }
        boolean externalAp=!TransferService.systemApHost.isEmpty();
        boolean localAp=TransferService.hotspotReady;
        String host=direct?TransferService.directHost:(localAp?TransferService.hotspotHost:
            externalAp?TransferService.systemApHost:Util.ip());
        if(localAp&&host.isEmpty()){
            receiveHint.setText("Ожидаем адрес локальной точки доступа...");
            qrImage.setImageDrawable(null);lastQr="";return;
        }
        if(externalAp&&!host.equals(WifiState.hostedApAddress(this))){
            receiveHint.setText("Системная точка доступа отключилась");
            qrImage.setImageDrawable(null);lastQr="";
            receiveCaption.setText("Включите точку доступа и обновите QR-код.");return;
        }
        if(!direct&&!externalAp&&!localAp&&(!WifiState.clientConnected(this)||
            "127.0.0.1".equals(host)||host.isEmpty())){
            receiveHint.setText("Локальная сеть не обнаружена");qrImage.setImageDrawable(null);lastQr="";
            receiveCaption.setText("Создание Wi-Fi Direct не завершилось. "+TransferService.detail+" Если Wi-Fi выключен, включите его, затем обновите QR.");return;
        }
        String value=direct?Pairing.buildDirect(host,key,TransferService.directMac,
            TransferService.directSsid,TransferService.directPass):localAp?
            Pairing.buildHotspot(host,key,TransferService.hotspotSsid,
                TransferService.hotspotPassword,TransferService.hotspotSecurity):
            Pairing.build(host,key);
        if(!value.equals(lastQr)){
            try{qrImage.setImageBitmap(makeQr(value));lastQr=value;
                if(motion()){
                    qrImage.setAlpha(.15f);qrImage.setScaleX(.92f);qrImage.setScaleY(.92f);
                    qrImage.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(245)
                        .setInterpolator(new OvershootInterpolator(.5f)).start();
                }
            }catch(Exception e){receiveHint.setText("Ошибка QR: "+e.getMessage());return;}
        }
        if(localAp){
            receiveHint.setText("Локальная точка доступа · готово");
            receiveCaption.setText("Отсканируйте QR на отправителе. При необходимости подтвердите подключение Android.");
        }else if(externalAp){
            receiveHint.setText("Системная точка доступа · приём готов");
            receiveCaption.setText("Подключите второй телефон к этой точке доступа через Android, затем отсканируйте QR LINK. Данные системной точки доступа защищены самой ОС.");
        }else if(direct){
            receiveHint.setText("Wi-Fi Direct · готово к подключению");
            receiveCaption.setText("Прямая группа «"+TransferService.directSsid+"» создана без интернета. Отправитель сканирует QR, при необходимости подтверждает соединение Android. Приём поддерживает блокировку экрана.");
        }else{
            receiveHint.setText("QR готов · общая сеть Wi-Fi");
            receiveCaption.setText("Покажите QR отправителю. Передача продолжится в фоне и при блокировке экрана.");
        }
    }
    private Bitmap makeQr(String value)throws Exception{
        BitMatrix bits=new com.google.zxing.qrcode.QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,600,600,
            Collections.singletonMap(EncodeHintType.MARGIN,2));
        int w=bits.getWidth(),h=bits.getHeight();int[]pix=new int[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)pix[y*w+x]=bits.get(x,y)?0xff101e28:0xffffffff;
        return Bitmap.createBitmap(pix,w,h,Bitmap.Config.ARGB_8888);
    }
    private void startScanner(){if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED){
            startActivityForResult(new Intent(this,QrScannerActivity.class),SCAN);
        }else requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION);
    }
    @Override public void onRequestPermissionsResult(int req,String[] perms,int[] result){super.onRequestPermissionsResult(req,perms,result);
        if(req==WIFI_SEND_PERMISSION){
            if(result.length>0&&result[0]==PackageManager.PERMISSION_GRANTED&&!pendingHotspotQr.isEmpty()){
                String scanned=pendingHotspotQr;pendingHotspotQr="";connectQr(scanned);
            }else{pendingHotspotQr="";toast("Для подключения по QR требуется разрешение Wi-Fi");}
        }
        if(req==WIFI_PERMISSION){
            if(result.length>0&&result[0]==PackageManager.PERMISSION_GRANTED)start(TransferService.RECEIVE);
            else toast("Разрешение на устройства поблизости нужно для Wi-Fi Direct");
        }
        if(req==CAMERA_PERMISSION){if(result.length>0&&result[0]==PackageManager.PERMISSION_GRANTED)startScanner();
            else new AlertDialog.Builder(this).setTitle("Доступ к камере")
                .setMessage("Для сканирования QR нужен доступ к камере. Также можно выбрать фотографию QR-кода из галереи.")
                .setPositiveButton("QR из галереи",(d,w)->startActivityForResult(new Intent(this,QrScannerActivity.class),SCAN))
                .setNegativeButton("Отмена",null).show();}
    }
    private void connectQr(String raw){final Pairing.Info info;
        try{info=Pairing.parse(raw);}catch(Exception e){toast("QR не подходит: "+e.getMessage());return;}
        if(pairLabel!=null)pairLabel.setText("Проверяем QR и соединение...");
        if(info.hotspot){
            if(!ensureWifiEnabled())return;
            String wifiPermission=Build.VERSION.SDK_INT>=33?Manifest.permission.NEARBY_WIFI_DEVICES:Manifest.permission.ACCESS_FINE_LOCATION;
            if(checkSelfPermission(wifiPermission)!=PackageManager.PERMISSION_GRANTED){
                pendingHotspotQr=raw;
                requestPermissions(new String[]{wifiPermission},WIFI_SEND_PERMISSION);
                return;
            }
            Intent i=new Intent(this,TransferService.class);i.setAction(TransferService.CONNECT);
            i.putExtra("qr",raw);launchService(i);
            toast("Соединяем через Wi-Fi Direct. Подтвердите запрос Android, если появится.");
            navigate(SEND);return;
        }
        if(!WifiState.clientConnected(this)&&WifiState.hostedApAddress(this).isEmpty()){
            toast("Для этого QR нужен доступ к общей локальной сети. Проверьте Wi-Fi.");
            return;
        }
        new Thread(()->{
            try{
                org.json.JSONObject check=new org.json.JSONObject(Util.http(info.host,info.port,info.key,"/hello","GET",null));
                if(!"XARKON LINK".equals(check.optString("app")))throw new Exception("На этом устройстве не запущен совместимый приёмник QR LINK");
                prefs().edit().putString("host",info.host).putInt("port",info.port).putString("key",info.key).apply();
                runOnUiThread(()->{navigate(SEND);toast("QR принят. Можно отправлять файлы.");});
            }catch(Exception e){runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Не удалось подключиться")
                .setMessage(e.getMessage()+"\n\nУбедитесь, что оба телефона подключены к одной сети и получатель показывает активный QR-код.")
                .setPositiveButton("Повторить",(d,w)->startScanner()).setNegativeButton("Закрыть",null).show());}
        },"qr-connect-check").start();
    }
    private void validatePaired(){if(!paired()){toast("Сначала отсканируйте QR-код");return;}
        String host=prefs().getString("host",""),key=prefs().getString("key","");int port=prefs().getInt("port",Util.PORT);
        new Thread(()->{try{Util.http(host,port,key,"/hello","GET",null);runOnUiThread(()->toast("Получатель доступен"));}
            catch(Exception e){runOnUiThread(()->toast("Получатель не отвечает: "+e.getMessage()));}},"qr-check").start();
    }
    private void pickFilesOrFolder(){
        startActivityForResult(new Intent(this,FileBrowserActivity.class),BROWSER);
    }
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);
        if(result!=RESULT_OK||data==null)return;
        if(req==SCAN){connectQr(data.getStringExtra("qr"));return;}
        if(req==BROWSER){updateQueue();
            if(paired())start(TransferService.SEND);
            else toast("Файлы добавлены. Отсканируйте QR получателя.");
            return;}
        try{
            if(req==FILES){ArrayList<FileCatalog.Item> files=new ArrayList<>();
                if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++){
                    Uri u=data.getClipData().getItemAt(i).getUri();persist(u);files.add(FileCatalog.single(this,u,""));}}
                else if(data.getData()!=null){Uri u=data.getData();persist(u);files.add(FileCatalog.single(this,u,""));}
                FileCatalog.append(this,files);toast("Добавлено: "+files.size());updateQueue();
            }else if(req==FOLDER){Uri folder=data.getData();persist(folder);new Thread(()->{
                try{ArrayList<FileCatalog.Item> all=FileCatalog.fromFolder(this,folder);FileCatalog.append(this,all);
                    runOnUiThread(()->{toast("Из папки добавлено: "+all.size());updateQueue();});
                }catch(Exception e){runOnUiThread(()->toast("Не удалось прочитать папку: "+e.getMessage()));}
            },"folder-indexer").start();}
        }catch(Exception e){toast("Ошибка выбора: "+e.getMessage());}
    }
    private void persist(Uri u){try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}}
    /** BROWSABLE URI allows compatible camera and QR-reader apps to open QR LINK directly. */
    private void handlePairLink(Intent intent){
        if(intent==null||!Intent.ACTION_VIEW.equals(intent.getAction()))return;
        android.net.Uri uri=intent.getData();
        if(uri==null||!"xarkonlink".equals(uri.getScheme())||
            !"pair".equals(uri.getHost()))return;
        intent.setData(null);intent.setAction(Intent.ACTION_MAIN);
        handler.post(()->connectQr(uri.toString()));
    }
    private void incomingShare(Intent i){if(i==null)return;String action=i.getAction();
        if(!Intent.ACTION_SEND.equals(action)&&!Intent.ACTION_SEND_MULTIPLE.equals(action))return;
        ArrayList<FileCatalog.Item> list=new ArrayList<>();
        if(Intent.ACTION_SEND.equals(action)){
            Uri u=i.getParcelableExtra(Intent.EXTRA_STREAM);if(u!=null)list.add(FileCatalog.single(this,u,""));
        }else{ArrayList<Uri> clips=i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if(clips!=null)for(Uri u:clips)list.add(FileCatalog.single(this,u,""));}
        if(!list.isEmpty()){FileCatalog.append(this,list);i.setAction(Intent.ACTION_MAIN);i.removeExtra(Intent.EXTRA_STREAM);page=SEND;show();toast("Файлы из «Поделиться» добавлены: "+list.size());}
    }
    private void shareExternal(){ArrayList<FileCatalog.Item> list=FileCatalog.load(this);if(list.isEmpty()){toast("Сначала выберите файлы");return;}
        ArrayList<Uri> uris=new ArrayList<>();for(FileCatalog.Item j:list)uris.add(Uri.parse(j.uri));
        Intent i=new Intent(uris.size()==1?Intent.ACTION_SEND:Intent.ACTION_SEND_MULTIPLE);
        i.setType("*/*");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if(uris.size()==1)i.putExtra(Intent.EXTRA_STREAM,uris.get(0));else i.putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris);
        try{startActivity(Intent.createChooser(i,"Передать через SHAREit / ShareMe"));}catch(Exception e){toast(e.getMessage());}
    }
    private boolean ensureWifiEnabled(){
        if(WifiState.localTransferReady(this))return true;
        toast("Включите Wi-Fi. Подключаться к интернету не требуется.");
        try{startActivity(new Intent(Settings.Panel.ACTION_WIFI));}
        catch(Exception e){
            try{startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));}
            catch(Exception ignored){toast("Откройте настройки Wi-Fi телефона");}
        }
        return false;
    }
    private void beginReceive(){
        if(!WifiState.clientConnected(this)&&WifiState.hostedApAddress(this).isEmpty()){
            android.net.wifi.WifiManager wifi=(android.net.wifi.WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
            if(wifi!=null&&!wifi.isWifiEnabled()){
                resumeReceiveAfterWifi=true;
                toast("Включите Wi-Fi. Подключаться к роутеру не требуется.");
                try{startActivity(new Intent(Settings.Panel.ACTION_WIFI));}
                catch(Exception e){startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));}
                return;
            }

            String perm=Build.VERSION.SDK_INT>=33?Manifest.permission.NEARBY_WIFI_DEVICES:Manifest.permission.ACCESS_FINE_LOCATION;
            if(checkSelfPermission(perm)!=PackageManager.PERMISSION_GRANTED){
                requestPermissions(new String[]{perm},WIFI_PERMISSION);return;
            }
        }
        start(TransferService.RECEIVE);
    }
    private void start(String action){
        Intent i=new Intent(this,TransferService.class);i.setAction(action);launchService(i);
        if(TransferService.RECEIVE.equals(action)&&Build.VERSION.SDK_INT>=33
                &&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
                &&!prefs().getBoolean("notification_requested",false)){
            prefs().edit().putBoolean("notification_requested",true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},20);
        }
    }
    private void launchService(Intent intent){try{
        if(TransferService.STOP.equals(intent.getAction()))startService(intent);else startForegroundService(intent);
    }catch(Exception e){toast("Служба: "+e.getMessage());}}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
}
