package dev.xarkon.link;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/** Low-overhead, self-contained QR signal glyph with optional breathing animation. */
public final class QrPulseView extends View {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int accent;
    private final boolean animated;
    private ValueAnimator animator;
    private float phase=.32f;
    public QrPulseView(Context c,int color,boolean motion){super(c);accent=color;animated=motion;}
    private void line(Canvas c,float x,float y,float x2,float y2){c.drawLine(x,y,x2,y2,p);}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        float scale=Math.min(getWidth(),getHeight())/100f;
        c.save();c.translate((getWidth()-100*scale)/2f,(getHeight()-100*scale)/2f);c.scale(scale,scale);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.5f);p.setColor((accent&0x00ffffff)|((int)(34+phase*35)<<24));
        float ring=38+phase*3;c.drawCircle(50,50,ring,p);
        p.setColor((accent&0x00ffffff)|0x26000000);p.setStrokeWidth(1f);c.drawCircle(50,50,43,p);
        p.setColor(accent);p.setStrokeWidth(4.5f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
        line(c,26,40,26,26);line(c,26,26,40,26);
        line(c,60,26,74,26);line(c,74,26,74,40);
        line(c,26,60,26,74);line(c,26,74,40,74);
        line(c,60,74,74,74);line(c,74,74,74,60);
        p.setStyle(Paint.Style.FILL);p.setColor(Color.WHITE);c.drawRoundRect(new RectF(40,41,60,59),5,5,p);
        p.setColor(accent);c.drawRoundRect(new RectF(44,45,56,55),3,3,p);
        c.restore();
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();
        if(!animated)return;
        animator=ValueAnimator.ofFloat(.15f,1f);animator.setDuration(1700);animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(a->{phase=(float)a.getAnimatedValue();invalidate();});animator.start();
    }
    @Override protected void onDetachedFromWindow(){if(animator!=null){animator.cancel();animator=null;}super.onDetachedFromWindow();}
}
