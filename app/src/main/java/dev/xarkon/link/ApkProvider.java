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

/** One read-only APK URI shared with Android's system package installer. */
public final class ApkProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    private File approved(Uri uri)throws FileNotFoundException{
        if(uri==null||!"/apk".equals(uri.getPath()))throw new FileNotFoundException("Недопустимый путь");
        File file=UpdateManager.apkFile(getContext());
        if(!file.isFile())throw new FileNotFoundException("Обновление не загружено");
        return file;
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
                if(OpenableColumns.DISPLAY_NAME.equals(cols[i]))values[i]="QR_LINK_Update.apk";
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
