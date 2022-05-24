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
package org.graalvm.compiler.phases.common.inlining;

import static jdk.vm.ci.meta.DeoptimizationAction.InvalidateReprofile;
import static jdk.vm.ci.meta.DeoptimizationReason.NullCheckException;
import static org.graalvm.compiler.core.common.GraalOptions.HotSpotPrintInlining;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.EconomicSet;
import org.graalvm.collections.Equivalence;
import org.graalvm.collections.Pair;
import org.graalvm.collections.UnmodifiableEconomicMap;
import org.graalvm.collections.UnmodifiableMapCursor;
import org.graalvm.compiler.core.common.calc.CanonicalCondition;
import org.graalvm.compiler.core.common.type.Stamp;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.core.common.type.TypeReference;
import org.graalvm.compiler.core.common.util.Util;
import org.graalvm.compiler.debug.DebugCloseable;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.graph.Graph.DuplicationReplacement;
import org.graalvm.compiler.graph.Graph.Mark;
import org.graalvm.compiler.graph.Graph.NodeEventScope;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.graph.NodeInputList;
import org.graalvm.compiler.graph.NodeMap;
import org.graalvm.compiler.graph.NodeSourcePosition;
import org.graalvm.compiler.graph.NodeWorkList;
import org.graalvm.compiler.graph.Position;
import org.graalvm.compiler.nodeinfo.InputType;
import org.graalvm.compiler.nodeinfo.Verbosity;
import org.graalvm.compiler.nodes.AbstractBeginNode;
import org.graalvm.compiler.nodes.AbstractEndNode;
import org.graalvm.compiler.nodes.AbstractMergeNode;
import org.graalvm.compiler.nodes.BeginNode;
import org.graalvm.compiler.nodes.CallTargetNode.InvokeKind;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.DeoptimizeNode;
import org.graalvm.compiler.nodes.DeoptimizingGuard;
import org.graalvm.compiler.nodes.EndNode;
import org.graalvm.compiler.nodes.FixedGuardNode;
import org.graalvm.compiler.nodes.FixedNode;
import org.graalvm.compiler.nodes.FrameState;
import org.graalvm.compiler.nodes.GraphState.GuardsStage;
import org.graalvm.compiler.nodes.GraphState.StageFlag;
import org.graalvm.compiler.nodes.InliningLog;
import org.graalvm.compiler.nodes.Invoke;
import org.graalvm.compiler.nodes.InvokeWithExceptionNode;
import org.graalvm.compiler.nodes.LogicNode;
import org.graalvm.compiler.nodes.MergeNode;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.ParameterNode;
import org.graalvm.compiler.nodes.PhiNode;
import org.graalvm.compiler.nodes.PiNode;
import org.graalvm.compiler.nodes.ReturnNode;
import org.graalvm.compiler.nodes.StartNode;
import org.graalvm.compiler.nodes.StateSplit;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.UnwindNode;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.VirtualState;
import org.graalvm.compiler.nodes.WithExceptionNode;
import org.graalvm.compiler.nodes.calc.CompareNode;
import org.graalvm.compiler.nodes.calc.IsNullNode;
import org.graalvm.compiler.nodes.extended.ForeignCall;
import org.graalvm.compiler.nodes.extended.GuardedNode;
import org.graalvm.compiler.nodes.extended.GuardingNode;
import org.graalvm.compiler.nodes.extended.LoadHubNode;
import org.graalvm.compiler.nodes.extended.ValueAnchorNode;
import org.graalvm.compiler.nodes.java.ExceptionObjectNode;
import org.graalvm.compiler.nodes.java.MethodCallTargetNode;
import org.graalvm.compiler.nodes.java.MonitorExitNode;
import org.graalvm.compiler.nodes.java.MonitorIdNode;
import org.graalvm.compiler.nodes.spi.CoreProviders;
import org.graalvm.compiler.nodes.type.StampTool;
import org.graalvm.compiler.nodes.util.GraphUtil;
import org.graalvm.compiler.phases.common.inlining.info.InlineInfo;
import org.graalvm.compiler.phases.common.util.EconomicSetNodeEventListener;
import org.graalvm.compiler.phases.util.ValueMergeUtil;
import org.graalvm.compiler.serviceprovider.SpeculationReasonGroup;

import jdk.vm.ci.code.BytecodeFrame;
import jdk.vm.ci.meta.DeoptimizationAction;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaTypeProfile;
import jdk.vm.ci.meta.ProfilingInfo;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.SpeculationLog;
import jdk.vm.ci.meta.SpeculationLog.Speculation;
import jdk.vm.ci.meta.TriState;

