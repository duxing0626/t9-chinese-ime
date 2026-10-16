package io.github.t9ime;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shows the key codes the keypad sends, to check how a particular phone's keys are mapped. */
public class KeyTestActivity extends Activity {
    private TextView log;
    private final StringBuilder lines = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log = new TextView(this);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        log.setPadding(pad, pad, pad, pad);
        log.setTextSize(16);
        log.setText("請按手機上的按鍵（按兩次返回鍵離開）\n");
        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);
        setContentView(scroll);
    }

    private long lastBack;

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getRepeatCount() == 0) {
            lines.insert(0, KeyEvent.keyCodeToString(keyCode) + "  (" + keyCode + ")  scan=" + event.getScanCode()
                    + "\n");
            if (lines.length() > 4000) lines.setLength(4000);
            log.setText("請按手機上的按鍵（按兩次返回鍵離開）\n\n" + lines);
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            long now = System.currentTimeMillis();
            if (now - lastBack < 1500) finish();
            lastBack = now;
        }
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return true;
    }
}
