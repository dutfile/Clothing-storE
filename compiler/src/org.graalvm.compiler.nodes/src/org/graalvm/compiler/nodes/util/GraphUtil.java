/*
 * Copyright (c) 2011, 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.nodes.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.EconomicSet;
import org.graalvm.collections.Equivalence;
import org.graalvm.collections.MapCursor;
import org.graalvm.compiler.bytecode.Bytecode;
import org.graalvm.compiler.code.SourceStackTraceBailoutException;
import org.graalvm.compiler.core.common.type.ObjectStamp;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.graph.Graph;
import org.graalvm.compiler.graph.LinkedStack;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.graph.NodeBitMap;
import org.graalvm.compiler.graph.NodeSourcePosition;
import org.graalvm.compiler.graph.NodeStack;
import org.graalvm.compiler.graph.Position;
import org.graalvm.compiler.graph.iterators.NodeIterable;
import org.graalvm.compiler.nodes.AbstractBeginNode;
import org.graalvm.compiler.nodes.AbstractEndNode;
import org.graalvm.compiler.nodes.AbstractMergeNode;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.ControlSinkNode;
import org.graalvm.compiler.nodes.ControlSplitNode;
import org.graalvm.compiler.nodes.EndNode;
import org.graalvm.compiler.nodes.FixedNode;
import org.graalvm.compiler.nodes.FixedWithNextNode;
import org.graalvm.compiler.nodes.FrameState;
import org.graalvm.compiler.nodes.GuardNode;
import org.graalvm.compiler.nodes.IfNode;
import org.graalvm.compiler.nodes.LoopBeginNode;
import org.graalvm.compiler.nodes.LoopEndNode;
import org.graalvm.compiler.nodes.LoopExitNode;
import org.graalvm.compiler.nodes.MergeNode;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.PhiNode;
import org.graalvm.compiler.nodes.PiNode;
import org.graalvm.compiler.nodes.ProxyNode;
import org.graalvm.compiler.nodes.StateSplit;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.ValuePhiNode;
import org.graalvm.compiler.nodes.ValueProxyNode;
import org.graalvm.compiler.nodes.WithExceptionNode;
import org.graalvm.compiler.nodes.extended.MultiGuardNode;
import org.graalvm.compiler.nodes.java.LoadIndexedNode;
import org.graalvm.compiler.nodes.java.MethodCallTargetNode;
import org.graalvm.compiler.nodes.java.MonitorIdNode;
import org.graalvm.compiler.nodes.memory.MemoryPhiNode;
import org.graalvm.compiler.nodes.spi.ArrayLengthProvider;
import org.graalvm.compiler.nodes.spi.ArrayLengthProvider.FindLengthMode;
import org.graalvm.compiler.nodes.spi.CoreProviders;
import org.graalvm.compiler.nodes.spi.CoreProvidersDelegate;
import org.graalvm.compiler.nodes.spi.LimitedValueProxy;
import org.graalvm.compiler.nodes.spi.SimplifierTool;
import org.graalvm.compiler.nodes.spi.ValueProxy;
import org.graalvm.compiler.nodes.spi.VirtualizerTool;
import org.graalvm.compiler.nodes.type.StampTool;
import org.graalvm.compiler.nodes.virtual.VirtualArrayNode;
import org.graalvm.compiler.nodes.virtual.VirtualObjectNode;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionKey;
import org.graalvm.compiler.options.OptionType;
import org.graalvm.compiler.options.OptionValues;

import jdk.vm.ci.code.BailoutException;
import jdk.vm.ci.code.BytecodePosition;
import jdk.vm.ci.meta.Assumptions;
import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

public class GraphUtil {

    public static class Options {
        @Option(help = "Verify that there are no new unused nodes when performing killCFG", type = OptionType.Debug)//
        public static final OptionKey<Boolean> VerifyKillCFGUnusedNodes = new OptionKey<>(false);
    }

    public static final int MAX_FRAMESTATE_SEARCH_DEPTH = 4;

    private static void killCFGInner(FixedNode node) {
        EconomicSet<Node> markedNodes = EconomicSet.create();
        EconomicMap<AbstractMergeNode, List<AbstractEndNode>> unmarkedMerges = EconomicMap.create();

        // Detach this node from CFG
        node.replaceAtPredecessor(null);

        markFixedNodes(node, markedNodes, unmarkedMerges);

        fixSurvivingAffectedMerges(markedNodes, unmarkedMerges);

        DebugContext debug = node.getDebug();
        debug.dump(DebugContext.DETAILED_LEVEL, node.graph(), "After fixing merges (killCFG %s)", node);

        // Mark non-fixed nodes
        markUsagesForKill(markedNodes);

        // Detach marked nodes from non-marked nodes
        for (Node marked : markedNodes) {
            for (Node input : marked.inputs()) {
                if (!markedNodes.contains(input)) {
                    marked.replaceFirstInput(input, null);
                    tryKillUnused(input);
                }
            }
        }
        debug.dump(DebugContext.VERY_DETAILED_LEVEL, node.graph(), "After disconnecting non-marked inputs (killCFG %s)", node);
        // Kill marked nodes
        for (Node marked : markedNodes) {
            if (marked.isAlive()) {
                marked.markDeleted();
            }
        }
    }

    private static void markFixedNodes(FixedNode node, EconomicSet<Node> markedNodes, EconomicMap<AbstractMergeNode, List<AbstractEndNode>> unmarkedMerges) {
        NodeStack workStack = new NodeStack();
        workStack.push(node);
        while (!workStack.isEmpty()) {
            Node fixedNode = workStack.pop();
            markedNodes.add(fixedNode);
            if (fixedNode instanceof AbstractMergeNode) {
                unmarkedMerges.removeKey((AbstractMergeNode) fixedNode);
            }
            while (fixedNode instanceof FixedWithNextNode) {
                fixedNode = ((FixedWithNextNode) fixedNode).next();
                if (fixedNode != null) {
                    markedNodes.add(fixedNode);
                }
            }
            if (fixedNode instanceof ControlSplitNode) {
                for (Node successor : fixedNode.successors()) {
                    workStack.push(successor);
                }
            } else if (fixedNode instanceof AbstractEndNode) {
                AbstractEndNode end = (AbstractEndNode) fixedNode;
                AbstractMergeNode merge = end.merge();
                if (merge != null) {
                    assert !markedNodes.contains(merge) || (merge instanceof LoopBeginNode && end instanceof LoopEndNode) : merge;
                    if (merge instanceof LoopBeginNode) {
                        if (end == ((LoopBeginNode) merge).forwardEnd()) {
                            workStack.push(merge);
                            continue;
                        }
                        if (markedNodes.contains(merge)) {
                            continue;
                        }
                    }
                    List<AbstractEndNode> endsSeen = unmarkedMerges.get(merge);
                    if (endsSeen == null) {
                        endsSeen = new ArrayList<>(merge.forwardEndCount());
                        unmarkedMerges.put(merge, endsSeen);
                    }
                    endsSeen.add(end);
                    if (!(end instanceof LoopEndNode) && endsSeen.size() == merge.forwardEndCount()) {
                        assert merge.forwardEnds().filter(n -> !markedNodes.contains(n)).isEmpty();
                        // all this merge's forward ends are marked: it needs to be killed
                        workStack.push(merge);
                    }
                }
            }
        }
    }

    private static void fixSurvivingAffectedMerges(EconomicSet<Node> markedNodes, EconomicMap<AbstractMergeNode, List<AbstractEndNode>> unmarkedMerges) {
        MapCursor<AbstractMergeNode, List<AbstractEndNode>> cursor = unmarkedMerges.getEntries();
        while (cursor.advance()) {
            AbstractMergeNode merge = cursor.getKey();
            for (AbstractEndNode end : cursor.getValue()) {
                merge.removeEnd(end);
            }
            if (merge.phiPredecessorCount() == 1) {
                if (merge instanceof LoopBeginNode) {
                    LoopBeginNode loopBegin = (LoopBeginNode) merge;
                    assert merge.forwardEndCount() == 1;
                    for (LoopExitNode loopExit : loopBegin.loopExits().snapshot()) {
                        if (markedNodes.contains(loopExit)) {
                            /*
                             * disconnect from loop begin so that reduceDegenerateLoopBegin doesn't
                             * transform it into a new beginNode
                 