public class InliningUtil extends ValueMergeUtil {

    private static final String inliningDecisionsScopeString = "InliningDecisions";

    /**
     * Print a HotSpot-style inlining message to the console.
     */
    private static void printInlining(final InlineInfo info, final int inliningDepth, final boolean success, final String msg, final Object... args) {
        printInlining(info.methodAt(0), info.invoke(), inliningDepth, success, msg, args);
    }

    /**
     * @see #printInlining
     */
    private static void printInlining(final ResolvedJavaMethod method, final Invoke invoke, final int inliningDepth, final boolean success, final String msg, final Object... args) {
        if (HotSpotPrintInlining.getValue(invoke.asNode().getOptions())) {
            Util.printInlining(method, invoke.bci(), inliningDepth, success, msg, args);
        }
    }

    /**
     * Trace a decision to inline a method.
     *
     * This prints a HotSpot-style inlining message to the console, and it also logs the decision to
     * the logging stream.
     *
     * Phases that perform inlining should use this method to trace the inlining decisions, and use
     * the {@link #traceNotInlinedMethod} methods only for debugging purposes.
     */
    public static void traceInlinedMethod(InlineInfo info, int inliningDepth, boolean allowLogging, String msg, Object... args) {
        traceMethod(info, inliningDepth, allowLogging, true, msg, args);
    }

    /**
     * Trace a decision to inline a method.
     *
     * This prints a HotSpot-style inlining message to the console, and it also logs the decision to
     * the logging stream.
     *
     * Phases that perform inlining should use this method to trace the inlining decisions, and use
     * the {@link #traceNotInlinedMethod} methods only for debugging purposes.
     */
    public static void traceInlinedMethod(Invoke invoke, int inliningDepth, boolean allowLogging, ResolvedJavaMethod method, String msg, Object... args) {
        traceMethod(invoke, inliningDepth, allowLogging, true, method, msg, args);
    }

    /**
     * Trace a decision to not inline a method.
     *
     * This prints a HotSpot-style inlining message to the console, and it also logs the decision to
     * the logging stream.
     *
     * Phases that perform inlining should use this method to trace the inlining decisions, and use
     * the {@link #traceNotInlinedMethod} methods only for debugging purposes.
     */
    public static void traceNotInlinedMethod(InlineInfo info, int inliningDepth, String msg, Object... args) {
        traceMethod(info, inliningDepth, true, false, msg, args);
    }

    /**
     * Trace a decision about not inlining a method.
     *
     * This prints a HotSpot-style inlining message to the console, and it also logs the decision to
     * the logging stream.
     *
     * Phases that perform inlining should use this method to trace the inlining decisions, and use
     * the {@link #traceNotInlinedMethod} methods only for debugging purposes.
     */
    public static void traceNotInlinedMethod(Invoke invoke, int inliningDepth, ResolvedJavaMethod method, String msg, Object... args) {
        traceMethod(invoke, inliningDepth, true, false, method, msg, args);
    }

    private static void traceMethod(Invoke invoke, int inliningDepth, boolean allowLogging, boolean success, ResolvedJavaMethod method, String msg, Object... args) {
        if (allowLogging) {
            DebugContext debug = invoke.asNode().getDebug();
            printInlining(method, invoke, inliningDepth, success, msg, args);
            if (shouldLogMethod(debug)) {
                String methodString = methodName(method, invoke);
                logMethod(debug, methodString, success, msg, args);
            }
        }
    }

    private static void traceMethod(InlineInfo info, int inliningDepth, boolean allowLogging, boolean success, String msg, final Object... args) {
        if (allowLogging) {
            printInlining(info, inliningDepth, success, msg, args);
            DebugContext debug = info.graph().getDebug();
            if (shouldLogMethod(debug)) {
                logMethod(debug, methodName(info), success, msg, args);
            }
        }
    }

    /**
     * Output a generic inlining decision to the logging stream (e.g. inlining termination
     * condition).
     *
     * Used for debugging purposes.
     */
    public static void logInliningDecision(DebugContext debug, final String msg, final Object... args) {
        logInlining(debug, msg, args);
    }

    /**
     * Output a decision about not inlining a method to the logging stream, for debugging purposes.
     */
    public static void logNotInlinedMethod(Invoke invoke, String msg) {
        DebugContext debug = invoke.asNode().getDebug();
        if (shouldLogMethod(debug)) {
            String methodString = invoke.toString();
            if (invoke.callTarget() == null) {
                methodString += " callTarget=null";
            } else {
                String targetName = invoke.callTarget().targetName();
                if (!methodString.endsWith(targetName)) {
                    methodString += " " + targetName;
                }
            }
            logMethod(debug, methodString, false, msg, new Object[0]);
        }
    }

