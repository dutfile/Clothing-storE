/*
 * Copyright (c) 2013, 2021, Oracle and/or its affiliates. All rights reserved.
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

import org.graalvm.compiler.core.common.GraalBailoutException;
import org.graalvm.compiler.core.common.GraalOptions;
import org.graalvm.compiler.core.common.PermanentBailoutException;
import org.graalvm.compiler.debug.TTY;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.replacements.PEGraphDecoder;
import org.graalvm.compiler.truffle.common.TruffleCompilerRuntime;
import org.graalvm.compiler.truffle.runtime.OptimizedCallTarget;
import org.graalvm.compiler.truffle.test.nodes.AbstractTestNode;
import org.graalvm.compiler.truffle.test.nodes.AddTestNode;
import org.graalvm.compiler.truffle.test.nodes.BlockTestNode;
import org.graalvm.compiler.truffle.test.nodes.ConstantTestNode;
import org.graalvm.compiler.truffle.test.nodes.ExplodeLoopUntilReturnNode;
import org.graalvm.compiler.truffle.test.nodes.ExplodeLoopUntilReturnWithThrowNode;
import org.graalvm.compiler.truffle.test.nodes.InliningNullCheckNode1;
import org.graalvm.compiler.truffle.test.nodes.InliningNullCheckNode2;
import org.graalvm.compiler.truffle.test.nodes.LambdaTestNode;
import org.graalvm.compiler.truffle.test.nodes.LoadLocalTestNode;
import org.graalvm.compiler.truffle.test.nodes.LoopTestNode;
import org.graalvm.compiler.truffle.test.nodes.NeverPartOfCompilationTestNode;
import org.graalvm.compiler.truffle.test.nodes.ObjectEqualsNode;
import org.graalvm.compiler.truffle.test.nodes.ObjectHashCodeNode;
import org.graalvm.compiler.truffle.test.nodes.PartialIntrinsicNode;
import org.graalvm.compiler.truffle.test.nodes.RecursionTestNode;
import org.graalvm.compiler.truffle.test.nodes.RootTestNode;
import org.graalvm.compiler.truffle.test.nodes.StoreLocalTestNode;
import org.graalvm.compiler.truffle.test.nodes.StringEqualsNode;
import org.graalvm.compiler.truffle.test.nodes.StringHashCodeFinalNode;
import org.graalvm.compiler.truffle.test.nodes.StringHashCodeNonFinalNode;
import org.graalvm.compiler.truffle.test.nodes.SynchronizedExceptionMergeNode;
import org.graalvm.compiler.truffle.test.nodes.UnrollLoopUntilReturnNode;
import org.graalvm.compiler.truffle.test.nodes.UnrollLoopUntilReturnWithThrowNode;
import org.graalvm.compiler.truffle.test.nodes.explosion.LoopExplosionPhiNode;
import org.graalvm.compiler.truffle.test.nodes.explosion.NestedExplodedLoopTestNode;
import org.graalvm.compiler.truffle.test.nodes.explosion.TwoMergesExplodedLoopTestNode;
import org.graalvm.compiler.truffle.test.nodes.explosion.UnrollingTestNode;
import org.graalvm.polyglot.Context;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.impl.FrameWithoutBoxing;
import com.oracle.truffle.api.nodes.RootNode;

import jdk.vm.ci.code.BailoutException;

public class SimplePartialEvaluationTest extends PartialEvaluationTest {

    @Before
    public void setup() {
        setupContext(Context.newBuilder().allowExperimentalOptions(true).option("engine.CompileImmediately", "false").build());
    }

    public static Object constant42() {
        return 42;
    }

    @Test
    public void constantValue() {
        FrameDescriptor fd = new FrameDescriptor();
        AbstractTestNode result = new ConstantTestNode(42);
        assertPartialEvalEquals(SimplePartialEvaluationTest::constant42, new RootTestNode(fd, "constantValue", result));
    }

    @Test
    public void addConstants() {
        FrameDescriptor fd = new FrameDescriptor();
        AbstractTestNode result = new AddTestNode(new ConstantTestNode(40), new ConstantTestNode(2));
        assertPartialEvalEquals(SimplePartialEvaluationTest::constant42, new RootTestNode(fd, "addConstants", result));
    }

    @Test
    @SuppressWarnings("try")
    public void neverPartOfCompilationTest() {
        FrameDescriptor fd = new FrameDescriptor();
        AbstractTestNode firstTree = new NeverPartOfCompilationTestNode(new ConstantTestNode(1), 2);
        assertPartialEvalEquals(SimplePartialEvaluationTest::constant42, new RootTestNode(fd, "neverPartOfCompilationTest", firstTree));

        AbstractTestNode secondTree = new NeverPartOfCompilationTestNode(new ConstantTestNode(1), 1);
        try (PreventDumping noDump = new PreventDumping()) {
            assertPartialEvalEquals(SimplePartialEvaluationTest::constant42, new RootTestNode(fd, "neverPartOfCompilationTest", secondTree));
            Assert.fail("Expected verification error!");
        } catch (GraalBailoutException t) {
            // Expected verification error occurred.
            StackTraceElement[] trace = t.getStackTrace();
            if (getTruffleCompiler(null).getPartialEvaluator().getConfig().trackNodeSourcePosition() || GraalOptions.TrackNodeSourcePosition.getValue(getInitialOptions())) {
                assertStack(trace[0], "com.oracle.truffle.api.CompilerAsserts", "neverPartOfCompilation", "CompilerAsserts.java");
                assertStack(trace[1], "org.graalvm.compiler.truffle.test.nodes.NeverPartOfCompilationTestNode", "execute", "NeverPartOfCompilationTestNode.java");
                assertStack(trace[2], "org.graalvm.compiler.truffle.test.nodes.RootTestNode", "execute", "RootTestNode.java");
            } else {
                assertStack(trace[0], "org.graalvm.compiler.truffle.test.nodes.NeverPartOfCompilationTestNode", "execute", "NeverPartOfCompilationTestNode.java");
                assertStack(trace[1], "org.graalvm.compiler.truffle.test.nodes.RootTestNode", "execute", "RootTestNode.java");
            }
        }
    }

    private static void assertStack(StackTraceElement stack, String className, String methodName, String fileName) {
        Assert.assertEquals(className, stack.getClassName());
        Assert.assertEquals(methodName, stack.getMethodName());
        Assert.assertEquals(fileName, stack.getFileName());
    }

    @Test
    public void nestedLoopExplosion() {
        FrameDescriptor fd = new FrameDescriptor();
        AbstractTestNode result = new AddTestNode(new NestedExplodedLoopTestNode(5), new ConstantTestNode(17));
        assertPartialEvalEquals(SimplePartialEvaluationTest::constant42, new RootTestNode(fd, "nestedLoopExplosion", result));
    }

    @Test
    public void twoMergesLoopExplosion() {
        FrameDescriptor fd = new FrameDescriptor();
        AbstractTestNode result = new AddTestNode(new TwoMergesExplodedLoopTestNode(5), new ConstantTestNode(37));
        assertPartialEvalEquals(SimplePartialEvaluationTest::constant42, new RootTestNode(fd, "twoMergesLoopExplosion", result));
    }

    @Test
    public void sequenceConstants() {
        FrameDescriptor fd = new FrameDescriptor();
        AbstractTestNode result = new BlockTestNode(new AbstractTestNode[]{new ConstantTestNode(40), new ConstantTestNode(42)});
        assertPartialEvalEquals(SimplePartialEvaluationTest::constant42, new RootTestNode(fd, "sequenceConstants", result));
    }

    @Test
    public void localVariable() {
        var builder = FrameDescriptor.newBuilder();
        int x = builder.addSlot(FrameSlotKind.Int, "x", null);
        FrameDescriptor fd = builder.build();

        AbstractTestNode result = new BlockTestNode(new AbstractTestNode[]{new StoreLocalTestNode(x, new ConstantTestNode(42)), new LoadLocalTestNode(x)});
        assertPartialEvalEquals(SimplePartialEvaluationTest::constant42, new RootTestNode(fd, "localVariable", result));
    }

    @Test
    public void unrollUntilReturnNoLoop() {
        FrameDescriptor fd = new FrameDescriptor();
        UnrollingTestNode t = new UnrollingTestNode(5);
        AbstractTestNode result = new AddTestNode(t.new FullUnrollUntilReturnNoLoop(), new ConstantTestNode(37));
        assertPartialEvalEquals(SimplePartialEvaluationTest::constant42, new RootTestNode(fd, "unrollUntilReturnNoLoop", result));
    }

    @Test
    public void unrollSimple() {
        FrameDescriptor fd = new FrameDescriptor();
        final int loopIterations = 5;
        UnrollingTestNode t = new UnrollingTestNode(loopIterations);
        AbstractTestNode result = new AddTestNode(t.new UnrollOnlyExample(), new ConstantTestNode(37));
        compileHelper("Test", new RootTestNode(fd, "simpleUnroll", result), new Object[]{});
        StructuredGraph peResult = lastCompiledGraph;

        Assert.assertEquals(UnrollingTestNode.INSIDE_LOOP_MARKER, loopIterations, UnrollingTestNode.countBlackholeNodes(peResult, UnrollingTestNode.INSIDE_LOOP_MARKER));
        Assert.assertEquals(UnrollingTestNode.AFTER_LOOP_MARKER, 1, UnrollingTestNode.countBlackholeNodes(peResult, UnrollingTestNode.AFTER_LOOP_MARKER));
    }

    @Test
    public void explodeSimple() {
        FrameDescriptor fd = new FrameDescriptor();
        final int loopIterations = 5;
        UnrollingTestNode t = new UnrollingTestNode(loopIterations);
        AbstractTestNode result = new AddTestNode(t.new ExplodeAlongLoopEndExample(), new ConstantTestNode(37));
        compileHelper("Test", new RootTestNode(fd, "simpleUnroll", result), new Object[]{});
        StructuredGraph peResult = lastCompiledGraph;

        Assert.assertEquals(UnrollingTestNode.INSIDE_LOOP_MARKER, 31, UnrollingTestNode.countBlackholeNodes(peResult, UnrollingTestNode.INSIDE_LOOP_MARKER));
        Assert.assertEquals(UnrollingTestNode.AFTER_LOOP_MARKER, 1, UnrollingTestNode.countBlackholeNodes(peResult, UnrollingTestNode.AFTER_LOOP_MARKER));
    }

    @Test
    public void unrollUntilReturnConsecutiveLoops() {
        FrameDescriptor fd = new FrameDescriptor();
        final int loopIterations = 5;
        UnrollingTestNode t = new UnrollingTestNode(loopIterations);
        AbstractTestNode result = new AddTestNode(t.new FullUnrollUntilReturnConsecutiveLoops(), new ConstantTestNode(37));
        compileHelper("Test", new RootTestNode(fd, "consecutiveLoops", result), new Object[]{});
        StructuredGraph peResult = lastCompiledGraph;

        Assert.assertEquals(UnrollingTestNode.INSIDE_LOOP_MARKER, loopIterations * 2, UnrollingTestNode.countBlackholeNodes(peResult, UnrollingTestNode.INSIDE_LOOP_MARKER));
        Assert.assertEquals(UnrollingTestNode.OUTSIDE_LOOP_MARKER, loopIterations * 2, UnrollingTestNode.countBlackholeNodes(peResult, UnrollingTestNode.OUTSIDE_LOOP_MARKER));
        // +1: the original exit
        Assert.assertEquals(UnrollingTestNode.AFTER_LOOP_MARKER, 1, UnrollingTestNode.countBlackholeNodes(peResult, UnrollingTestNode.AFTER_LOOP_MARKER));
    }

    @Test
    public void unrollUntilReturnNestedLoops() {
        FrameDescriptor fd = new FrameDescriptor();
        final int loopIterations = 2;
        UnrollingTestNode t = new UnrollingTestNode(loopIterations);
        AbstractTestNode result = new AddTestNode(t.new FullUnrollUntilReturnNestedLoops(), new ConstantTestNode(37));
        compileHelper("Test", new RootTestNode(fd, "nestedLoopExplosion", result), new Object[]{});
        StructuredGraph peResult = lastCompiledGraph;

        Assert.assertEquals(UnrollingTestNode.INSIDE_LOOP_MARKER, loopIterations * loopIterations, UnrollingTestNode.countBlackholeNodes(peResult, UnrollingTestNode.INSIDE_LOOP_MARKER));
        Assert.assertEquals(UnrollingTestNode.OUTSIDE_LOOP_MARKER, loopIterations * loopIterations, UnrollingTestNode.countBlackholeNodes(peResult, UnrollingTestNode.OUTSIDE_LOOP_MARKER));
        // +1: the original exit
        Assert.assertEquals(UnrollingTestNode.AFTER_LOOP_MARKER, 1, UnrollingTestNode.countBlackholeNodes(peResult, UnrollingTestNode.AFTER_LOOP_MARKER));
    }

    @Test
    public void unrollUntilReturnNestedLoopsContinueOuter01() {
        FrameDescriptor fd = new FrameDescriptor();
        final int loopIterations = 2;
        UnrollingTestNode t = new UnrollingTestNode(loopIterations);
        AbstractTestNode result = new AddTestNode(t.new FullUnrollUntilReturnNestedLoopsContinueOuter01(), new ConstantTestNode(37), true);
        compileHelper("Test", new RootTestNode(fd, "nestedLoopExplosion", result, true), new Object[]{});
        StructuredGraph peResult = lastCompiledGraph;

        Assert.assertEquals(UnrollingTestNode.INSIDE_LOOP_MARKER, 4, UnrollingTestNode.countBlackholeNodes(peResult, UnrollingTestNode.INSIDE_LOOP_MARKER));
        Assert.assertEquals(UnrollingTestNode.AFTER_LOOP_MARKER, 1/* forced explosion */, UnrollingTestNode.countBlackholeNodes(peResult, UnrollingTestNode.AFTER_LOOP_MARKER));
    }

    @Test
    public void unrollUntilReturnNestedLoopsContinueOuter02() {
        FrameDescriptor fd = new FrameDescriptor();
        final int loopIterations = 2;
        UnrollingTestNode t = new UnrollingTestNode(loopIterations);
        AbstractTestNode result = new AddTestNode(t.new FullUnrollUntilReturnNestedLoopsContinueOuter02(), new ConstantTestNode(37), true);
        compileHelper("Test", new RootTestNode(fd, "nestedLoopExplosion", result, true), new Object[]{});
        StructuredGraph peResult = lastCompiledGraph;

        Assert.assertEquals(UnrollingTestNode.INSIDE_LOOP_MARKER, 8, UnrollingTestNode.countBlackholeNodes(peResult, UnrollingTestNode.INSIDE_LOOP_MARKER));
        Assert.assertEquals(UnrollingTestNode.AFTER_LOOP_MARKER, 1, UnrollingTestNode.countBlackholeNodes(peResult, UnrollingTestNode.AFTER_LOOP_MARKER));
    }

    @Test
    public void unrollUntilReturnNestedLoopsContinueOuter03() {
        FrameDescriptor fd = new FrameDescriptor();
        final int loopIterations = 2;
        UnrollingTestNode t = new UnrollingTestNode(loopIterations);
        AbstractTestNode result = new AddTestNode(t.new FullUnrollUntilReturnNestedLoopsContinueOuter03(), new ConstantTestNode(37), true);
        compileHelper("Test", new RootTestNode(fd, "nestedLoopExplosion", result, true), new Object[]{});
        StructuredGraph peResult = lastCompiledGraph;

        Assert.assertEquals(UnrollingTestNode.INSIDE_LOOP_MARKER, 4, UnrollingTestNode.countBlackholeNodes(peResult, UnrollingTestNode.INSIDE_LOOP_MARKER));
        Assert.assertEquals(UnrollingTestNode.AFTER_LOOP_MARKER, 1, UnrollingTestNode.countBlackholeNodes(peResult, UnrollingTestNode.AFTER_LOOP_MARKER));
    }

    @Test
    public void unrollUntilReturnNestedLoopsContinueOuter04() {
        FrameDescriptor fd = new FrameDescriptor();
        final int loopIterations = 2;
        UnrollingTestNode t = new UnrollingTestNode(loopIterations);
        AbstractTestNode result = new AddTestNode(t.new FullUnrollUntilReturnNestedLoopsContinueOuter04(), new ConstantTestNode(37), true);
        compileHelper("Test", new RootTestNode(fd, "nestedLoopExplosion", result, true), new Object[]{});
        StructuredGraph peResult = lastCompiledGraph;

        Assert.assertEquals(UnrollingTestNode.INSIDE_LOOP_MARKER, 4, UnrollingTestNode.countBlackholeNodes(peResult, UnrollingTestNode.INSIDE_LOOP_MARKER));
        Assert.assertEquals(UnrollingTestNode.AFTER_LOOP_MARKER, 1, UnrollingTestNode.countBlackholeNodes(peResult, UnrollingTestNode.AFTER_LOOP_MARKER));
    }

    @Test
    public void unrollUntilReturnNestedLoopsContinueOuter05() {
        FrameDescriptor fd = new FrameDescriptor();
        final int loopIterations = 2;
        UnrollingTestNode t = new UnrollingTestNode(loopIterations);
        AbstractTestNode result = new AddTestNode(t.new FullUnrollUntilReturnNestedLoopsContinueOuter05(), new ConstantTestNode(37), true);
        compileHelper("Test", new RootTestNode(fd, "nestedLoopExplosion", result, true), new Object[]{});
        StructuredGraph peResult = lastCompiledGraph;

        Assert.assertEquals(UnrollingTestNode.INSIDE_LOOP_MARKER, 6, UnrollingTestNode.countBlackholeNodes(peResult, UnrollingTestNode.INSIDE_LOOP_MARKER));
        Assert.assertEquals(UnrollingTestNode.AFTER_LOOP_MARKER, 1, UnrollingTestNode.countBlackholeNodes(peResult, UnrollingTestNode.AFTER_LOOP_MARKER));
    }

    public static final boolean DEBUG_TTY = false;

    @Test
    public void unrollUntilReturnNestedLoopsContinueOuter06() {
        FrameDescriptor fd = new FrameDescriptor();
        final int loopIterations = UnrollingTestNode.ExecutingUnrollUntilReturnTest.specialIterationNumber;
        UnrollingTestNode t = new UnrollingTestNode(loopIterations);

        int addNodeconstant = 37;

        UnrollingTestNode.ExecutingUnrollUntilReturnTest.clearSpecialEffect();
        int resBefore = t.new FullUnrollUntilReturnNestedLoopsContinueOuter06().execute(new FrameWithoutBoxing(fd, null)) + addNodeconstant;

        int effectBefore1 = UnrollingTestNode.ExecutingUnrollUntilReturnTest.SpecialSideEffect1;
        int effectBefore2 = UnrollingTestNode.ExecutingUnrollUntilReturnTest.SpecialSideEffect2;
        int effectBefore3 = UnrollingTestNode.ExecutingUnrollUntilReturnTest.SpecialSideEffect3;
        int effectBefore4 = UnrollingTestNode.ExecutingUnrollUntilReturnTest.SpecialSideEffect4;
        int effectBefore5 = UnrollingTestNode.ExecutingUnrollUntilReturnTest.SpecialSideEffect5;
        int effectBefore6 = UnrollingTestNode.ExecutingUnrollUntilReturnTest.SpecialSideEffect6;
        int effectBefore7 = UnrollingTestNode.ExecutingUnrollUntilReturnTest.SpecialSideEffect7;
        int effectBefore8 = UnrollingTestNode.ExecutingUnrollUntilReturnTest.SpecialSideEffect8;
        int effectBefore9 = UnrollingTestNode.ExecutingUnrollUntilReturnTest.SpecialSideEffect9;
        int effectBefore10 = UnrollingTestNode.ExecutingUnrollUntilReturnTest.SpecialSideEffect10;
        int effectBefore11 = UnrollingTestNode.ExecutingUnrollUntilReturnTest.SpecialSideEffect11;
        int effectBefore12 = UnrollingTestNode.ExecutingUnrollUntilReturnTest.SpecialSideEffect12;

        if (DEBUG_TTY) {
            TTY.printf("before1 %d\n", effectBefore1);
            TTY.printf("before2 %d\n", effectBefore2);
            TTY.printf("before3 %d\n", effectBefore3);
            TTY.printf("before4 %d\n", effectBefore4);
            TTY.printf("before5 %d\n", effectBefore5);
            TTY.printf("before6 %d\n", effectBefore6);
            TTY.printf("before7 %d\n", effectBefore7);
            TTY.printf("before8 %d\n", effectBefore8);
            TTY.printf("before9 %d\n", effectBefore9);
            TTY.printf("before10 %d\n", effectBefore10);
            TTY.printf("before11 %d\n", effectBefore11);
            TTY.printf("before12 %d\n", effectBefore12);
        }
        Assert.assertEquals(1, UnrollingTestNode.ExecutingUnrollUntilReturnTest.interpretedInvocationCounts);
        Assert.assertEquals(0, UnrollingTestNode.ExecutingUnrollUntilReturnTest.compiledInvocationCounts);

        AbstractTestNode result = new AddTestNode(t.new FullUnrollUntilReturnNestedLoopsContinueOuter06(), new ConstantTestNode(addNodeconstant));
        OptimizedCallTarget compilable = compileHelper("Test", new RootTestNode(fd, "nestedLoopExplosion", result), new Object[]{});
        StructuredGraph peResult = lastCompiledGraph;

        UnrollingTestNode.ExecutingUnrollUntilReturnTest.compiledInvocationCounts = 0;

        int interpretedCountBefore = UnrollingTestNode.ExecutingUnrollUntilReturnTest.interpretedInvocationCounts;

        UnrollingTestNode.ExecutingUnrollUntilReturnTest.clearSpecialEffect();

        int resAfter = (int) compilable.call();

        Assert.assertEquals(1, UnrollingTestNode.ExecutingUnrollUntilReturnTest.compiledInvocationCounts);
        Assert.assertEquals(interpretedCountBefore, UnrollingTestNode.ExecutingUnrollUntilReturnTest.interpretedInvocationCounts);

        int effectAfter1 = UnrollingTestNode.ExecutingUnrollUntilReturnTest.SpecialSideEffect1;
        int effectAfter2 = UnrollingTestNode.ExecutingUnrollUntilReturnTest.SpecialSideEffect2;
        int effectAfter3 = UnrollingTestNode.ExecutingUnrollUntilReturnTest.SpecialSideEffect3;
        int effectAfter4 = UnrollingTestNode.ExecutingUnrollUntilReturnTest.SpecialSideEffect4;
        int effectAfter5 = UnrollingTestNode.ExecutingUnrollUntilReturnTest.SpecialSideEffect5;
        int effectAfter6 = UnrollingTestNode.ExecutingUnrollUntilReturnTest.SpecialSideEffect6;
        int effectAfter7 = UnrollingTestNode.ExecutingUnrollUntilReturnTest.SpecialSideEffect7;
        int effectAfter8 = UnrollingTestNode.ExecutingUnrollUntilReturnTest.SpecialSideEffect8;
        int effectAfter9 = UnrollingTestNode.ExecutingUnrollUntilReturnTest.SpecialSideEffect9;
        int effectAfter10 = UnrollingTestNode.ExecutingUnrollUntilReturnTest.SpecialSideEffect10;
        int effectAfter11 = UnrollingTestNode.ExecutingUnrollUntilReturnTest.SpecialSideEffect11;
        int effectAfter12 = UnrollingTestNode.ExecutingUnrollUntilReturnTest.SpecialSideEffect12;

        if (DEBUG_TTY) {
            TTY.printf("after1 %d\n", effectAfter1);
            TTY.printf("after2 %d\n", effectAfter2);
            TTY.printf("after3 %d\n", effectAfter3);
       