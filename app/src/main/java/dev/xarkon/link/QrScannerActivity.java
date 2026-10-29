package dev.xarkon.link;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.hardware.Camera;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.net.Uri;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;

/** QR scanning is entirely local. Legacy Camera API avoids heavyweight AARs and works with API 29+. */
@SuppressWarnings("deprecation")
public final class QrScannerActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private Camera camera;
    private SurfaceView surface;
    private boolean previewReady, torchOn;
    private final AtomicBoolean decoding=new AtomicBoolean(false);
    private volatile long lastDecode;
    private TextView notice;
    private int ink=0xffe5f4f5,background=0xff0b121b,accent=0xff5bdacb;
    @Override public void onCreate(Bundle b){
        android.content.SharedPreferences p=getSharedPreferences("settings",0);
        String mode=p.getString("theme_mode",p.getBoolean("light",false)?"light":"dark");
        boolean light="light".equals(mode),amoled="amoled".equals(mode);
        setTheme(amoled?R.style.AppThemeAmoled:light?R.style.AppThemeLight:R.style.AppTheme);
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        accent=p.getInt("accent",0xff55dfcb);
        background=amoled?0xff000000:light?0xfff5f7fb:0xff0a111b;
        ink=light?0xff152536:0xfff1f6fb;
        getWindow().setStatusBarColor(background);getWindow().setNavigationBarColor(background);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(background);
        root.setPadding(dp(17),dp(22),dp(17),dp(17));
        UiMotion.applySafeInsets(this,root,dp(22),dp(17));
        setContentView(root);
        UiMotion.systemBars(this, light);
        root.post(root::requestApplyInsets);
        TextView title=new TextView(this);title.setText("Сканирование QR-кода");title.setTextSize(22);title.setTextColor(ink);title.setTypeface(null,1);root.addView(title);
        notice=new TextView(this);notice.setText("Наведите камеру на QR-код получателя. Подключение начнётся автоматически.");
        notice.setTextColor(ink);notice.setTextSize(14);notice.setPadding(0,dp(12),0,dp(15));root.addView(notice);
        FrameLayout frame=new FrameLayout(this);frame.setBackgroundColor(0xff000000);
        LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-1,0,1);root.addView(frame,fp);
        surface=new SurfaceView(this);frame.addView(surface,new FrameLayout.LayoutParams(-1,-1));surface.getHolder().addCallback(this);
        ScanOverlayView finder=new ScanOverlayView(this,accent,p.getBoolean("motion",true));
        frame.addView(finder,new FrameLayout.LayoutParams(-1,-1));
                LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER);actions.setPadding(0,dp(12),0,0);root.addView(actions);
        scanButton(actions,"flash","Фонарик",()->toggleTorch());
        scanButton(actions,"folder","QR из галереи",()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.setType("image/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,22);});

    }
    private void scanButton(LinearLayout parent,String glyph,String name,Runnable run){
        LinearLayout b=new LinearLayout(this);b.setGravity(Gravity.CENTER_VERTICAL);b.setPadding(dp(9),dp(13),dp(9),dp(13));
        UiIcon i=new UiIcon(this,glyph,ink);b.addView(i,new LinearLayout.LayoutParams(dp(20),dp(20)));
        TextView t=new TextView(this);t.setText(name);t.setTextColor(ink);t.setTextSize(12);
        t.setPadding(dp(7),0,0,0);b.addView(t);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);parent.addView(b,lp);
        b.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf((accent&0x00ffffff)|0x30000000),
            null,new android.graphics.drawable.ColorDrawable(0xffffffff)));
        b.setOnClickListener(v->run.run());
    }
    private int dp(float p){return (int)(p*getResources().getDisplayMetrics().density+.5f);}
    @Override public void surfaceCreated(SurfaceHolder h){previewReady=true;openCamera();}
    @Override public void surfaceChanged(SurfaceHolder h,int format,int w,int hh){}
    @Override public void surfaceDestroyed(SurfaceHolder h){previewReady=false;releaseCamera();}
    @Override protected void onPause(){releaseCamera();super.onPause();}
    @Override protected void onResume(){super.onResume();if(previewReady&&camera==null)openCamera();}
    private void openCamera(){if(!previewReady||camera!=null)return;
        try{
            camera=Camera.open();Camera.Parameters p=camera.getParameters();
            List<Camera.Size> sizes=p.getSupportedPreviewSizes();Camera.Size best=null;
            if(sizes==null)throw new Exception("Нет поддерживаемых размеров камеры");
            for(Camera.Size s:sizes){if(s.width>=640&&s.width<=1280 && (best==null||s.width*s.height<best.width*best.height))best=s;}
            if(best!=null)p.setPreviewSize(best.width,best.height);
            if(p.getSupportedFocusModes()!=null&&p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            p.setPreviewFormat(android.graphics.ImageFormat.NV21);camera.setParameters(p);
            Camera.CameraInfo info=new Camera.CameraInfo();Camera.getCameraInfo(0,info);
            int rotation=getWindowManager().getDefaultDisplay().getRotation();int degrees=rotation==Surface.ROTATION_90?90:rotation==Surface.ROTATION_180?180:rotation==Surface.ROTATION_270?270:0;
            int orientation=(info.orientation-degrees+360)%360;
            camera.setDisplayOrientation(orientation);
            camera.setPreviewDisplay(surface.getHolder());camera.setPreviewCallback(this);camera.startPreview();
        }catch(Exception e){releaseCamera();notice.setText("Камера недоступна: "+e.getMessage()+". Можно выбрать фото QR из галереи.");}
    }
    private void releaseCamera(){Camera c=camera;camera=null;if(c!=null){try{c.setPreviewCallback(null);c.stopPreview();c.release();}catch(Exception ignored){}}}
    private void toggleTorch(){if(camera==null)return;try{Camera.Parameters p=camera.getParameters();
        if(p.getSupportedFlashModes()==null||!p.getSupportedFlashModes().contains(Camera.Parameters.FLASH_MODE_TORCH))return;
        torchOn=!torchOn;p.setFlashMode(torchOn?Camera.Parameters.FLASH_MODE_TORCH:Camera.Parameters.FLASH_MODE_OFF);camera.setParameters(p);
    }catch(Exception ignored){}}
    @Override public void onPreviewFrame(byte[] data,Camera c){
        if(data==null||decoding.get()||android.os.SystemClock.elapsedRealtime()-lastDecode<350)return;
        Camera.Parameters p;try{p=c.getParameters();}catch(Exception e){return;}
        Camera.Size s=p.getPreviewSize();if(s==null)return;
        if(!decoding.compareAndSet(false,true))return;
        lastDecode=android.os.SystemClock.elapsedRealtime();
        final int w=s.width,h=s.height;final byte[] frame=Arrays.copyOf(data,w*h);
        new Thread(()->{try{
            String found=decodeY(frame,w,h);
            if(found!=null)runOnUiThread(()->finishWith(found));
        }catch(Exception ignored){}finally{decoding.set(false);}},"qr-decode").start();
    }
    private String decodeY(byte[] y,int width,int height){
        try{
            byte[] rotated=new byte[y.length];
            for(int j=0;j<height;j++)for(int i=0;i<width;i++)rotated[i*height+(height-1-j)]=y[j*width+i];
            String found=decodeSource(new PlanarYUVLuminanceSource(rotated,height,width,0,0,height,width,false));
            if(found!=null)return found;
            found=decodeSource(new PlanarYUVLuminanceSource(y,width,height,0,0,width,height,false));
            if(found!=null)return found;
            for(int j=0;j<height;j++)for(int i=0;i<width;i++)rotated[(width-i-1)*height+j]=y[j*width+i];
            return decodeSource(new PlanarYUVLuminanceSource(rotated,height,width,0,0,height,width,false));
        }catch(Exception e){return null;}
    }
    private String decodeSource(LuminanceSource source){try{
        BinaryBitmap bitmap=new BinaryBitmap(new HybridBinarizer(source));
        Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS,Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER,Boolean.TRUE);
        return new MultiFormatReader().decode(bitmap,hints).getText();
    }catch(Exception e){return null;}}
    private void finishWith(String value){try{Pairing.parse(value);}catch(Exception e){notice.setText("Неверный QR: "+e.getMessage());return;}
        setResult(RESULT_OK,new Intent().putExtra("qr",value));finish();}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);
        if(req!=22||result!=RESULT_OK||data==null||data.getData()==null)return;
        try{Uri uri=data.getData();android.graphics.Bitmap bitmap;
            try(java.io.InputStream in=getContentResolver().openInputStream(uri)){
                android.graphics.BitmapFactory.Options o=new android.graphics.BitmapFactory.Options();o.inSampleSize=2;
                bitmap=BitmapFactory.decodeStream(in,null,o);
            }
            if(bitmap==null)throw new Exception("Фотография не прочитана");
            int w=bitmap.getWidth(),h=bitmap.getHeight();int[] pixels=new int[w*h];bitmap.getPixels(pixels,0,w,0,0,w,h);
            String text=decodeSource(new RGBLuminanceSource(w,h,pixels));bitmap.recycle();
            if(text!=null)finishWith(text);else notice.setText("QR не распознан. Попробуйте другое фото.");
        }catch(Exception e){notice.setText("Ошибка изображения: "+e.getMessage());}
    }
}
