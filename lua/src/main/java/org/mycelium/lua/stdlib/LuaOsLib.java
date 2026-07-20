package org.mycelium.lua.stdlib;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.mycelium.lua.LuaException;
import org.mycelium.lua.LuaLanguage;
import org.mycelium.lua.nodes.controlflow.LuaReturnException;
import org.mycelium.lua.runtime.LuaNil;
import org.mycelium.lua.runtime.LuaTable;

import static org.mycelium.lua.stdlib.LuaBuiltinRootNode.arg;
import static org.mycelium.lua.stdlib.LuaBuiltinRootNode.wrap;
import static org.mycelium.lua.stdlib.LuaLibUtil.*;

/**
 * Lua 5.1 &sect;5.8: the {@code os} library.
 *
 * <p>Two functions here reach outside the Lua sandbox into the host system &mdash;
 * {@code os.execute} (spawns a subprocess) and {@code os.getenv} (reads process environment
 * variables). Both go through {@link TruffleLanguage.Env}'s access-controlled APIs ({@link
 * TruffleLanguage.Env#newProcessBuilder} / {@link TruffleLanguage.Env#isCreateProcessAllowed}
 * and {@link TruffleLanguage.Env#getEnvironment}) rather than {@code Runtime.exec}/{@code
 * System.getenv} directly, so an embedder that builds its {@code
 * org.graalvm.polyglot.Context} with process creation or environment access denied gets that
 * denial honored here automatically instead of the sandbox silently leaking.
 */
final class LuaOsLib {

    /** Baseline for {@code os.clock()}; CPU/wall time elapsed since is reported in seconds. */
    private static final long START_NANOS = System.nanoTime();

