package io.github.t9ime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Two-row bar: mode + composing text on top, a page of candidates below. Pages hold at most 9 items. */
final class CandidateView extends View {
    interface Listener {
        void onCandidateTapped(int index);
    }

    private static final int BG = 0xFF202124;
    private static final int FG = 0xFFFFFFFF;
    private static final int DIM = 0xFF9AA0A6;
    private static final int ACCENT = 0xFF1A73E8;
    private static final int MODE_BG = 0xFF3C4043;

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private Listener listener;
    private String mode = "";
    private String preedit = "";
    private List<Candidate> candidates = Collections.emptyList();
    private int highlight = -1;
    private boolean numbered;
    private final List<Integer> pageStarts = new ArrayList<Integer>();
    private final List<float[]> hitBoxes = new ArrayList<float[]>();
    private float pad;
    private int pagesForWidth = -1;

    CandidateView(Context context) {
        super(context);
        setTextSize(22);
        text.setColor(FG);
        small.setColor(DIM);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setTextSize(int sp) {
        float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics());
        text.setTextSize(px);
        small.setTextSize(px * 0.62f);
        pad = px * 0.3f;
        pagesForWidth = -1;
        requestLayout();
        invalidate();
    }

    void show(String mode, String preedit, List<Candidate> candidates, int highlight, boolean numbered) {
        this.mode = mode;
        this.preedit = preedit;
        if (this.candidates != candidates) pagesForWidth = -1;
        this.candidates = candidates;
        this.highlight = highlight;
        this.numbered = numbered;
        invalidate();
    }

    private int barWidth() {
        int w = getWidth();
        return w > 0 ? w : getResources().getDisplayMetrics().widthPixels;
    }

    private float itemWidth(Candidate c) {
        return small.measureText("9") + pad * 0.4f + text.measureText(label(c)) + pad * 2;
    }

    private static String label(Candidate c) {
        return "\n".equals(c.text) ? "↵" : c.text;
    }

    private void paginate() {
        int width = barWidth();
        if (pagesForWidth == width) return;
        pagesForWidth = width;
        pageStarts.clear();
        float avail = width - pad * 2 - text.measureText("▶");
        float x = 0;
        int inPage = 0;
        pageStarts.add(0);
        for (int i = 0; i < candidates.size(); i++) {
            float w = itemWidth(candidates.get(i));
            if (inPage > 0 && (x + w > avail || inPage == 9)) {
                pageStarts.add(i);
                x = 0;
                inPage = 0;
            }
            x += w;
            inPage++;
        }
    }

    int pageCount() {
        paginate();
        return pageStarts.size();
    }

    int pageOf(int index) {
        paginate();
        int p = 0;
        for (int k = 0; k < pageStarts.size(); k++) {
            if (pageStarts.get(k) <= index) p = k;
        }
        return p;
    }

    int pageStart(int page) {
        paginate();
        return pageStarts.get(Math.max(0, Math.min(page, pageStarts.size() - 1)));
    }

    int pageEnd(int page) {
        paginate();
        return page + 1 < pageStarts.size() ? pageStarts.get(page + 1) : candidates.size();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        pagesForWidth = -1;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Paint.FontMetrics fm = text.getFontMetrics();
        Paint.FontMetrics sm = small.getFontMetrics();
        int h = (int) Math.ceil((fm.descent - fm.ascent) + (sm.descent - sm.ascent) + pad * 3);
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        canvas.drawColor(BG);
        Paint.FontMetrics sm = small.getFontMetrics();
        Paint.FontMetrics fm = text.getFontMetrics();

        // Row 1: mode badge and composing text.
        float row1Base = pad - sm.ascent;
        float x = pad;
        if (mode.length() > 0) {
            float mw = small.measureText(mode) + pad;
            rect.set(x, pad * 0.5f, x + mw, row1Base + sm.descent);
            fill.setColor(MODE_BG);
            canvas.drawRoundRect(rect, pad * 0.4f, pad * 0.4f, fill);
            small.setColor(FG);
            canvas.drawText(mode, x + pad * 0.5f, row1Base, small);
            x += mw + pad;
        }
        small.setColor(0xFFFDD663);
        canvas.drawText(preedit, x, row1Base, small);
        small.setColor(DIM);

        // Row 2: one page of candidates.
        hitBoxes.clear();
        if (candidates.isEmpty()) return;
        float top = row1Base + sm.descent + pad * 0.5f;
        float base = top + pad * 0.5f - fm.ascent;
        int page = pageOf(Math.max(highlight, 0));
        int start = pageStart(page);
        int end = pageEnd(page);
        x = pad;
        for (int i = start; i < end; i++) {
            Candidate c = candidates.get(i);
            float w = itemWidth(c);
            if (i == highlight) {
                rect.set(x, top, x + w - pad * 0.5f, getHeight() - pad * 0.3f);
                fill.setColor(ACCENT);
                canvas.drawRoundRect(rect, pad * 0.4f, pad * 0.4f, fill);
            }
            float tx = x + pad * 0.5f;
            if (numbered) {
                small.setColor(i == highlight ? FG : DIM);
                canvas.drawText(String.valueOf(i - start + 1), tx, base, small);
            }
            tx += small.measureText("9") + pad * 0.4f;
            canvas.drawText(label(c), tx, base, text);
            hitBoxes.add(new float[] {x, x + w, i});
            x += w;
        }
        small.setColor(DIM);
        int pages = pageCount();
        if (pages > 1) {
            String arrow = (page > 0 ? "◀" : "") + (page < pages - 1 ? "▶" : "");
            text.setColor(DIM);
            canvas.drawText(arrow, width - pad - text.measureText(arrow), base, text);
            text.setColor(FG);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP && listener != null) {
            for (float[] box : hitBoxes) {
                if (e.getX() >= box[0] && e.getX() < box[1]) {
                    listener.onCandidateTapped((int) box[2]);
                    break;
                }
            }
        }
        return true;
    }
}
