/*
 * Copyright (c) 2019, 2021, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2019, Arm Limited and affiliates. All rights reserved.
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

package org.graalvm.compiler.asm.aarch64.test;

import static org.junit.Assert.assertArrayEquals;

import org.graalvm.compiler.asm.aarch64.AArch64Assembler;
import org.graalvm.compiler.asm.aarch64.AArch64MacroAssembler;
import org.graalvm.compiler.test.GraalTest;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import jdk.vm.ci.aarch64.AArch64;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.runtime.JVMCI;

public class AArch64MoveConstantTest extends GraalTest {

    private AArch64MacroAssembler masm;
    private TestProtectedAssembler asm;
    private Register dst;
    private Register zr;

    @Before
    public void setupEnvironment() {
        // Setup AArch64 MacroAssembler and Assembler.
        TargetDescription target = JVMCI.getRuntime().getHostJVMCIBackend().getTarget();
        Assume.assumeTrue("AArch64-specific test", target.arch instanceof AArch64);
        masm = new AArch64MacroAssembler(target);
        asm = new TestProtectedAssembler(target);
        dst = AArch64.r10;
        zr = AArch64.zr;
    }

    /**
     * MacroAssembler behavior test for 32-bit constant move.
     */
    @Test
    public void testMoveIntZero() {
        masm.mov(dst, 0);   // zero is specially handled by OR(dst, zr, zr).
        asm.orr(32, dst, zr, zr, AArch64Assembler.ShiftType.LSL, 0);
        compareAssembly();
    }

    @Test
    public void testMoveIntLogicalImm() {
        masm.mov(dst, 0x5555_5555);  // 0b01010101...0101 is a 32-bit logical immediate.
        asm.orr(32, dst, zr, 0x5555_5555);
        compareAssembly();
    }

    @Test
    public void testMoveIntMinusOne() {
        masm.mov(dst, -1);
        asm.movn(32, dst, 0, 0);
        compareAssembly();
    }

    @Test
    public void testMoveIntHighZero() {
        masm.mov(dst, 0x0000_1234);
        asm.movz(32, dst, 0x1234, 0);
        compareAssembly();
    }

    @Test
    public void testMoveIntLowZero() {
        masm.mov(dst, 0x5678_0000);
        asm.movz(32, dst, 0x5678, 16);
        compareAssembly();
    }

    @Test
    public void testMoveIntHighNeg() {
        masm.mov(dst, 0xFFFF_CAFE);
        asm.movn(32, dst, 0xCAFE ^ 0xFFFF, 0);
        compareAssembly();
    }

    @Test
    public void testMoveIntLowNeg() {
        masm.mov(dst, 0xBABE_FFFF);
        asm.movn(32, dst, 0xBABE ^ 0xFFFF, 16);
        compareAssembly();
    }

    @Test
    public void testMoveIntCommon() {
        masm.mov(dst, 0x1357_BEEF);
        asm.movz(32, dst, 0xBEEF, 0);
        asm.movk(32, dst, 0x1357, 16);
        compareAssembly();
    }

    /**
     * MacroAssembler behavior test for 64-bit constant move.
     */
    @Test
    public void testMoveLongZero() {
        masm.mov(dst, 0L);  // zero is specially handled by OR(dst, zr, zr).
        asm.orr(64, dst, zr, zr, AArch64Assembler.ShiftType.LSL, 0);
        compareAssembly();
    }

    @Test
    public void testMoveLongLogicalImm() {
        masm.mov(dst, 0x3333_3333_3333_3333L); // 0b00110011...0011 is a 64-bit logical immediate.
        asm.orr(64, dst, z