package io.github.t9ime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** Turns the keys typed so far into a ranked candidate list. */
abstract class Engine {
    static final int MAX_CANDIDATES = 450;

    /** Whether this key (a digit or letter) extends the current input. */
    abstract boolean acceptsKey(char key, String input);

    abstract List<Candidate> query(String input);

    /** Text for the composing line: what has been typed, plus the highlighted candidate's code. */
    abstract String preedit(String input, Candidate highlighted);

    static char t9Digit(char letter) {
        switch (letter) {
            case 'a': case 'b': case 'c': return '2';
            case 'd': case 'e': case 'f': return '3';
            case 'g': case 'h': case 'i': return '4';
            case 'j': case 'k': case 'l': return '5';
            case 'm': case 'n': case 'o': return '6';
            case 'p': case 'q': case 'r': case 's': return '7';
            case 't': case 'u': case 'v': return '8';
            case 'w': case 'x': case 'y': case 'z': return '9';
            default: return letter;
        }
    }

    /** Collects (rank, line) pairs packed into longs so ranking is a primitive sort. */
    static final class Ranker {
        private long[] ranks = new long[256];
        private int count;

        void add(long rankHigh, int score, int line) {
            if (count == ranks.length) ranks = Arrays.copyOf(ranks, count * 2);
            long s = Math.min(Math.max(score, 0), (1 << 30) - 1);
            ranks[count++] = rankHigh | (s << 20) | (0xFFFFFL - Math.min(line, 0xFFFFF));
        }

        int size() {
            return count;
        }

        /** Returns line numbers, best first. */
        int[] sorted() {
            Arrays.sort(ranks, 0, count);
            int[] out = new int[count];
            for (int k = 0; k < count; k++) {
                out[k] = (int) (0xFFFFFL - (ranks[count - 1 - k] & 0xFFFFFL));
            }
            return out;
        }

    }

    // ---------------------------------------------------------------------------------------

    /** Cangjie, Quick, stroke and custom (e.g. 九方) tables: a single code per character. */
    static final class Shape extends Engine {
        static final int CANGJIE = 0, QUICK = 1, STROKE = 2, CUSTOM = 3;
        static final String RADICALS = "日月金木水火土竹戈十大中一弓人心手口尸廿山女田難卜重";
        static final String STROKES = "一丨丿丶乛";

        final Table table;
        final int kind;
        final int maxLength;
        final char wildcard;
        private final String keys;
        private final int textCol;

        Shape(Table table, int kind) {
            this(table, kind, null);
        }

        /** @param customKeys for CUSTOM tables: the digits that appear in codes. */
        Shape(Table table, int kind, String customKeys) {
            this.table = table;
            this.kind = kind;
            switch (kind) {
                case CANGJIE: maxLength = 5; wildcard = '1'; keys = "23456789"; textCol = 2; break;
                case QUICK: maxLength = 2; wildcard = '1'; keys = "23456789"; textCol = 2; break;
                case STROKE: maxLength = 40; wildcard = '6'; keys = "12345"; textCol = 1; break;
                default:
                    int max = 1;
                    for (int i = 0; i < table.size; i++) max = Math.max(max, table.keyLength(i));
                    maxLength = max;
                    keys = customKeys;
                    wildcard = '\0';
                    textCol = 1;
            }
        }

        boolean usesLetters() {
            return kind == CANGJIE || kind == QUICK;
        }

        @Override
        boolean acceptsKey(char key, String input) {
            if (input.length() >= maxLength) return false;
            if (key >= 'a' && key <= 'z') return usesLetters();
            if (key == '?' || (wildcard != '\0' && key == wildcard)) return input.length() > 0;
            return keys.indexOf(key) >= 0;
        }

