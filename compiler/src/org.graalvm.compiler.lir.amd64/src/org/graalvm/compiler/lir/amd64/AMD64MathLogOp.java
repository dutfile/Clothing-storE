/*
 * Copyright (c) 2018, 2021, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2016, Intel Corporation. All rights reserved.
 * Intel Math Library (LIBM) Source Code
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
package org.graalvm.compiler.lir.amd64;

import static jdk.vm.ci.amd64.AMD64.r11;
import static jdk.vm.ci.amd64.AMD64.r8;
import static jdk.vm.ci.amd64.AMD64.rax;
import static jdk.vm.ci.amd64.AMD64.rcx;
import static jdk.vm.ci.amd64.AMD64.rdx;
import static jdk.vm.ci.amd64.AMD64.rsp;
import static jdk.vm.ci.amd64.AMD64.xmm0;
import static jdk.vm.ci.amd64.AMD64.xmm1;
import static jdk.vm.ci.amd64.AMD64.xmm2;
import static jdk.vm.ci.amd64.AMD64.xmm3;
import static jdk.vm.ci.amd64.AMD64.xmm4;
import static jdk.vm.ci.amd64.AMD64.xmm5;
import static jdk.vm.ci.amd64.AMD64.xmm6;
import static jdk.vm.ci.amd64.AMD64.xmm7;
import static org.graalvm.compiler.lir.amd64.AMD64HotSpotHelper.pointerConstant;
import static org.graalvm.compiler.lir.amd64.AMD64HotSpotHelper.recordExternalAddress;

import org.graalvm.compiler.asm.Label;
import org.graalvm.compiler.asm.amd64.AMD64Address;
import org.graalvm.compiler.asm.amd64.AMD64Assembler;
import org.graalvm.compiler.asm.amd64.AMD64Assembler.ConditionFlag;
import org.graalvm.compiler.asm.amd64.AMD64MacroAssembler;
import org.graalvm.compiler.core.common.Stride;
import org.graalvm.compiler.lir.LIRInstructionClass;
import org.graalvm.compiler.lir.StubPort;
import org.graalvm.compiler.lir.asm.ArrayDataPointerConstant;
import org.graalvm.compiler.lir.asm.CompilationResultBuilder;

import jdk.vm.ci.amd64.AMD64;

/**
 * <pre>
 *                     ALGORITHM DESCRIPTION - LOG()
 *                     ---------------------
 *
 *    x=2^k * mx, mx in [1,2)
 *
 *    Get B~1/mx based on the output of rcpss instruction (B0)
 *    B = int((B0*2^7+0.5))/2^7
 *
 *    Reduced argument: r=B*mx-1.0 (computed accurately in high and low parts)
 *
 *    Result:  k*log(2) - log(B) + p(r) if |x-1| >= small value (2^-6)  and
 *             p(r) is a degree 7 polynomial
 *             -log(B) read from data table (high, low parts)
 *             Result is formed from high and low parts.
 *
 * Special cases:
 *  log(NaN) = quiet NaN, and raise invalid exception
 *  log(+INF) = that INF
 *  log(0) = -INF with divide-by-zero exception raised
 *  log(1) = +0
 *  log(x) = NaN with invalid exception raised if x < -0, including -INF
 * </pre>
 */
// @formatter:off
@StubPort(path      = "src/hotspot/cpu/x86/stubGenerator_x86_64_log.cpp",
          lineStart = 32,
          lineEnd   = 363,
          commit    = "090cdfc7a2e280c620a0926512fb67f0ce7f3c21",
          sha1      = "fd2dcad178f60306e830e0f7aeaeee376d47ea81")
// @formatter:on
public final class AMD64MathLogOp extends AMD64MathIntrinsicUnaryOp {

    public static final LIRInstructionClass<AMD64MathLogOp> TYPE = LIRInstructionClass.create(AMD64MathLogOp.class);

    public AMD64MathLogOp() {
        super(TYPE, /* GPR */ rax, rcx, rdx, r8, r11,
                        /* XMM */ xmm1, xmm2, xmm3, xmm4, xmm5, xmm6, xmm7);
    }