    private static final String[] WEEKDAY_ABBREV = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    private static final String[] WEEKDAY_FULL =
            {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
    private static final String[] MONTH_ABBREV =
            {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    private static final String[] MONTH_FULL = {
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    };

    private LuaOsLib() {}

    static void install(TruffleLanguage<?> language, LuaTable globals) {
        LuaTable os = new LuaTable();
        globals.rawset("os", os);

        os.rawset("clock", wrap(language, "clock", (frame, cn) -> (System.nanoTime() - START_NANOS) / 1.0e9));

        os.rawset("time", wrap(language, "time", (frame, cn) -> {
            Object tableArg = arg(frame, 1);
            if (isNil(tableArg)) {
                return (double) (System.currentTimeMillis() / 1000L);
            }
            LuaTable t = checkTable("time", 1, tableArg);
            int year = (int) requireDateField(t, "year");
            int month = (int) requireDateField(t, "month");
            int day = (int) requireDateField(t, "day");
            int hour = (int) optDateField(t, "hour", 12);
            int min = (int) optDateField(t, "min", 0);
            int sec = (int) optDateField(t, "sec", 0);
            // Building from Jan 1st and adding each component (rather than LocalDateTime.of, which
            // rejects out-of-range fields) mirrors C's mktime, which normalizes overflowing
            // month/day/hour/min/sec instead of erroring.
            LocalDateTime ldt = LocalDateTime.of(year, 1, 1, 0, 0, 0)
                    .plusMonths(month - 1L)
                    .plusDays(day - 1L)
                    .plusHours(hour)
                    .plusMinutes(min)
                    .plusSeconds(sec);
            long epoch = ldt.atZone(ZoneId.systemDefault()).toEpochSecond();
            return (double) epoch;
        }));

        os.rawset("date", wrap(language, "date", (frame, cn) -> {
            String format = optString(arg(frame, 1), "%c");
            double timeArg = optNumber(arg(frame, 2), System.currentTimeMillis() / 1000.0);
            boolean utc = format.startsWith("!");
            if (utc) {
                format = format.substring(1);
            }
            long epochSeconds = (long) timeArg;
            ZonedDateTime zdt = utc
                    ? ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC)
                    : ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.systemDefault());
            if (format.equals("*t")) {
                return dateTable(zdt);
            }
            return strftime(format, zdt);
        }));

        os.rawset("difftime", wrap(language, "difftime", (frame, cn) ->
                checkNumber("difftime", 1, arg(frame, 1)) - checkNumber("difftime", 2, arg(frame, 2))));

        os.rawset("execute", wrap(language, "execute", (frame, cn) -> {
            TruffleLanguage.Env env = LuaLanguage.getContext(cn).getEnv();
            Object cmdArg = arg(frame, 1);
            if (isNil(cmdArg)) {
                // No command: report whether a shell could be invoked at all.
                return env.isCreateProcessAllowed();
            }
            String command = checkString("execute", 1, cmdArg);
            if (!env.isCreateProcessAllowed()) {
                throw new LuaReturnException(
                        new Object[] {LuaNil.INSTANCE, "exit", -1.0});
            }
            try {
                Process process = env.newProcessBuilder("sh", "-c", command).inheritIO(true).start();
                int exitCode = process.waitFor();
                throw new LuaReturnException(new Object[] {
                        exitCode == 0 ? Boolean.TRUE : LuaNil.INSTANCE, "exit", (double) exitCode
                });
            } catch (IOException e) {
                throw new LuaReturnException(new Object[] {LuaNil.INSTANCE, "exit", -1.0});
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw LuaException.runtimeError(null, "os.execute interrupted");
            }
        }));

        os.rawset("exit", wrap(language, "exit", (frame, cn) -> {
            Object codeArg = arg(frame, 1);
            int code;
            if (isNil(codeArg)) {
                code = 0;
            } else if (codeArg instanceof Boolean b) {
                code = b ? 0 : 1;
            } else {
                code = (int) checkNumber("exit", 1, codeArg);
            }
            // Rather than calling System.exit() (which would tear down the whole JVM, including
            // any other, unrelated polyglot contexts hosted in the same process), os.exit unwinds
            // the Lua call stack as a Truffle exception that an embedder can catch at the
            // org.graalvm.polyglot.Context.eval() boundary and translate into whatever
            // "stop this guest program" behavior fits its host (e.g. exit the whole process, or
            // just discard the Context). This mirrors how a "real" Lua process being embedded as
            // a library (rather than run standalone via `lua`) ought to behave.
            throw new LuaExitException(code);
        }));

        os.rawset("getenv", wrap(language, "getenv", (frame, cn) -> {
            String name = checkString("getenv", 1, arg(frame, 1));
            TruffleLanguage.Env env = LuaLanguage.getContext(cn).getEnv();
            String value = env.getEnvironment().get(name);
            return value == null ? LuaNil.INSTANCE : value;
        }));

        os.rawset("remove", wrap(language, "remove", (frame, cn) -> {
            String filename = checkString("remove", 1, arg(frame, 1));
            File file = new File(filename);
            if (file.delete()) {
                return Boolean.TRUE;
            }
            throw new LuaReturnException(
                    new Object[] {LuaNil.INSTANCE, filename + ": unable to remove file"});
        }));

        os.rawset("rename", wrap(language, "rename", (frame, cn) -> {
            String oldname = checkString("rename", 1, arg(frame, 1));
            String newname = checkString("rename", 2, arg(frame, 2));
            if (new File(oldname).renameTo(new File(newname))) {
                return Boolean.TRUE;
            }
            throw new LuaReturnException(
                    new Object[] {LuaNil.INSTANCE, oldname + ": unable to rename file"});
        }));

        os.rawset("tmpname", wrap(language, "tmpname", (frame, cn) -> {
            try {
                File f = File.createTempFile("lua", ".tmp");
                String path = f.getAbsolutePath();
                // os.tmpname only reserves a plausible unique name (unlike io.tmpfile, which
                // opens it); delete the placeholder file so the name is free for the caller to
                // create themselves.
                f.delete();
                return path;
            } catch (IOException e) {
                throw LuaException.runtimeError(null, "unable to generate a unique filename");
            }
        }));

        os.rawset("setlocale", wrap(language, "setlocale", (frame, cn) -> {
            // Full C-locale support (numeric formatting, collation, etc.) is out of scope; the
            // only locale we ever recognize is "C" (and "POSIX", its synonym) — see Lua 5.1 §5.8.
            Object localeArg = arg(frame, 1);
            if (isNil(localeArg)) {
                return "C";
            }
            String locale = checkString("setlocale", 1, localeArg);
            if (locale.isEmpty() || locale.equals("C") || locale.equals("POSIX")) {
                return "C";
            }
            return LuaNil.INSTANCE;
        }));
    }

    // -------------------------------------------------------------------
    // os.time / os.date table helpers
    // -------------------------------------------------------------------

    private static double requireDateField(LuaTable t, String name) {
        Object v = t.rawget(name);
        Double n = v == null ? null : org.mycelium.lua.runtime.LuaTypeUtils.toNumber(v);
        if (n == null) {
            throw LuaException.runtimeError(null, "field '" + name + "' missing in date table");
        }
        return n;
    }

    private static double optDateField(LuaTable t, String name, double defaultValue) {
        Object v = t.rawget(name);
        if (isNil(v)) {
            return defaultValue;
        }
        Double n = org.mycelium.lua.runtime.LuaTypeUtils.toNumber(v);
        return n == null ? defaultValue : n;
    }

    /** Builds the {@code "*t"} result table for {@code os.date}: see Lua 5.1 &sect;5.8. */
    private static LuaTable dateTable(ZonedDateTime zdt) {
        LuaTable t = new LuaTable();
        t.rawset("sec", (double) zdt.getSecond());
        t.rawset("min", (double) zdt.getMinute());
        t.rawset("hour", (double) zdt.getHour());
        t.rawset("day", (double) zdt.getDayOfMonth());
        t.rawset("month", (double) zdt.getMonthValue());
        t.rawset("year", (double) zdt.getYear());
        t.rawset("wday", (double) sundayBasedWeekday(zdt));
        t.rawset("yday", (double) zdt.getDayOfYear());
        t.rawset("isdst", zdt.getZone().getRules().isDaylightSavings(zdt.toInstant()));
        return t;
    }

