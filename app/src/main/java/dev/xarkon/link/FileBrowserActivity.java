package dev.xarkon.link;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import android.util.Size;
import java.lang.ref.WeakReference;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** Permission-aware in-app browser. SAF is used only to grant access to external folders. */
public final class FileBrowserActivity extends Activity {
    private static final String[] TABS={"Картинки","Видео","Музыка","Приложения","Файлы"};
    private static final int PICK_TREE=501,MEDIA_PERMISSION=502,MAX_QUEUE=5000,MAX_VISIBLE=220;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService previews=Executors.newFixedThreadPool(2);
    private final LruCache<String,Bitmap> previewCache=new LruCache<String,Bitmap>(8*1024*1024){
        @Override protected int sizeOf(String key,Bitmap image){return image.getByteCount();}
    };
    // Accessed on the UI thread. Weak references never retain off-screen rows.
    private final HashMap<String,ArrayList<WeakReference<ImageView>>> previewWaiters=new HashMap<>();
    private final LinkedHashMap<String,Entry> selected=new LinkedHashMap<>();
    private final ArrayList<Entry> current=new ArrayList<>();
    private final ArrayDeque<Location> history=new ArrayDeque<>();
    private final HashSet<Integer> permissionPrompted=new HashSet<>();
    private LinearLayout list,tabs,root;
    private TextView title,subtitle,count,action,sortButton,empty;
    private EditText search;
    private int accent,bg,card,fg,muted,stroke,tab=0,serial=0,sort=0;
    private String mediaPath="",folderPath="",folderId="";
    private Uri tree;
    private boolean busy=false;
    private final Handler ui=new Handler(Looper.getMainLooper());