    private static void logMethod(DebugContext debug, final String methodString, final boolean success, final String msg, final Object... args) {
        String inliningMsg = "inlining " + methodString + ": " + msg;
        if (!success) {
            inliningMsg = "not " + inliningMsg;
        }
        logInlining(debug, inliningMsg, args);
    }

    @SuppressWarnings("try")
    private static void logInlining(DebugContext debug, final String msg, final Object... args) {
        try (DebugContext.Scope s = debug.scope(inliningDecisionsScopeString)) {
            // Can't use log here since we are varargs
            if (debug.isLogEnabled()) {
                debug.logv(msg, args);
            }
        }
    }

    @SuppressWarnings("try")
    private static boolean shouldLogMethod(DebugContext debug) {
        try (DebugContext.Scope s = debug.scope(inliningDecisionsScopeString)) {
            return debug.isLogEnabled();
        }
    }

    private static String methodName(ResolvedJavaMethod method, Invoke invoke) {
        if (invoke != null && invoke.stateAfter() != null) {
            return methodName(invoke.stateAfter(), invoke.bci()) + ": " + method.format("%H.%n(%p):%r") + " (" + method.getCodeSize() + " bytes)";
        } else {
            return method.format("%H.%n(%p):%r") + " (" + method.getCodeSize() + " bytes)";
        }
    }

    private static String methodName(InlineInfo info) {
        if (info == null) {
            return "null";
        } else if (info.invoke() != null && info.invoke().stateAfter() != null) {
            return methodName(info.invoke().stateAfter(), info.invoke().bci()) + ": " + info.toString();
        } else {
            return info.toString();
        }
    }

    private static String methodName(FrameState frameState, int bci) {
        StringBuilder sb = new StringBuilder();
        if (frameState.outerFrameState() != null) {
            sb.append(methodName(frameState.outerFrameState(), frameState.outerFrameState().bci));
            sb.append("->");
        }
        ResolvedJavaMethod method = frameState.getMethod();
        sb.append(method != null ? method.format("%h.%n") : "?");
        sb.append("@").append(bci);
        return sb.toString();
    }

    public static void replaceInvokeCallTarget(Invoke invoke, StructuredGraph graph, InvokeKind invokeKind, ResolvedJavaMethod targetMethod) {
        MethodCallTargetNode oldCallTarget = (MethodCallTargetNode) invoke.callTarget();
        MethodCallTargetNode newCallTarget = graph.add(new MethodCallTargetNode(invokeKind, targetMethod, oldCallTarget.arguments().toArray(ValueNode.EMPTY_ARRAY), oldCallTarget.returnStamp(),
                        oldCallTarget.getTypeProfile()));
        invoke.asNode().replaceFirstInput(oldCallTarget, newCallTarget);
    }

    public static PiNode createAnchoredReceiver(StructuredGraph graph, GuardingNode anchor, ResolvedJavaType commonType, ValueNode receiver, boolean exact) {
        return createAnchoredReceiver(graph, anchor, receiver,
                        exact ? StampFactory.objectNonNull(TypeReference.createExactTrusted(commonType)) : StampFactory.objectNonNull(TypeReference.createTrusted(graph.getAssumptions(), commonType)));
    }

    private static PiNode createAnchoredReceiver(StructuredGraph graph, GuardingNode anchor, ValueNode receiver, Stamp stamp) {
        // to avoid that floating reads on receiver fields float above the type check
        return graph.unique(new PiNode(receiver, stamp, (ValueNode) anchor));
    }

    /**
     * @return null iff the check succeeds, otherwise a (non-null) descriptive message.
     */
    public static String checkInvokeConditions(Invoke invoke) {
        if (invoke.predecessor() == null || !invoke.asNode().isAlive()) {
            return "the invoke is dead code";
        }
        if (!(invoke.callTarget() instanceof MethodCallTargetNode)) {
            return "the invoke has already been lowered, or has been created as a low-level node";
        }
        MethodCallTargetNode callTarget = (MethodCallTargetNode) invoke.callTarget();
        if (callTarget.targetMethod() == null) {
            return "target method is null";
        }
        assert invoke.stateAfter() != null : invoke;
        if (!invoke.useForInlining()) {
            return "the invoke is marked to be not used for inlining";
        }
        ValueNode receiver = callTarget.receiver();
        if (receiver != null && receiver.isConstant() && receiver.isNullConstant()) {
            return "receiver is null";
        }
        return null;
    }

