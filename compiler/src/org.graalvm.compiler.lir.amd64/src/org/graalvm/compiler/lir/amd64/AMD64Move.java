/*
 * Copyright (c) 2011, 2021, Oracle and/or its affiliates. All rights reserved.
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

import static java.lang.Double.doubleToRawLongBits;
import static java.lang.Float.floatToRawIntBits;
import static jdk.vm.ci.code.ValueUtil.asRegister;
import static jdk.vm.ci.code.ValueUtil.isRegister;
import static jdk.vm.ci.code.ValueUtil.isStackSlot;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.ConditionFlag.Equal;
import static org.graalvm.compiler.asm.amd64.AMD64Assembler.ConditionFlag.NotEqual;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.COMPOSITE;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.CONST;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.HINT;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.ILLEGAL;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.REG;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.STACK;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.UNINITIALIZED;
import static org.graalvm.compiler.lir.LIRValueUtil.asJavaConstant;
import static org.graalvm.compiler.lir.LIRValueUtil.isJavaConstant;

import org.graalvm.compiler.asm.Label;
import org.graalvm.compiler.asm.amd64.AMD64Address;
import org.graalvm.compiler.core.common.Stride;
import org.graalvm.compiler.asm.amd64.AMD64Assembler.AMD64MIOp;
import org.graalvm.compiler.asm.amd64.AMD64Assembler.AMD64MOp;
import org.graalvm.compiler.asm.amd64.AMD64BaseAssembler.OperandSize;
import org.graalvm.compiler.asm.amd64.AMD64MacroAssembler;
import org.graalvm.compiler.core.common.CompressEncoding;
import org.graalvm.compiler.core.common.LIRKind;
import org.graalvm.compiler.core.common.NumUtil;
import org.graalvm.compiler.core.common.spi.LIRKindTool;
import org.graalvm.compiler.core.common.type.DataPointerConstant;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.lir.LIRFrameState;
import org.graalvm.compiler.lir.LIRInstructionClass;
import org.graalvm.compiler.lir.Opcode;
import org.graalvm.compiler.lir.StandardOp.LoadConstantOp;
import org.graalvm.compiler.lir.StandardOp.NullCheck;
import org.graalvm.compiler.lir.StandardOp.ValueMoveOp;
import org.graalvm.compiler.lir.VirtualStackSlot;
import org.graalvm.compiler.lir.asm.CompilationResultBuilder;

import jdk.vm.ci.amd64.AMD64;
import jdk.vm.ci.amd64.AMD64Kind;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterValue;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.Value;

public class AMD64Move {

    private abstract static class AbstractMoveOp extends AMD64LIRInstruction implements ValueMoveOp {
        public static final LIRInstructionClass<AbstractMoveOp> TYPE = LIRInstructionClass.create(AbstractMoveOp.class);

        private AMD64Kind moveKind;

        protected AbstractMoveOp(LIRInstructionClass<? extends AbstractMoveOp> c, AMD64Kind moveKind) {
            super(c);
            this.moveKind = moveKind;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            move(moveKind, crb, masm, getResult(), getInput());
        }
    }

    @Opcode("MOVE")
    public static final class MoveToRegOp extends AbstractMoveOp {
        public static final LIRInstructionClass<MoveToRegOp> TYPE = LIRInstructionClass.create(MoveToRegOp.class);

        @Def({REG, STACK, HINT}) protected AllocatableValue result;
        @Use({REG, STACK}) protected AllocatableValue input;

        public MoveToRegOp(AMD64Kind moveKind, AllocatableValue result, AllocatableValue input) {
            super(TYPE, moveKind);
            this.result = result;
            this.input = input;
        }

        @Override
        public AllocatableValue getInput() {
            return input;
        }

        @Override
        public AllocatableValue getResult() {
            return result;
        }
    }

    @Opcode("MOVE")
    public static final class MoveFromRegOp extends AbstractMoveOp {
        public static final LIRInstructionClass<MoveFromRegOp> TYPE = LIRInstructionClass.create(MoveFromRegOp.class);

        @Def({REG, STACK}) protected AllocatableValue result;
        @Use({REG, HINT}) protected AllocatableValue input;

        public MoveFromRegOp(AMD64Kind moveKind, AllocatableValue result, AllocatableValue input) {
            super(TYPE, moveKind);
            this.result = result;
            this.input = input;
        }

        @Override
        public AllocatableValue getInput() {
            return input;
        }

        @Override
        public AllocatableValue getResult() {
            return result;
        }
    }

    @Opcode("MOVE")
    public static class MoveFromConstOp extends AMD64LIRInstruction implements LoadConstantOp {
        public static final LIRInstructionClass<MoveFromConstOp> TYPE = LIRInstructionClass.create(MoveFromConstOp.class);

        @Def({REG, STACK}) protected AllocatableValue result;
        private final JavaConstant input;

        public MoveFromConstOp(AllocatableValue result, JavaConstant input) {
            super(TYPE);
            this.result = result;
            this.input = input;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            if (isRegister(result)) {
                const2reg(crb, masm, asRegister(result), input, (AMD64Kind) result.getPlatformKind());
            } else {
                assert isStackSlot(result);
                const2stack(crb, masm, result, input);
            }
        }

        @Override
        public Constant getConstant() {
            return input;
        }

        @Override
        public AllocatableValue getResult() {
            return result;
        }
    }

    @Opcode("STACKMOVE")
    public static final class AMD64StackMove extends AMD64LIRInstruction implements ValueMoveOp {
        public static final LIRInstructionClass<AMD64StackMove> TYPE = LIRInstructionClass.create(AMD64StackMove.class);

        @Def({STACK}) protected AllocatableValue result;
        @Use({STACK, HINT}) protected AllocatableValue input;
        @Alive({OperandFlag.STACK, OperandFlag.UNINITIALIZED}) private AllocatableValue backupSlot;

        private Register scratch;

        public AMD64StackMove(AllocatableValue result, AllocatableValue input, Register scratch, AllocatableValue backupSlot) {
            super(TYPE);
            this.result = result;
            this.input = input;
            this.backupSlot = backupSlot;
            this.scratch = scratch;
        }

        @Override
        public AllocatableValue getInput() {
            return input;
        }

        @Override
        public AllocatableValue getResult() {
            return result;
        }

        public Register getScratchRegister() {
            return scratch;
        }

        public AllocatableValue getBackupSlot() {
            return backupSlot;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            AMD64Kind backupKind = (AMD64Kind) backupSlot.getPlatformKind();
            if (backupKind.isXMM()) {
                // graal doesn't use vector values, so it's safe to backup using DOUBLE
                backupKind = AMD64Kind.DOUBLE;
            }

            // backup scratch register
            reg2stack(backupKind, crb, masm, backupSlot, scratch);
            // move stack slot
            stack2reg((AMD64Kind) getInput().getPlatformKind(), crb, masm, scratch, getInput());
            reg2stack((AMD64Kind) getResult().getPlatformKind(), crb, masm, getResult(), scratch);
            // restore scratch register
            stack2reg(backupKind, crb, masm, scratch, backupSlot);
        }
    }

    @Opcode("MULTISTACKMOVE")
    public static final class AMD64MultiStackMove extends AMD64LIRInstruction {
        public static final LIRInstructionClass<AMD64MultiStackMove> TYPE = LIRInstructionClass.create(AMD64MultiStackMove.class);

        @Def({STACK}) protected AllocatableValue[] results;
        @Use({STACK}) protected Value[] inputs;
        @Alive({OperandFlag.STACK, OperandFlag.UNINITIALIZED}) private AllocatableValue backupSlot;

        private Register scratch;

        public AMD64MultiStackMove(AllocatableValue[] results, Value[] inputs, Register scratch, AllocatableValue backupSlot) {
            super(TYPE);
            this.results = results;
            this.inputs = inputs;
            this.backupSlot = backupSlot;
            this.scratch = scratch;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            AMD64Kind backupKind = (AMD64Kind) backupSlot.getPlatformKind();
            if (backupKind.isXMM()) {
                // graal doesn't use vector values, so it's safe to backup using DOUBLE
                backupKind = AMD64Kind.DOUBLE;
            }

            // backup scratch register
            move(backupKind, crb, masm, backupSlot, scratch.asValue(backupSlot.getValueKind()));
            for (int i = 0; i < results.length; i++) {
                Value input = inputs[i];
                AllocatableValue result = results[i];
                // move stack slot
                move((AMD64Kind) input.getPlatformKind(), crb, masm, scratch.asValue(input.getValueKind()), input);
                move((AMD64Kind) result.getPlatformKind(), crb, masm, result, scratch.asValue(result.getValueKind()));
            }
            // restore scratch register
            move(backupKind, crb, masm, scratch.asValue(backupSlot.getValueKind()), backupSlot);
        }
    }

    @Opcode("STACKMOVE")
    public static final class AMD64PushPopStackMove extends AMD64LIRInstruction implements ValueMoveOp {
        public static final LIRInstructionClass<AMD64PushPopStackMove> TYPE = LIRInstructionClass.create(AMD64PushPopStackMove.class);

        @Def({STACK}) protected AllocatableValue result;
        @Use({STACK, HINT}) protected AllocatableValue input;
        private final OperandSize size;

        public AMD64PushPopStackMove(OperandSize size, AllocatableValue result, AllocatableValue input) {
            super(TYPE);
            this.result = result;
            this.input = input;
            this.size = size;
        }

        @Override
        public AllocatableValue getInput() {
            return input;
        }

        @Override
        public AllocatableValue getResult() {
            return result;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            AMD64MOp.PUSH.emit(masm, size, (AMD64Address) crb.asAddress(input));
            AMD64MOp.POP.emit(masm, size, (AMD64Address) crb.asAddress(result));
        }
    }

    public static final class LeaOp extends AMD64LIRInstruction {
        public static final LIRInstructionClass<LeaOp> TYPE = LIRInstructionClass.create(LeaOp.class);

        @Def({REG}) protected AllocatableValue result;
        @Use({COMPOSITE, UNINITIALIZED}) protected AMD64AddressValue address;
        private final OperandSize size;

        public LeaOp(AllocatableValue result, AMD64AddressValue address, OperandSize size) {
            super(TYPE);
            this.result = result;
            this.address = address;
            this.size = size;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            if (size == OperandSize.QWORD) {
                masm.leaq(asRegister(result, AMD64Kind.QWORD), address.toAddress());
            } else {
                assert size == OperandSize.DWORD;
                masm.lead(asRegister(result, AMD64Kind.DWORD), address.toAddress());
            }
        }
    }

    public static final class LeaDataOp extends AMD64LIRInstruction {
        public static final LIRInstructionClass<LeaDataOp> TYPE = LIRInstructionClass.create(LeaDataOp.class);

        @Def({REG}) protected AllocatableValue result;
        private final DataPointerConstant data;

        public LeaDataOp(AllocatableValue result, DataPointerConstant data) {
            super(TYPE);
            this.result = result;
            this.data = data;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            masm.leaq(asRegister(result), (AMD64Address) crb.recordDataReferenceInCode(data));
        }
    }

    public static final class StackLeaOp extends AMD64LIRInstruction {
        public static final LIRInstructionClass<StackLeaOp> TYPE = LIRInstructionClass.create(StackLeaOp.class);

        @Def({REG}) protected AllocatableValue result;
        @Use({STACK, UNINITIALIZED}) protected AllocatableValue slot;

        public StackLeaOp(AllocatableValue result, AllocatableValue slot) {
            super(TYPE);
            this.result = result;
            this.slot = slot;
            assert slot instanceof VirtualStackSlot || slot instanceof StackSlot;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            masm.leaq(asRegister(result, AMD64Kind.QWORD), (AMD64Address) crb.asAddress(slot));
        }
    }

    public static final class MembarOp extends AMD64LIRInstruction {
        public static final LIRInstructionClass<MembarOp> TYPE = LIRInstructionClass.create(MembarOp.class);

        private final int barriers;

        public MembarOp(final int barriers) {
            super(TYPE);
            this.barriers = barriers;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            masm.membar(barriers);
        }
    }

    public static final class NullCheckOp exten