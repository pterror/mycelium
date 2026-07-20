package org.mycelium.lua.stdlib;

import org.mycelium.lua.LuaException;

/**
 * Lua 5.1 &sect;5.4.1 pattern matching &mdash; a from-scratch port of the reference {@code
 * lstrlib.c} matcher (Lua is deliberately <em>not</em> regex; classes, quantifiers, anchors,
 * captures, {@code %b}, {@code %f}, and back-references all have their own hand-rolled
 * semantics). Used by {@code string.find}, {@code string.match}, {@code string.gmatch}, and
 * {@code string.gsub}.
 */
final class LuaPattern {

    private static final char L_ESC = '%';
    private static final int MAXCAPTURES = 32;
    private static final int CAP_UNFINISHED = -1;
    private static final int CAP_POSITION = -2;
    private static final int MAX_MATCH_DEPTH = 200;

    private LuaPattern() {}

    /** One successful match: 0-based {@code [start, end)} span plus resolved captures (empty if the pattern had none). */
    static final class Result {
        final int start;
        final int end;
        final Object[] captures;

        Result(int start, int end, Object[] captures) {
            this.start = start;
            this.end = end;
            this.captures = captures;
        }
    }

    private static final class MatchState {
        final String src;
        final String pat;
        int level;
        int matchDepth;
        final int[] capStart = new int[MAXCAPTURES];
        final int[] capLen = new int[MAXCAPTURES];

        MatchState(String src, String pat) {
            this.src = src;
            this.pat = pat;
        }
    }

    private static LuaException error(String message) {
        return LuaException.runtimeError(null, message);
    }

    /**
     * Tries to match {@code pattern} against {@code s} starting at 0-based offset {@code init}
     * (and, unless the pattern is anchored with {@code ^}, at every later offset in turn), Lua
     * 5.1's usual "first successful start position wins" semantics. Returns {@code null} if
     * there is no match anywhere from {@code init} onward.
     */
    static Result find(String s, String pattern, int init) {
        boolean anchor = pattern.startsWith("^");
        int pp = anchor ? 1 : 0;
        int sInit = Math.max(init, 0);
        int len = s.length();
        do {
            MatchState ms = new MatchState(s, pattern);
            int e = doMatch(ms, sInit, pp);
            if (e != -1) {
                return new Result(sInit, e, getCaptures(ms, sInit, e));
            }
            sInit++;
        } while (sInit <= len && !anchor);
        return null;
    }

