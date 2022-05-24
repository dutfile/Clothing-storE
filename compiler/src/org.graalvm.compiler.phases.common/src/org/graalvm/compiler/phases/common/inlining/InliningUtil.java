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
        logInlining(d