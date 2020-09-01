/*
 * Copyright (c) 2022, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.cpufeature;

import static jdk.vm.ci.amd64.AMD64.CPUFeature.ADX;
import static jdk.vm.ci.amd64.AMD64.CPUFeature.AES;
import static jdk.vm.ci.amd64.AMD64.CPUFeature.AVX;
import static jdk.vm.ci.amd64.AMD64.CPUFeature.AVX2;
import static jdk.vm.ci.amd64.AMD64.CPUFeature.BMI2;
import static jdk.vm.ci.amd64.AMD64.CPUFeature.CLMUL;
import static jdk.vm.ci.amd64.AMD64.CPUFeature.POPCNT;
import static jdk.vm.ci.amd64.AMD64.CPUFeature.SSE2;
import static jdk.vm.ci.amd64.AMD64.CPUFeature.SSE3;
import static jdk.vm.ci.amd64.AMD64.CPUFeature.SSE4_1;
import static jdk.vm.ci.amd64.AMD64.CPUFeature.SSE4_2;
import static jdk.vm.ci.amd64.AMD64.CPUFeature.SSSE3;

import java.util.EnumSet;

import org.graalvm.compiler.api.replacements.Fold;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.replacements.nodes.AESNode;
import org.graalvm.compiler.replacements.nodes.BigIntegerMulAddNode;
import org.graalvm.compiler.replacements.nodes.BigIntegerMultiplyToLenNode;
import org.graalvm.compiler.replacements.nodes.BigIntegerSquareToLenNode;
import org.graalvm.compiler.replacements.nodes.CipherBlockChainingAESNode;
import org.graalvm.compiler.replacements.nodes.CounterModeAESNode;
import org.graalvm.compiler.replacements.nodes.GHASHProcessBlocksNode;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.SubstrateTargetDescription;

import jdk.vm.ci.aarch64.AArch64;
import jdk.vm.ci.amd64.AMD64;
import jdk.vm.ci.code.Architecture;

public final class Stubs {

    @Platforms(Platform.AMD64.class)
    public static class AMD64Features {
        public static final EnumSet<AMD64.CPUFeature> RUNTI