package io.github.t9ime;

import android.content.Intent;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.util.SparseBooleanArray;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Chinese input for phones with a 12-key (0-9 * #) hardware keypad.
 *
 * Keys: digits type codes; 0 / OK commits the highlighted candidate; ◀ ▶ move the highlight;
 * ▼ switches to "pick by number" (1-9 choose, 0 next page); # cycles input modes;
 * * opens symbols; DEL / BACK deletes. Holding a digit enters the digit itself.
 */
public class T9ImeService extends InputMethodService implements CandidateView.Listener {
    static final int M_JYUTPING = 0, M_CANGJIE = 1, M_QUICK = 2, M_STROKE = 3, M_CUSTOM = 4,
            M_ABC = 5, M_LOWER = 6, M_UPPER = 7, M_NUMBER = 8;
    private static final String[] MODE_PREF = {"mode_jyutping", "mode_cangjie", "mode_quick", "mode_stroke",
            "mode_custom", "mode_english", "mode_english", "mode_english", "mode_numeric"};
    private static final int[] MODE_DICT = {Dicts.JYUTPING, Dicts.CANGJIE, Dicts.QUICK, Dicts.STROKE, Dicts.CUSTOM};
    private static final String[] MODE_BADGE = {"粵", "倉", "速", "筆", "", "Abc", "abc", "ABC", "123"};
    private static final String[] MODE_NAME = {"粵拼", "倉頡", "速成", "筆畫", "", "英文 Abc", "英文 abc", "英文 ABC", "數字 123"};
    private static final int[] MODE_ICON = {R.drawable.st_jyut, R.drawable.st_cangjie, R.drawable.st_quick,
            R.drawable.st_stroke, R.drawable.st_custom, R.drawable.st_abc, R.drawable.st_lower, R.drawable.st_upper,
            R.drawable.st_number};

    private static final int LIST_NONE = 0, LIST_COMPOSE = 1, LIST_ASSOC = 2, LIST_SYMBOL = 3;

    private static final String[] TAP_LETTERS = {"", "", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"};
    private static final String TAP_PUNCT = ".,?!'\"-@:;/()1";
    private static final String[] SYMBOLS_ZH = {"，", "。", "？", "！", "、", "：", "；", "「", "」", "『", "』",
            "（", "）", "……", "——", "～", "·", "《", "》", "〈", "〉", "【", "】", "\n", "＠", "＃", "％", "＆", "＊",
            "＋", "－", "＝", "／", "＄", "￥", "“", "”", "‘", "’", "○", "●", "★", "☆", "♪", "♥", "※", "→", "←", "↑", "↓"};
    private static final String[] SYMBOLS_EN = {".", ",", "?", "!", "'", "\"", ":", ";", "-", "@", "\n", "(", ")",
            "_", "/", "&", "#", "*", "+", "=", "%", "$", "~", "\\", "|", "^", "<", ">", "[", "]", "{", "}", "`", "€", "£"};

    private SharedPreferences prefs;
    private CandidateView view;
    private final Handler handler = new Handler();
    private Toast toast;

    private boolean active;
    private boolean passwordField;
    private int mode = M_JYUTPING;

    private final StringBuilder input = new StringBuilder();
    /** Jyutping: text already picked for the start of the input, and how to undo each pick. */
    private final StringBuilder chosen = new StringBuilder();
    private final ArrayList<String[]> chosenSteps = new ArrayList<String[]>();

    private List<Candidate> cands = Collections.emptyList();
    private int listKind = LIST_NONE;
    private int highlight;
    private boolean selecting;

    private int tapKey = -1;
    private int tapIndex;
    private long tapTime;
    private boolean englishPending;

    private final SparseBooleanArray downConsumed = new SparseBooleanArray();
    private int longPressedKey = -1;

    private UserDict user;
    private int symbolSet;

    private boolean letterTaps;
    private boolean learnOn;
    private boolean emojiOn;
    private boolean assocOn;
    private boolean backDelete;
    private boolean backDeletesText;
    /** BACK went down over text: delete one character on release, or go back if held. */
    private boolean backPending;
    private int tapTimeoutMs = 900;
    private int candSize = 22;

    private final Runnable tapTimeout = new Runnable() {
        @Override
        public void run() {
            finishTap();
        }
    };

    private final Runnable saveUser = new Runnable() {
        @Override
        public void run() {
            user.save();
        }
    };

    private final Runnable dictReady = new Runnable() {
        @Override
        public void run() {
            if (listKind == LIST_COMPOSE) refresh();
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        PreferenceManager.setDefaultValues(this, R.xml.prefs, false);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        user = Dicts.user(this);
        readPrefs();
        mode = prefs.getInt("last_mode", M_JYUTPING);
        loadDictsForMode();
        Dicts.ensure(this, Dicts.EMOJI_GROUPS, null);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(saveUser);
        user.save();
        super.onDestroy();
    }

    @Override
    public View onCreateCandidatesView() {
        view = new CandidateView(this);
        view.setListener(this);
        view.setTextSize(candSize);
        return view;
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        return false;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        readPrefs();
        finishTap();
        resetComposing();
        clearList();

        int type = attribute.inputType;
        int cls = type & InputType.TYPE_MASK_CLASS;
        // Number, phone and date fields understand the keypad directly; leave them to the app.
        active = cls == InputType.TYPE_CLASS_TEXT;
        if (!active) {
            hideStatusIcon();
            return;
        }
        int variation = type & InputType.TYPE_MASK_VARIATION;
        passwordField = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
        boolean latinField = passwordField
                || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_URI;
        mode = prefs.getInt("last_mode", M_JYUTPING);
        List<Integer> modes = enabledModes();
        if (!modes.contains(mode)) mode = modes.get(0);
        if (latinField && mode < M_ABC) mode = M_LOWER;
        loadDictsForMode();
        showStatusIcon(MODE_ICON[mode]);
    }

    @Override
    public void onFinishInput() {
        finishTap();
        resetComposing();
        clearList();
        hideStatusIcon();
        handler.removeCallbacks(saveUser);
        user.save();
        super.onFinishInput();
    }

    private void readPrefs() {
        letterTaps = prefs.getBoolean("cangjie_multitap", false);
        learnOn = prefs.getBoolean("learn", true);
        emojiOn = prefs.getBoolean("emoji", true);
        assocOn = prefs.getBoolean("assoc", true);
        backDelete = prefs.getBoolean("back_delete", true);
        backDeletesText = prefs.getBoolean("back_delete_text", true);
        tapTimeoutMs = parse(prefs.getString("tap_timeout", "900"), 900);
        int size = parse(prefs.getString("cand_size", "22"), 22);
        if (size != candSize) {
            candSize = size;
            if (view != null) view.setTextSize(size);
        }
    }

    private static int parse(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private List<Integer> enabledModes() {
        List<Integer> modes = new ArrayList<Integer>();
        for (int m = M_JYUTPING; m <= M_NUMBER; m++) {
            if (!prefs.getBoolean(MODE_PREF[m], true)) continue;
            if (m == M_CUSTOM && !Dicts.customFile().exists()) continue;
            modes.add(m);
        }
        if (modes.isEmpty()) modes.add(M_JYUTPING);
        return modes;
    }

    private boolean isChinese() {
        return mode < M_ABC;
    }

    private void loadDictsForMode() {
        if (isChinese()) {
            Dicts.ensure(this, MODE_DICT[mode], dictReady);
            if (assocOn) Dicts.ensure(this, Dicts.ASSOC, null);
            if (emojiOn) Dicts.ensure(this, Dicts.EMOJI_WORDS, null);
        }
    }

    private Engine engine() {
        Engine e = isChinese() ? (Engine) Dicts.get(MODE_DICT[mode]) : null;
        if (e instanceof Engine.Jyutping) {
            Engine.Jyutping j = (Engine.Jyutping) e;
            j.lazyMask = (prefs.getBoolean("lazy_nl", true) ? Engine.Jyutping.LAZY_NL : 0)
                    | (prefs.getBoolean("lazy_ng", true) ? Engine.Jyutping.LAZY_NG : 0)
                    | (prefs.getBoolean("lazy_gwo", true) ? Engine.Jyutping.LAZY_GWO : 0);
            j.abbreviations = prefs.getBoolean("abbr", true);
            j.looseSpelling = prefs.getBoolean("loose", true);
            j.user = learnOn ? user : null;
        }
        return e;
    }

    private void learnLater() {
        handler.removeCallbacks(saveUser);
        handler.postDelayed(saveUser, 5000);
    }

    private String badge() {
        if (mode == M_CUSTOM) return Dicts.customName.length() > 2 ? Dicts.customName.substring(0, 2) : Dicts.customName;
        return MODE_BADGE[mode];
    }

    private String modeName(int m) {
        return m == M_CUSTOM ? Dicts.customName : MODE_NAME[m];
    }

    // ------------------------------------------------------------------ key dispatch

    private static int digitOf(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) return keyCode - KeyEvent.KEYCODE_0;
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            return keyCode - KeyEvent.KEYCODE_NUMPAD_0;
        }
        return -1;
    }

    private static boolean isTracked(int keyCode) {
        return digitOf(keyCode) >= 0 || keyCode == KeyEvent.KEYCODE_STAR || keyCode == KeyEvent.KEYCODE_POUND;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (active && handleKeyDown(keyCode, event)) {
            downConsumed.put(keyCode, true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (downConsumed.get(keyCode)) {
            downConsumed.delete(keyCode);
            if (active && isTracked(keyCode)) {
                if (longPressedKey == keyCode) {
                    longPressedKey = -1;
                } else {
                    handleTap(keyCode);
                }
            } else if (keyCode == KeyEvent.KEYCODE_BACK && backPending) {
                backPending = false;
                if (longPressedKey == keyCode) {
                    longPressedKey = -1;
                } else {
                    deletePreviousChar();
                }
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && backPending) {
            // Holding BACK always means "go back", even with text in the field.
            backPending = false;
            longPressedKey = keyCode;
            sendDownUpKeyEvents(KeyEvent.KEYCODE_BACK);
            return true;
        }
        if (active && handleLongPress(keyCode)) {
            longPressedKey = keyCode;
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    private boolean handleKeyDown(int keyCode, KeyEvent event) {
        if (isTracked(keyCode)) {
            // Acted on at key-up so that holding the key can do something else.
            if (event.getRepeatCount() == 0) {
                event.startTracking();
                longPressedKey = -1;
            }
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL:
                return onDelete(false);
            case KeyEvent.KEYCODE_CLEAR:
                // Apps ignore CLEAR, so the C key on some keypads would otherwise do nothing.
                if (onDelete(false)) return true;
                if (!fieldHasText()) return false;
                deletePreviousChar();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (onDelete(true)) return true;
                if (event.getRepeatCount() > 0) return backPending;
                if (!backDeletesText || !fieldHasText()) return false;
                event.startTracking();
                longPressedKey = -1;
                backPending = true;
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return onDpad(keyCode);
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return onConfirm();
            case KeyEvent.KEYCODE_SPACE:
                if (listKind == LIST_COMPOSE) {
                    pick(highlight);
                    return true;
                }
                break;
            default:
                break;
        }
        // Some other key: settle what is in progress and let the app have it.
        finishTap();
        if (listKind == LIST_ASSOC || listKind == LIST_SYMBOL) clearList();
        return false;
    }

    private void handleTap(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_POUND) {
            if (listKind == LIST_SYMBOL) {
                showSymbolSet(symbolSet - 1);
            } else {
                switchMode();
            }
        } else if (keyCode == KeyEvent.KEYCODE_STAR) {
            onStar();
        } else {
            onDigit(digitOf(keyCode));
        }
    }

    private boolean handleLongPress(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_POUND) {
            finishTap();
            commitChosenAndReset();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_STAR) {
            if (listKind == LIST_COMPOSE) return false;
            finishTap();
            clearList();
            Intent i = new Intent(this, SettingsActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        }
        int d = digitOf(keyCode);
        if (d < 0 || mode == M_NUMBER || listKind == LIST_COMPOSE) return false;
        if (listKind == LIST_SYMBOL || (listKind == LIST_ASSOC && selecting)) return false;
        finishTap();
        clearList();
        commit(String.valueOf(d));
        return true;
    }

    // ------------------------------------------------------------------ key actions

    private void onDigit(int d) {
        char key = (char) ('0' + d);
        if (mode == M_NUMBER) {
            commit(String.valueOf(key));
            return;
        }
        if (listKind == LIST_SYMBOL || (selecting && listKind != LIST_NONE)) {
            if (d == 0) {
                movePage(1, true);
                return;
            }
            int page = view.pageOf(highlight);
            int index = view.pageStart(page) + d - 1;
            if (index < view.pageEnd(page)) pick(index);
            return;
        }
        if (listKind == LIST_ASSOC) clearList();
        if (!isChinese()) {
            englishTap(d);
            return;
        }
        Engine engine = engine();
        if (engine == null) {
            loadDictsForMode();
            if (mode == M_CUSTOM) {
                say(Dicts.customStatus.length() > 0 ? Dicts.customStatus : "正在載入碼表…");
            } else {
                say("正在載入字典，請稍候…");
            }
            return;
        }
        String typed = input.toString();
        boolean taps = letterTaps && (mode == M_CANGJIE || mode == M_QUICK);
        if (taps && d >= 2 && d <= 9) {
            letterTap(d, engine);
            return;
        }
        if (taps && d == 1 && engine.acceptsKey('?', typed)) {
            tapKey = -1;
            input.append('?');
            refresh();
            return;
        }
        if (!taps && engine.acceptsKey(key, typed)) {
            input.append(key);
            refresh();
            return;
        }
        if (listKind == LIST_COMPOSE) {
            if (d == 0) pick(highlight);
            return;
        }
        if (d == 0) {
            commit(" ");
        } else if (d == 1) {
            showSymbols();
        } else {
            commit(String.valueOf(key));
        }
    }

    /** Cangjie / Quick with multi-tap: repeated presses of one key cycle through its letters. */
    private void letterTap(int d, Engine engine) {
        String letters = TAP_LETTERS[d];
        long now = SystemClock.uptimeMillis();
        if (tapKey == d && now - tapTime < tapTimeoutMs && input.length() > 0) {
            tapIndex = (tapIndex + 1) % letters.length();
            input.setCharAt(input.length() - 1, letters.charAt(tapIndex));
        } else {
            if (!engine.acceptsKey('a', input.toString())) return;
            tapKey = d;
            tapIndex = 0;
            input.append(letters.charAt(0));
        }
        tapTime = now;
        handler.removeCallbacks(tapTimeout);
        handler.postDelayed(tapTimeout, tapTimeoutMs);
        refresh();
    }

    private void englishTap(int d) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (d == 0) {
            finishTap();
            commit(" ");
            return;
        }
        String group = d == 1 ? TAP_PUNCT : TAP_LETTERS[d] + d;
        long now = SystemClock.uptimeMillis();
        boolean upper;
        if (englishPending && tapKey == d && now - tapTime < tapTimeoutMs) {
            tapIndex = (tapIndex + 1) % group.length();
            upper = tapUpper;
        } else {
            finishTap();
            tapKey = d;
            tapIndex = 0;
            upper = mode == M_UPPER || (mode == M_ABC && atSentenceStart(ic));
            tapUpper = upper;
        }
        char c = group.charAt(tapIndex);
        if (upper) c = Character.toUpperCase(c);
        ic.setComposingText(String.valueOf(c), 1);
        englishPending = true;
        tapTime = now;
        handler.removeCallbacks(tapTimeout);
        handler.postDelayed(tapTimeout, tapTimeoutMs);
    }

    private boolean tapUpper;

    private static boolean atSentenceStart(InputConnection ic) {
        CharSequence before = ic.getTextBeforeCursor(4, 0);
        if (before == null) return true;
        String s = before.toString();
        int end = s.length();
        if (end == 0) return true;
        if (s.charAt(end - 1) == '\n') return true;
        if (s.charAt(end - 1) != ' ') return false;
        while (end > 0 && s.charAt(end - 1) == ' ') end--;
        if (end == 0) return s.length() == before.length() && before.length() < 4;
        char last = s.charAt(end - 1);
        return last == '.' || last == '!' || last == '?' || last == '\n';
    }

    /** Settles a pending multi-tap letter. */
    private void finishTap() {
        handler.removeCallbacks(tapTimeout);
        tapKey = -1;
        if (englishPending) {
            englishPending = false;
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.finishComposingText();
        }
    }

    private void onStar() {
        if (listKind == LIST_COMPOSE) return;
        finishTap();
        if (listKind == LIST_SYMBOL) {
            showSymbolSet(symbolSet + 1);
        } else {
            showSymbols();
        }
    }

    private void switchMode() {
        finishTap();
        commitChosenAndReset();
        List<Integer> modes = enabledModes();
        int next = modes.indexOf(mode) + 1;
        mode = modes.get(next >= modes.size() ? 0 : next);
        if (!passwordField) prefs.edit().putInt("last_mode", mode).apply();
        loadDictsForMode();
        showStatusIcon(MODE_ICON[mode]);
        say(modeName(mode));
    }

    private boolean onDelete(boolean isBack) {
        if (englishPending) {
            handler.removeCallbacks(tapTimeout);
            englishPending = false;
            tapKey = -1;
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.commitText("", 1);
            return true;
        }
        if (listKind == LIST_SYMBOL || listKind == LIST_ASSOC) {
            clearList();
            return true;
        }
        if (listKind != LIST_COMPOSE) return false;
        if (isBack && selecting) {
            selecting = false;
            updateView();
            return true;
        }
        if (isBack && !backDelete) {
            resetComposing();
            clearList();
            return true;
        }
        tapKey = -1;
        if (input.length() > 0) {
            input.deleteCharAt(input.length() - 1);
        } else if (!chosenSteps.isEmpty()) {
            String[] step = chosenSteps.remove(chosenSteps.size() - 1);
            chosen.setLength(chosen.length() - step[0].length());
            input.insert(0, step[1]);
        }
        refresh();
        return true;
    }

    /** Whether the editor has text before the cursor (or a selection) that BACK / C could delete. */
    private boolean fieldHasText() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return false;
        CharSequence selected = ic.getSelectedText(0);
        if (selected != null && selected.length() > 0) return true;
        CharSequence before = ic.getTextBeforeCursor(1, 0);
        return before != null && before.length() > 0;
    }

    /** Lets the app delete as if DEL were pressed, so selections and emoji are handled properly. */
    private void deletePreviousChar() {
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
    }

    private boolean onDpad(int keyCode) {
        if (listKind == LIST_NONE) {
            finishTap();
            return false;
        }
        if (listKind == LIST_ASSOC && !selecting) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                selecting = true;
                highlight = 0;
                updateView();
                return true;
            }
            clearList();
            return false;
        }
        int n = cands.size();
        if (n == 0) return true;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                highlight = (highlight - 1 + n) % n;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                highlight = (highlight + 1) % n;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!selecting) {
                    selecting = true;
                } else {
                    movePage(1, true);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (selecting && view.pageOf(highlight) == 0 && listKind != LIST_SYMBOL) {
                    selecting = false;
                } else {
                    movePage(-1, false);
                    return true;
                }
                break;
            default:
                break;
        }
        updateView();
        return true;
    }

    private void movePage(int delta, boolean wrap) {
        if (view == null || cands.isEmpty()) return;
        int pages = view.pageCount();
        int page = view.pageOf(highlight) + delta;
        if (page >= pages) page = wrap ? 0 : pages - 1;
        if (page < 0) page = 0;
        highlight = view.pageStart(page);
        updateView();
    }

    private boolean onConfirm() {
        if (englishPending) {
            finishTap();
            return true;
        }
        if (listKind == LIST_NONE) return false;
        if (listKind == LIST_ASSOC && !selecting) {
            clearList();
            return false;
        }
        pick(highlight);
        return true;
    }

    @Override
    public void onCandidateTapped(int index) {
        if (listKind != LIST_NONE) pick(index);
    }

    // ------------------------------------------------------------------ candidates

    private void pick(int index) {
        if (listKind == LIST_COMPOSE && cands.isEmpty()) {
            if (chosen.length() > 0) {
                String out = chosen.toString();
                resetComposing();
                commit(out);
                showAssociations(out);
            }
            return;
        }
        if (index < 0 || index >= cands.size()) return;
        Candidate c = cands.get(index);
        switch (listKind) {
            case LIST_SYMBOL:
                clearList();
                commit(c.text);
                if (learnOn && !"\n".equals(c.text)) {
                    user.learnSymbol(c.text);
                    learnLater();
                }
                break;
            case LIST_ASSOC:
                commit(c.text);
                showAssociations(c.text);
                break;
            case LIST_COMPOSE:
                if (learnOn) {
                    user.learnPick(mode, UserDict.keysFor(input.toString(), c), c.text);
                    learnLater();
                }
                if (c.consumed > 0 && c.consumed < input.length()) {
                    chosenSteps.add(new String[] {c.text, input.substring(0, c.consumed), c.code});
                    chosen.append(c.text);
                    input.delete(0, c.consumed);
                    tapKey = -1;
                    refresh();
                } else {
                    String out = chosen.toString() + c.text;
                    if (learnOn && mode == M_JYUTPING && !chosenSteps.isEmpty() && isHanzi(out)) {
                        // A phrase built from several picks becomes a word of its own.
                        StringBuilder reading = new StringBuilder();
                        for (String[] step : chosenSteps) reading.append(step[2]).append(' ');
                        reading.append(c.code);
                        user.learnWord(reading.toString(), out);
                        StringBuilder keys = new StringBuilder();
                        for (String[] step : chosenSteps) keys.append(step[1]);
                        user.learnPick(mode, keys.append(input).toString(), out);
                    }
                    resetComposing();
                    commit(out);
                    showAssociations(out);
                }
                break;
            default:
                break;
        }
    }

    private void refresh() {
        if (input.length() == 0 && chosen.length() == 0) {
            clearList();
            return;
        }
        Engine engine = engine();
        if (engine == null || input.length() == 0) {
            cands = Collections.<Candidate>emptyList();
        } else {
            List<Candidate> list = engine.query(input.toString());
            if (emojiOn) addEmoji(list);
            if (learnOn) user.rerank(mode, input.toString(), list);
            cands = list;
        }
        listKind = LIST_COMPOSE;
        highlight = 0;
        selecting = false;
        updateView();
    }

    /** After the first top candidate that names an emoji (開心, 笑, 貓 …), offer up to three emoji. */
    private static void addEmoji(List<Candidate> list) {
        for (int k = 0; k < list.size() && k < 6; k++) {
            Candidate c = list.get(k);
            String[] emoji = Dicts.emojiFor(c.text);
            if (emoji.length == 0) continue;
            for (int e = 0; e < emoji.length && e < 3; e++) {
                list.add(k + 1 + e, new Candidate(emoji[e], c.code, c.consumed, c.tier));
            }
            return;
        }
    }

    private static boolean isHanzi(String s) {
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (!(c >= 0x3400 && c <= 0x9FFF) && !(c >= 0xF900 && c <= 0xFAFF)) return false;
        }
        return s.length() > 0;
    }

    private void showAssociations(String committed) {
        if (!assocOn || !isChinese() || committed.length() == 0) {
            clearList();
            return;
        }
        String[] words = Dicts.associations(committed.substring(committed.length() - 1));
        if (words.length == 0) {
            clearList();
            return;
        }
        List<Candidate> list = new ArrayList<Candidate>(words.length);
        for (String w : words) {
            if (w.length() > 0) list.add(new Candidate(w, "", -1));
        }
        cands = list;
        listKind = LIST_ASSOC;
        highlight = 0;
        selecting = false;
        updateView();
    }

    /** Symbol pages: punctuation, recently used, then the emoji groups. Each entry is {name, items...}. */
    private List<String[]> symbolSets() {
        List<String[]> sets = new ArrayList<String[]>();
        sets.add(withName("中文標點", SYMBOLS_ZH));
        sets.add(withName("英文符號", SYMBOLS_EN));
        List<String> recent = user.recentSymbols();
        if (learnOn && !recent.isEmpty()) sets.add(withName("最近", recent.toArray(new String[0])));
        sets.addAll(Dicts.emojiGroups());
        return sets;
    }

    private static String[] withName(String name, String[] items) {
        String[] out = new String[items.length + 1];
        out[0] = name;
        System.arraycopy(items, 0, out, 1, items.length);
        return out;
    }

    private void showSymbols() {
        Dicts.ensure(this, Dicts.EMOJI_GROUPS, null);
        showSymbolSet(isChinese() ? 0 : 1);
    }

    private void showSymbolSet(int index) {
        List<String[]> sets = symbolSets();
        symbolSet = (index % sets.size() + sets.size()) % sets.size();
        String[] set = sets.get(symbolSet);
        List<Candidate> list = new ArrayList<Candidate>(set.length - 1);
        for (int k = 1; k < set.length; k++) list.add(new Candidate(set[k], "", -1));
        cands = list;
        listKind = LIST_SYMBOL;
        highlight = 0;
        selecting = true;
        updateView();
    }

    private void updateView() {
        if (view == null) setCandidatesView(onCreateCandidatesView());
        Candidate h = highlight >= 0 && highlight < cands.size() ? cands.get(highlight) : null;
        String pre;
        switch (listKind) {
            case LIST_COMPOSE:
                Engine engine = engine();
                if (engine == null) {
                    pre = "載入中…";
                } else if (input.length() == 0) {
                    pre = chosen.toString();
                } else {
                    pre = chosen + engine.preedit(input.toString(), h);
                }
                break;
            case LIST_ASSOC:
                pre = selecting ? "聯想：按數字選擇" : "聯想：按 ▼ 選擇";
                break;
            case LIST_SYMBOL:
                List<String[]> sets = symbolSets();
                int index = Math.min(symbolSet, sets.size() - 1);
                pre = sets.get(index)[0] + " " + (index + 1) + "/" + sets.size() + "　＊下一類 ＃上一類 0下一頁";
                break;
            default:
                pre = "";
        }
        view.show(badge(), pre, cands, highlight, selecting || listKind == LIST_SYMBOL);
        setCandidatesViewShown(listKind != LIST_NONE);
    }

    private void clearList() {
        cands = Collections.emptyList();
        listKind = LIST_NONE;
        highlight = 0;
        selecting = false;
        setCandidatesViewShown(false);
    }

    private void resetComposing() {
        input.setLength(0);
        chosen.setLength(0);
        chosenSteps.clear();
        tapKey = -1;
    }

    private void commitChosenAndReset() {
        if (chosen.length() > 0) commit(chosen.toString());
        resetComposing();
        clearList();
    }

    private void commit(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(text, 1);
    }

    private void say(String message) {
        if (toast != null) toast.cancel();
        toast = Toast.makeText(this, message, Toast.LENGTH_SHORT);
        toast.show();
    }
}
