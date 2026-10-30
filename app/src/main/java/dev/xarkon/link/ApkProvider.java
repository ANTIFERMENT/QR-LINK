package dev.xarkon.link;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

/** One read-only APK URI shared with Android's system package installer. */
public final class ApkProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    private File approved(Uri uri)throws FileNotFoundException{
        if(uri==null)throw new FileNotFoundException("Недопустимый путь");
        if("/apk".equals(uri.getPath())){
            File update=UpdateManager.apkFile(getContext());
            if(!update.isFile())throw new FileNotFoundException("Обновление не загружено");
            return update;
        }
        java.util.List<String> parts=uri.getPathSegments();
        if(parts.size()==2&&"installed".equals(parts.get(0))){
            String pkg=parts.get(1);
            try{
                ApplicationInfo app=getContext().getPackageManager().getApplicationInfo(pkg,0);
                File file=new File(app.publicSourceDir);
                if(file.isFile()&&file.getName().endsWith(".apk"))return file;
            }catch(PackageManager.NameNotFoundException ignored){}
        }
        throw new FileNotFoundException("APK недоступен");
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        if(!"r".equals(mode))throw new FileNotFoundException("Только чтение");
        return ParcelFileDescriptor.open(approved(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri){return "application/vnd.android.package-archive";}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sortOrder){
        try{
            File file=approved(uri);
            String[] cols=projection!=null?projection:new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE};
            MatrixCursor c=new MatrixCursor(cols);Object[] values=new Object[cols.length];
            for(int i=0;i<cols.length;i++){
                if(OpenableColumns.DISPLAY_NAME.equals(cols[i]))values[i]=
                    "/apk".equals(uri.getPath())?"QR_LINK_Update.apk":file.getName();
                else if(OpenableColumns.SIZE.equals(cols[i]))values[i]=file.length();
                else values[i]=null;
            }
            c.addRow(values);return c;
        }catch(Exception e){return null;}
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){return 0;}
    @Override public int delete(Uri uri,String selection,String[] args){return 0;}
}
