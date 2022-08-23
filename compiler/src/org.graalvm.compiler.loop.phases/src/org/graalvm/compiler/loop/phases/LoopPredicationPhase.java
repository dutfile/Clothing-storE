/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2020, Red Hat Inc. All rights reserved.
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

package org.graalvm.compiler.loop.phases;

import static org.graalvm.compiler.core.common.GraalOptions.LoopPredicationMainPath;
import static org.graalvm.compiler.core.common.calc.Condition.EQ;
import static org.graalvm.compiler.core.common.calc.Condition.NE;

import java.util.Optional;

import org.graalvm.collections.EconomicMap;
import org.graalvm.compiler.core.common.calc.Condition;
import org.graalvm.compiler.core.common.cfg.AbstractControlFlowGraph;
import org.graalvm.compiler.core.common.type.IntegerStamp;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.graph.iterators.NodeIterable;
import org.graalvm.compiler.nodes.AbstractBeginNode;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.FrameState;
import org.graalvm.compiler.nodes.GraphState;
import org.graalvm.compiler.nodes.GuardNode;
import org.graalvm.compiler.nodes.GuardedValueNode;
import org.graalvm.compiler.nodes.LogicNode;
import org.graalvm.compiler.nodes.LoopEndNode;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.calc.CompareNode;
import org.graalvm.compiler.nodes.calc.IntegerBelowNode;
import org.graalvm.compiler.nodes.calc.IntegerConvertNode;
import org.graalvm.compiler.nodes.cfg.HIRBlock;
import org.graalvm.compiler.nodes.cfg.ControlFlowGraph;
import org.graalvm.compiler.nodes.extended.AnchoringNode;
import org.graalvm.compiler.nodes.extended.GuardingNode;
import org.graalvm.compiler.nodes.extended.MultiGuardNode;
import org.graalvm.compiler.nodes.loop.CountedLoopInfo;
import org.graalvm.compiler.nodes.loop.InductionVariable;
import org.graalvm.compiler.nodes.loop.LoopEx;
import org.graalvm.compiler.nodes.loop.LoopsData;
import org.graalvm.compiler.nodes.loop.MathUtil;
import org.graalvm.compiler.phases.Speculative;
import org.graalvm.compiler.phases.common.CanonicalizerPhase;
import org.graalvm.compiler.phases.common.PostRunCanonicalizationPhase;
import org.graalvm.compiler.phases.tiers.MidTierContext;
import org.graalvm.compiler.serviceprovider.SpeculationReasonGroup;

import jdk.vm.ci.code.BytecodePosition;
import jdk.vm.ci.meta.SpeculationLog;

public class LoopPredicationPhase extends PostRunCanonicalizationPhase<MidTierContext> implements Speculative {
    private static final SpeculationReasonGroup LOOP_PREDICATION = new SpeculationReasonGroup("Loop Predication", BytecodePosition.class);

    public LoopPredicationPhase(CanonicalizerPhase canonicalizer) {
        super(canonicalizer);
    }

    @Override
    public Optional<NotApplicable> notApplicableTo(GraphState graphState) {
        return NotApplicable.ifAny(
                        super.notApplicableTo(graphState),
                        NotApplicable.withoutSpeculationLog(this, graphState),
                        NotApplicable.when(!graphState.getGuardsStage().allowsFloatingGuards(), "Floating guards must be allowed."));
    }

    @Override
    @SuppressWarnings("try")
    protected void run(StructuredGraph graph, MidTierContext context) {
        DebugContext debug = graph.getDebug();
        final SpeculationLog speculationLog = graph.getSpeculationLog();
        if (graph.hasLoops() && context.getOptimisticOptimizations().useLoopLimitChecks(graph.getOptions())) {
            LoopsData data = context.getLoopsDataProvider().getLoopsData(graph);
            final ControlFlowGraph cfg = data.getCFG();
            try (DebugContext.Scope s = debug.scope("predication", cfg)) {
                for (LoopEx loop : data.loops()) {
                    // Only inner most loops.
                    if (!loop.loop().getChildren().isEmpty()) {
                        continue;
                    }
                    if (!loop.detectCounted()) {
                        continue;
                    }
                    final FrameState state = loop.loopBegin().stateAfter();
                    final BytecodePosition pos = new BytecodePosition(null, state.getMethod(), state.bci);
                    SpeculationLog.SpeculationReason reason = LOOP_PREDICATION.createSpeculationReason(pos);
                    if (speculationLog.maySpeculate(reason)) {
                        final CountedLoopInfo counted = loop.counted();
                        final InductionVariable counter = counted.getLimitCheckedIV();
                        final Condition condition = ((CompareNode) counted.getLimitTest().condition()).condition().asCondition();
                        final boolean inverted = loop.counted().isInverted();
                        if ((((IntegerStamp) counter.valueNode().stamp(NodeView.DEFAULT)).getBits() == 32) &&
                                        !counted.isUnsignedCheck() &&
                                        ((condition != NE && condition != EQ)