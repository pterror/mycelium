package org.mycelium.lua.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.SourceSection;

public abstract class LuaStatementNode extends Node {
    private SourceSection sourceSection;

    public abstract void executeVoid(VirtualFrame frame);

    /** Attaches the source range this statement was parsed from (see {@link #getSourceSection()}). */
    public final void setSourceSection(SourceSection sourceSection) {
        this.sourceSection = sourceSection;
    }

    @Override
    public SourceSection getSourceSection() {
        return sourceSection;
    }
}
