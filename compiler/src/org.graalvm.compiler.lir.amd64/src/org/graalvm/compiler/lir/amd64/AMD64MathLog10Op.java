/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
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

/**
 * <pre>
 *                     ALGORITHM DESCRIPTION - LOG10()
 *                     ---------------------
 *
 *    Let x=2^k * mx, mx in [1,2)
 *
 *    Get B~1/mx based on the output of rcpss instruction (B0)
 *    B = int((B0*LH*2^7+0.5))/2^7
 *    LH is a short approximation for log10(e)
 *
 *    Reduced argument: r=B*mx-LH (computed accurately in high and low parts)
 *
 *    Result:  k*log10(2) - log(B) + p(r)
 *             p(r) is a degree 7 polynomial
 *             -log(B) read from data table (high, low parts)
 *             Result is formed from high and low parts.
 *
 * Special cases:
 *  log10(0) = -INF with divide-by-zero exception raised
 *  log10(1) = +0
 *  log10(x) = NaN with invalid exception raised if x < -0, including -INF
 *  log10(+INF) = +INF
 * </pre>
 */
// @formatter:off
@StubPort(path      = "src/hotspot/cpu/x86/stubGenerator_x86_64_log.cpp",
          lineStart = 365,
          lineEnd   = 707,
          commit    = "090cdfc7a2e280c620a0926512fb67f0ce7f3c21",
          sha1      = "f9be8829233550e1708f81a2770fccac94c9c940")
// @formatter:on
public final class AMD64MathLog10Op extends AMD64MathIntrinsicUnaryOp {

    public static final LIRInstructionClass<AMD64MathLog10Op> TYPE = LIRInstructionClass.create(AMD64MathLog10Op.class);

    public AMD64MathLog10Op() {
        super(TYPE, /* GPR */ rax, rcx, rdx, r8, r11,
                        /* XMM */ xmm1, xmm2, xmm3, xmm4, xmm5, xmm6, xmm7);
    }

    private static ArrayDataPointerConstant highsigmask = pointerConstant(16, new int[]{
            // @formatter:off
            0xf8000000, 0xffffffff, 0x00000000, 0xffffe000
            // @formatter:on
    });

    private static ArrayDataPointerConstant log10E = pointerConstant(8, new int[]{
            // @formatter:off
            0x00000000, 0x3fdbc000,
    });
    private static ArrayDataPointerConstant log10E8 = pointerConstant(8, new int[]{
            0xbf2e4108, 0x3f5a7a6c
            // @formatter:on
    });

    private static ArrayDataPointerConstant lTbl = pointerConstant(16, new int[]{
            // @formatter:off
            0x509f7800, 0x3fd34413, 0x1f12b358, 0x3d1fef31, 0x80333400,
            0x3fd32418, 0xc671d9d0, 0xbcf542bf, 0x51195000, 0x3fd30442,
            0x78a4b0c3, 0x3d18216a, 0x6fc79400, 0x3fd2e490, 0x80fa389d,
            0xbc902869, 0x89d04000, 0x3fd2c502, 0x75c2f564, 0x3d040754,
            0x4ddd1c00, 0x3fd2a598, 0xd219b2c3, 0xbcfa1d84, 0x6baa7c00,
            0x3fd28651, 0xfd9abec1, 0x3d1be6d3, 0x94028800, 0x3fd2672d,
            0xe289a455, 0xbd1ede5e, 0x78b86400, 0x3fd2482c, 0x6734d179,
            0x3d1fe79b, 0xcca3c800, 0x3fd2294d, 0x981a40b8, 0xbced34ea,
            0x439c5000, 0x3fd20a91, 0xcc392737, 0xbd1a9cc3, 0x92752c00,
            0x3fd1ebf6, 0x03c9afe7, 0x3d1e98f8, 0x6ef8dc00, 0x3fd1cd7d,
            0x71dae7f4, 0x3d08a86c, 0x8fe4dc00, 0x3fd1af25, 0xee9185a1,
            0xbcff3412, 0xace59400, 0x3fd190ee, 0xc2cab353, 0x3cf17ed9,
            0x7e925000, 0x3fd172d8, 0x6952c1b2, 0x3cf1521c, 0xbe694400,
            0x3fd154e2, 0xcacb79ca, 0xbd0bdc78, 0x26cbac00, 0x3fd1370d,
            0xf71f4de1, 0xbd01f8be, 0x72fa0800, 0x3fd11957, 0x55bf910b,
            0x3c946e2b, 0x5f106000, 0x3fd0fbc1, 0x39e639c1, 0x3d14a84b,
            0xa802a800, 0x3fd0de4a, 0xd3f31d5d, 0xbd178385, 0x0b992000,
            0x3fd0c0f3, 0x3843106f, 0xbd1f602f, 0x486ce800, 0x3fd0a3ba,
            0x8819497c, 0x3cef987a, 0x1de49400, 0x3fd086a0, 0x1caa0467,
            0x3d0faec7, 0x4c30cc00, 0x3fd069a4, 0xa4424372, 0xbd1618fc,
            0x94490000, 0x3fd04cc6, 0x946517d2, 0xbd18384b, 0xb7e84000,
            0x3fd03006, 0xe0109c37, 0xbd19a6ac, 0x798a0c00, 0x3fd01364,
            0x5121e864, 0xbd164cf7, 0x38ce8000, 0x3fcfedbf, 0x46214d1a,
            0xbcbbc402, 0xc8e62000, 0x3fcfb4ef, 0xdab93203, 0x3d1e0176,
            0x2cb02800, 0x3fcf7c5a, 0x2a2ea8e4, 0xbcfec86a, 0xeeeaa000,
            0x3fcf43fd, 0xc18e49a4, 0x3cf110a8, 0x9bb6e800, 0x3fcf0bda,
            0x923cc9c0, 0xbd15ce99, 0xc093f000, 0x3fced3ef, 0x4d4b51e9,
            0x3d1a04c7, 0xec58f800, 0x3fce9c3c, 0x163cad59, 0x3cac8260,
            0x9a907000, 0x3fce2d7d, 0x3fa93646, 0x3ce4a1c0, 0x37311000,
            0x3fcdbf99, 0x32abd1fd, 0x3d07ea9d, 0x6744b800, 0x3fcd528c,
            0x4dcbdfd4, 0xbd1b08e2, 0xe36de800, 0x3fcce653, 0x0b7b7f7f,
            0xbd1b8f03, 0x77506800, 0x3fcc7aec, 0xa821c9fb, 0x3d13c163,
            0x00ff8800, 0x3fcc1053, 0x536bca76, 0xbd074ee5, 0x70719800,
            0x3fcba684, 0xd7da9b6b, 0xbd1fbf16, 0xc6f8d800, 0x3fcb3d7d,
            0xe2220bb3, 0x3d1a295d, 0x16c15800, 0x3fcad53c, 0xe724911e,
            0xbcf55822, 0x82533800, 0x3fca6dbc, 0x6d982371, 0x3cac567c,
            0x3c19e800, 0x3fca06fc, 0x84d17d80, 0x3d1da204, 0x85ef8000,
            0x3fc9a0f8, 0x54466a6a, 0xbd002