    private static ArrayDataPointerConstant lTbl = pointerConstant(16, new int[]{
            // @formatter:off
            0xfefa3800, 0x3fe62e42, 0x93c76730, 0x3d2ef357, 0xaa241800,
            0x3fe5ee82, 0x0cda46be, 0x3d220238, 0x5c364800, 0x3fe5af40,
            0xac10c9fb, 0x3d2dfa63, 0x26bb8c00, 0x3fe5707a, 0xff3303dd,
            0x3d09980b, 0x26867800, 0x3fe5322e, 0x5d257531, 0x3d05ccc4,
            0x835a5000, 0x3fe4f45a, 0x6d93b8fb, 0xbd2e6c51, 0x6f970c00,
            0x3fe4b6fd, 0xed4c541c, 0x3cef7115, 0x27e8a400, 0x3fe47a15,
            0xf94d60aa, 0xbd22cb6a, 0xf2f92400, 0x3fe43d9f, 0x481051f7,
            0xbcfd984f, 0x2125cc00, 0x3fe4019c, 0x30f0c74c, 0xbd26ce79,
            0x0c36c000, 0x3fe3c608, 0x7cfe13c2, 0xbd02b736, 0x17197800,
            0x3fe38ae2, 0xbb5569a4, 0xbd218b7a, 0xad9d8c00, 0x3fe35028,
            0x9527e6ac, 0x3d10b83f, 0x44340800, 0x3fe315da, 0xc5a0ed9c,
            0xbd274e93, 0x57b0e000, 0x3fe2dbf5, 0x07b9dc11, 0xbd17a6e5,
            0x6d0ec000, 0x3fe2a278, 0xe797882d, 0x3d206d2b, 0x1134dc00,
            0x3fe26962, 0x05226250, 0xbd0b61f1, 0xd8bebc00, 0x3fe230b0,
            0x6e48667b, 0x3d12fc06, 0x5fc61800, 0x3fe1f863, 0xc9fe81d3,
            0xbd2a7242, 0x49ae6000, 0x3fe1c078, 0xed70e667, 0x3cccacde,
            0x40f23c00, 0x3fe188ee, 0xf8ab4650, 0x3d14cc4e, 0xf6f29800,
            0x3fe151c3, 0xa293ae49, 0xbd2edd97, 0x23c75c00, 0x3fe11af8,
            0xbb9ddcb2, 0xbd258647, 0x8611cc00, 0x3fe0e489, 0x07801742,
            0x3d1c2998, 0xe2d05400, 0x3fe0ae76, 0x887e7e27, 0x3d1f486b,
            0x0533c400, 0x3fe078bf, 0x41edf5fd, 0x3d268122, 0xbe760400,
            0x3fe04360, 0xe79539e0, 0xbd04c45f, 0xe5b20800, 0x3fe00e5a,
            0xb1727b1c, 0xbd053ba3, 0xaf7a4800, 0x3fdfb358, 0x3c164935,
            0x3d0085fa, 0xee031800, 0x3fdf4aa7, 0x6f014a8b, 0x3d12cde5,
            0x56b41000, 0x3fdee2a1, 0x5a470251, 0x3d2f27f4, 0xc3ddb000,
            0x3fde7b42, 0x5372bd08, 0xbd246550, 0x1a272800, 0x3fde148a,
            0x07322938, 0xbd1326b2, 0x484c9800, 0x3fddae75, 0x60dc616a,
            0xbd1ea42d, 0x46def800, 0x3fdd4902, 0xe9a767a8, 0x3d235baf,
            0x18064800, 0x3fdce42f, 0x3ec7a6b0, 0xbd0797c3, 0xc7455800,
            0x3fdc7ff9, 0xc15249ae, 0xbd29b6dd, 0x693fa000, 0x3fdc1c60,
            0x7fe8e180, 0x3d2cec80, 0x1b80e000, 0x3fdbb961, 0xf40a666d,
            0x3d27d85b, 0x04462800, 0x3fdb56fa, 0x2d841995, 0x3d109525,
            0x5248d000, 0x3fdaf529, 0x52774458, 0xbd217cc5, 0x3c8ad800,
            0x3fda93ed, 0xbea77a5d, 0x3d1e36f2, 0x0224f800, 0x3fda3344,
            0x7f9d79f5, 0x3d23c645, 0xea15f000, 0x3fd9d32b, 0x10d0c0b0,
            0xbd26279e, 0x43135800, 0x3fd973a3, 0xa502d9f0, 0xbd152313,
            0x635bf800, 0x3fd914a8, 0x2ee6307d, 0xbd1766b5, 0xa88b3000,
            0x3fd8b639, 0xe5e70470, 0xbd205ae1, 0x776dc800, 0x3fd85855,
            0x3333778a, 0x3d2fd56f, 0x3bd81800, 0x3fd7fafa, 0xc812566a,
            0xbd272090, 0x687cf800, 0x3fd79e26, 0x2efd1778, 0x3d29ec7d,
            0x76c67800, 0x3fd741d8, 0x49dc60b3, 0x3d2d8b09, 0xe6af1800,
            0x3fd6e60e, 0x7c222d87, 0x3d172165, 0x3e9c6800, 0x3fd68ac8,
            0x2756eba0, 0x3d20a0d3, 0x0b3ab000, 0x3fd63003, 0xe731ae00,
            0xbd2db623, 0xdf596000, 0x3fd5d5bd, 0x08a465dc, 0xbd0a0b2a,
            0x53c8d000, 0x3fd57bf7, 0xee5d40ef, 0x3d1faded, 0x0738a000,
            0x3fd522ae, 0x8164c759, 0x3d2ebe70, 0x9e173000, 0x3fd4c9e0,
            0x1b0ad8a4, 0xbd2e2089, 0xc271c800, 0x3fd4718d, 0x0967d675,
            0xbd2f27ce, 0x23d5e800, 0x3fd419b4, 0xec90e09d, 0x3d08e436,
            0x77333000, 0x3fd3c252, 0xb606bd5c, 0x3d183b54, 