        @Override
        List<Candidate> query(String input) {
            int n = input.length();
            List<Candidate> out = new ArrayList<Candidate>();
            if (n == 0) return out;
            String letters = null;
            char[] digits = new char[n];
            for (int k = 0; k < n; k++) {
                char c = input.charAt(k);
                if (c >= 'a' && c <= 'z') {
                    letters = input;
                    digits[k] = t9Digit(c);
                } else if (c == '?' || c == wildcard) {
                    digits[k] = '?';
                } else {
                    digits[k] = c;
                }
            }
            String pattern = new String(digits);
            int w = pattern.indexOf('?');
            String prefix = w < 0 ? pattern : pattern.substring(0, w);
            int lo = table.lowerBound(prefix);
            int hi = table.upperBound(prefix);
            Ranker ranker = new Ranker();
            for (int i = lo; i < hi; i++) {
                int klen = table.keyLength(i);
                if (klen < n) continue;
                if (w >= 0 && !wildMatch(i, pattern, w)) continue;
                if (letters != null && !letterMatch(table.field(i, 1), letters)) continue;
                int score = table.score(i);
                // An exact code match goes first, unless it is a character nobody uses.
                ranker.add(klen == n && score > 0 ? 1L << 62 : 0L, score, i);
            }
            int[] lines = ranker.sorted();
            HashSet<String> seen = new HashSet<String>();
            for (int k = 0; k < lines.length && out.size() < MAX_CANDIDATES; k++) {
                int i = lines[k];
                String text = table.field(i, textCol);
                if (seen.add(text)) out.add(new Candidate(text, codeOf(i), -1, table.keyLength(i) == n ? 1 : 0));
            }
            return out;
        }

        private boolean wildMatch(int i, String pattern, int from) {
            for (int k = from; k < pattern.length(); k++) {
                char p = pattern.charAt(k);
                if (p != '?' && table.keyChar(i, k) != p) return false;
            }
            return true;
        }

        private static boolean letterMatch(String code, String letters) {
            if (code.length() < letters.length()) return false;
            for (int k = 0; k < letters.length(); k++) {
                char l = letters.charAt(k);
                if (l >= 'a' && l <= 'z' && code.charAt(k) != l) return false;
            }
            return true;
        }

        private String codeOf(int i) {
            switch (kind) {
                case CANGJIE:
                case QUICK:
                    return radicals(table.field(i, 1));
                case STROKE:
                    return strokes(table.key(i));
                default:
                    return table.key(i);
            }
        }

