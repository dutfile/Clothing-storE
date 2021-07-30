/*
 * Copyright (c) 2012, 2021, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.hotspot.replacements;

import static jdk.vm.ci.meta.DeoptimizationAction.InvalidateReprofile;
import static jdk.vm.ci.meta.DeoptimizationReason.OptimizedTypeCheckViolated;
import static org.graalvm.compiler.hotspot.replacements.HotSpotReplacementsUtil.PRIMARY_SUPERS_LOCATION;
import static org.graalvm.compiler.hotspot.replacements.HotSpotReplacementsUtil.SECONDARY_SUPER_CACHE_LOCATION;
import static org.graalvm.compiler.hotspot.replacements.HotSpotReplacementsUtil.loadHubIntrinsic;
import static org.graalvm.compiler.hotspot.replacements.HotSpotReplacementsUtil.loadHubOrNullIntrinsic;
import static org.graalvm.compiler.hotspot.replacements.HotspotSnippetsOptions.TypeCheckMaxHints;
import static org.graalvm.compiler.hotspot.replacements.HotspotSnippetsOptions.TypeCheckMinProfileHitProbability;
import static org.graalvm.compiler.hotspot.replacements.TypeCheckSnippetUtils.checkSecondarySubType;
import static org.graalvm.compiler.hotspot.replacements.TypeCheckSnippetUtils.checkUnknownSubType;
import static org.graalvm.compiler.hotspot.replacements.TypeCheckSnippetUtils.createHints;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.FAST_PATH_PROBABILITY;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.LIKELY_PROBABILITY;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.NOT_FREQUENT_PROBABILITY;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.NOT_LIKELY_PROBABILITY;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.probability;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.unknownProbability;

import org.graalvm.compiler.api.replacements.Snippet;
import org.graalvm.compiler.api.replacements.Snippet.ConstantParameter;
import org.graalvm.compiler.api.replacements.Snippet.NonNullParameter;
import org.graalvm.compiler.api.replacements.Snippet.VarargsParameter;
import org.graalvm.compiler.core.common.type.ObjectStamp;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.hotspot.meta.HotSpotProviders;
import org.graalvm.compiler.hotspot.nodes.type.KlassPointerStamp;
import org.graalvm.compiler.hotspot.replacements.TypeCheckSnippetUtils.Counters;
import org.graalvm.compiler.hotspot.replacements.TypeCheckSnippetUtils.Hints;
import org.graalvm.compiler.hotspot.word.KlassPointer;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.DeoptimizeNode;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.PiNode;
import org.graalvm.compiler.nodes.SnippetAnchorNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.TypeCheckHints;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.extended.GuardingNode;
import org.graalvm.compiler.nodes.java.ClassIsAssignableFromNode;
import org.graalvm.compiler.nodes.java.InstanceOfDynamicNode;
import org.graalvm.compiler.nodes.java.InstanceOfNode;
import org.graalvm.compiler.nodes.spi.LoweringTool;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.replacements.InstanceOfSnippetsTemplates;
import org.graalvm.compiler.replacements.SnippetCounter;
import org.graalvm.compiler.replacements.SnippetTemplate.Arguments;
import org.graalvm.compiler.replacements.SnippetTemplate.SnippetInfo;
import org.graalvm.compiler.replacements.Snippets;
import org.graalvm.compiler.replacements.nodes.ExplodeLoopNode;

import jdk.vm.ci.hotspot.HotSpotResolvedObjectType;
import jdk.vm.ci.meta.Assumptions;
import jdk.vm.ci.meta.DeoptimizationAction;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaTypeProfile;
import jdk.vm.ci.meta.TriState;

/**
 * Snippets used for implementing the type test of an instanceof instruction. Since instanceof is a
 * floating node, it is lowered separately for each of its usages.
 *
 * The type tests implemented are described in the paper
 * <a href="http://dl.acm.org/citation.cfm?id=583821"> Fast subtype checking in the HotSpot JVM</a>
 * by Cliff Click and John Rose.
 */
public class InstanceOfSnippets implements Snippets {

    /**
     * A test against a set of hints derived from a profile with 100% precise coverage of seen
     * types. This snippet deoptimizes on hint miss paths.
     */
    @Snippet
    public static Object instanceofWithProfile(Object object, @VarargsParameter KlassPointer[] hints, @VarargsParameter boolean[] hintIsPositive, Object trueValue, Object falseValue,
                    @ConstantParameter boolean nullSeen, @ConstantParameter Counters counters) {
        if (probability(NOT_FREQUENT_PROBABILITY, object == null)) {
            counters.isNull.inc();
            if (!nullSeen) {
                // See comment below for other deoptimization path; the
                // same reasoning applies here.
                DeoptimizeNode.deopt(InvalidateReprofile, OptimizedTypeCheckViolated);
            }
            return falseValue;
        }
        GuardingNode anchorNode = SnippetAnchorNode.anchor();
        KlassPointer objectHub = loadHubIntrinsic(PiNode.piCastNonNull(object, anchorNode));
        // if we get an exact match: succeed immediately
        ExplodeLoopNode.explodeLoop();
        for (int i = 0; i < hints.length; i++) {
            KlassPointer hintHub = hints[i];
            boolean positive = hintIsPositive[i];
            if (probability(LIKELY_PROBABILITY, hintHub.equal(objectHub))) {
                counters.hintsHit.inc();
                return unknownProbability(positive) ? trueValue : falseValue;
            }
            counters.hintsMiss.inc();
        }
        // This maybe just be a rare event but it might also indicate a phase change
        // in the application. Ideally we want to use DeoptimizationAction.None for
        // the former but the cost is too high if indeed it is the latter. As such,
        // we defensively opt for InvalidateReprofile.
        DeoptimizeNode.deopt(DeoptimizationAction.InvalidateReprofile, OptimizedTypeCheckViolated);
        return falseValue;
    }

    /**
     * A test against a final type.
     */
    @Snippet
    public static Object instanceofExact(Object object, KlassPointer exactHub, Object trueValue,