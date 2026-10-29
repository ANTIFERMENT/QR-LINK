package dev.xarkon.link;

import android.app.Activity;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/** Edge-to-edge handling for API 29–37; no controller lookup before decor attachment. */
@SuppressWarnings("deprecation")
public final class UiMotion {
    private UiMotion() {}

    /**
     * Set system-bar colors and icon contrast without calling PhoneWindow.getInsetsController().
     * Some Android 17/vivo builds throw NPE from that API before the DecorView exists.
     * Instead, request the controller from an ATTACHED DecorView, after setContentView.
     */
    public static void systemBars(Activity activity, boolean light) {
        Window window = activity.getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            final View decor = window.getDecorView();
            final int flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            final int appearance = light ? flags : 0;
            if (decor.isAttachedToWindow()) {
                applyAppearance(decor, appearance, flags);
            } else {
                decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View view) {
                        view.removeOnAttachStateChangeListener(this);
                        applyAppearance(view, appearance, flags);
                    }
                    @Override public void onViewDetachedFromWindow(View view) {
                        view.removeOnAttachStateChangeListener(this);
                    }
                });
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (light) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            window.getDecorView().setSystemUiVisibility(flags);
        }
        if (Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(false);
    }

    /** Losing icon-contrast adjustment must never crash the whole app. */
    private static void applyAppearance(View decor, int appearance, int mask) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            WindowInsetsController controller = decor.getWindowInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(appearance, mask);
            } else {
                // Some OEM implementations expose a controller only in the following frame.
                decor.post(() -> {
                    try {
                        WindowInsetsController ready = decor.getWindowInsetsController();
                        if (ready != null) ready.setSystemBarsAppearance(appearance, mask);
                    } catch (RuntimeException ignored) {
                        // Theme still works; only status-bar icon contrast may use system default.
                    }
                });
            }
        } catch (RuntimeException ignored) {
            // Avoid bringing down the Activity due to vendor-specific Window implementation.
        }
    }

    /**
     * In Android 15–17 the app is edge-to-edge even before the first insets dispatch.
     * Reserve the device's status bar dimension immediately, then replace it with
     * actual status/navigation/cutout insets. Never add the fallback twice.
     */
    public static void applySafeInsets(Activity activity, View view, int baseTop, int baseBottom) {
        final int baseLeft=view.getPaddingLeft();
        final int baseRight=view.getPaddingRight();
        final int statusHeight=resourceStatusHeight(activity);
        view.setPadding(baseLeft,baseTop+statusHeight,baseRight,baseBottom);
        view.setOnApplyWindowInsetsListener((v,insets)->{
            int left,top,right,bottom;
            if(Build.VERSION.SDK_INT>=30){
                int bars=WindowInsets.Type.statusBars()|WindowInsets.Type.navigationBars();
                Insets safe=insets.getInsetsIgnoringVisibility(bars);
                Insets cutout=insets.getInsets(WindowInsets.Type.displayCutout());
                left=Math.max(safe.left,cutout.left);
                top=Math.max(Math.max(safe.top,cutout.top),statusHeight);
                right=Math.max(safe.right,cutout.right);
                bottom=Math.max(safe.bottom,cutout.bottom);
            }else{
                left=insets.getSystemWindowInsetLeft();
                top=Math.max(insets.getSystemWindowInsetTop(),statusHeight);
                right=insets.getSystemWindowInsetRight();
                bottom=insets.getSystemWindowInsetBottom();
            }
            v.setPadding(baseLeft+left,baseTop+top,baseRight+right,baseBottom+bottom);
            return insets;
        });
        // Android OEMs can dispatch first insets after content installation.
        // The fallback above protects the header on that initial frame as well.
        if(view.isAttachedToWindow())view.requestApplyInsets();
        else view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            @Override public void onViewAttachedToWindow(View attached){
                attached.removeOnAttachStateChangeListener(this);
                attached.requestApplyInsets();
            }
            @Override public void onViewDetachedFromWindow(View detached){}
        });
    }
    private static int resourceStatusHeight(Activity activity){
        int id=activity.getResources().getIdentifier("status_bar_height","dimen","android");
        if(id==0)return 0;
        try{return activity.getResources().getDimensionPixelSize(id);}
        catch(android.content.res.Resources.NotFoundException e){return 0;}
    }
}
