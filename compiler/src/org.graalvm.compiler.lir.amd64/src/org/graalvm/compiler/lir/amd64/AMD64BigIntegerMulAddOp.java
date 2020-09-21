/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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

import static jdk.vm.ci.amd64.AMD64.r10;
import static jdk.vm.ci.amd64.AMD64.r11;
import static jdk.vm.ci.amd64.AMD64.r12;
import static jdk.vm.ci.amd64.AMD64.r13;
import static jdk.vm.ci.amd64.AMD64.r14;
import static jdk.vm.ci.amd64.AMD64.r8;
import static jdk.vm.ci.amd64.AMD64.r9;
import static jdk.vm.ci.amd64.AMD64.rax;
import static jdk.vm.ci.amd64.AMD64.rbx;
import static jdk.vm.ci.amd64.AMD64.rcx;
import static jdk.vm.ci.amd64.AMD64.rdi;
import static jdk.vm.ci.amd64.AMD64.rdx;
import static jdk.vm.ci.amd64.AMD64.rsi;
import static jdk.vm.ci.amd64.AMD64.CPUFeature.AVX;
import static jdk.vm.ci.amd64.AMD64.CPUFeature.BMI2;
import static jdk.vm.ci.code.ValueUtil.asRegister;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.REG;

import org.graalvm.compiler.asm.Label;
import org.graalvm.compiler.asm.amd64.AMD64Address;
import org.graalvm.compiler.asm.amd64.AMD64Assembler.ConditionFlag;
import org.graalvm.compiler.asm.amd64.AMD64MacroAssembler;
import org.graalvm.compiler.core.common.Stride;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.lir.LIRInstructionClass;
import org.graalvm.compiler.lir.StubPort;
import org.graalvm.compiler.lir.asm.CompilationResultBuilder;

import jdk.vm.ci.amd64.AMD64Kind;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.Value;

// @formatter:off
@StubPort(path      = "src/hotspot/cpu/x86/stubGenerator_x86_64.cpp",
          lineStart = 3186,
          lineEnd   = 3238,
          commit    = "fbc036e7454720b589d99a8cae30369a10471528",
          sha1      = "f8e50fddbd2c5ccdf258bd713c14e857c5f2926d")
@StubPort(path      = "src/hotspot/cpu/x86/macroAssembler_x86.cpp",
          lineStart = 6888,
          lineEnd   = 6922,
          commit    = "fbc036e7454720b589d99a8cae30369a10471528",
          sha1      = "9925ac20c28f0aec605c339c58617196097ab840")
@StubPort(path      = "src/hotspot/cpu/x86/macroAssembler_x86.cpp",
          lineStart = 7140,
          lineEnd   = 7317,
          commit    = "fbc036e7454720b589d99a8cae30369a10471528",
          sha1      = "90e45105ef8d2750bfd97f0087c446870327c8e2")
// @formatter:on
public final class AMD64BigIntegerMulAddOp extends AMD64LIRInstruction {

    public static final LIRInstructionClass<AMD64BigIntegerMulAddOp> TYPE = LIRInstructionClass.create(AMD64BigIntegerMulAddOp.class);

    @Use({REG}) private Value outValue;
    @Use({REG}) private Value inValue;
    @Use({REG}) private Value offsetValue;
    @Use({REG}) private Value lenValue;
    @Use({REG}) private Value kValue;

    @Temp({REG}) private Value tmp1Value;
    @Temp({REG}) private Value[] tmpValues;

