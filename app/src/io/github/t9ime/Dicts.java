package io.github.t9ime;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Loads dictionaries in the background and keeps them for the life of the process. */
final class Dicts {
    static final int JYUTPING = 0, CANGJIE = 1, QUICK = 2, STROKE = 3, CUSTOM = 4, ASSOC = 5,
            EMOJI_WORDS = 6, EMOJI_GROUPS = 7;
    private static final int COUNT = 8;

    private static final Object[] loaded = new Object[COUNT];
    private static final boolean[] loading = new boolean[COUNT];
    private static final Handler main = new Handler(Looper.getMainLooper());

    static String customName = "自訂";
    static String customStatus = "";
    private static long customStamp = -1;

    private Dicts() {}

    private static UserDict user;

    /** The learning store shared by the keyboard and the settings screen. */
    static synchronized UserDict user(Context context) {
        if (user == null) user = new UserDict(new File(context.getApplicationContext().getFilesDir(), "user.txt"));
        return user;
    }

    static File customFile() {
        return new File(Environment.getExternalStorageDirectory(), "T9IME/custom.txt");
    }

    static synchronized Object get(int which) {
        return loaded[which];
    }

    /** Starts loading {@code which} if needed; {@code onReady} runs on the main thread once it is available. */
    static void ensure(final Context context, final int which, final Runnable onReady) {
        synchronized (Dicts.class) {
            if (which == CUSTOM) {
                File f = customFile();
                long stamp = f.exists() ? f.lastModified() ^ f.length() : 0;
                if (stamp != customStamp) {
                    loaded[CUSTOM] = null;
                    customStamp = stamp;
                }
            }
            if (loaded[which] != null || loading[which]) return;
            loading[which] = true;
        }
        final Context app = context.getApplicationContext();
        new Thread("dict-load-" + which) {
            @Override
            public void run() {
                Object result = null;
                try {
                    result = load(app, which);
                } catch (IOException e) {
                    if (which == CUSTOM) customStatus = "讀取失敗：" + e.getMessage();
                } catch (OutOfMemoryError e) {
                    if (which == CUSTOM) customStatus = "記憶體不足";
                }
                synchronized (Dicts.class) {
                    loaded[which] = result;
                    loading[which] = false;
                }
                if (result != null && onReady != null) main.post(onReady);
            }
        }.start();
    }

    private static Table asset(Context c, String name) throws IOException {
        return Table.read(c.getAssets().open(name));
    }

    private static Object load(Context c, int which) throws IOException {
        switch (which) {
            case JYUTPING:
                return new Engine.Jyutping(Table.read(c.getAssets().open("jyutping.txt"), 4),
                        asset(c, "jyutping_abbr.txt"), asset(c, "loose.txt"));
            case CANGJIE: return new Engine.Shape(asset(c, "cangjie.txt"), Engine.Shape.CANGJIE);
            case QUICK: return new Engine.Shape(asset(c, "quick.txt"), Engine.Shape.QUICK);
            case STROKE: return new Engine.Shape(asset(c, "stroke.txt"), Engine.Shape.STROKE);
            case ASSOC: return asset(c, "assoc.txt");
            case EMOJI_WORDS: return asset(c, "emoji_words.txt");
            case EMOJI_GROUPS: return loadEmojiGroups(c);
            case CUSTOM: return loadCustom();
            default: return null;
        }
    }

    /**
     * Reads /sdcard/T9IME/custom.txt: one "digits code &lt;tab or space&gt; text [score]" entry per line.
     * A line "#name=九方" sets the mode name shown on screen.
     */
    private static Engine loadCustom() throws IOException {
        File f = customFile();
        if (!f.exists()) {
            customStatus = "未找到 " + f.getPath();
            return null;
        }
        final List<String[]> rows = new ArrayList<String[]>();
        StringBuilder keys = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            String line;
            int lineNo = 0;
            while ((line = r.readLine()) != null) {
                lineNo++;
                if (lineNo == 1 && line.length() > 0 && line.charAt(0) == '﻿') line = line.substring(1);
                line = line.trim();
                if (line.length() == 0) continue;
                if (line.startsWith("#")) {
                    if (line.startsWith("#name=")) customName = line.substring(6).trim();
                    continue;
                }
                String[] parts = line.split("[\\t ]+");
                if (parts.length < 2 || !parts[0].matches("[0-9]+")) continue;
                String score = parts.length >= 3 && parts[2].matches("[0-9]{1,9}") ? parts[2] : null;
                rows.add(new String[] {parts[0], parts[1], score, String.valueOf(rows.size())});
                for (int k = 0; k < parts[0].length(); k++) {
                    if (keys.indexOf(parts[0].substring(k, k + 1)) < 0) keys.append(parts[0].charAt(k));
                }
            }
        } finally {
            r.close();
        }
        if (rows.isEmpty()) {
            customStatus = "碼表沒有有效資料";
            return null;
        }
        final int n = rows.size();
        for (String[] row : rows) {
            // Without an explicit score, earlier lines rank higher.
            if (row[2] == null) row[2] = String.valueOf(n - Integer.parseInt(row[3]));
        }
        Collections.sort(rows, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                int c = a[0].compareTo(b[0]);
                if (c != 0) return c;
                return Integer.parseInt(b[2]) - Integer.parseInt(a[2]);
            }
        });
        StringBuilder data = new StringBuilder(n * 12);
        for (String[] row : rows) {
            data.append(row[0]).append('\t').append(row[1]).append('\t').append(row[2]).append('\n');
        }
        customStatus = "已載入 " + n + " 個字碼";
        return new Engine.Shape(new Table(data.toString()), Engine.Shape.CUSTOM, keys.toString());
    }

    /** Emoji pages for the symbol menu: each entry is {name, emoji...}. */
    private static List<String[]> loadEmojiGroups(Context c) throws IOException {
        List<String[]> groups = new ArrayList<String[]>();
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("emoji_groups.txt"), "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String[] emoji = line.substring(tab + 1).split(" ");
                String[] group = new String[emoji.length + 1];
                group[0] = line.substring(0, tab);
                System.arraycopy(emoji, 0, group, 1, emoji.length);
                groups.add(group);
            }
        } finally {
            r.close();
        }
        return groups;
    }

    @SuppressWarnings("unchecked")
    static List<String[]> emojiGroups() {
        Object o = get(EMOJI_GROUPS);
        return o == null ? new ArrayList<String[]>() : (List<String[]>) o;
    }

    /** Emoji for a word such as 開心, or an empty array. */
    static String[] emojiFor(String word) {
        return lookupList(EMOJI_WORDS, word);
    }

    /** Common continuations for words starting with {@code ch}. */
    static String[] associations(String ch) {
        return lookupList(ASSOC, ch);
    }

    private static String[] lookupList(int which, String key) {
        Table t = (Table) get(which);
        if (t == null || key.length() == 0) return new String[0];
        int i = t.lowerBound(key);
        if (i >= t.size || !t.keyEquals(i, key)) return new String[0];
        return t.field(i, 1).split(" ");
    }
}