    private static Object[] getCaptures(MatchState ms, int s, int e) {
        int nlevels = ms.level;
        Object[] result = new Object[nlevels];
        for (int i = 0; i < nlevels; i++) {
            if (ms.capLen[i] == CAP_POSITION) {
                result[i] = (double) (ms.capStart[i] + 1);
            } else {
                result[i] = ms.src.substring(ms.capStart[i], ms.capStart[i] + ms.capLen[i]);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Character classes
    // ------------------------------------------------------------------

    private static boolean isPunct(char c) {
        return (c >= 33 && c <= 47) || (c >= 58 && c <= 64) || (c >= 91 && c <= 96) || (c >= 123 && c <= 126);
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean matchClass(char c, char cl) {
        boolean res;
        switch (Character.toLowerCase(cl)) {
            case 'a': res = Character.isLetter(c); break;
            case 'd': res = Character.isDigit(c); break;
            case 'l': res = Character.isLowerCase(c); break;
            case 's': res = Character.isWhitespace(c); break;
            case 'u': res = Character.isUpperCase(c); break;
            case 'w': res = Character.isLetterOrDigit(c); break;
            case 'c': res = Character.isISOControl(c); break;
            case 'p': res = isPunct(c); break;
            case 'x': res = isHexDigit(c); break;
            default: return cl == c;
        }
        return Character.isUpperCase(cl) ? !res : res;
    }

    /** {@code p} points at {@code '['}; {@code ec} is the index of the matching {@code ']'}. */
    private static boolean matchSet(char c, String pat, int p, int ec) {
        boolean negate = false;
        p++;
        if (p < pat.length() && pat.charAt(p) == '^') {
            negate = true;
            p++;
        }
        while (p < ec) {
            if (pat.charAt(p) == L_ESC) {
                p++;
                if (matchClass(c, pat.charAt(p))) {
                    return !negate;
                }
                p++;
            } else if (p + 2 < ec && pat.charAt(p + 1) == '-') {
                if (pat.charAt(p) <= c && c <= pat.charAt(p + 2)) {
                    return !negate;
                }
                p += 3;
            } else {
                if (pat.charAt(p) == c) {
                    return !negate;
                }
                p++;
            }
        }
        return negate;
    }

    /** Advances past one pattern "item" (a literal char, {@code %x} escape, or {@code [set]}) starting at {@code p}. */
    private static int classEnd(MatchState ms, int p) {
        String pat = ms.pat;
        char c = pat.charAt(p++);
        if (c == L_ESC) {
            if (p >= pat.length()) {
                throw error("malformed pattern (ends with '%')");
            }
            return p + 1;
        } else if (c == '[') {
            if (p < pat.length() && pat.charAt(p) == '^') {
                p++;
            }
            do {
                if (p >= pat.length()) {
                    throw error("malformed pattern (missing ']')");
                }
                char cc = pat.charAt(p++);
                if (cc == L_ESC && p < pat.length()) {
                    p++;
                }
            } while (p >= pat.length() || pat.charAt(p) != ']');
            return p + 1;
        } else {
            return p;
        }
    }

    private static boolean singleMatch(MatchState ms, int s, int p, int ep) {
        if (s >= ms.src.length()) {
            return false;
        }
        char c = ms.src.charAt(s);
        char pc = ms.pat.charAt(p);
        switch (pc) {
            case '.': return true;
            case L_ESC: return matchClass(c, ms.pat.charAt(p + 1));
            case '[': return matchSet(c, ms.pat, p, ep - 1);
            default: return pc == c;
        }
    }

    // ------------------------------------------------------------------
    // Captures
    // ------------------------------------------------------------------

    private static int startCapture(MatchState ms, int s, int p, int what) {
        int level = ms.level;
        if (level >= MAXCAPTURES) {
            throw error("too many captures");
        }
        ms.capStart[level] = s;
        ms.capLen[level] = what;
        ms.level = level + 1;
        int res = doMatch(ms, s, p);
        if (res == -1) {
            ms.level--;
        }
        return res;
    }

    private static int endCapture(MatchState ms, int s, int p) {
        int l = captureToClose(ms);
        ms.capLen[l] = s - ms.capStart[l];
        int res = doMatch(ms, s, p);
        if (res == -1) {
            ms.capLen[l] = CAP_UNFINISHED;
        }
        return res;
    }

    private static int captureToClose(MatchState ms) {
        for (int level = ms.level - 1; level >= 0; level--) {
            if (ms.capLen[level] == CAP_UNFINISHED) {
                return level;
            }
        }
        throw error("invalid pattern capture");
    }

    private static int checkCapture(MatchState ms, int digit) {
        int l = digit - 1;
        if (l < 0 || l >= ms.level || ms.capLen[l] == CAP_UNFINISHED) {
            throw error("invalid capture index");
        }
        return l;
    }

    private static int matchCapture(MatchState ms, int s, int digit) {
        int l = checkCapture(ms, digit);
        int len = ms.capLen[l];
        String src = ms.src;
        if (src.length() - s >= len && src.regionMatches(ms.capStart[l], src, s, len)) {
            return s + len;
        }
        return -1;
    }

    private static int matchBalance(MatchState ms, int s, int p) {
        if (p + 1 >= ms.pat.length()) {
            throw error("missing arguments to '%b'");
        }
        if (s >= ms.src.length() || ms.src.charAt(s) != ms.pat.charAt(p)) {
            return -1;
        }
        char b = ms.pat.charAt(p);
        char e = ms.pat.charAt(p + 1);
        int cont = 1;
        int i = s + 1;
        int len = ms.src.length();
        while (i < len) {
            char cc = ms.src.charAt(i);
            if (cc == e) {
                if (--cont == 0) {
                    return i + 1;
                }
            } else if (cc == b) {
                cont++;
            }
            i++;
        }
        return -1;
    }

    private static int maxExpand(MatchState ms, int s, int p, int ep) {
        int i = 0;
        while (singleMatch(ms, s + i, p, ep)) {
            i++;
        }
        while (i >= 0) {
            int res = doMatch(ms, s + i, ep + 1);
            if (res != -1) {
                return res;
            }
            i--;
        }
        return -1;
    }

    private static int minExpand(MatchState ms, int s, int p, int ep) {
        while (true) {
            int res = doMatch(ms, s, ep + 1);
            if (res != -1) {
                return res;
            } else if (singleMatch(ms, s, p, ep)) {
                s++;
            } else {
                return -1;
            }
        }
    }

    // ------------------------------------------------------------------
    // Core recursive matcher
    // ------------------------------------------------------------------

    private static int doMatch(MatchState ms, int s, int p) {
        ms.matchDepth++;
        if (ms.matchDepth > MAX_MATCH_DEPTH) {
            throw error("pattern too complex");
        }
        try {
            String pat = ms.pat;
            String src = ms.src;
            while (p != pat.length()) {
                char pc = pat.charAt(p);
                switch (pc) {
                    case '(':
                        if (p + 1 < pat.length() && pat.charAt(p + 1) == ')') {
                            return startCapture(ms, s, p + 2, CAP_POSITION);
                        } else {
                            return startCapture(ms, s, p + 1, CAP_UNFINISHED);
                        }
                    case ')':
                        return endCapture(ms, s, p + 1);
                    case '$':
                        if (p + 1 == pat.length()) {
                            return (s == src.length()) ? s : -1;
                        }
                        break; // literal '$' not at pattern end: falls through to default handling
                    case L_ESC:
                        if (p + 1 < pat.length()) {
                            char nx = pat.charAt(p + 1);
                            if (nx == 'b') {
                                int s2 = matchBalance(ms, s, p + 2);
                                if (s2 == -1) {
                                    return -1;
                                }
                                s = s2;
                                p = p + 4;
                                continue;
                            }
                            if (nx == 'f') {
                                int p2 = p + 2;
                                if (p2 >= pat.length() || pat.charAt(p2) != '[') {
                                    throw error("missing '[' after '%f' in pattern");
                                }
                                int ep = classEnd(ms, p2);
                                char previous = (s == 0) ? '\0' : src.charAt(s - 1);
                                char current = (s < src.length()) ? src.charAt(s) : '\0';
                                if (!matchSet(previous, pat, p2, ep - 1) && matchSet(current, pat, p2, ep - 1)) {
                                    p = ep;
                                    continue;
                                }
                                return -1;
                            }
                            if (Character.isDigit(nx)) {
                                int s2 = matchCapture(ms, s, nx - '0');
                                if (s2 == -1) {
                                    return -1;
                                }
                                s = s2;
                                p = p + 2;
                                continue;
                            }
                        }
                        break; // plain '%x' escape: falls through to default handling
                    default:
                        break;
                }
                // Default: match one pattern "item" (possibly quantified).
                int ep = classEnd(ms, p);
                boolean m = s < src.length() && singleMatch(ms, s, p, ep);
                char epc = ep < pat.length() ? pat.charAt(ep) : '\0';
                if (!m) {
                    if (epc == '*' || epc == '?' || epc == '-') {
                        p = ep + 1;
                        continue;
                    }
                    return -1;
                } else {
                    switch (epc) {
                        case '?': {
                            int res = doMatch(ms, s + 1, ep + 1);
                            if (res != -1) {
                                return res;
                            }
                            p = ep + 1;
                            continue;
                        }
                        case '+':
                            return maxExpand(ms, s + 1, p, ep);
                        case '*':
                            return maxExpand(ms, s, p, ep);
                        case '-':
                            return minExpand(ms, s, p, ep);
                        default:
                            s = s + 1;
                            p = ep;
                            continue;
                    }
                }
            }
            return s;
        } finally {
            ms.matchDepth--;
        }
    }
}
