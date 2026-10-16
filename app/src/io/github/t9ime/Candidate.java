package io.github.t9ime;

final class Candidate {
    final String text;
    /** Human-readable code shown for the highlighted candidate (radicals, strokes, Jyutping). */
    final String code;
    /** How many characters of the raw input this candidate consumes; -1 means all. */
    final int consumed;
    /** Match quality (Jyutping: 3 exact, 2 completion or abbreviation, 1 covers only the start). */
    final int tier;

    Candidate(String text, String code, int consumed) {
        this(text, code, consumed, 0);
    }

    Candidate(String text, String code, int consumed, int tier) {
        this.text = text;
        this.code = code;
        this.consumed = consumed;
        this.tier = tier;
    }
}
