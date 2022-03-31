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
package org.graalvm.compiler.lir.amd64.vector;

import static jdk.vm.ci.code.ValueUtil.asRegister;
import static jdk.vm.ci.code.ValueUtil.isRegister;
import static jdk.vm.ci.code.ValueUtil.isStackSlot;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexMRIOp.VEXTRACTF128;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexMRIOp.VEXTRACTF32X4;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexMRIOp.VEXTRACTF32X8;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexMRIOp.VEXTRACTF64X2;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexMRIOp.VEXTRACTF64X4;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexMRIOp.VEXTRACTI128;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexMRIOp.VEXTRACTI32X4;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexMRIOp.VEXTRACTI32X8;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexMRIOp.VEXTRACTI64X2;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexMRIOp.VEXTRACTI64X4;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexMRIOp.VPEXTRB;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexMRIOp.VPEXTRD;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexMRIOp.VPEXTRQ;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexMRIOp.VPEXTRW;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexMoveOp.VMOVD;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexMoveOp.VMOVDQU64;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexMoveOp.VMOVQ;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexRVMIOp.VINSERTF128;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexRVMIOp.VINSERTF32X4;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexRVMIOp.VINSERTF32X8;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexRVMIOp.VINSERTF64X2;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexRVMIOp.VINSERTF64X4;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexRVMIOp.VINSERTI128;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexRVMIOp.VINSERTI32X4;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexRVMIOp.VINSERTI32X8;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexRVMIOp.VINSERTI64X2;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexRVMIOp.VINSERTI64X4;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexRVMIOp.VSHUFPD;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexRVMIOp.VSHUFPS;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexRVMOp.VPERMT2B;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.VexRVMOp.VPSHUFB;
import static org.graalvm.compiler.asm.amd64.AVXKind.AVXSize.XMM;
import static org.graalvm.compiler.asm.amd64.AVXKind.AVXSize.ZMM;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.REG;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.STACK;

import jdk.vm.ci.code.Register;
import org.graalvm.compiler.asm.amd64.AMD64Address;
import org.graalvm.compiler.asm.amd64.AMD64Assembler.VexMRIOp;
import org.graalvm.compiler.asm.amd64.AMD64Assembler.VexRMIOp;
import org.graalvm.compiler.asm.amd64.AMD64Assembler.VexRVMIOp;
import org.graalvm.compiler.asm.amd64.AMD64BaseAssembler;
import org.graalvm.compiler.asm.amd64.AMD64MacroAssembler;
import org.graalvm.compiler.asm.amd64.AVXKind;
import org.graalvm.compiler.core.common.LIRKind;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.lir.LIRInstructionClass;
import org.graalvm.compiler.lir.amd64.AMD64LIRInstruction;
import org.graalvm.compiler.lir.asm.CompilationResultBuilder;

import jdk.vm.ci.amd64.AMD64.CPUFeature;
import jdk.vm.ci.amd64.AMD64Kind;
import jdk.vm.ci.meta.AllocatableValue;
import org.graalvm.compiler.lir.gen.LIRGeneratorTool;

public class AMD64VectorShuffle {

    public static final class IntToVectorOp extends AMD64LIRInstruction {
        public static final LIRInstructionClass<IntToVectorOp> TYPE = LIRInstructionClass.create(IntToVectorOp.class);

        @Def({REG}) protected AllocatableValue result;
        @Use({REG, STACK}) protected AllocatableValue value;

        public IntToVectorOp(AllocatableValue result, AllocatableValue value) {
            super(TYPE);
            assert ((AMD64Kind) result.getPlatformKind()).getScalar().isInteger() : result.getPlatformKind();
            this.result = result;
            this.value = value;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            if (isRegister(value)) {
                VMOVD.emit(masm, XMM, asRegister(result), asRegister(value));
            } else {
                assert isStackSlot(value);
                VMOVD.emit(masm, XMM, asRegister(result), (AMD64Address) crb.asAddress(value));
            }
        }
    }

    public static final class LongToVectorOp extends AMD64LIRInstruction {
        public static final LIRInstructionClass<LongToVectorOp> TYPE = LIRInstructionClass.create(LongToVectorOp.class);
        @Def({REG}) protected AllocatableValue result;
        @Use({REG, STACK}) protected AllocatableValue value;

        pub