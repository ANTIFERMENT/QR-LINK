package dev.xarkon.link;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Theme-aware outline glyphs. No image packs, fonts or network dependencies. */
public final class UiIcon extends View {
    private final String symbol;
    private int tint;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public UiIcon(Context c,String s,int color){super(c);symbol=s;tint=color;}
    private void l(Canvas c,float x,float y,float a,float b){c.drawLine(x,y,a,b,paint);}
    private void circle(Canvas c,float x,float y,float r){c.drawCircle(x,y,r,paint);}
    private void rect(Canvas c,float x,float y,float w,float h,float r){c.drawRoundRect(x,y,x+w,y+h,r,r,paint);}
    @Override protected void onDraw(Canvas nativeCanvas){super.onDraw(nativeCanvas);
        int w=getWidth(),h=getHeight();
        nativeCanvas.save();nativeCanvas.translate((w-Math.min(w,h)*.72f)/2f,(h-Math.min(w,h)*.72f)/2f);
        nativeCanvas.scale(Math.min(w,h)*.72f/24f,Math.min(w,h)*.72f/24f);
        Paint p=paint;p.setColor(tint);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setStrokeWidth(2.2f);
        switch(symbol){
            case "send": l(nativeCanvas,3,11,21,3);l(nativeCanvas,21,3,14,21);l(nativeCanvas,21,3,10,13);l(nativeCanvas,10,13,14,21);l(nativeCanvas,3,11,10,13);break;
            case "receive": l(nativeCanvas,12,3,12,16);l(nativeCanvas,6,11,12,17);l(nativeCanvas,18,11,12,17);l(nativeCanvas,4,18,4,21);l(nativeCanvas,4,21,20,21);l(nativeCanvas,20,21,20,18);break;
            case "scan": l(nativeCanvas,3,9,3,3);l(nativeCanvas,3,3,9,3);l(nativeCanvas,15,3,21,3);l(nativeCanvas,21,3,21,9);l(nativeCanvas,3,15,3,21);l(nativeCanvas,3,21,9,21);l(nativeCanvas,15,21,21,21);l(nativeCanvas,21,21,21,15);l(nativeCanvas,6,12,18,12);break;
            case "file":l(nativeCanvas,6,2,15,2);l(nativeCanvas,15,2,20,7);l(nativeCanvas,20,7,20,22);l(nativeCanvas,20,22,6,22);l(nativeCanvas,6,22,6,2);l(nativeCanvas,15,2,15,7);l(nativeCanvas,15,7,20,7);l(nativeCanvas,9,13,17,13);l(nativeCanvas,9,17,16,17);break;
            case "folder":l(nativeCanvas,2,7,2,20);l(nativeCanvas,2,20,22,20);l(nativeCanvas,22,20,22,9);l(nativeCanvas,22,9,11,9);l(nativeCanvas,11,9,9,5);l(nativeCanvas,9,5,2,5);l(nativeCanvas,2,5,2,7);break;
            case "theme":circle(nativeCanvas,12,12,9);circle(nativeCanvas,7,11,1);circle(nativeCanvas,13,7,1);circle(nativeCanvas,18,12,1);circle(nativeCanvas,12,17,1);break;
            case "history":circle(nativeCanvas,12,12,9);l(nativeCanvas,12,6,12,12);l(nativeCanvas,12,12,17,15);break;
            case "speed":l(nativeCanvas,4,19,20,19);l(nativeCanvas,5,19,5,13);l(nativeCanvas,19,19,19,13);l(nativeCanvas,5,13,12,5);l(nativeCanvas,12,5,19,13);l(nativeCanvas,12,15,17,9);break;
            case "trash":rect(nativeCanvas,6,7,12,14,2);l(nativeCanvas,4,5,20,5);l(nativeCanvas,9,2,15,2);l(nativeCanvas,10,10,10,18);l(nativeCanvas,14,10,14,18);break;
            case "link":circle(nativeCanvas,7,16,4);circle(nativeCanvas,17,8,4);l(nativeCanvas,9,14,15,10);break;
            case "settings":{
                Path gear=new Path();float inner=8.5f,outer=11.5f;
                for(int t=0;t<32;t++){
                    double angle=(Math.PI*2*t)/32.0-Math.PI/2;
                    float radius=(t%4==0||t%4==3)?outer:inner;
                    float x=12+(float)Math.cos(angle)*radius,y=12+(float)Math.sin(angle)*radius;
                    if(t==0)gear.moveTo(x,y);else gear.lineTo(x,y);
                }
                gear.close();p.setStrokeWidth(1.9f);nativeCanvas.drawPath(gear,p);
                circle(nativeCanvas,12,12,3.3f);break;
            }
            case "chevron":l(nativeCanvas,5,9,12,16);l(nativeCanvas,12,16,19,9);break;
            case "moon":nativeCanvas.drawArc(3,2,21,22,65,230,false,p);l(nativeCanvas,15,3,20,3);break;
            case "amoled":rect(nativeCanvas,3,3,18,18,4);p.setStyle(Paint.Style.FILL);circle(nativeCanvas,12,12,4);break;
            case "sun":circle(nativeCanvas,12,12,4);for(int n=0;n<8;n++){double a=n*Math.PI/4;float dx=(float)Math.cos(a),dy=(float)Math.sin(a);l(nativeCanvas,12+dx*8,12+dy*8,12+dx*10.5f,12+dy*10.5f);}break;
            case "sparkles":l(nativeCanvas,12,2,12,19);l(nativeCanvas,5,10,19,10);l(nativeCanvas,7,5,17,15);l(nativeCanvas,17,5,7,15);circle(nativeCanvas,19,20,1.3f);break;
            case "info":circle(nativeCanvas,12,12,10);circle(nativeCanvas,12,7,1);l(nativeCanvas,12,11,12,17);break;
            case "qr":l(nativeCanvas,2,8,2,2);l(nativeCanvas,2,2,8,2);l(nativeCanvas,16,2,22,2);l(nativeCanvas,22,2,22,8);l(nativeCanvas,2,16,2,22);l(nativeCanvas,2,22,8,22);l(nativeCanvas,16,22,22,22);l(nativeCanvas,22,22,22,16);rect(nativeCanvas,8,8,8,8,2);break;
            case "back":l(nativeCanvas,19,12,5,12);l(nativeCanvas,5,12,12,5);l(nativeCanvas,5,12,12,19);break;
            case "check":l(nativeCanvas,4,12,10,18);l(nativeCanvas,10,18,21,5);break;
            case "wifi":nativeCanvas.drawArc(3,4,21,20,205,130,false,p);nativeCanvas.drawArc(7,9,17,20,205,130,false,p);circle(nativeCanvas,12,20,1.5f);break;
            case "share":circle(nativeCanvas,19,5,3);circle(nativeCanvas,5,12,3);circle(nativeCanvas,19,19,3);l(nativeCanvas,8,11,16,6);l(nativeCanvas,8,13,16,18);break;
            case "flash":l(nativeCanvas,13,2,5,14);l(nativeCanvas,5,14,12,14);l(nativeCanvas,12,14,10,22);l(nativeCanvas,10,22,20,10);l(nativeCanvas,20,10,13,10);l(nativeCanvas,13,10,13,2);break;
            default:circle(nativeCanvas,12,12,8);
        }
        nativeCanvas.restore();
    }
}
