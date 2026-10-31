package dev.xarkon.link;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.*;
import java.util.*;

public final class FileCatalog {
    public static final String PREF="xarkon_queue";
    private FileCatalog(){}
    // Queue metadata is a JSON snapshot. Per-file status changes must not
    // deserialize and rewrite thousands of unrelated entries after every file.
    // A small SQLite table keeps each status update durable and O(log n).
    private static StatusStore statusStore;
    private static final class StatusStore extends SQLiteOpenHelper {
        StatusStore(Context c){super(c.getApplicationContext(),"qr_link_status.db",null,1);}
        @Override public void onCreate(SQLiteDatabase db){
            db.execSQL("CREATE TABLE item_status (id TEXT PRIMARY KEY NOT NULL, status TEXT NOT NULL)");
        }
        @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){
            db.execSQL("DROP TABLE IF EXISTS item_status");onCreate(db);
        }
    }
    private static SQLiteDatabase statusDb(Context c){
        if(statusStore==null)statusStore=new StatusStore(c);
        return statusStore.getWritableDatabase();
    }
    public static class Item {
        public String id,uri,name,mime,path,status;
        public long size;
        public JSONObject json()throws JSONException{
            JSONObject j=new JSONObject();j.put("id",id).put("uri",uri).put("name",name).put("mime",mime)
               .put("path",path).put("status",status).put("size",size);return j;
        }
        public static Item from(JSONObject j){
            Item x=new Item();x.id=j.optString("id");x.uri=j.optString("uri");
            x.name=j.optString("name");x.mime=j.optString("mime","application/octet-stream");
            x.path=j.optString("path");x.status=j.optString("status","В очереди");x.size=j.optLong("size",-1);return x;
        }
    }
    public static synchronized ArrayList<Item> load(Context c){
        ArrayList<Item> out=new ArrayList<>();
        try{JSONArray j=new JSONArray(c.getSharedPreferences(PREF,0).getString("list","[]"));
            for(int i=0;i<j.length();i++)out.add(Item.from(j.getJSONObject(i)));
        }catch(Exception ignored){}
        if(out.isEmpty())return out;
        try{
            HashMap<String,Item> byId=new HashMap<>();
            for(Item item:out)byId.put(item.id,item);
            try(Cursor cursor=statusDb(c).rawQuery("SELECT id,status FROM item_status",null)){
                while(cursor.moveToNext()){
                    Item item=byId.get(cursor.getString(0));
                    if(item!=null)item.status=cursor.getString(1);
                }
            }
        }catch(Exception ignored){
            // Older JSON snapshots remain readable if the status DB is unavailable.
        }
        return out;
    }
    public static synchronized void save(Context c,List<Item> items){
        saveSnapshot(c,items,true);
    }
    private static void saveSnapshot(Context c,List<Item> items,boolean mergePendingStatuses){
        // A caller may hold a stale queue snapshot while the transfer service
        // has written newer statuses to SQLite. Merge them BEFORE deleting the
        // status journal, otherwise a queue edit can revert "Передан" to "В очереди".
        if(mergePendingStatuses&&!items.isEmpty())try{
            HashMap<String,Item> byId=new HashMap<>();
            for(Item item:items)if(item.id!=null)byId.put(item.id,item);
            try(Cursor cursor=statusDb(c).rawQuery("SELECT id,status FROM item_status",null)){
                while(cursor.moveToNext()){
                    Item item=byId.get(cursor.getString(0));
                    if(item!=null)item.status=cursor.getString(1);
                }
            }
        }catch(Exception ignored){
            // Preserve the supplied queue snapshot if the status DB is unavailable.
        }
        JSONArray a=new JSONArray();try{for(Item it:items)a.put(it.json());}catch(Exception ignored){}
        // Never discard the status journal unless the merged snapshot is durable.
        boolean saved=c.getSharedPreferences(PREF,0).edit().putString("list",a.toString()).commit();
        if(saved)try{statusDb(c).delete("item_status",null,null);}catch(Exception ignored){}
    }
    public static synchronized void append(Context c,List<Item> items){ArrayList<Item> all=load(c);all.addAll(items);save(c,all);}
    public static synchronized void setStatus(Context c,String id,String status){
        if(id==null||id.isEmpty())return;
        try{
            ContentValues values=new ContentValues();
            values.put("id",id);values.put("status",status);
            if(statusDb(c).insertWithOnConflict("item_status",null,values,
                    SQLiteDatabase.CONFLICT_REPLACE)!=-1)return;
        }catch(Exception ignored){}
        // Conservative fallback: retain the original JSON status path if
        // SQLite is temporarily unavailable. No transfer result is discarded.
        ArrayList<Item> all=load(c);
        for(Item it:all)if(id.equals(it.id)){it.status=status;break;}
        // The fallback has explicitly updated this status. Do not overlay a
        // stale SQLite row if the database has become read-only.
        saveSnapshot(c,all,false);
    }
    public static Item single(Context c,Uri uri,String path){
        Item it=new Item();it.id=Util.id();it.uri=uri.toString();it.name=Util.name(c,uri);
        it.size=Util.size(c,uri);it.mime=Util.mime(c,uri);it.path=Util.safePath(path);it.status="В очереди";return it;
    }
    public static ArrayList<Item> fromDocumentFolder(Context c,Uri tree,String docId,String name)throws Exception{
        ArrayList<Item> result=new ArrayList<>();
        if(tree==null||docId==null||docId.isEmpty())throw new IllegalArgumentException("Папка недоступна");
        collect(c,tree,docId,Util.safePath(name),result,0);
        return result;
    }
    public static ArrayList<Item> fromFolder(Context c,Uri tree)throws Exception{
        ArrayList<Item> result=new ArrayList<>();
        String id=DocumentsContract.getTreeDocumentId(tree);
        Uri base=DocumentsContract.buildDocumentUriUsingTree(tree,id);
        String root=Util.name(c,base);
        collect(c,tree,id,root,result,0);
        return result;
    }
    private static void collect(Context c,Uri tree,String docId,String path,ArrayList<Item> result,int depth)throws Exception{
        if(depth>24||result.size()>12000)throw new IllegalStateException("Папка слишком велика: максимум 12000 файлов / 24 уровня");
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,docId);
        String[]cols={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE};
        try(Cursor cursor=c.getContentResolver().query(children,cols,null,null,null)){
            if(cursor==null)throw new IllegalStateException("Каталог недоступен");
            while(cursor.moveToNext()){
                String childId=cursor.getString(0),name=Util.safeName(cursor.getString(1)),mime=cursor.getString(2);
                if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime))collect(c,tree,childId,path+"/"+name,result,depth+1);
                else {
                    Uri u=DocumentsContract.buildDocumentUriUsingTree(tree,childId);
                    // The directory cursor already contains all metadata. Avoid
                    // three extra ContentResolver queries per file (name/size/MIME).
                    Item it=new Item();
                    it.id=Util.id();it.uri=u.toString();it.name=name;
                    it.mime=mime==null||mime.isEmpty()?"application/octet-stream":mime;
                    it.path=path;it.status="В очереди";
                    it.size=cursor.isNull(3)?-1:cursor.getLong(3);
                    result.add(it);
                    if(result.size()>12000)throw new IllegalStateException("Больше 12000 файлов");
                }
            }
        }
    }
}