    private static final class Entry {
        Uri uri,tree;
        String name="",mime="",path="",docId="",group="";
        long size=-1,date=0;
        boolean folder=false,mediaFolder=false;
        String key(){return (folder?"dir:":"file:")+(tree==null?"":tree.toString()+"|")+
            (mediaFolder?group+"|"+path:docId.isEmpty()?uri.toString():docId);}
    }
    private static final class Location {
        Uri tree;String docId,path,media;
        Location(Uri t,String d,String p,String m){tree=t;docId=d;path=p;media=m;}
    }
    private int dp(float value){return (int)(getResources().getDisplayMetrics().density*value+.5f);}
    private GradientDrawable shape(int color,int border,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));
        if(border!=0)d.setStroke(dp(1),border);return d;
    }
    private TextView label(String s,int sp,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextColor(color);t.setTextSize(sp);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
    }
    private void pad(View v,int x,int y){v.setPadding(dp(x),dp(y),dp(x),dp(y));}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(1);return v;}
    private void theme(){
        android.content.SharedPreferences p=getSharedPreferences("settings",0);
        accent=p.getInt("accent",0xff55dfcb);
        String mode=p.getString("theme_mode",p.getBoolean("light",false)?"light":"dark");
        boolean light="light".equals(mode),amoled="amoled".equals(mode);
        bg=amoled?Color.BLACK:light?0xfff5f7fb:0xff0a111b;
        card=amoled?0xff0b0b0d:light?Color.WHITE:0xff121e2a;
        fg=light?0xff152536:0xfff1f6fb;
        muted=light?0xff607587:0xff9caebd;
        stroke=amoled?0xff27282e:light?0xffdce5ed:0xff283947;
        getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg);
        getWindow().getDecorView().setSystemUiVisibility(light?
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0);
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);theme();buildUi();switchTab(0);
    }
    private void buildUi(){
        root=column();root.setBackgroundColor(bg);setContentView(root);
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);pad(top,16,14);
        TextView back=label("‹",35,accent,true);back.setGravity(Gravity.CENTER);
        top.addView(back,new LinearLayout.LayoutParams(dp(42),dp(44)));
        back.setOnClickListener(v->onBackPressed());
        LinearLayout heads=column();title=label("ПРОВОДНИК",20,fg,true);
        subtitle=label("Выберите файлы для передачи",12,muted,false);
        heads.addView(title);heads.addView(subtitle);
        top.addView(heads,new LinearLayout.LayoutParams(0,-2,1));root.addView(top);
        HorizontalScrollView horizontal=new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        tabs=new LinearLayout(this);tabs.setPadding(dp(12),dp(5),dp(12),dp(12));
        horizontal.addView(tabs);root.addView(horizontal);
        LinearLayout tools=new LinearLayout(this);tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(dp(16),dp(3),dp(16),dp(10));
        search=new EditText(this);search.setSingleLine(true);search.setTextSize(14);
        search.setHint("Поиск в текущей папке");search.setHintTextColor(muted);
        search.setTextColor(fg);search.setBackground(shape(card,stroke,14));
        search.setCompoundDrawablePadding(dp(8));pad(search,12,10);
        tools.addView(search,new LinearLayout.LayoutParams(0,dp(48),1));
        sortButton=label("↕",22,accent,true);sortButton.setGravity(Gravity.CENTER);
        tools.addView(sortButton,new LinearLayout.LayoutParams(dp(52),dp(48)));
        sortButton.setOnClickListener(v->{sort=(sort+1)%3;render();Toast.makeText(this,
            sort==0?"По имени":sort==1?"По размеру":"По дате",Toast.LENGTH_SHORT).show();});
        root.addView(tools);
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int c,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){render();}
            public void afterTextChanged(Editable e){}
        });
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        list=column();list.setPadding(dp(16),dp(3),dp(16),dp(20));
        scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=column();pad(footer,16,12);
        footer.setBackground(shape(card,stroke,16));
        count=label("Ничего не выбрано",13,muted,false);footer.addView(count);
        action=label("ДОБАВИТЬ В ОЧЕРЕДЬ",15,Color.BLACK,true);
        action.setGravity(Gravity.CENTER);pad(action,12,15);
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(52));
        ap.topMargin=dp(9);footer.addView(action,ap);
        action.setOnClickListener(v->commitSelection());
        LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-1,-2);
        fp.setMargins(dp(12),dp(4),dp(12),dp(10));root.addView(footer,fp);
        updateCount();
    }
    private void updateTabs(){
        tabs.removeAllViews();
        for(int i=0;i<TABS.length;i++){
            final int index=i;TextView chip=label(TABS[i],13,i==tab?Color.BLACK:fg,true);
            chip.setGravity(Gravity.CENTER);pad(chip,16,12);
            chip.setBackground(shape(i==tab?accent:card,i==tab?0:stroke,14));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);
            lp.rightMargin=dp(8);tabs.addView(chip,lp);
            chip.setOnClickListener(v->switchTab(index));
        }
    }
    private void switchTab(int index){
        tab=index;tree=null;folderId="";folderPath="";mediaPath="";history.clear();
        search.setText("");updateTabs();load();
    }
    private boolean isMedia(){return tab<3;}
    private Uri collection(int category){
        if(category==0)return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        if(category==1)return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        return MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
    }
    private String permission(){
        if(Build.VERSION.SDK_INT>=33){
            return tab==0?Manifest.permission.READ_MEDIA_IMAGES:
                tab==1?Manifest.permission.READ_MEDIA_VIDEO:Manifest.permission.READ_MEDIA_AUDIO;
        }
        return Manifest.permission.READ_EXTERNAL_STORAGE;
    }
    private boolean hasMediaAccess(){
        if(checkSelfPermission(permission())==PackageManager.PERMISSION_GRANTED)return true;
        return Build.VERSION.SDK_INT>=34&&tab<2&&
            checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)==PackageManager.PERMISSION_GRANTED;
    }
    private void requestMedia(){
        if(!isMedia()||hasMediaAccess())return;
        if(Build.VERSION.SDK_INT>=34&&tab<2)
            requestPermissions(new String[]{permission(),Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED},MEDIA_PERMISSION);
        else requestPermissions(new String[]{permission()},MEDIA_PERMISSION);
    }
    @Override public void onRequestPermissionsResult(int req,String[] p,int[] grants){
        super.onRequestPermissionsResult(req,p,grants);
        if(req==MEDIA_PERMISSION)load();
    }
    private void showMessage(String message,boolean grant){
        list.removeAllViews();
        TextView m=label(message,14,muted,false);m.setGravity(Gravity.CENTER);
        pad(m,15,32);list.addView(m);
        if(grant){
            addWide("РАЗРЕШИТЬ ДОСТУП",this::requestMedia);
            addWide("ВЫБРАТЬ ПАПКУ",this::pickTree);
        }
    }
    private void addWide(String text,Runnable action){
        TextView b=label(text,13,accent,true);b.setGravity(Gravity.CENTER);pad(b,15,15);
        b.setBackground(shape(card,stroke,14));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(8);
        list.addView(b,lp);b.setOnClickListener(v->action.run());
    }
    private void load(){
        int token=++serial;
        subtitle.setText(isMedia()?TABS[tab]+(mediaPath.isEmpty()?"":" · "+mediaPath):
            tab==3?"Установленные приложения":
            tree==null?"Разрешённые папки":"Папка · "+folderPath);
        if(isMedia()&&!hasMediaAccess()){
            showMessage("Для просмотра медиа требуется разрешение Android.\nМожно также выбрать папку вручную.",true);
            if(permissionPrompted.add(tab))requestMedia();
            return;
        }
        showMessage("Загрузка файлов…",false);
        final int cat=tab;final Uri rootTree=tree;final String doc=folderId;
        final String mediaFolder=mediaPath;
        io.execute(()->{
            try{
                ArrayList<Entry> result=cat<3?queryMedia(cat,mediaFolder,false):cat==3?queryApps():
                    queryDocuments(rootTree,doc);
                ui.post(()->{if(token!=serial||isFinishing())return;
                    current.clear();current.addAll(result);render();});
            }catch(Exception ex){
                ui.post(()->{if(token==serial&&!isFinishing())
                    showMessage("Не удалось открыть каталог: "+ex.getMessage(),false);});
            }
        });
    }
    /**
     * A folder query must not silently drop older files because the top-level
     * gallery only indexes its newest MAX_QUEUE entries. When a whole folder is
     * selected, reject oversized folders rather than sending an incomplete set.
     */
    private ArrayList<Entry> queryMedia(int cat,String prefix,boolean complete){
        ArrayList<Entry> result=new ArrayList<>();
        Uri base=collection(cat);
        String[] cols={MediaStore.MediaColumns._ID,MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,MediaStore.MediaColumns.RELATIVE_PATH};
        String selection=prefix.isEmpty()?null:MediaStore.MediaColumns.RELATIVE_PATH+" LIKE ?";
        String[] args=prefix.isEmpty()?null:new String[]{prefix+"%"};
        try(Cursor c=getContentResolver().query(base,cols,selection,args,
                MediaStore.MediaColumns.DATE_MODIFIED+" DESC")){
            if(c==null)throw new IllegalStateException("Медиатека недоступна");
            while(c.moveToNext()){
                String path=c.isNull(5)?"":c.getString(5);
                // Check the actual path too: SQL LIKE treats % and _ as wildcards.
                if(!path.startsWith(prefix))continue;
                if(result.size()>=MAX_QUEUE){
                    if(complete)throw new IllegalStateException(
                        "В папке больше 5000 файлов. Выберите меньшую папку или отдельные файлы.");
                    break;
                }
                Entry e=new Entry();e.uri=ContentUris.withAppendedId(base,c.getLong(0));
                e.name=Util.safeName(c.getString(1));e.size=c.isNull(2)?-1:c.getLong(2);
                e.date=c.isNull(3)?0:c.getLong(3);
                e.mime=c.getString(4);e.path=path;
                result.add(e);
            }
        }
        return result;
    }
    private ArrayList<Entry> queryApps(){
        PackageManager pm=getPackageManager();
        Intent launch=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos=pm.queryIntentActivities(launch,0);
        LinkedHashSet<String> seen=new LinkedHashSet<>();
        ArrayList<Entry> result=new ArrayList<>();
        for(ResolveInfo info:infos){
            if(info.activityInfo==null)continue;
            String pkg=info.activityInfo.packageName;
            if(!seen.add(pkg))continue;
            try{
                ApplicationInfo app=pm.getApplicationInfo(pkg,0);
                File apk=new File(app.publicSourceDir);
                if(!apk.isFile())continue;
                Entry e=new Entry();e.name=Util.safeName(pm.getApplicationLabel(app).toString())+".apk";
                e.uri=Uri.parse("content://dev.xarkon.link.updates/installed/"+Uri.encode(pkg));
                e.mime="application/vnd.android.package-archive";
                e.size=apk.length();e.date=apk.lastModified()/1000;
                result.add(e);
            }catch(Exception ignored){}
        }
        return result;
    }
    private ArrayList<Entry> queryDocuments(Uri rootTree,String doc){
        ArrayList<Entry> result=new ArrayList<>();
        if(rootTree==null){
            for(UriPermission perm:getContentResolver().getPersistedUriPermissions()){
                if(!perm.isReadPermission())continue;
                Uri u=perm.getUri();
                try{
                    String id=DocumentsContract.getTreeDocumentId(u);
                    Uri d=DocumentsContract.buildDocumentUriUsingTree(u,id);
                    Entry e=new Entry();e.folder=true;e.tree=u;e.docId=id;
                    e.name=Util.name(this,d);e.uri=d;result.add(e);
                }catch(Exception ignored){}
            }
            return result;
        }
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(rootTree,doc);
        String[] cols={DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        try(Cursor c=getContentResolver().query(children,cols,null,null,null)){
            if(c==null)throw new IllegalStateException("Папка недоступна");
            int limit=0;
            while(c.moveToNext()&&limit++<MAX_QUEUE){
                Entry e=new Entry();e.docId=c.getString(0);e.name=Util.safeName(c.getString(1));
                e.mime=c.getString(2);e.folder=DocumentsContract.Document.MIME_TYPE_DIR.equals(e.mime);
                e.size=c.isNull(3)?-1:c.getLong(3);e.date=c.isNull(4)?0:c.getLong(4)/1000;
                e.tree=rootTree;e.uri=DocumentsContract.buildDocumentUriUsingTree(rootTree,e.docId);
                e.path=folderPath;result.add(e);
            }
        }
        return result;
    }
    private void render(){
        if(list==null)return;
        list.removeAllViews();
        if(tab==4&&tree==null){
            addWide("+ ОТКРЫТЬ ПАПКУ ЧЕРЕЗ ANDROID",this::pickTree);
            TextView hint=label("Разрешение выдаётся Android один раз. Затем содержимое открывается здесь, в собственном проводнике.",12,muted,false);
            pad(hint,6,8);list.addView(hint);
        }
        ArrayList<Entry> rows=new ArrayList<>();
        if(isMedia()){
            String prefix=mediaPath;LinkedHashMap<String,Entry> folders=new LinkedHashMap<>();
            for(Entry e:current){
                if(!e.path.startsWith(prefix))continue;
                String remain=e.path.substring(prefix.length());
                if(!remain.isEmpty()){
                    String segment=remain.split("/",2)[0];
                    if(segment.isEmpty())continue;
                    String path=prefix+segment+"/";
                    if(!folders.containsKey(path)){
                        Entry f=new Entry();f.folder=true;f.mediaFolder=true;f.group=TABS[tab];
                        f.path=path;f.name=segment;folders.put(path,f);
                    }
                }else rows.add(e);
            }
            rows.addAll(0,folders.values());
        }else rows.addAll(current);
        String filter=search.getText().toString().trim().toLowerCase(Locale.ROOT);
        if(!filter.isEmpty())rows.removeIf(e->!e.name.toLowerCase(Locale.ROOT).contains(filter));
        Comparator<Entry> cmp=(a,b)->{
            if(a.folder!=b.folder)return a.folder?-1:1;
            if(sort==1)return Long.compare(b.size,a.size);
            if(sort==2)return Long.compare(b.date,a.date);
            return a.name.compareToIgnoreCase(b.name);
        };
        Collections.sort(rows,cmp);
        if(rows.isEmpty()){
            TextView nothing=label(tab==4&&tree==null?"Откройте папку, чтобы увидеть файлы":
                "В этом разделе пока нет файлов",14,muted,false);
            nothing.setGravity(Gravity.CENTER);pad(nothing,8,35);list.addView(nothing);
        }
        int shown=0;
        for(Entry e:rows){
            if(shown++>=MAX_VISIBLE)break;
            drawRow(e);
        }
        if(rows.size()>MAX_VISIBLE){
            TextView more=label("Показано "+MAX_VISIBLE+" из "+rows.size()+". Уточните поиск для остальных.",12,muted,false);
            pad(more,12,15);list.addView(more);
        }
        if(isMedia()&&mediaPath.isEmpty()&&current.size()>=MAX_QUEUE){
            TextView cap=label("Показаны последние 5000 файлов. Для более старых используйте выбор папки.",12,muted,false);
            pad(cap,10,12);list.addView(cap);
        }
    }
    private void drawRow(Entry e){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12),dp(11),dp(9),dp(11));
        row.setBackground(shape(card,stroke,14));
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.bottomMargin=dp(8);
        list.addView(row,rp);
        String glyph=e.folder?"folder":tab==0?"image":tab==1?"video":tab==2?"music":tab==3?"apps":"file";
        UiIcon icon=new UiIcon(this,glyph,accent);
        if(!e.folder&&(tab==0||tab==1||tab==3))addPreview(row,e,icon,tab);
        else row.addView(icon,new LinearLayout.LayoutParams(dp(36),dp(36)));
        LinearLayout detail=column();detail.setPadding(dp(10),0,dp(4),0);
        TextView name=label(e.name,14,fg,true);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);
        detail.addView(name);
        String sub=e.folder?"Папка · нажмите, чтобы открыть":
            Util.readable(e.size)+(e.mime==null||e.mime.isEmpty()?"":" · "+e.mime);
        TextView meta=label(sub,11,muted,false);meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);detail.addView(meta);
        row.addView(detail,new LinearLayout.LayoutParams(0,-2,1));
        boolean checked=selected.containsKey(e.key());
        TextView check=label(checked?"✓":"＋",22,checked?Color.BLACK:accent,true);
        check.setGravity(Gravity.CENTER);check.setBackground(shape(checked?accent:bg,checked?0:stroke,11));
        row.addView(check,new LinearLayout.LayoutParams(dp(39),dp(39)));
        check.setOnClickListener(v->toggle(e));
        row.setOnClickListener(v->{if(e.folder)open(e);else toggle(e);});
        row.setOnLongClickListener(v->{toggle(e);return true;});
    }
    /** Asynchronous, bounded-memory thumbnails; never decode images on the UI thread. */
    private void addPreview(LinearLayout row,Entry entry,UiIcon placeholder,int category){
        FrameLayout frame=new FrameLayout(this);
        frame.setBackground(shape(bg,stroke,11));
        frame.setClipToOutline(true);
        row.addView(frame,new LinearLayout.LayoutParams(dp(40),dp(40)));
        frame.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));
        ImageView image=new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setVisibility(View.GONE);
        frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
        String key=entry.uri.toString();
        Bitmap cached=previewCache.get(key);
        if(cached!=null){
            image.setImageBitmap(cached);image.setVisibility(View.VISIBLE);return;
        }
        ArrayList<WeakReference<ImageView>> waiting=previewWaiters.get(key);
        if(waiting==null){
            waiting=new ArrayList<>();previewWaiters.put(key,waiting);
            final Uri uri=entry.uri;
            previews.execute(()->{
                Bitmap bitmap=null;
                try{
                    if(category==3){
                        Drawable icon=getPackageManager().getApplicationIcon(uri.getLastPathSegment());
                        int side=dp(72);
                        bitmap=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888);
                        Canvas canvas=new Canvas(bitmap);
                        icon.setBounds(0,0,side,side);icon.draw(canvas);
                    }else{
                        bitmap=getContentResolver().loadThumbnail(uri,new Size(dp(80),dp(80)),null);
                    }
                }catch(Exception ignored){
                    // Keep the category icon when Android denies a thumbnail.
                }
                final Bitmap ready=bitmap;
                ui.post(()->{
                    if(isDestroyed())return;
                    if(ready!=null)previewCache.put(key,ready);
                    ArrayList<WeakReference<ImageView>> listeners=previewWaiters.remove(key);
                    if(listeners==null||ready==null)return;
                    for(WeakReference<ImageView> ref:listeners){
                        ImageView view=ref.get();
                        if(view!=null&&view.isAttachedToWindow()){
                            view.setImageBitmap(ready);view.setVisibility(View.VISIBLE);
                        }
                    }
                });
            });
        }
        waiting.add(new WeakReference<>(image));
    }
    private void toggle(Entry e){
        String key=e.key();
        if(selected.containsKey(key))selected.remove(key);
        else selected.put(key,e);
        updateCount();render();
    }
    private void updateCount(){
        if(count==null)return;
        count.setText(selected.isEmpty()?"Ничего не выбрано":
            "Выбрано: "+selected.size()+" · файлы и папки");
        action.setAlpha(selected.isEmpty()||busy?.42f:1f);
        action.setBackground(shape(accent,0,14));
    }
    private void open(Entry e){
        history.push(new Location(tree,folderId,folderPath,mediaPath));
        if(e.mediaFolder)mediaPath=e.path;
        else{tree=e.tree;folderId=e.docId;folderPath=
            folderPath.isEmpty()?e.name:folderPath+"/"+e.name;}
        search.setText("");load();
    }
    @Override public void onBackPressed(){
        if(!history.isEmpty()){
            Location l=history.pop();tree=l.tree;folderId=l.docId;folderPath=l.path;mediaPath=l.media;
            search.setText("");load();return;
        }
        super.onBackPressed();
    }
    private void pickTree(){
        try{
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent,PICK_TREE);
        }catch(Exception e){Toast.makeText(this,"Выбор папки недоступен: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=PICK_TREE||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        try{
            getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            tab=4;tree=null;folderId="";folderPath="";mediaPath="";history.clear();
            updateTabs();load();
        }catch(Exception e){Toast.makeText(this,"Нет доступа к папке: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private void commitSelection(){
        if(busy||selected.isEmpty())return;
        busy=true;updateCount();action.setText("ПОДГОТОВКА ФАЙЛОВ…");
        ArrayList<Entry> items=new ArrayList<>(selected.values());
        io.execute(()->{
            try{
                ArrayList<FileCatalog.Item> jobs=new ArrayList<>();
                HashSet<String> used=new HashSet<>();
                HashMap<String,ArrayList<Entry>> media=new HashMap<>();
                for(Entry e:items){
                    if(e.mediaFolder&&!media.containsKey(e.group))
                        media.put(e.group,queryMedia(Arrays.asList(TABS).indexOf(e.group),e.path,true));
                }
                for(Entry e:items){
                    if(e.folder){
                        if(e.mediaFolder){
                            for(Entry child:media.get(e.group))
                                if(child.path.startsWith(e.path))addJob(jobs,used,child,child.path);
                        }else{
                            ArrayList<FileCatalog.Item> folderJobs=FileCatalog.fromDocumentFolder(
                                this,e.tree,e.docId,e.name);
                            for(FileCatalog.Item f:folderJobs)
                                if(used.add(f.uri))jobs.add(f);
                        }
                    }else addJob(jobs,used,e,e.path);
                    if(jobs.size()>MAX_QUEUE)throw new IllegalStateException("Не более 5000 файлов в одной операции");
                }
                if(jobs.isEmpty())throw new IllegalStateException("В выбранных папках нет доступных файлов");
                FileCatalog.append(this,jobs);
                ui.post(()->{setResult(RESULT_OK,new Intent());finish();});
            }catch(Exception e){
                ui.post(()->{busy=false;action.setText("ДОБАВИТЬ В ОЧЕРЕДЬ");
                    updateCount();Toast.makeText(this,"Ошибка: "+e.getMessage(),Toast.LENGTH_LONG).show();});
            }
        });
    }
    private void addJob(ArrayList<FileCatalog.Item> jobs,Set<String> used,Entry e,String path){
        if(e.uri==null||!used.add(e.uri.toString()))return;
        FileCatalog.Item it=new FileCatalog.Item();
        it.id=Util.id();it.uri=e.uri.toString();it.name=Util.safeName(e.name);
        it.mime=e.mime==null||e.mime.isEmpty()?"application/octet-stream":e.mime;
        it.size=e.size;it.path=Util.safePath(path);it.status="В очереди";
        jobs.add(it);
    }
    @Override protected void onDestroy(){
        ++serial;io.shutdownNow();previews.shutdownNow();previewWaiters.clear();
        previewCache.evictAll();super.onDestroy();
    }
}
