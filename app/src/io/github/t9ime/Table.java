package io.github.t9ime;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;

/**
 * A sorted, tab-separated lookup table kept as one big string to stay light on old phones.
 * Each line is "key \t ... \t score"; lines are sorted by key.
 */
final class Table {
    final String data;
    final int size;
    private final int[] starts;
    private final int[] keyLens;
    private final int[] scores;
    private final int[] extras;

    Table(String data) {
        this(data, -1);
    }

    /** @param extraCol a numeric column to index besides the score, or -1. */
    Table(String data, int extraCol) {
        this.data = data;
        int len = data.length();
        int n = 0;
        for (int i = 0; i < len; i++) {
            if (data.charAt(i) == '\n') n++;
        }
        if (len > 0 && data.charAt(len - 1) != '\n') n++;
        starts = new int[n];
        keyLens = new int[n];
        scores = new int[n];
        extras = extraCol >= 0 ? new int[n] : null;
        int line = 0;
        int pos = 0;
        while (pos < len && line < n) {
            int end = data.indexOf('\n', pos);
            if (end < 0) end = len;
            if (end > pos) {
                int tab = data.indexOf('\t', pos);
                if (tab < 0 || tab > end) tab = end;
                int lastTab = data.lastIndexOf('\t', end - 1);
                starts[line] = pos;
                keyLens[line] = tab - pos;
                scores[line] = lastTab > pos ? parseInt(lastTab + 1, end) : 0;
                if (extras != null) {
                    int f = pos;
                    for (int c = 0; c < extraCol && f >= 0; c++) {
                        f = data.indexOf('\t', f);
                        if (f >= 0) f++;
                    }
                    int fe = f < 0 ? -1 : data.indexOf('\t', f);
                    extras[line] = f >= 0 && fe > f && fe <= end ? parseInt(f, fe) : 0;
                }
                line++;
            }
            pos = end + 1;
        }
        size = line;
    }

    static Table read(InputStream in) throws IOException {
        return read(in, -1);
    }

    static Table read(InputStream in, int extraCol) throws IOException {
        Reader r = new InputStreamReader(in, "UTF-8");
        try {
            StringBuilder sb = new StringBuilder(1 << 18);
            char[] buf = new char[16384];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            return new Table(sb.toString(), extraCol);
        } finally {
            r.close();
        }
    }

    private int parseInt(int from, int to) {
        int v = 0;
        for (int i = from; i < to; i++) {
            char c = data.charAt(i);
            if (c < '0' || c > '9') return 0;
            v = v * 10 + (c - '0');
            if (v > 100000000) return 100000000;
        }
        return v;
    }

    int score(int i) {
        return scores[i];
    }

    int extra(int i) {
        return extras == null ? 0 : extras[i];
    }

    int keyLength(int i) {
        return keyLens[i];
    }

    char keyChar(int i, int k) {
        return data.charAt(starts[i] + k);
    }

    String key(int i) {
        return data.substring(starts[i], starts[i] + keyLens[i]);
    }

    String field(int i, int col) {
        int s = starts[i];
        for (int c = 0; c < col; c++) {
            s = data.indexOf('\t', s) + 1;
        }
        int e = s;
        int len = data.length();
        while (e < len) {
            char ch = data.charAt(e);
            if (ch == '\t' || ch == '\n') break;
            e++;
        }
        return data.substring(s, e);
    }

    /** Compares only the first p.length() characters of key i with p. */
    private int comparePrefix(int i, String p) {
        int s = starts[i];
        int klen = keyLens[i];
        int n = p.length();
        for (int k = 0; k < n; k++) {
            if (k >= klen) return -1;
            char c = data.charAt(s + k);
            char d = p.charAt(k);
            if (c != d) return c < d ? -1 : 1;
        }
        return 0;
    }

    boolean keyEquals(int i, String p) {
        return keyLens[i] == p.length() && comparePrefix(i, p) == 0;
    }

    /** First line whose key starts with p (or would follow p). */
    int lowerBound(String p) {
        int lo = 0, hi = size;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (comparePrefix(mid, p) < 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** One past the last line whose key starts with p. */
    int upperBound(String p) {
        int lo = 0, hi = size;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (comparePrefix(mid, p) <= 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}