        static String radicals(String code) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < code.length(); k++) {
                char c = code.charAt(k);
                sb.append(c >= 'a' && c <= 'z' ? RADICALS.charAt(c - 'a') : '？');
            }
            return sb.toString();
        }

        static String strokes(String digits) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < digits.length(); k++) {
                char c = digits.charAt(k);
                sb.append(c >= '1' && c <= '5' ? STROKES.charAt(c - '1') : '？');
            }
            return sb.toString();
        }

        @Override
        String preedit(String input, Candidate highlighted) {
            String typed;
            switch (kind) {
                case CANGJIE:
                case QUICK:
                    typed = hasLetters(input) ? radicals(input) : input.replace('1', '？');
                    break;
                case STROKE:
                    typed = strokes(input);
                    break;
                default:
                    typed = input;
            }
            if (highlighted == null || highlighted.code.equals(typed)) return typed;
            return typed + "　〔" + highlighted.code + "〕";
        }

        private static boolean hasLetters(String s) {
            for (int k = 0; k < s.length(); k++) {
                char c = s.charAt(k);
                if (c >= 'a' && c <= 'z') return true;
            }
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------

    /**
     * T9 Jyutping: the digits are matched against whole words and characters; key 1 marks a
     * syllable break. Candidates that cover only the start of the input leave the rest for later.
     * Also matches lazy pronunciations, initials-only abbreviations and words the user has built.
     */
    static final class Jyutping extends Engine {
        static final int MAX_LENGTH = 32;
        static final int LAZY_NL = 1, LAZY_NG = 2, LAZY_GWO = 4;
        private static final int SCAN_LIMIT = 200;

        /** digits, syllable lengths, reading, text, lazy flags, score */
        final Table table;
        /** initials as digits, reading, text, score; may be null */
        final Table abbr;
        /** Digits of a loose spelling (shi, dan, heung …) -> digits of the Jyutping syllables it may mean. */
        private final HashMap<String, String[]> loose = new HashMap<String, String[]>();
        volatile int lazyMask = LAZY_NL | LAZY_NG | LAZY_GWO;
        volatile boolean abbreviations = true;
        volatile boolean looseSpelling = true;
        volatile UserDict user;

        Jyutping(Table table, Table abbr) {
            this(table, abbr, null);
        }

        /** @param looseTable lines of "loose spelling digits \t syllable digits"; may be null */
        Jyutping(Table table, Table abbr, Table looseTable) {
            this.table = table;
            this.abbr = abbr;
            if (looseTable != null) {
                HashMap<String, List<String>> m = new HashMap<String, List<String>>();
                for (int i = 0; i < looseTable.size; i++) {
                    String k = looseTable.key(i);
                    List<String> l = m.get(k);
                    if (l == null) m.put(k, l = new ArrayList<String>());
                    l.add(looseTable.field(i, 1));
                }
                for (java.util.Map.Entry<String, List<String>> e : m.entrySet()) {
                    loose.put(e.getKey(), e.getValue().toArray(new String[0]));
                }
            }
        }

        @Override
        boolean acceptsKey(char key, String input) {
            if (input.length() >= MAX_LENGTH) return false;
            if (key == '1') return input.length() > 0 && input.charAt(input.length() - 1) != '1';
            return key >= '2' && key <= '9';
        }

        /** Table score, lowered for lazy spellings; -1 if that lazy spelling is switched off. */
        private int baseScore(int i) {
            int flags = table.extra(i);
            if (flags == 0) return table.score(i);
            if ((flags & ~lazyMask) != 0) return -1;
            return table.score(i) / 3;
        }

        @Override
        List<Candidate> query(String raw) {
            StringBuilder d = new StringBuilder();
            boolean[] sepAt = new boolean[raw.length() + 2];
            int[] rawAfter = new int[raw.length() + 2];
            boolean anySep = false;
            for (int k = 0; k < raw.length(); k++) {
                char c = raw.charAt(k);
                if (c == '1') {
                    sepAt[d.length()] = true;
                    rawAfter[d.length()] = k + 1;
                    anySep = true;
                } else {
                    d.append(c);
                    rawAfter[d.length()] = k + 1;
                }
            }
            String digits = d.toString();
            int n = digits.length();
            List<Candidate> out = new ArrayList<Candidate>();
            if (n == 0) return out;

            Ranker ranker = new Ranker();
            // Entries spelling all of the input, or completing its last syllable.
            int lo = table.lowerBound(digits);
            int hi = table.upperBound(digits);
            for (int i = lo; i < hi; i++) {
                int score = baseScore(i);
                if (score < 0) continue;
                boolean exact = table.keyLength(i) == n;
                // With a single key, only offer single characters starting with that letter.
                if (!exact && n < 2 && table.field(i, 1).length() > 1) continue;
                if (anySep && !boundariesOk(table.field(i, 1), sepAt, n)) continue;
                ranker.add(1L << 62, exact ? score : score / 8, i);
            }
            // Entries spelling only the beginning of the input.
            for (int len = n - 1; len >= 1; len--) {
                String prefix = digits.substring(0, len);
                for (int i = table.lowerBound(prefix); i < table.size && table.keyEquals(i, prefix); i++) {
                    int score = baseScore(i);
                    if (score < 0) continue;
                    if (anySep && !boundariesOk(table.field(i, 1), sepAt, len)) continue;
                    ranker.add((long) len << 52, score, i);
                }
            }
            int[] lines = ranker.sorted();

            // Initials-only abbreviations (e.g. 6 4 = m g = 唔該), merged into the full matches by score.
            List<Candidate> abbrCands = new ArrayList<Candidate>();
            List<Integer> abbrScores = new ArrayList<Integer>();
            if (abbreviations && abbr != null && n >= 2 && !anySep) {
                Ranker ar = new Ranker();
                for (int i = abbr.lowerBound(digits); i < abbr.size && abbr.keyEquals(i, digits); i++) {
                    ar.add(0L, abbr.score(i) / 4, i);
                }
                int[] al = ar.sorted();
                for (int k = 0; k < al.length && k < 40; k++) {
                    abbrCands.add(new Candidate(abbr.field(al[k], 2), abbr.field(al[k], 1), -1, 2,
                            abbr.score(al[k]) / 4));
                    abbrScores.add(abbr.score(al[k]) / 4);
                }
            }

            int nextAbbr = 0;
            int fullCount = 0;
            int partialCount = 0;
            for (int k = 0; k < lines.length; k++) {
                int i = lines[k];
                int klen = table.keyLength(i);
                boolean full = klen >= n;
                // Keep room for the partial matches, which sort after all full ones.
                if (full ? fullCount++ >= SCAN_LIMIT : partialCount++ >= SCAN_LIMIT) continue;
                if (full) {
                    int adj = klen == n ? baseScore(i) : baseScore(i) / 8;
                    while (nextAbbr < abbrCands.size() && abbrScores.get(nextAbbr) > adj) {
                        out.add(abbrCands.get(nextAbbr++));
                    }
                } else {
                    while (nextAbbr < abbrCands.size()) out.add(abbrCands.get(nextAbbr++));
                }
                int consumed = full ? -1 : rawAfter[klen];
                if (consumed >= raw.length()) consumed = -1;
                int tier = klen == n ? 3 : full ? 2 : 1;
                int rank = !full ? baseScore(i) : klen == n ? baseScore(i) : baseScore(i) / 8;
                out.add(new Candidate(table.field(i, 3), table.field(i, 2), consumed, tier, rank));
            }
            while (nextAbbr < abbrCands.size()) out.add(abbrCands.get(nextAbbr++));

            if (looseSpelling && !loose.isEmpty()) addLooseMatches(out, raw, digits, sepAt, rawAfter);
            addUserWords(out, raw, digits, sepAt, rawAfter, anySep);

            HashSet<String> seen = new HashSet<String>();
            List<Candidate> unique = new ArrayList<Candidate>(Math.min(out.size(), MAX_CANDIDATES));
            for (Candidate c : out) {
                if (unique.size() >= MAX_CANDIDATES) break;
                if (seen.add(c.text + '\u0001' + c.consumed)) unique.add(c);
            }
            return unique;
        }

        /**
         * Reads the keys as loose spellings (shi dan = 是但, heung gong = 香港, yau = 有): splits them
         * into spellings from the loose map, maps each to Jyutping and looks the result up. Only
         * splits that really use a loose spelling are added; the rest are already found above.
         */
        private void addLooseMatches(List<Candidate> out, String raw, String digits, boolean[] sepAt, int[] rawAfter) {
            LooseSearch search = new LooseSearch(digits, sepAt, rawAfter, raw.length());
            search.dfs(0, "", "", false);
            List<Candidate> full = new ArrayList<Candidate>();
            List<Candidate> partial = new ArrayList<Candidate>();
            for (Candidate c : search.found) (c.consumed < 0 ? full : partial).add(c);
            Comparator<Candidate> byRank = new Comparator<Candidate>() {
                @Override
                public int compare(Candidate a, Candidate b) {
                    if (a.consumed != b.consumed) return b.consumed - a.consumed;
                    return b.rank - a.rank;
                }
            };
            Collections.sort(full, byRank);
            Collections.sort(partial, byRank);
            for (Candidate c : full) {
                int pos = 0;
                while (pos < out.size()) {
                    Candidate o = out.get(pos);
                    if (o.consumed >= 0 || o.rank < c.rank) break;
                    pos++;
                }
                out.add(pos, c);
            }
            for (Candidate c : partial) {
                int pos = 0;
                while (pos < out.size()) {
                    Candidate o = out.get(pos);
                    if (o.consumed >= 0 && (o.consumed < c.consumed || (o.consumed == c.consumed && o.rank < c.rank))) {
                        break;
                    }
                    pos++;
                }
                out.add(pos, c);
            }
        }

        private final class LooseSearch {
            private static final int MAX_NODES = 3000;
            private static final int MAX_FULL = 80;
            private static final int MAX_PARTIAL = 60;
            final String digits;
            final boolean[] sepAt;
            final int[] rawAfter;
            final int rawLength;
            final List<Candidate> found = new ArrayList<Candidate>();
            private int nodes;
            private int fullCount;
            private int partialCount;

            LooseSearch(String digits, boolean[] sepAt, int[] rawAfter, int rawLength) {
                this.digits = digits;
                this.sepAt = sepAt;
                this.rawAfter = rawAfter;
                this.rawLength = rawLength;
            }

            /** @param canon Jyutping digits so far, @param lengths their syllable lengths, @param used a loose spelling was used */
            void dfs(int pos, String canon, String lengths, boolean used) {
                if (++nodes > MAX_NODES || fullCount >= MAX_FULL) return;
                int n = digits.length();
                if (pos > 0 && used) collect(pos, canon, lengths);
                for (int end = pos + 1; end <= n && end <= pos + 8; end++) {
                    if (end - 1 > pos && sepAt[end - 1]) break;  // a syllable cannot span a typed separator
                    String seg = digits.substring(pos, end);
                    String[] syllables = loose.get(seg);
                    if (syllables == null) continue;
                    for (String syl : syllables) {
                        String next = canon + syl;
                        // Only follow spellings that begin some dictionary entry.
                        if (table.lowerBound(next) >= table.upperBound(next)) continue;
                        dfs(end, next, lengths + (char) ('0' + Math.min(syl.length(), 9)), used || !syl.equals(seg));
                    }
                }
            }

            private void collect(int pos, String canon, String lengths) {
                int consumed = pos >= digits.length() ? -1 : rawAfter[pos];
                if (consumed >= rawLength) consumed = -1;
                for (int i = table.lowerBound(canon); i < table.size && table.keyEquals(i, canon); i++) {
                    if (table.extra(i) != 0 || !table.field(i, 1).equals(lengths)) continue;
                    if (consumed < 0 ? fullCount++ >= MAX_FULL : partialCount++ >= MAX_PARTIAL) continue;
                    found.add(new Candidate(table.field(i, 3), table.field(i, 2), consumed,
                            consumed < 0 ? 3 : 1, table.score(i) / 3));
                }
            }
        }

        private void addUserWords(List<Candidate> out, String raw, String digits, boolean[] sepAt,
                int[] rawAfter, boolean anySep) {
            UserDict u = user;
            if (u == null) return;
            int n = digits.length();
            for (UserDict.Word w : u.words()) {
                int tier;
                int consumed = -1;
                if (w.digits.equals(digits)) {
                    tier = 3;
                } else if (n >= 2 && w.digits.startsWith(digits)) {
                    tier = 2;
                } else if (digits.startsWith(w.digits)) {
                    tier = 1;
                    consumed = rawAfter[w.digits.length()];
                    if (consumed >= raw.length()) consumed = -1;
                } else {
                    continue;
                }
                if (anySep && !boundariesOk(w.lengths, sepAt, Math.min(n, w.digits.length()))) continue;
                // Place it with its tier; a partial match goes ahead of partials that cover less input.
                int pos = 0;
                while (pos < out.size()) {
                    Candidate c = out.get(pos);
                    if (c.tier < tier || (tier == 1 && c.tier == 1 && c.consumed < consumed)) break;
                    pos++;
                }
                out.add(pos, new Candidate(w.text, w.reading, consumed, tier));
            }
        }

        /** Every separator the user typed before position {@code upTo} must fall on a syllable boundary. */
        private static boolean boundariesOk(String lengths, boolean[] sepAt, int upTo) {
            boolean[] boundary = new boolean[sepAt.length + 8];
            int pos = 0;
            for (int k = 0; k < lengths.length(); k++) {
                pos += lengths.charAt(k) - '0';
                if (pos < boundary.length) boundary[pos] = true;
            }
            for (int p = 1; p < upTo && p < sepAt.length; p++) {
                if (sepAt[p] && !boundary[p]) return false;
            }
            return true;
        }

        @Override
        String preedit(String input, Candidate highlighted) {
            String typed = input.replace('1', '\'');
            if (highlighted == null) return typed;
            if (highlighted.consumed < 0) return highlighted.code;
            return highlighted.code + " " + typed.substring(highlighted.consumed);
        }
    }
}
