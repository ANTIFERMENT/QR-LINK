package dev.xarkon.link;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.*;
import java.util.*;

public final class FileCatalog {
    public static final String PREF="xarkon_queue";
    private FileCatalog(){}
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
        return out;
    }
    public static synchronized void save(Context c,List<Item> items){
        JSONArray a=new JSONArray();try{for(Item it:items)a.put(it.json());}catch(Exception ignored){}
        c.getSharedPreferences(PREF,0).edit().putString("list",a.toString()).commit();
    }
    public static synchronized void append(Context c,List<Item> items){ArrayList<Item> all=load(c);all.addAll(items);save(c,all);}
    public static synchronized void setStatus(Context c,String id,String status){
        ArrayList<Item> all=load(c);for(Item it:all){if(it.id.equals(id)){it.status=status;break;}}save(c,all);
    }
    public static Item single(Context c,Uri uri,String path){
        Item it=new Item();it.id=Util.id();it.uri=uri.toString();it.name=Util.name(c,uri);
        it.size=Util.size(c,uri);it.mime=Util.mime(c,uri);it.path=Util.safePath(path);it.status="В очереди";return it;
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
        if(depth>24||result.size()>5000)throw new IllegalStateException("Папка слишком велика: максимум 5000 файлов / 24 уровня");
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
                    Item it=single(c,u,path);it.name=name;
                    if(!cursor.isNull(3))it.size=cursor.getLong(3);
                    result.add(it);
                    if(result.size()>5000)throw new IllegalStateException("Больше 5000 файлов");
                }
            }
        }
    }
}
