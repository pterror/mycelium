package org.mycelium.lua.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Lua table: a hybrid array + hash map with an optional metatable.
 *
 * <p>Spec references: Lua 5.1 &sect;2.2 (table type), &sect;2.5.7 (table constructors),
 * &sect;2.8 (metatables/metamethods), &sect;5.5 (table library).
 *
 * <p>Integer keys {@code 1..n} that form a contiguous run starting at 1 are kept in a dense
 * {@code ArrayList} (the "array part", 0-based internally, 1-based externally as Lua expects).
 * Every other key &mdash; strings, booleans, tables, functions, non-integer numbers, and
 * integer keys outside the current contiguous run &mdash; lives in the "hash part", a
 * {@link LinkedHashMap} so that {@link #next} can offer a stable iteration order.
 *
 * <p>A key mapped to nil is considered absent (Lua 5.1 &sect;2.5.5): setting a key's value to
 * nil removes the entry rather than storing it. {@code nil} itself cannot be a key, and neither
 * can NaN; both {@link #rawset} calls throw {@link IllegalArgumentException} in those cases,
 * leaving it to callers with access to a source location to translate that into a proper
 * {@code LuaException}.
 *
 * <p>Since Lua 5.1 has no integer subtype, all numeric keys arrive as {@link Double}. Keys that
 * represent a whole number (e.g. {@code 1.0}) are normalized to a canonical {@link Long} before
 * being used as an array index or hash key, so that {@code t[1]} and {@code t[1.0]} refer to
 * the same slot.
 *
 * <p><b>Interop:</b> exported as both an object with members (the hash part's string keys) and
 * an array (the array part, exposed 0-based per interop convention while Lua code keeps seeing
 * 1-based indices &mdash; see {@link #readArrayElement} et al. for the conversion).
 */
@ExportLibrary(InteropLibrary.class)
public class LuaTable implements TruffleObject {

    private final ArrayList<Object> arrayPart = new ArrayList<>();
    private final LinkedHashMap<Object, Object> hashPart = new LinkedHashMap<>();
    private LuaTable metatable;

    // ---------------------------------------------------------------------
    // Raw access
    // ---------------------------------------------------------------------

    /** Raw get: no metamethods consulted. Returns {@code null} if the key is absent. */
    public Object rawget(Object key) {
        Object normalized = normalizeForLookup(key);
        if (normalized instanceof Long) {
            long idx = (Long) normalized;
            if (idx >= 1 && idx <= arrayPart.size()) {
                return arrayPart.get((int) (idx - 1));
            }
        }
        return hashPart.get(normalized);
    }

    /**
     * Raw set: no metamethods consulted. Setting {@code value} to {@code null} (or
     * {@link LuaNil#INSTANCE}) removes the key.
     *
     * @throws IllegalArgumentException if {@code key} is nil or NaN
     */
    public void rawset(Object key, Object value) {
        Object normalized = normalizeForStore(key);
        boolean isRemoval = value == null || value == LuaNil.INSTANCE;

        if (normalized instanceof Long) {
            long idx = (Long) normalized;
            if (idx >= 1 && idx <= arrayPart.size()) {
                if (isRemoval) {
                    removeFromArray((int) idx);
                } else {
                    arrayPart.set((int) (idx - 1), value);
                }
                return;
            }
            if (!isRemoval && idx == arrayPart.size() + 1) {
                arrayPart.add(value);
                absorbContiguousHashEntries();
                return;
            }
        }

        if (isRemoval) {
            hashPart.remove(normalized);
        } else {
            hashPart.put(normalized, value);
        }
    }

    /** The length of the (contiguous, nil-free) array part &mdash; the {@code #} operator for tables without {@code __len}. */
    public int rawlen() {
        return arrayPart.size();
    }

    // ---------------------------------------------------------------------
    // next() / pairs() support
    // ---------------------------------------------------------------------

    /**
     * Implements Lua's {@code next(table, key)}. Iterates the array part first (in index
     * order), then the hash part (in insertion order). Passing {@code null} (nil) for
     * {@code key} starts iteration from the beginning.
     *
     * @return a two-element array {@code {nextKey, value}}, or {@code null} if there is nothing
     *     left to iterate
     * @throws IllegalArgumentException if {@code key} is not present in the table
     */
    public Object[] next(Object key) {
        if (key == null || key == LuaNil.INSTANCE) {
            if (!arrayPart.isEmpty()) {
                return new Object[] {1L, arrayPart.get(0)};
            }
            return firstHashEntry();
        }

        Object normalized = normalizeForLookup(key);
        if (normalized instanceof Long) {
            long idx = (Long) normalized;
            if (idx >= 1 && idx <= arrayPart.size()) {
                if (idx < arrayPart.size()) {
                    return new Object[] {idx + 1, arrayPart.get((int) idx)};
                }
                // Last array element: continue into the hash part.
                return firstHashEntry();
            }
        }

        // Must be a hash-part key; walk the map to find the entry after it.
        Iterator<Map.Entry<Object, Object>> it = hashPart.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Object, Object> entry = it.next();
            if (entry.getKey().equals(normalized)) {
                if (it.hasNext()) {
                    Map.Entry<Object, Object> next = it.next();
                    return new Object[] {next.getKey(), next.getValue()};
                }
                return null;
            }
        }
        throw new IllegalArgumentException("invalid key to 'next'");
    }

    private Object[] firstHashEntry() {
        Iterator<Map.Entry<Object, Object>> it = hashPart.entrySet().iterator();
        if (it.hasNext()) {
            Map.Entry<Object, Object> entry = it.next();
            return new Object[] {entry.getKey(), entry.getValue()};
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Metatable
    // ---------------------------------------------------------------------

    public LuaTable getMetatable() {
        return metatable;
    }

    public void setMetatable(LuaTable metatable) {
        this.metatable = metatable;
    }

    /** Looks up a metamethod (e.g. {@code "__index"}) on this table's metatable, or {@code null} if there is none. */
    public Object getMetamethod(String name) {
        if (metatable == null) {
            return null;
        }
        Object value = metatable.rawget(name);
        return (value == null || value == LuaNil.INSTANCE) ? null : value;
    }

    // ---------------------------------------------------------------------
    // Interop: members (hash part, string keys)
    // ---------------------------------------------------------------------

    @ExportMessage
    boolean hasMembers() {
        return true;
    }

    @ExportMessage
    Object getMembers(@SuppressWarnings("unused") boolean includeInternal) {
        List<String> keys = new ArrayList<>();
        for (Object key : hashPart.keySet()) {
            if (key instanceof String) {
                keys.add((String) key);
            }
        }
        return new Keys(keys);
    }

    @ExportMessage
    boolean isMemberReadable(String member) {
        return rawget(member) != null;
    }

    @ExportMessage
    Object readMember(String member) throws UnknownIdentifierException {
        Object value = rawget(member);
        if (value == null) {
            throw UnknownIdentifierException.create(member);
        }
        return value;
    }

    @ExportMessage
    boolean isMemberModifiable(@SuppressWarnings("unused") String member) {
        return true;
    }

    @ExportMessage
    boolean isMemberInsertable(String member) {
        return rawget(member) == null;
    }

    @ExportMessage
    void writeMember(String member, Object value) {
        rawset(member, value);
    }

    @ExportMessage
    boolean isMemberRemovable(String member) {
        return rawget(member) != null;
    }

    @ExportMessage
    void removeMember(String member) throws UnknownIdentifierException {
        if (rawget(member) == null) {
            throw UnknownIdentifierException.create(member);
        }
        rawset(member, null);
    }

    // ---------------------------------------------------------------------
    // Interop: array elements (array part, 0-based at the interop boundary)
    // ---------------------------------------------------------------------

    @ExportMessage
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    long getArraySize() {
        return rawlen();
    }

    @ExportMessage
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < rawlen();
    }

    @ExportMessage
    Object readArrayElement(long index) throws InvalidArrayIndexException {
        if (!isArrayElementReadable(index)) {
            throw InvalidArrayIndexException.create(index);
        }
        Object value = rawget((double) (index + 1));
        return value == null ? LuaNil.INSTANCE : value;
    }

    @ExportMessage
    boolean isArrayElementModifiable(long index) {
        return index >= 0 && index < rawlen();
    }

    @ExportMessage
    boolean isArrayElementInsertable(long index) {
        return index == rawlen();
    }

    @ExportMessage
    void writeArrayElement(long index, Object value) throws InvalidArrayIndexException {
        if (!isArrayElementModifiable(index) && !isArrayElementInsertable(index)) {
            throw InvalidArrayIndexException.create(index);
        }
        rawset((double) (index + 1), value);
    }

    @ExportMessage
    boolean isArrayElementRemovable(long index) {
        return isArrayElementModifiable(index);
    }

    @ExportMessage
    void removeArrayElement(long index) throws InvalidArrayIndexException {
        if (!isArrayElementModifiable(index)) {
            throw InvalidArrayIndexException.create(index);
        }
        rawset((double) (index + 1), null);
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    /** Normalizes a key for a read-only lookup: nil/NaN simply never match anything, so no error is raised. */
    private Object normalizeForLookup(Object key) {
        if (key == null || key == LuaNil.INSTANCE) {
            return null;
        }
        if (key instanceof Double) {
            double d = (Double) key;
            if (Double.isNaN(d)) {
                return key;
            }
            if (isWholeNumber(d)) {
                return (long) d;
            }
        }
        return key;
    }

    /** Normalizes a key for storage, enforcing the "nil and NaN cannot be keys" rule (Lua 5.1 &sect;2.5.5). */
    private Object normalizeForStore(Object key) {
        if (key == null || key == LuaNil.INSTANCE) {
            throw new IllegalArgumentException("table index is nil");
        }
        if (key instanceof Double) {
            double d = (Double) key;
            if (Double.isNaN(d)) {
                throw new IllegalArgumentException("table index is NaN");
            }
            if (isWholeNumber(d)) {
                return (long) d;
            }
        }
        return key;
    }

    private static boolean isWholeNumber(double d) {
        return !Double.isInfinite(d) && d == Math.floor(d)
                && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE;
    }

    /** Removes array index {@code idx} (1-based), pushing any surviving tail entries into the hash part to preserve contiguity. */
    private void removeFromArray(int idx) {
        int size = arrayPart.size();
        if (idx == size) {
            arrayPart.remove(size - 1);
            return;
        }
        for (int j = size; j > idx; j--) {
            Object tailValue = arrayPart.remove(j - 1);
            hashPart.put((long) j, tailValue);
        }
        arrayPart.remove(idx - 1);
    }

    /** After appending to the array part, pull in any hash-part entries that now continue the contiguous run. */
    private void absorbContiguousHashEntries() {
        long next = arrayPart.size() + 1;
        Object value;
        while ((value = hashPart.remove(next)) != null) {
            arrayPart.add(value);
            next++;
        }
    }

    /** A simple interop-array view over a fixed list of member names, for {@link #getMembers}. */
    @ExportLibrary(InteropLibrary.class)
    static final class Keys implements TruffleObject {
        private final List<String> names;

        Keys(List<String> names) {
            this.names = names;
        }

        @ExportMessage
        boolean hasArrayElements() {
            return true;
        }

        @ExportMessage
        long getArraySize() {
            return names.size();
        }

        @ExportMessage
        boolean isArrayElementReadable(long index) {
            return index >= 0 && index < names.size();
        }

        @ExportMessage
        Object readArrayElement(long index) throws InvalidArrayIndexException {
            if (!isArrayElementReadable(index)) {
                throw InvalidArrayIndexException.create(index);
            }
            return names.get((int) index);
        }
    }
}