    /**
     * Performs an actual inlining, thereby replacing the given invoke with the given
     * {@code inlineGraph}.
     *
     * @param invoke the invoke that will be replaced
     * @param inlineGraph the graph that the invoke will be replaced with
     * @param receiverNullCheck true if a null check needs to be generated for non-static inlinings,
     *            false if no such check is required
     * @param inlineeMethod the actual method being inlined. Maybe be null for snippets.
     */
    @SuppressWarnings("try")
    public static UnmodifiableEconomicMap<Node, Node> inline(Invoke invoke, StructuredGraph inlineGraph, boolean receiverNullCheck, ResolvedJavaMethod inlineeMethod) {
        try {
            return inline(invoke, inlineGraph, receiverNullCheck, inlineeMethod, "reason not specified", "phase not specified");
        } catch (GraalError ex) {
            ex.addContext("inlining into", invoke.asNode().graph().method());
            ex.addContext("inlinee", inlineGraph.method());
            throw ex;
        }
    }

    public static UnmodifiableEconomicMap<Node, Node> inline(Invoke invoke, StructuredGraph inlineGraph, boolean receiverNullCheck, ResolvedJavaMethod inlineeMethod, String reason, String phase) {
        return inline(invoke, inlineGraph, receiverNullCheck, inlineeMethod, reason, phase, NoReturnAction);
    }

