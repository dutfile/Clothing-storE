/*
 * Copyright (c) 2011, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.virtual.phases.ea;

import static org.graalvm.compiler.core.common.GraalOptions.MaximumEscapeAnalysisArrayLength;

import java.util.List;

import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.graph.NodeSourcePosition;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.FixedNode;
import org.graalvm.compiler.nodes.FixedWithNextNode;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.calc.FloatingNode;
import org.graalvm.compiler.nodes.calc.UnpackEndianHalfNode;
import org.graalvm.compiler.nodes.java.MonitorIdNode;
import org.graalvm.compiler.nodes.spi.CanonicalizerTool;
import org.graalvm.compiler.nodes.spi.CoreProviders;
import org.graalvm.compiler.nodes.spi.CoreProvidersDelegate;
import org.graalvm.compiler.nodes.spi.VirtualizerTool;
import org.graalvm.compiler.nodes.virtual.VirtualArrayNode;
import org.graalvm.compiler.nodes.virtual.VirtualInstanceNode;
import org.graalvm.compiler.nodes.virtual.VirtualObjectNode;
import org.graalvm.compiler.options.OptionValues;

import jdk.vm.ci.meta.Assumptions;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;

/**
 * Forwards calls from {@link VirtualizerTool} to the actual {@link PartialEscapeBlockState}.
 */
class VirtualizerToolImpl extends CoreProvidersDelegate implements VirtualizerTool, CanonicalizerTool {

    private final PartialEscapeClosure<?> closure;
    private final Assumptions assumptions;
    private final OptionValues options;
    private final DebugContext debug;
    private ConstantNode illegalConstant;

    VirtualizerToolImpl(CoreProviders providers, PartialEscapeClosure<?> closure, Assumptions assumptions, OptionValues options, DebugContext debug) {
        super(providers);
        this.closure = closure;
        this.assumptions = assumptions;
        this.options = options;
        this.debug = debug;
    }

    private boolean deleted;
    private PartialEscapeBlockState<?> state;
    private ValueNode current;
    private FixedNode position;
    private GraphEffectList effects;

    @Override
    public OptionValues getOptions() {
        return options;
    }

    @Override
    public DebugContext getDebug() {
        return debug;
    }

    public void reset(PartialEscapeBlockState<?> newState, ValueNode newCurrent, FixedNode newPosition, GraphEffectList newEffects) {
        deleted = false;
        state = newState;
        current = newCurrent;
        position = newPosition;
        effects = newEffects;
    }

    public boolean isDeleted() {
        return deleted;
    }

    @Override
    public ValueNode getAlias(ValueNode value) {
        return closure.getAliasAndResolve(state, value);
    }

    @Override
    public ValueNode getEntry(VirtualObjectNode virtualObject, int index) {
        return state.getObjectState(virtualObject).getEntry(index);
    }

    @Override
    public boolean setVirtualEntry(VirtualObjectNode virtual, int index, ValueNode value, JavaKind theAccessKind, long offset) {
        ObjectState obj = state.getObjectState(virtual);
        assert obj.isVirtual() : "not virtual: " + obj;
        JavaKind entryKind = virtual.entryKind(this.getMetaAccessExtensionProvider(), index);
        JavaKind accessKind = theAccessKind != null ? theAccessKind : entryKind;
        ValueNode newValue = closure.getAliasAndResolve(state, value);
        getDebug().log(DebugContext.DETAILED_LEVEL, "Setting entry %d in virtual object %s %s results in %s", index, virtual.getObjectId(), virtual, state.getObjectState(virtual.getObjectId()));
        ValueNode oldValue = getEntry(virtual, index);
        boolean oldIsIllegal = oldValue.isIllegalConstant();
        boolean canVirtualize = entryKind == accessKind || (entryKind == accessKind.getStackKind() && virtual instanceof VirtualInstanceNode);
        if (!canVirtualize) {
            assert entryKind != JavaKind.Long || newValue != null;
            if (entryKind == JavaKind.Long && oldValue.getStackKind() == newValue.getStackKind() && oldValue.getStackKind().isPrimitive()) {
                /*
                 * Special case: If the entryKind is long, allow arbitrary kinds as long as a value
                 * of the same kind is already there. This can only happen if some other node
                 * initialized the entry with a value of a different kind. One example where this
                 * happens is the Truffle NewFrameNode.
                 */
                getDebug().log(DebugContext.DETAILED_LEVEL, "virtualizing %s with primitive of kind %s in long entry ", current, oldValue.getStackKind());
                canVirtualize = true;
            } else if (entryKind == JavaKind.Int && (accessKind == JavaKind.Long || accessKind == JavaKind.Double) && offset % 8 == 0) {
                /*
                 * Special case: Allow storing a single long or double value into two consecutive
                 * int slots.
                 */
                int nextIndex = virtual.entryIndexForOffset(getMetaAccess(), offset + 4, JavaKind.Int);
               