/*
 * Copyright (c) 2012, 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.replacements.test;

import java.util.HashMap;

import org.graalvm.compiler.nodes.IfNode;
import org.graalvm.compiler.nodes.Invoke;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.calc.AbsNode;
import org.graalvm.compiler.nodes.calc.ReinterpretNode;
import org.graalvm.compiler.replacements.nodes.BitCountNode;
import org.graalvm.compiler.replacements.nodes.BitScanForwardNode;
import org.graalvm.compiler.replacements.nodes.BitScanReverseNode;
import org.graalvm.compiler.replacements.nodes.CountLeadingZerosNode;
import org.graalvm.compiler.replacements.nodes.CountTrailingZerosNode;
import org.graalvm.compiler.replacements.nodes.ReverseBytesNode;
import org.junit.Test;

import jdk.vm.ci.code.InstalledCode;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Tests the VM independent method substitutions.
 */
public class StandardMethodSubstitutionsTest extends MethodSubstitutionTest {

    @Test
    public void testMathSubstitutions() {
        assertInGraph(assertNotInGraph(testGraph("mathAbs"), IfNode.class), AbsNode.class);     // Java
        double value = 34567.891D;
        testGraph("mathCos");
        testGraph("mathLog");
        testGraph("mathLog10");
        testGraph("mathSin");
        testGraph("mathSqrt");
        testGraph("mathTan");
        testGraph("mathAll");

        test("mathCos", value);
        test("mathLog", value);
        test("mathLog10", value);
        test("mathSin", value);
        test("mathSqrt", value);
        test("mathTan", value);
        test("mathAll", value);
    }

    @Test
    public void testMathPow() {
        double a = 34567.891D;
        double b = 4.6D;
        test("mathPow", a, b);

        // Test the values directly handled by the substitution

        // If the second argument is positive or negative zero, then the result is 1.0.
        test("mathPow", a, 0.0D);
        test("mathPow", a, -0.0D);
        // If the second argument is 1.0, then the result is the same as the first argument.
        test("mathPow", a, 1.0D);
        // If the second argument is NaN, then the result is NaN.
        test("mathPow", a, Double.NaN);
        // If the first argument is NaN and the second argument is nonzero, then the result is NaN.
        test("mathPow", Double.NaN, b);
        test("mathPow", Double.NaN, 0.0D);
        // x**-1 = 1/x
        test("mathPow", a, -1.0D);
        // x**2 = x*x
        test("mathPow", a, 2.0D);
        // x**0.5 = sqrt(x)
        test("mathPow", a, 0.5D);
    }

    public static double mathPow(double a, double b) {
        return mathPow0(a, b);
    }

    public static double mathPow0(double a, double b) {
        return Math.pow(a, b);
    }

    public static double mathAbs(double value) {
        return Math.abs(value);
    }

    public static double mathSqrt(double value) {
        return Math.sqrt(value);
    }

    public static double mathLog(double value) {
        return Math.log(value);
    }

    public static double mathLog10(double value) {
        return Math.log10(value);
    }

    public static double mathSin(double value) {
        return Math.sin(value);
    }

    public static double mathCos(double value) {
        return Math.cos(value);
    }

    public static double mathTan(double value) {
        return Math.tan(value);
    }

    public static double mathAll(double value) {
        return Math.sqrt(value) + Math.log(value) + Math.log10(value) + Math.sin(value) + Math.cos(value) + Math.tan(value);
    }

    public void testSubstitution(String testMethodName, Class<?> holder, String methodName, boolean optional, Object[] args, Class<?>... intrinsicClasses) {
        ResolvedJavaMethod realJavaMethod = getResolvedJavaMethod(holder, methodName);
        ResolvedJavaMethod testJavaMethod = getResolvedJavaMethod(testMethodName);
        StructuredGraph graph = testGraph(testMethodName);

        // Check to see if the resulting graph contains the expected node
        StructuredGraph replacement = getReplacements().getInlineSubstitution(realJavaMethod, 0, Invoke.InlineControl.Normal, false, null, graph.allowAssumptions(), graph.getOptions());
        if (replacement == null && !optional) {
            assertInGraph(graph, intrinsicClasses);
        }

        for (Ob