    /**
     * Performs an actual inlining, thereby replacing the given invoke with the given
     * {@code inlineGraph}.
     *
     * @param invoke the invoke that will be replaced
     * @param inlineGraph the graph that the invoke will be replaced with
     * @param receiverNullCheck true if a null check needs to be generated for non-static inlinings,
     *            false if no such check is required
     * @param inlineeMethod the actual method being inlined. Maybe be null for snippets.
     * @param reason the reason for inlining, used in tracing
     * @param phase the phase that invoked inlining
     */
    @SuppressWarnings("try")
    public static UnmodifiableEconomicMap<Node, Node> inline(Invoke invoke, StructuredGraph inlineGraph, boolean receiverNullCheck, ResolvedJavaMethod inlineeMethod, String reason, String phase,
                    InlineeReturnAction returnAction) {
        FixedNode invokeNode = invoke.asFixedNode();
        StructuredGraph graph = invokeNode.graph();
        final NodeInputList<ValueNode> parameters = invoke.callTarget().arguments();

        assert inlineGraph.getGuardsStage().ordinal() >= graph.getGuardsStage().ordinal();
        assert invokeNode.graph().isBeforeStage(StageFlag.FLOATING_READS) : "inline isn't handled correctly after floating reads phase";

        if (receiverNullCheck && !((MethodCallTargetNode) invoke.callTarget()).isStatic()) {
            nonNullReceiver(invoke);
        }

        ArrayList<Node> nodes = new ArrayList<>(inlineGraph.getNodes().count());
        ArrayList<ReturnNode> returnNodes = new ArrayList<>(4);
        ArrayList<Invoke> partialIntrinsicExits = new ArrayList<>();
        UnwindNode unwindNode = null;
        final StartNode entryPointNode = inlineGraph.start();
        FixedNode firstCFGNode = entryPointNode.next();
        if (firstCFGNode == null) {
            throw new IllegalStateException("Inlined graph is in invalid state: " + inlineGraph);
        }
        for (Node node : inlineGraph.getNodes()) {
            if (node == entryPointNode || (node == entryPointNode.stateAfter() && node.hasExactlyOneUsage()) || node instanceof ParameterNode) {
                // Do nothing.
            } else {
                nodes.add(node);
                if (node instanceof ReturnNode) {
                    returnNodes.add((ReturnNode) node);
                } else if (node instanceof Invoke) {
                    Invoke invokeInInlineGraph = (Invoke) node;
                    if (invokeInInlineGraph.bci() == BytecodeFrame.UNKNOWN_BCI) {
                        ResolvedJavaMethod target1 = inlineeMethod;
                        ResolvedJavaMethod target2 = invokeInInlineGraph.callTarget().targetMethod();
                        assert target1.equals(target2) : String.format("invoke in inlined method expected to be partial intrinsic exit (i.e., call to %s), not a call to %s",
                                        target1.format("%H.%n(%p)"), target2.format("%H.%n(%p)"));
                        partialIntrinsicExits.add(invokeInInlineGraph);
                    }
                } else if (node instanceof UnwindNode) {
                    assert unwindNode == null;
                    unwindNode = (UnwindNode) node;
                }
            }
        }

        final AbstractBeginNode prevBegin = AbstractBeginNode.prevBegin(invokeNode);
        DuplicationReplacement localReplacement = new DuplicationReplacement() {

            @Override
            public Node replacement(Node node) {
                if (node instanceof ParameterNode) {
                    return parameters.get(((ParameterNode) node).index());
                } else if (node == entryPointNode) {
                    return prevBegin;
                }
                return node;
            }
        };

        assert invokeNode.successors().first() != null : invoke;
        assert invokeNode.predecessor() != null;

        Mark mark = graph.getMark();
        // Instead, attach the inlining log of the child graph to the current inlining log.
        EconomicMap<Node, Node> duplicates;
        InliningLog inliningLog = graph.getInliningLog();
        try (InliningLog.UpdateScope scope = InliningLog.openDefaultUpdateScope(inliningLog)) {
            duplicates = graph.addDuplicates(nodes, inlineGraph, inlineGraph.getNodeCount(), localReplacement);
            graph.notifyInliningDecision(invoke, true, phase, duplicates, inlineGraph.getInliningLog(), inlineGraph.getOptimizationLog(), inlineeMethod, reason);
        }

        FrameState stateAfter = invoke.stateAfter();
        assert stateAfter == null || stateAfter.isAlive();

        FrameState stateAtExceptionEdge = null;
        if (invoke instanceof InvokeWithExceptionNode) {
            InvokeWithExceptionNode invokeWithException = ((InvokeWithExceptionNode) invoke);
            if (unwindNode != null) {
                ExceptionObjectNode obj = (ExceptionObjectNode) invokeWithException.exceptionEdge();
                stateAtExceptionEdge = obj.stateAfter();
            }
        }

        updateSourcePositions(invoke, inlineGraph, duplicates, !Objects.equals(inlineGraph.method(), inlineeMethod), mark);
        if (stateAfter != null) {
            processFrameStates(invoke, inlineGraph, duplicates, stateAtExceptionEdge, returnNodes.size() > 1);
            int callerLockDepth = stateAfter.nestedLockDepth();
            if (callerLockDepth != 0) {
                for (MonitorIdNode original : inlineGraph.getNodes(MonitorIdNode.TYPE)) {
                    MonitorIdNode monitor = (MonitorIdNode) duplicates.get(original);
                    processMonitorId(invoke.stateAfter(), monitor);
                }
            }
        } else {
            assert checkContainsOnlyInvalidOrAfterFrameState(duplicates);
        }

        firstCFGNode = (FixedNode) duplicates.get(firstCFGNode);
        for (int i = 0; i < returnNodes.size(); i++) {
            returnNodes.set(i, (ReturnNode) duplicates.get(returnNodes.get(i)));
        }
        for (Invoke exit : partialIntrinsicExits) {
            // A partial intrinsic exit must be replaced with a call to
            // the intrinsified method.
            Invoke dup = (Invoke) duplicates.get(exit.asNode());
            dup.setBci(invoke.bci());
        }
        if (unwindNode != null) {
            unwindNode = (UnwindNode) duplicates.get(unwindNode);
        }

        finishInlining(invoke, graph, firstCFGNode, returnNodes, unwindNode, inlineGraph, returnAction);
        GraphUtil.killCFG(invokeNode);

        return duplicates;
    }

    /**
     * Inline {@code inlineGraph} into the current replacing the node {@code Invoke} and return the
     * set of nodes which should be canonicalized. The set should only contain nodes which modified
     * by the inlining since the current graph and {@code inlineGraph} are expected to already be
     * canonical.
     *
     * @param invoke
     * @param inlineGraph
     * @param receiverNullCheck
     * @param inlineeMethod
     * @return the set of nodes to canonicalize
     */
    @SuppressWarnings("try")
    public static EconomicSet<Node> inlineForCanonicalization(Invoke invoke, StructuredGraph inlineGraph, boolean receiverNullCheck, ResolvedJavaMethod inlineeMethod, String reason, String phase) {
        return inlineForCanonicalization(invoke, inlineGraph, receiverNullCheck, inlineeMethod, null, reason, phase);
    }

    public static EconomicSet<Node> inlineForCanonicalization(Invoke invoke, StructuredGraph inlineGraph, boolean receiverNullCheck, ResolvedJavaMethod inlineeMethod,
                    Consumer<UnmodifiableEconomicMap<Node, Node>> duplicatesConsumer, Strin