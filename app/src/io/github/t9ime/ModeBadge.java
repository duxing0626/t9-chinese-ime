package io.github.t9ime;

import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.CursorAnchorInfo;
import android.widget.TextView;

/**
 * A small label next to the text cursor showing the current input mode (粵, 倉, Abc …).
 * Android 5 only lets an input method draw outside its own bottom panel through an overlay
 * window (SYSTEM_ALERT_WINDOW, granted at install there). The label takes no touches or focus.
 */
final class ModeBadge {
    private final InputMethodService ims;
    private final WindowManager wm;
    private final DisplayMetrics dm;
    private TextView view;
    private WindowManager.LayoutParams lp;
    private boolean added;
    /** Set when the system refuses the overlay (permission removed); then we stop trying. */
    private boolean refused;

    private boolean haveAnchor;
    private float anchorX;
    private float anchorTop;
    private float anchorBottom;

    ModeBadge(InputMethodService ims) {
        this.ims = ims;
        this.wm = (WindowManager) ims.getSystemService(InputMethodService.WINDOW_SERVICE);
        this.dm = ims.getResources().getDisplayMetrics();
    }

    private int dp(float v) {
        return (int) (v * dm.density + 0.5f);
    }

    private void ensureView() {
        if (view != null) return;
        view = new TextView(ims);
        view.setTextColor(0xFFFFFFFF);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setPadding(dp(5), dp(1), dp(5), dp(1));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC1A73E8);
        bg.setCornerRadius(dp(4));
        view.setBackground(bg);
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.setTitle("T9ImeModeBadge");
    }

    void show(String label) {
        if (refused) return;
        ensureView();
        view.setText(label);
        place();
        try {
            if (added) {
                wm.updateViewLayout(view, lp);
            } else {
                wm.addView(view, lp);
                added = true;
            }
        } catch (WindowManager.BadTokenException e) {
            refused = true;
            added = false;
        } catch (RuntimeException e) {
            added = false;
        }
    }

    void hide() {
        if (!added) return;
        try {
            wm.removeViewImmediate(view);
        } catch (RuntimeException ignored) {
            // already gone
        }
        added = false;
    }

    /** Where the cursor is on screen, from the editor's CursorAnchorInfo. */
    void setAnchor(CursorAnchorInfo info) {
        float x = info.getInsertionMarkerHorizontal();
        if (Float.isNaN(x)) {
            haveAnchor = false;
        } else {
            float[] pts = {x, info.getInsertionMarkerTop(), x, info.getInsertionMarkerBottom()};
            Matrix m = info.getMatrix();
            if (m != null) m.mapPoints(pts);
            anchorX = pts[0];
            anchorTop = pts[1];
            anchorBottom = pts[3];
            haveAnchor = true;
        }
        if (added) {
            place();
            try {
                wm.updateViewLayout(view, lp);
            } catch (RuntimeException ignored) {
                added = false;
            }
        }
    }

    void clearAnchor() {
        haveAnchor = false;
    }

    private void place() {
        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int w = view.getMeasuredWidth();
        int h = view.getMeasuredHeight();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;
        int gap = dp(3);
        if (haveAnchor) {
            // Just below the cursor; above it when the cursor is low on the screen (candidate bar area).
            int x = (int) anchorX + gap;
            int y = (int) anchorBottom + gap;
            if (y + h > screenH * 0.72f) y = (int) anchorTop - h - gap;
            lp.x = Math.max(0, Math.min(x, screenW - w));
            lp.y = Math.max(0, y);
        } else {
            // No cursor position from this app: bottom-right corner, clear of the candidate bar.
            lp.x = screenW - w - dp(6);
            lp.y = (int) (screenH * 0.62f);
        }
    }
}
