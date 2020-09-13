/*
 * Copyright (c) 2012, 2023, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.nodes.loop;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.Equivalence;
import org.graalvm.compiler.core.common.type.IntegerStamp;
import org.graalvm.compiler.debug.DebugCloseable;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.graph.Graph.DuplicationReplacement;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.graph.NodeBitMap;
import org.graalvm.compiler.graph.Position;
import org.graalvm.compiler.graph.iterators.NodeIterable;
import org.graalvm.compiler.nodeinfo.InputType;
import org.graalvm.compiler.nodes.AbstractBeginNode;
import org.graalvm.compiler.nodes.AbstractEndNode;
import org.graalvm.compiler.nodes.AbstractMergeNode;
import org.graalvm.compiler.nodes.BeginNode;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.EndNode;
import org.graalvm.compiler.nodes.FixedNode;
import org.graalvm.compiler.nodes.FixedWithNextNode;
import org.graalvm.compiler.nodes.FrameState;
import org.graalvm.compiler.nodes.GraphState.StageFlag;
import org.graalvm.compiler.nodes.GuardPhiNode;
import org.graalvm.compiler.nodes.GuardProxyNode;
import org.graalvm.compiler.nodes.IfNode;
import org.graalvm.compiler.nodes.LogicNode;
import org.graalvm.compiler.nodes.LoopBeginNode;
import org.graalvm.compiler.nodes.LoopEndNode;
import org.graalvm.compiler.nodes.LoopExitNode;
import org.graalvm.compiler.nodes.MemoryProxyNode;
import org.graalvm.compiler.nodes.MergeNode;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.PhiNode;
import org.graalvm.compiler.nodes.ProxyNode;
import org.graalvm.compiler.nodes.SafepointNode;
import org.graalvm.compiler.nodes.StateSplit;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.ValuePhiNode;
import org.graalvm.compiler.nodes.ValueProxyNode;
import org.graalvm.compiler.nodes.VirtualState.NodePositionClosure;
import org.graalvm.compiler.nodes.calc.AddNode;
import org.graalvm.compiler.nodes.calc.CompareNode;
import org.graalvm.compiler.nodes.calc.ConditionalNode;
import org.graalvm.compiler.nodes.calc.IntegerBelowNode;
import org.graalvm.compiler.nodes.calc.SubNode;
import org.graalvm.compiler.nodes.extended.AnchoringNode;
import org.graalvm.compiler.nodes.extended.GuardingNode;
import org.graalvm.compiler.nodes.extended.OpaqueNode;
import org.graalvm.compiler.nodes.extended.ValueAnchorNode;
import org.graalvm.compiler.nodes.memory.MemoryKill;
import org.graalvm.compiler.nodes.memory.MemoryPhiNode;
import org.graalvm.compiler.nodes.util.GraphUtil;
import org.graalvm.compiler.nodes.util.IntegerHelper;

public class LoopFragmentInside extends LoopFragment {

    /**
     * mergedInitializers. When an inside fragment's (loop)ends are merged to create a unique exit
     * point, some phis must be created : they phis together all the back-values of the loop-phis
     * These can then be used to update the loop-phis' forward edge value ('initializer') in the
     * peeling case. In the unrolling case they will be used as the value that replace the loop-phis
     * of the duplicated inside fragment
     */
    private EconomicMap<PhiNode, ValueNode> mergedInitializers;
    private final DuplicationReplacement dataFixBefore = new DuplicationReplacement() {

        @Override
        public Node replacement(Node oriInput) {
            if (!(oriInput instanceof ValueNode)) {
                return oriInput;
            }
            return prim((ValueNode) oriInput);
        }
    };

    private final DuplicationReplacement dataFixWithinAfter = new DuplicationReplacement() {

        @Override
        public Node replacement(Node oriInput) {
            if (!(oriInput instanceof ValueNode)) {
                return oriInput;
            }
            return primAfter((ValueNode) oriInput);
        }
    };

    public LoopFragmentInside(LoopEx loop) {
        super(loop);
    }

    public LoopFragmentInside(LoopFragmentInside original) {
        super(null, original);
    }

    @Override
    public LoopFragmentInside duplicate() {
        assert !isDuplicate();
        return new LoopFragmentInside(this);
    }

    @Override
    public LoopFragmentInside original() {
        return (LoopFragmentInside) super.original();
    }

    @SuppressWarnings("unused")
    public void appendInside(LoopEx loop) {
        GraalError.unimplemented(); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public LoopEx loop() {
        assert !this.isDuplicate();
        return super.loop();
    }

    @Override
    public void insertBefore(LoopEx loop) {
        assert this.isDuplicate() && this.original().loop() == loop;

        patchNodes(dataFixBefore);

        AbstractBeginNode end = mergeEnds();

        mergeEarlyExits();

        original().patchPeeling(this);

        AbstractBeginNode entry = getDuplicatedNode(loop.loopBegin());
        loop.entryPoint().replaceAtPredecessor(entry);
        end.setNext(loop.entryPoint());
    }

    /**
     * Duplicate the body within the loop after the current copy copy of the body, updating the
     * iteration limit to account for the duplication.
     */
    public void insertWithinAfter(LoopEx loop, EconomicMap<LoopBeginNode, OpaqueNode> opaqueUnrolledStrides) {
        assert isDuplicate() && original().loop() == loop;

        patchNodes(dataFixWithinAfter);

        /*
         * Collect any new back edges values before updating them since they might reference each
         * other.
         */
        LoopBeginNode mainLoopBegin = loop.loopBegin();
        ArrayList<ValueNode> backedgeValues = new ArrayList<>();
        EconomicMap<Node, Node> new2OldPhis = EconomicMap.create();
        EconomicMap<Node, Node> originalPhi2Backedges = EconomicMap.create();
        for (PhiNode mainPhiNode : mainLoopBegin.phis()) {
            originalPhi2Backedges.put(mainPhiNode, mainPhiNode.valueAt(1));
        }
        for (PhiNode mainPhiNode : mainLoopBegin.phis()) {
            ValueNode originalNode = mainPhiNode.valueAt(1);
            ValueNode duplicatedNode = getDuplicatedNode(originalNode);
            if (duplicatedNode == null) {
                if (mainLoopBegin.isPhiAtMerge(originalNode)) {
                    duplicatedNode = ((PhiNode) (originalNode)).valueAt(1);
                } else {
                    assert originalNode.isConstant() || loop.isOutsideLoop(originalNode) : "Not duplicated node " + originalNode;
                }
            }
            if (duplicatedNode != null) {
                new2OldPhis.put(duplicatedNode, originalNode);
            }
            backedgeValues.add(duplicatedNode);
        }
        int index = 0;
        for (PhiNode mainPhiNode : mainLoopBegin.phis()) {
            ValueNode duplicatedNode = backedgeValues.get(index++);
            if (duplicatedNode != null) {
                mainPhiNode.setValueAt(1, duplicatedNode);
            }
        }

        CompareNode condition = placeNewSegmentAndCleanup(loop, new2OldPhis, originalPhi2Backedges);

        // Remove any safepoints from the original copy leaving only the duplicated one, inverted
        // ones have their safepoint between the limit check and the backedge that have been removed
        // already.
        assert loop.whole().nodes().filter(SafepointNode.class).count() == nodes().filter(SafepointNode.class).count() || loop.counted.isInverted();
        for (SafepointNode safepoint : loop.whole().nodes().filter(SafepointNode.class)) {
            graph().removeFixed(safepoint);
        }

        StructuredGraph graph = mainLoopBegin.graph();
        if (opaqueUnrolledStrides != null) {
            OpaqueNode opaque = opaqueUnrolledStrides.get(loop.loopBegin());
            CountedLoopInfo counted = loop.counted();
            ValueNode counterStride = counted.getLimitCheckedIV().strideNode();
            if (opaque == null || opaque.isDeleted()) {
                ValueNode limit = counted.getLimit();
                opaque = new OpaqueNode(AddNode.add(counterStride, counterStride, NodeView.DEFAULT));
                ValueNode newLimit = partialUnrollOverflowCheck(opaque, limit, counted);
                GraalError.guarantee(condition.hasExactlyOneUsage(),
                                "Unrolling loop %s with condition %s, which has multiple usages. Usages other than the loop exit check would get an incorrect condition.", loop.loopBegin(), condition);
                condition.replaceFirstInput(limit, graph.addOrUniqueWithInputs(newLimit));
                opaqueUnrolledStrides.put(loop.loopBegin(), opaque);
            } else {
                assert counted.getLimitCheckedIV().isConstantStride();
                assert Math.addExact(counted.getLimitCheckedIV().constantStride(), counted.getLimitCheckedIV().constantStride()) == counted.getLimitCheckedIV().constantStride() * 2;
                ValueNode previousValue = opaque.getValue();
                opaque.setValue(graph.addOrUniqueWithInputs(AddNode.add(counterStride, previousValue, NodeView.DEFAULT)));
                GraphUtil.tryKillUnused(previousValue);
            }
        }
        mainLoopBegin.setUnrollFactor(mainLoopBegin.getUnrollFactor() * 2);
        graph.getDebug().dump(DebugContext.DETAILED_LEVEL, graph, "LoopPartialUnroll %s", loop);

        mainLoopBegin.getDebug().dump(DebugContext.VERBOSE_LEVEL, mainLoopBegin.graph(), "After insertWithinAfter %s", mainLoopBegin);
    }

    public static ValueNode partialUnrollOverflowCheck(OpaqueNode opaque, ValueNode limit, CountedLoopInfo counted) {
        int bits = ((IntegerStamp) limit.stamp(NodeView.DEFAULT)).getBits();
        ValueNode newLimit = SubNode.create(limit, opaque, NodeView.DEFAULT);
        IntegerHelper helper = counted.getCounterIntegerHelper();
        LogicNode overflowCheck;
        ConstantNode extremum;
        if (counted.getDirection() == InductionVariable.Direction.Up) {
            // limit - counterStride could overflow negatively if limit - min <
            // counterStride
            extremum = ConstantNode.forIntegerBits(bits, helper.minValue());
            overflowCheck = IntegerBelowNode.create(SubNode.create(limit, extremum, NodeView.DEFAULT), opaque, NodeView.DEFAULT);
        } else {
            assert counted.getDirection() == InductionVariable.Direction.Down;
            // limit - counterStride could overflow if max - limit < -counterStride
         