    public AMD64BigIntegerMulAddOp(
                    Value outValue,
                    Value inValue,
                    Value offsetValue,
                    Value lenValue,
                    Value kValue,
                    Register heapBaseRegister) {
        super(TYPE);

        // Due to lack of allocatable registers, we use fixed registers and mark them as @Use+@Temp.
        // This allows the fixed registers to be reused for hosting temporary values
        GraalError.guarantee(asRegister(outValue).equals(rdi), "expect outValue at rdi, but was %s", outValue);
        GraalError.guarantee(asRegister(inValue).equals(rsi), "expect inValue at rsi, but was %s", inValue);
        GraalError.guarantee(asRegister(offsetValue).equals(r11), "expect outValue at r11, but was %s", offsetValue);
        GraalError.guarantee(asRegister(lenValue).equals(rcx), "expect outValue at rcx, but was %s", lenValue);
        GraalError.guarantee(asRegister(kValue).equals(r8), "expect outValue at r8, but was %s", kValue);

        this.outValue = outValue;
        this.inValue = inValue;
        this.offsetValue = offsetValue;
        this.lenValue = lenValue;
        this.kValue = kValue;

        this.tmp1Value = r12.equals(heapBaseRegister) ? r14.asValue() : r12.asValue();

        this.tmpValues = new Value[]{
                        rax.asValue(),
                        rcx.asValue(),
                        rdx.asValue(),
                        rbx.asValue(),
                        rsi.asValue(),
                        rdi.asValue(),
                        r8.asValue(),
                        r9.asValue(),
                        r10.asValue(),
                        r11.asValue(),
                        r13.asValue(),
        };
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
        GraalError.guarantee(outValue.getPlatformKind().equals(AMD64Kind.QWORD), "Invalid outValue kind: %s", outValue);
        GraalError.guarantee(inValue.getPlatformKind().equals(AMD64Kind.QWORD), "Invalid inValue kind: %s", inValue);
        GraalError.guarantee(offsetValue.getPlatformKind().equals(AMD64Kind.DWORD), "Invalid offsetValue kind: %s", offsetValue);
        GraalError.guarantee(lenValue.getPlatformKind().equals(AMD64Kind.DWORD), "Invalid lenValue kind: %s", lenValue);
        GraalError.guarantee(kValue.getPlatformKind().equals(AMD64Kind.DWORD), "Invalid kValue kind: %s", kValue);

        Register out = asRegister(outValue);
        Register in = asRegister(inValue);
        Register offset = asRegister(offsetValue);
        Register len = asRegister(lenValue);
        Register k = asRegister(kValue);

        Register tmp1 = asRegister(tmp1Value);
        Register tmp2 = r13;
        Register tmp3 = r9;
        Register tmp4 = r10;
        Register tmp5 = rbx;

        mulAdd(masm, out, in, offset, len, k, tmp1, tmp2, tmp3, tmp4, tmp5, rdx, rax);
    }

    static boolean useBMI2Instructions(AMD64MacroAssembler masm) {
        return masm.supports(BMI2) && masm.supports(AVX);
    }

    static void multiplyAdd64Bmi2(AMD64MacroAssembler masm, Register sum, Register op1, Register op2, Register carry, Register tmp2) {
        GraalError.guarantee(rdx.equals(op2), "expect op2 to be rdx, but was %s", op2);

        masm.mulxq(tmp2, op1, op1);  // op1 * op2 -> tmp2:op1
        masm.addq(sum, carry);
        masm.adcq(tmp2, 0);
        masm.addq(sum, op1);
        masm.adcq(tmp2, 0);
        masm.movq(carry, tmp2);
    }

    static void multiplyAdd64(AMD64MacroAssembler masm, Register sum, Register op1, Register op2, Register carry, Register rdxReg, Register raxReg) {
        // rdx:rax = op1 * op2
        masm.movq(raxReg, op2);
        masm.mulq(op1);

        // rdx:rax = sum + carry + rdx:rax
        masm.addq(sum, carry);
        masm.adcq(rdxReg, 0);
        masm.addq(sum, raxReg);
        masm.adcq(rdxReg, 0);

        // carry:sum = rdx:sum
        masm.movq(carry, rdxReg);
    }

    static void mulAdd128X32Loop(AMD64MacroAssembler masm, Register out, Register in, Register offset, Register len,
                    Register tmp1, Register tmp2, Register tmp3, Register tmp4, Register tmp5, Register rdxReg, Register raxReg) {

        Label lFirstLoop = new Label();
        Label lFirstLoopExit = new Label();

        masm.movl(tmp1, len);
        masm.shrl(tmp1, 2);

        masm.bind(lFirstLoop);
        masm.sublAndJcc(tmp1, 1, ConditionFlag.Negative, lFirstLoopExit, true);

        masm.subl(len, 4);
        masm.subl(offset, 4);

        Register op2 = tmp2;
        Register sum = tmp3;
        Register op1 = tmp4;
        Register carry = tmp5;

        if (useBMI2Instructions(masm)) {
            op2 = rdxReg;
        }

        masm.movq(op1, new AMD64Address(in, len, Stride.S4, 8));
        masm.rorq(op1, 32);
        masm.movq(sum, new