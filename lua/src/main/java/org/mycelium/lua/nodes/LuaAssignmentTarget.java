package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;

/**
 * One assignable location on the left-hand side of a Lua assignment statement (Lua 5.1
 * &sect;2.4.3). Concrete subclasses: {@link LuaLocalTarget}, {@link LuaUpvalueTarget},
 * {@link LuaGlobalTarget}, {@link LuaTableFieldTarget}.
 *
 * <p>Note on evaluation order: per &sect;2.4.3 all right-hand-side values are evaluated before
 * any assignment is performed, which {@code LuaAssignmentNode} guarantees by evaluating its whole
 * value list up front. Any table/key subexpressions embedded in a target itself (only relevant
 * for {@link LuaTableFieldTarget}) are evaluated lazily, at the point {@link #assign} runs, not
 * ahead of time; this is a simplification most real-world code cannot observe.
 */
public abstract class LuaAssignmentTarget extends Node {
    public abstract void assign(VirtualFrame frame, Object value);
}
