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
package org.graalvm.compiler.truffle.test;

import org.graalvm.compiler.truffle.options.PolyglotCompilerOptions;
import org.graalvm.compiler.truffle.runtime.OptimizedCallTarget;
import org.graalvm.compiler.truffle.runtime.OptimizedDirectCallNode;
import org.graalvm.polyglot.Context;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.test.polyglot.ProxyLanguage;

public class NodeSplittingStrategyTest extends AbstractSplittingStrategyTest {

    @Before
    public void boostBudget() {
        createDummyTargetsToBoostGrowingSplitLimit();
    }

    @NodeChild
    abstract static class TurnsPolymorphicOnZeroNode extends SplittingTestNode {
        @Specialization(guards = "value != 0")
        static int do1(int value) {
            return value;
        }

        @Specialization
        static int do2(int value) {
            return value;
        }

        @Fallback
        static int do3(@SuppressWarnings("unused") VirtualFrame frame, @SuppressWarnings("unused") Object value) {
            return 0;
        }
    }

    @NodeChild
    abstract static class TurnsPolymorphicOnZeroButSpecializationIsExcludedNode extends SplittingTestNode {
        @Specialization(guards = "value != 0")
        int do1(int value) {
            return value;
        }

        @ReportPolymorphism.Exclude
        @Specialization
        int do2(int value) {
            return value;
        }

        @Fallback
        int do3(@SuppressWarnings("unused") VirtualFrame frame, @SuppressWarnings("unused") Object value) {
            return 0;
        }
    }

    @NodeChild
    @ReportPolymorphism.Exclude
    abstract static class TurnsPolymorphicOnZeroButClassIsExcludedNode extends SplittingTestNode {
        @Specialization(guards = "value != 0")
        int do1(int value) {
            return value;
        }

        @Specialization
        int do2(int value) {
            return value;
        }

        @Fallback
        int do3(@SuppressWarnings("unused") VirtualFrame frame, @SuppressWarnings("unused") Object value) {
            return 0;
        }
    }

    @NodeChild
    @ReportPolymorphism
    abstract static class HasInlineCacheNode extends SplittingTestNode {

        @Specialization(limit = "2", //
                        guards = "target.getRootNode() == cachedNode")
        protected static Object doDirect(RootCallTarget target, @Cached("target.getRootNode()") @SuppressWarnings("unused") RootNode cachedNode) {
            return target.call(noArguments);
        }

        @Specialization(replaces = "doDirect")
        protected static Object doIndirect(RootCallTarget target) {
            return target.call(noArguments);
        }
    }

    static class TwoDummiesAndAnotherNode extends SplittingTestNode {
        int counter;
        RootCallTarget dummy = new DummyRootNode().getCallTarget();

        @Override
        public Object execute(VirtualFrame frame) {
            if (counter < 2) {
                counter++;
            } else {
                counter = 0;
                dummy = new DummyRootNode().getCallTarget();
            }
            return dummy;
        }
    }

    @Test
    public void testSplitsDirectCalls() {
        OptimizedCallTarget callTarget = (OptimizedCallTarget) new SplittingTestRootNode(NodeSplittingStrategyTestFactory.HasInlineCacheNodeGen.create(new ReturnsFirstArgumentNode())).getCallTarget();
        Object[] first = new Object[]{new DummyRootNode().getCallTarget()};
        Object[] second = new Object[]{new DummyRootNode().getCallTarget()};
        testSplitsDirectCallsHelper(callTarget, first, second);

        callTarget = (OptimizedCallTarget) new SplittingTestRootNode(NodeSplittingStrategyTestFactory.TurnsPolymorphicOnZeroNodeGen.create(new ReturnsFirstArgumentNode())).getCallTarget();
        // two callers for a target are needed
        testSplitsDirectCallsHelper(callTarget, new Object[]{1}, new Object[]{0});
    }

    @Test
    public void testDoesNotSplitsDirectCalls() {
        OptimizedCallTarget callTarget = (OptimizedCallTarget) new SplittingTestRootNode(
                        NodeSplittingStrategyTestFactory.TurnsPolymorphicOnZeroButClassIsExcludedNodeGen.create(new ReturnsFirstArgumentNode())).getCallTarget();
        testDoesNotSplitDirectCallHelper(callTarget, new Object[]{1}, new Object[]{0});

        callTarget = (OptimizedCallTarget) new SplittingTestRootNode(
                        NodeSplittingStrategyTestFactory.TurnsPolymorphicOnZeroButSpecializationIsExcludedNodeGen.create(new ReturnsFirstArgumentNode())).getCallTarget();
        testDoesNotSplitDirectCallHelper(callTarget, new Object[]{1}, new Object[]{0});
    }

    class CallsInnerNode extends SplittableRootNode {

        private final RootCallTarget toCall;
        @Child private OptimizedDirectCallNode callNode;

        CallsInnerNode(RootCallTarget toCall) {
            this.toCall = toCall;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            /*
             * We lazily initialize the direct call node as this is the case typically for inline
             * caches in languages.
             */
            if (callNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                callNode = insert((OptimizedDirectCallNode) runtime.createDirectCallNode(toCall));
            }
            return callNode.call(frame.getArguments());
        }
    }

    @Test
    public void testSplitPropagatesThrongSoleCallers() {
        OptimizedCallTarget turnsPolymorphic = (OptimizedCallTarget) new SplittingTestRootNode(
                        NodeSplittingStrategyTestFactory.TurnsPolymorphicOnZeroNodeGen.create(new ReturnsFirstArgumentNode())).getCallTarget();
        testPropagatesThroughSoleCallers(turnsPolymorphic, new Object[]{1}, new Object[]{0});
        turnsPolymorphic = (OptimizedCallTarget) new SplittingTestRootNode(NodeSplittingStrategyTestFactory.HasInlineCacheNodeGen.create(new ReturnsFirstArgumentNode())).getCallTarget();
        Object[] first = new Object[]{new DummyRootNode().getCallTarget()};
        Object[] second = new Object[]{new DummyRootNode().getCallTarget()};
        testPropagatesThroughSoleCallers(turnsPolymorphic, first, second);
    }

    private void testPropagatesThroughSoleCallers(OptimizedCallTarget turnsPolymorphic, Object[] firstArgs, Object[] secondArgs) {
        final OptimizedCallTarget callsInner = (OptimizedCallTarget) new CallsInnerNode(turnsPolymorphic).getCallTarget();
        final OptimizedCallTarget callsCallsInner = (OptimizedCallTarget) new CallsInnerNode(callsInner).getCallTarget();
        // two callers for a target are needed
        runtime.createDirectCallNode(callsCallsInner);
        final DirectCallNode directCallNode = runtime.createDirectCallNode(callsCallsInner);
        directCallNode.call(firstArgs);
        Assert.assertFalse("Target needs split before the node went polymorphic", getNeedsSplit(callsCallsInner));
        Assert.assertFalse("Target needs split before the node went polymorphic", getNeedsSplit(callsInner));
        Assert.assertFalse("Target needs split before the node went polymorphic", getNeedsSplit(turnsPolymorphic));
        directCallNode.call(firstArgs);
        Assert.assertFalse("Target needs split before the node went polymorphic", getNeedsSplit(callsCallsInner));
        Assert.assertFalse("Target needs split before the node went polymorphic", getNeedsSplit(callsInner));
        Assert.assertFalse("Target needs split before the node went polymorphic", 