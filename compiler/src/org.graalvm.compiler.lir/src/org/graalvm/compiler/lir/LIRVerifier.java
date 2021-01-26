/*
 * Copyright (c) 2009, 2021, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.lir;

import static org.graalvm.compiler.lir.LIRValueUtil.asVariable;
import static org.graalvm.compiler.lir.LIRValueUtil.isConstantValue;
import static org.graalvm.compiler.lir.LIRValueUtil.isStackSlotValue;
import static org.graalvm.compiler.lir.LIRValueUtil.isVariable;
import static org.graalvm.compiler.lir.LIRValueUtil.stripCast;
import static jdk.vm.ci.code.ValueUtil.asRegister;
import static jdk.vm.ci.code.ValueUtil.isIllegal;
import static jdk.vm.ci.code.ValueUtil.isRegister;

import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumSet;

import org.graalvm.compiler.core.common.cfg.BasicBlock;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.debug.TTY;
import org.graalvm.compiler.lir.LIRInstruction.OperandFlag;
import org.graalvm.compiler.lir.LIRInstruction.OperandMode;
import org.graalvm.compiler.lir.framemap.FrameMap;
import org.graalvm.compiler.lir.ssa.SSAUtil;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.Value;

public final class LIRVerifier {

    private final LIR lir;
    private final FrameMap frameMap;

    private final boolean beforeRegisterAllocation;

    private final BitSet[] blockLiveOut;
    private final Object[] variableDefinitions;

    private BitSet liveOutFor(BasicBlock<?> block) {
        return blockLiveOut[block.getId()];
    }

    private void setLiveOutFor(BasicBlock<?> block, BitSet liveOut) {
        blockLiveOut[block.getId()] = liveOut;
    }

    private int maxRegisterNum() {
        return frameMap.getTarget().arch.getRegisters().size();
    }

    private boolean isAllocatableRegister(Value value) {
        return isRegister(value) && frameMap.getRegisterConfig().getAttributesMap()[asRegister(value).number].isAllocatable();
    }

    public static boolean verify(final LIRInstruction op) {

        op.visitEachInput(LIRV