    /** ISO {@link java.time.DayOfWeek#getValue()} is 1=Monday..7=Sunday; Lua wants 1=Sunday..7=Saturday. */
    private static int sundayBasedWeekday(ZonedDateTime zdt) {
        int iso = zdt.getDayOfWeek().getValue();
        return (iso % 7) + 1;
    }

    // -------------------------------------------------------------------
    // os.date format-string (strftime) support
    // -------------------------------------------------------------------

    private static String strftime(String format, ZonedDateTime zdt) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c == '%' && i + 1 < format.length()) {
                out.append(formatSpec(format.charAt(++i), zdt));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String formatSpec(char spec, ZonedDateTime zdt) {
        int isoWday = zdt.getDayOfWeek().getValue(); // 1=Mon..7=Sun
        int mondayBasedWday = isoWday % 7; // 0=Sun..6=Sat, index into WEEKDAY_* arrays
        switch (spec) {
            case 'a':
                return WEEKDAY_ABBREV[mondayBasedWday];
            case 'A':
                return WEEKDAY_FULL[mondayBasedWday];
            case 'b':
            case 'h':
                return MONTH_ABBREV[zdt.getMonthValue() - 1];
            case 'B':
                return MONTH_FULL[zdt.getMonthValue() - 1];
            case 'c':
                return String.format(Locale.ROOT, "%s %s %2d %02d:%02d:%02d %d",
                        WEEKDAY_ABBREV[mondayBasedWday], MONTH_ABBREV[zdt.getMonthValue() - 1],
                        zdt.getDayOfMonth(), zdt.getHour(), zdt.getMinute(), zdt.getSecond(), zdt.getYear());
            case 'd':
                return String.format(Locale.ROOT, "%02d", zdt.getDayOfMonth());
            case 'H':
                return String.format(Locale.ROOT, "%02d", zdt.getHour());
            case 'I': {
                int h12 = zdt.getHour() % 12;
                return String.format(Locale.ROOT, "%02d", h12 == 0 ? 12 : h12);
            }
            case 'j':
                return String.format(Locale.ROOT, "%03d", zdt.getDayOfYear());
            case 'm':
                return String.format(Locale.ROOT, "%02d", zdt.getMonthValue());
            case 'M':
                return String.format(Locale.ROOT, "%02d", zdt.getMinute());
            case 'n':
                return "\n";
            case 't':
                return "\t";
            case 'p':
                return zdt.getHour() < 12 ? "AM" : "PM";
            case 'S':
                return String.format(Locale.ROOT, "%02d", zdt.getSecond());
            case 'w':
                return Integer.toString(mondayBasedWday);
            case 'W':
                return String.format(Locale.ROOT, "%02d", weekOfYearMondayFirst(zdt));
            case 'x':
                return String.format(Locale.ROOT, "%02d/%02d/%02d",
                        zdt.getMonthValue(), zdt.getDayOfMonth(), zdt.getYear() % 100);
            case 'X':
                return String.format(Locale.ROOT, "%02d:%02d:%02d", zdt.getHour(), zdt.getMinute(), zdt.getSecond());
            case 'y':
                return String.format(Locale.ROOT, "%02d", zdt.getYear() % 100);
            case 'Y':
                return Integer.toString(zdt.getYear());
            case 'Z':
                return zdt.format(DateTimeFormatter.ofPattern("zzz", Locale.US));
            case '%':
                return "%";
            default:
                return "%" + spec;
        }
    }

    /** C's {@code %W}: week number (00-53), weeks starting Monday, days before the year's first Monday counting as week 0. */
    private static int weekOfYearMondayFirst(ZonedDateTime zdt) {
        LocalDate jan1 = LocalDate.of(zdt.getYear(), 1, 1);
        int jan1MondayBased = jan1.getDayOfWeek().getValue() - 1; // 0=Mon..6=Sun
        return (zdt.getDayOfYear() + jan1MondayBased - 1) / 7;
    }

    // -------------------------------------------------------------------
    // os.exit
    // -------------------------------------------------------------------

    /**
     * Thrown by {@code os.exit} to unwind the Lua call stack without tearing down the host JVM
     * (see the comment at the {@code os.exit} builtin above). Not a {@link LuaException}, so it
     * is deliberately <em>not</em> caught by {@code pcall}/{@code xpcall} &mdash; matching real
     * Lua, where {@code os.exit} cannot be intercepted from within the script.
     *
     * <p>Package-private for now (mirroring every other {@code LuaXyzLib} class): an embedder
     * that wants to catch this specifically at the {@code Context.eval} boundary currently must
     * do so via the generic {@link AbstractTruffleException}/exit-code protocol Truffle exposes,
     * rather than importing this class by name. If a future embedding needs to distinguish
     * "the guest called os.exit" from other guest exceptions, promote this to a public top-level
     * class first.
     */
    static final class LuaExitException extends AbstractTruffleException {
        private static final long serialVersionUID = 1L;

        private final int exitCode;

        LuaExitException(int exitCode) {
            super("os.exit(" + exitCode + ")");
            this.exitCode = exitCode;
        }

        int getExitCode() {
            return exitCode;
        }
    }
}
