package dev.xarkon.link;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/** A slim animated scanning line, with a stationary accessible corner finder. */
public final class ScanOverlayView extends View {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int accent;
    private final boolean animationAllowed;
    private float fraction=.22f;
    private ValueAnimator animator;
    public ScanOverlayView(Context c,int color,boolean animated){super(c);accent=color;animationAllowed=animated;}
    private void line(Canvas c,float x,float y,float xx,float yy){c.drawLine(x,y,xx,yy,p);}
    @Override protected void onDraw(Canvas c){super.onDraw(c);
        float len=Math.min(getWidth(),getHeight())*.72f;
        float x=(getWidth()-len)/2f,y=(getHeight()-len)/2f;
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(4));p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(accent);float side=dp(32);
        line(c,x,y+side,x,y);line(c,x,y,x+side,y);
        line(c,x+len-side,y,x+len,y);line(c,x+len,y,x+len,y+side);
        line(c,x,y+len-side,x,y+len);line(c,x,y+len,x+side,y+len);
        line(c,x+len-side,y+len,x+len,y+len);line(c,x+len,y+len,x+len,y+len-side);
        float yy=y+side*.6f+(len-1.2f*side)*fraction;
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(x,yy,x+len,yy,new int[]{Color.TRANSPARENT,accent,Color.TRANSPARENT},null,Shader.TileMode.CLAMP));
        c.drawRoundRect(x+side*.5f,yy-dp(1),x+len-side*.5f,yy+dp(1),dp(1),dp(1),p);
        p.setShader(null);
    }
    private int dp(float dp){return (int)(dp*getResources().getDisplayMetrics().density+.5f);}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();
        if(!animationAllowed)return;
        animator=ValueAnimator.ofFloat(0f,1f);animator.setDuration(1850);
        animator.setRepeatCount(ValueAnimator.INFINITE);animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(a->{fraction=(float)a.getAnimatedValue();invalidate();});animator.start();
    }
    @Override protected void onDetachedFromWindow(){if(animator!=null){animator.cancel();animator=null;}super.onDetachedFromWindow();}
}
