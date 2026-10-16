package io.github.t9ime;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the user has picked before: recently used candidates move to the front, phrases built from
 * several Jyutping picks become new words, and recently used symbols get their own page.
 */
final class UserDict {
    private static final int MAX_PICKS = 6000;
    private static final int MAX_WORDS = 3000;
    private static final int MAX_RECENT = 27;

    static final class Word {
        final String digits;
        final String lengths;
        final String reading;
        final String text;
        long last;

        Word(String reading, String text, long last) {
            this.reading = reading;
            this.text = text;
            this.last = last;
            StringBuilder d = new StringBuilder();
            StringBuilder l = new StringBuilder();
            for (String syl : reading.split(" ")) {
                for (int k = 0; k < syl.length(); k++) d.append(Engine.t9Digit(syl.charAt(k)));
                l.append((char) ('0' + Math.min(syl.length(), 9)));
            }
            digits = d.toString();
            lengths = l.toString();
        }
    }

    private final File file;
    private final HashMap<String, Long> picks = new HashMap<String, Long>();
    private final HashMap<String, Word> words = new HashMap<String, Word>();
    private final ArrayList<String> recent = new ArrayList<String>();
    private long clock;
    private boolean dirty;

    UserDict(File file) {
        this.file = file;
        load();
    }

    /** A pick is remembered for the exact keys typed, so it only wins where it was chosen. */
    private static String pickKey(int mode, String keys, String text) {
        return mode + "\t" + keys + "\t" + text;
    }

    /** The part of the input a candidate covers. */
    static String keysFor(String input, Candidate c) {
        return c.consumed < 0 || c.consumed > input.length() ? input : input.substring(0, c.consumed);
    }

    /** Use a counter rather than wall time so a wrong phone clock cannot scramble the order. */
    private long tick() {
        return ++clock;
    }

    void learnPick(int mode, String keys, String text) {
        if (keys.length() == 0 || keys.indexOf('\t') >= 0) return;
        picks.put(pickKey(mode, keys, text), tick());
        dirty = true;
    }

    void learnWord(String reading, String text) {
        if (text.length() < 2 || text.length() > 8) return;
        String key = reading + "\t" + text;
        Word w = words.get(key);
        if (w == null) {
            w = new Word(reading, text, 0);
            words.put(key, w);
        }
        w.last = tick();
        dirty = true;
    }

    void learnSymbol(String symbol) {
        recent.remove(symbol);
        recent.add(0, symbol);
        while (recent.size() > MAX_RECENT) recent.remove(recent.size() - 1);
        dirty = true;
    }

    List<String> recentSymbols() {
        return recent;
    }

    Iterable<Word> words() {
        return words.values();
    }

    void clear() {
        picks.clear();
        words.clear();
        recent.clear();
        clock = 0;
        dirty = true;
        save();
    }

    /**
     * Moves candidates picked before for this input to the front, most recent first. Candidates
     * that use all of the input stay ahead of those covering only its start, and those covering
     * more of it ahead of those covering less.
     */
    void rerank(int mode, String input, List<Candidate> list) {
        if (picks.isEmpty() || list.isEmpty()) return;
        final HashMap<Candidate, Long> used = new HashMap<Candidate, Long>();
        for (Candidate c : list) {
            Long t = picks.get(pickKey(mode, keysFor(input, c), c.text));
            if (t != null) used.put(c, t);
        }
        if (used.isEmpty()) return;
        List<Candidate> learnedFull = new ArrayList<Candidate>();
        List<Candidate> otherFull = new ArrayList<Candidate>();
        List<Candidate> partial = new ArrayList<Candidate>();
        for (Candidate c : list) {
            if (c.consumed >= 0) partial.add(c);
            else (used.containsKey(c) ? learnedFull : otherFull).add(c);
        }
        Collections.sort(learnedFull, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                return Long.compare(used.get(b), used.get(a));
            }
        });
        // Partials keep "covers more input first"; learned ones lead among those of the same length.
        Collections.sort(partial, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                if (a.consumed != b.consumed) return b.consumed - a.consumed;
                Long ta = used.get(a);
                Long tb = used.get(b);
                if (ta == null || tb == null) return ta != null ? -1 : tb != null ? 1 : 0;
                return Long.compare(tb, ta);
            }
        });
        list.clear();
        list.addAll(learnedFull);
        list.addAll(otherFull);
        list.addAll(partial);
    }

    // ------------------------------------------------------------------ persistence

    private void load() {
        if (file == null || !file.exists()) return;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.split("\t");
                    try {
                        if (p[0].equals("P") && p.length == 5) {
                            long t = Long.parseLong(p[4]);
                            picks.put(p[1] + "\t" + p[2] + "\t" + p[3], t);
                            clock = Math.max(clock, t);
                        } else if (p[0].equals("W") && p.length == 4) {
                            long t = Long.parseLong(p[3]);
                            words.put(p[1] + "\t" + p[2], new Word(p[1], p[2], t));
                            clock = Math.max(clock, t);
                        } else if (p[0].equals("R") && p.length == 2) {
                            recent.add(p[1]);
                        }
                    } catch (NumberFormatException ignored) {
                        // skip a damaged line
                    }
                }
            } finally {
                r.close();
            }
        } catch (IOException ignored) {
            // start fresh
        }
    }

    void save() {
        if (!dirty || file == null) return;
        trim();
        File tmp = new File(file.getPath() + ".tmp");
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            try {
                for (Map.Entry<String, Long> e : picks.entrySet()) {
                    w.write("P\t" + e.getKey() + "\t" + e.getValue() + "\n");
                }
                for (Word word : words.values()) {
                    w.write("W\t" + word.reading + "\t" + word.text + "\t" + word.last + "\n");
                }
                for (String s : recent) {
                    if (s.indexOf('\n') < 0 && s.indexOf('\t') < 0) w.write("R\t" + s + "\n");
                }
            } finally {
                w.close();
            }
            if (tmp.renameTo(file)) dirty = false;
        } catch (IOException ignored) {
            // try again next time
        }
    }

    private void trim() {
        if (picks.size() > MAX_PICKS) {
            List<Map.Entry<String, Long>> all = new ArrayList<Map.Entry<String, Long>>(picks.entrySet());
            Collections.sort(all, new Comparator<Map.Entry<String, Long>>() {
                @Override
                public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                    return Long.compare(b.getValue(), a.getValue());
                }
            });
            for (int k = MAX_PICKS * 3 / 4; k < all.size(); k++) picks.remove(all.get(k).getKey());
        }
        if (words.size() > MAX_WORDS) {
            List<Map.Entry<String, Word>> all = new ArrayList<Map.Entry<String, Word>>(words.entrySet());
            Collections.sort(all, new Comparator<Map.Entry<String, Word>>() {
                @Override
                public int compare(Map.Entry<String, Word> a, Map.Entry<String, Word> b) {
                    return Long.compare(b.getValue().last, a.getValue().last);
                }
            });
            for (int k = MAX_WORDS * 3 / 4; k < all.size(); k++) words.remove(all.get(k).getKey());
        }
    }
}
