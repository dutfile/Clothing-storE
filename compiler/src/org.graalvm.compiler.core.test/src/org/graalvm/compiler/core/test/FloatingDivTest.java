/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.core.test;

import java.util.ListIterator;

import org.graalvm.compiler.api.directives.GraalDirectives;
import org.graalvm.compiler.core.common.GraalOptions;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.StructuredGraph.AllowAssumptions;
import org.graalvm.compiler.nodes.calc.FloatingIntegerDivRemNode;
import org.graalvm.compiler.nodes.calc.IntegerDivRemNode;
import org.graalvm.compiler.nodes.calc.IntegerEqualsNode;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.phases.BasePhase;
import org.graalvm.compiler.phases.PhaseSuite;
import org.graalvm.compiler.phases.common.LoweringPhase;
import org.graalvm.compiler.phases.tiers.HighTierContext;
import org.graalvm.compiler.phases.tiers.Suites;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class FloatingDivTest extends GraalCompilerTest {

    private void checkHighTierGraph(String snippet, int fixedDivsBeforeLowering, int floatingDivsBeforeLowering, int fixedDivAfterLowering, int floatingDivAfterLowering) {
        StructuredGraph graph = parseEager(snippet, AllowAssumptions.YES);
        Suites suites = super.createSuites(new OptionValues(getInitialOptions(), GraalOptions.LoopPeeling, false, GraalOptions.EarlyGVN, false));
        PhaseSuite<HighTierContext> ht = suites.getHighTier().copy();
        ListIterator<BasePhase<? super HighTierContext>> position = ht.findPhase(LoweringPhase.class);
        position.previous();
        position.add(new TestBasePhase<HighTierContext>() {

            @Override
            protected void run(@SuppressWarnings("hiding") StructuredGraph graph, HighTierContext context) {
                Assert.assertEquals(fixedDivsBeforeLowering, graph.getNodes().filter(IntegerDivRemNode.class).count());
                Assert.assertEquals(floatingDivsBeforeLowering, graph.getNodes().filter(FloatingIntegerDivRemNode.class).count());
            }
        });
        ht.apply(graph, getDefaultHighTierContext());

        Assert.assertEquals(fixedDivAfterLowering, graph.getNodes().filter(IntegerDivRemNode.class).count());
        Assert.assertEquals(floatingDivAfterLowering, graph.getNodes().filter(FloatingIntegerDivRemNode.class).count());
    }

    private void checkFinalGraph(String snippet, int fixedDivs, int floatingDivs, int zeroChecks) {
        if (!isArchitecture("AMD64")) {
            /*
             * We only try to fold divs and their guards back together if the architecture supports
             * it, i.e., amd64 at the moment.
             */
            return;
        }
        StructuredGraph graph = parseEager(snippet, AllowAssumptions.YES);
        Suites suites = super.createSuites(new OptionValues(getInitialOptions(), GraalOptions.LoopPeeling, false, GraalOptions.EarlyGVN, false));

        suites.getHighTier().apply(graph, getDefaultHighTierContext());
        suites.getMidTier().apply(graph, getDefaultMidTierConte