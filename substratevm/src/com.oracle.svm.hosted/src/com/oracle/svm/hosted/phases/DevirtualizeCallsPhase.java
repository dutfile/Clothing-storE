/*
 * Copyright (c) 2017, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted.phases;

import static com.oracle.svm.core.graal.snippets.DeoptHostedSnippets.AnalysisSpeculation;
import static com.oracle.svm.core.graal.snippets.DeoptHostedSnippets.AnalysisSpeculationReason;

import org.graalvm.compiler.core.common.type.Stamp;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.core.common.type.TypeReference;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.nodes.CallTargetNode.InvokeKind;
import org.graalvm.compiler.nodes.FixedGuardNode;
import org.graalvm.compiler.nodes.Invoke;
import org.graalvm.compiler.nodes.LogicConstantNode;
import org.graalvm.compiler.nodes.PiNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.extended.ValueAnchorNode;
import org.graalvm.compiler.phases.Phase;
import org.graalvm.compiler.phases.common.inlining.InliningUtil;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.nodes.SubstrateMethodCallTargetNode;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.util.ImageBuildStatistics;

import jdk.vm.ci.meta.DeoptimizationAction;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.JavaMethodProfile;

/**
 * Devirtualize invokes based on Static Analysis results.
 * <p>
 * Specifically, if the Static Analysis determined that:
 * <ul>
 * <li>Invoke has no callees, it gets removed.
 * <li>Indirect invoke has a single callee, it gets converted to a special invoke.
 * </ul>
 */
public class DevirtualizeCallsPhase extends Phase {

    @Override
    protected void run(StructuredGraph graph) {
        for (Invoke invoke : graph.getInvokes()) {
            if (invoke.callTarget() instanceof SubstrateMethodCallTargetNode) {
                SubstrateMethodCallTargetNode callTarget = (SubstrateMethodCallTargetNode) invoke.callTarget();

                if (callTarget.invokeKind().isDirect() && !((HostedMethod) callTarget.targetMethod()).getWrapped().isSimplyImplementationInvoked()) {
                    /*
                     * This is a direct call to a method that the static analysis did not see as
                     * invoked. This can happen when the receiver is always null. In most cases, the
                     * method profile also has a length of 0 and the below code to kill the invoke
                     * would trigger. But not all methods have profiles, for example methods with
                     * manually constructed graphs.
                     */
                    unreachableInvoke(graph, invoke, callTarget);