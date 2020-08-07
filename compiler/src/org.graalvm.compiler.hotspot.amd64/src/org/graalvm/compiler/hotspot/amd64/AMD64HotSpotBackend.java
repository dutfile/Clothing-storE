/*
 * Copyright (c) 2012, 2021, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.hotspot.amd64;

import static jdk.vm.ci.amd64.AMD64.r10;
import static jdk.vm.ci.amd64.AMD64.rax;
import static jdk.vm.ci.amd64.AMD64.rbp;
import static jdk.vm.ci.amd64.AMD64.rsp;
import static jdk.vm.ci.code.ValueUtil.asRegister;
import static org.graalvm.compiler.core.common.GraalOptions.CanOmitFrame;
import static org.graalvm.compiler.core.common.GraalOptions.ZapStackOnMethodEntry;

import org.graalvm.compiler.asm.amd64.AMD64Address;
import org.graalvm.compiler.asm.amd64.AMD64Assembler;
import org.graalvm.compiler.asm.amd64.AMD64Assembler.ConditionFlag;
import org.graalvm.compiler.asm.amd64.AMD64BaseAssembler;
import org.graalvm.compiler.asm.amd64.AMD64MacroAssembler;
import org.graalvm.compiler.code.CompilationResult;
import org.graalvm.compiler.core.amd64.AMD64NodeMatchRules;
import org.graalvm.compiler.core.common.NumUtil;
import org.graalvm.compiler.core.common.alloc.RegisterAllocationConfig;
import org.graalvm.compiler.core.gen.LIRGenerationProvider;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.hotspot.GraalHotSpotVMConfig;
import org.graalvm.compiler.hotspot.HotSpotDataBuilder;
import org.graalvm.compiler.hotspot.HotSpotGraalRuntimeProvider;
import org.graalvm.compiler.hotspot.HotSpotHostBackend;
import org.graalvm.compiler.hotspot.HotSpotLIRGenerationResult;
import org.graalvm.compiler.hotspot.HotSpotMarkId;
import org.graalvm.compiler.hotspot.meta.HotSpotForeignCallsProvider;
import org.graalvm.compiler.hotspot.meta.HotSpotProviders;
import org.graalvm.compiler.hotspot.stubs.Stub;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.amd64.AMD64Call;
import org.graalvm.compiler.lir.amd64.AMD64FrameMap;
import org.graalvm.compiler.lir.amd64.AMD64FrameMapBuilder;
import org.graalvm.compiler.lir.asm.CompilationResultBuilder;
import org.graalvm.compiler.lir.asm.CompilationResultBuilderFactory;
import org.graalvm.compiler.lir.asm.DataBuilder;
import org.graalvm.compiler.lir.asm.FrameContext;
import org.graalvm.compiler.lir.framemap.FrameMap;
import org.graalvm.compiler.lir.framemap.FrameMapBuilder;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.gen.LIRGeneratorTool;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.spi.NodeLIRBuilderTool;
import org.graalvm.compiler.options.OptionValues;

import jdk.vm.ci.amd64.AMD64;
import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterConfig;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.hotspot.HotSpotCallingConventionType;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * HotSpot AMD64 specific backend.
 */
public class AMD64HotSpotBackend extends HotSpotHostBackend implements LIRGenerationProvider {

    public AMD64HotSpotBackend(GraalHotSpotVMConfig config, HotSpotGraalRuntimeProvider runtime, HotSpotProviders providers) {
        super(config, runtime, providers);
    }

    @Override
    protected FrameMapBuilder newFrameMapBuilder(RegisterConfig registerConfig) {
        RegisterConfig registerConfigNonNull = registerConfig == null ? getCodeCache().getRegisterConfig() : registerConfig;
        FrameMap frameMap = new AMD64FrameMap(getCodeCache(), registerConfigNonNull, this, config.preserveFramePointer);
        return new AMD64FrameMapBuilder(frameMap, getCodeCache(), registerConfigNonNull);
    }

    @Override
    public LIRGeneratorTool newLIRGenerator(LIRGenerationResult lirGenRes) {
        return new AMD64HotSpotLIRGenerator(getProviders(), config, lirGenRes);
    }

    @Override
    public NodeLIRBuilderTool newNodeLIRBuilder(StructuredGraph graph, LIRGeneratorTool lirGen) {
        return new AMD64HotSpotNodeLIRBuilder(graph, lirGen, new AMD64NodeMatchRules(lirGen));
    }

    @Override
    protected void bangStackWithOffset(CompilationResultBuilder crb, int bangOffset) {
        AMD64MacroAssembler asm = (AMD64MacroAssembler) crb.asm;
        int pos = asm.position();
        asm.movl(new AMD64Address(rsp, -bangOffset), AMD64.rax);
        assert asm.position() - pos >= PATCHED_VERIFIED_ENTRY_POINT_INSTRUCTION_SIZE;
    }

    /**
     * The size of the instruction used to patch the verified entry point of an nmethod when the
     * nmethod is made non-entrant or a zombie (e.g. during deopt or class unloading). The first
     * instruction emitted at an nmethod's verified entry point must be at least this length to
     * ensure mt-safe patching.
     */
    public static final int PATCHED_VERIFIED_ENTRY_POINT_INSTRUCTION_SIZE = 5;

    /**
     * Emits code at the verified entry point and return point(s) of a method.
     */
    class HotSpotFrameContext implements FrameContext {

        final boolean isStub;
        final boolean omitFrame;
        final boolean useStandardFrameProlog;

        HotSpotFrameContext(boolean isStub, boolean omitFrame, boolean useStandardFrameProlog) {
            this.isStub = isStub;
            this.omitFrame = omitFrame;
            this.useStandardFrameProlog = useStandardFrameProlog;
        }

        @Override
        public boolean hasFrame() {
            return !omitFrame;
        }

        @Override
        public void enter(CompilationResultBuilder crb) {
            FrameMap frameMap = crb.frameMap;
            int frameSize = frameMap.frameSize();
            AMD64MacroAssembler asm = (AMD64MacroAssembler) crb.asm;
            if (omitFrame) {
                if (!isStub) {
                    asm.nop(PATCHED_VERIFIED_ENTRY_POINT_INSTRUCTION_SIZE);
                }
            } else {
                int verifiedEntryPointOffset = asm.position();
                if (!isStub) {
                    emitStackOverflowCheck(crb);
                    // assert asm.position() - verifiedEntryPointOffset >=
                    // PATCHED_VERIFIED_ENTRY_POINT_INSTRUCTION_SIZE;
                }
                if (useStandardFrameProlog) {
                    // Stack-walking friendly instructions
                    asm.push(rbp);
                    asm.movq(rbp, rsp);
                }
                if (!isStub && asm.position() == verifiedEntryPointOffset) {
                    asm.subqWide(rsp, frameSize);
                    assert asm.position() - verifiedEntryPointOffset >= PATCHED_VERIFIED_ENTRY_POINT_INSTRUCTION_SIZE;
                } else {
                    asm.decrementq(rsp, frameSize);
                }
                if (HotSpotMarkId.FRAME_COMPLETE.isAvailable()) {
                    crb.recordMark(HotSpotMarkId.FRAME_COMPLETE);
                }
                if (ZapStackOnMethodEntry.getValue(crb.getOptions())) {
                    final int intSize = 4;
                    for (int i = 0; i < frameSize / intSize; ++i) {
                        asm.movl(new AMD64Address(rsp, i * intSize), 0xC1C1C1C1);
                    }
                }
                assert frameMap.getRegisterConfig().getCalleeSaveRegisters() == null;
            }
        }

        @Override
        public void leave(CompilationResultBuilder crb) {
            if (!omitFrame) {
                AMD64MacroAssembler asm = (AMD64MacroAssembler) crb.asm;
                assert crb.frameMap.getRegisterConfig().getCalleeSaveRegisters() == null;

                int frameSize = crb.frameMap.frameSize();
                if (useStandardFrameProlog) {
                    asm.movq(rsp, rbp);
                    asm.pop(rbp);
                } else {
                    asm.incrementq(rsp, frameSize);
                }
            }
        }

        @Override
        public void returned(CompilationResultBuilder crb) {
            // nothing to do
        }
    }

    @Override
    public CompilationResultBuilder newCompilationResultBuilder(LIRGenerationResult lirGenRen, FrameMap frameMap, CompilationResult compilationResult, CompilationResultBuilderFactory factory) {
        // Omit the frame if the method:
        // - has no spill slots or other slots allocated during register allocation
        // - has no callee-saved registers
        // - has no incoming arguments passed on the stack
        // - has no deoptimization points
        // - makes no foreign calls (which require an aligned stack)
        HotSpotLIRGenerationResult gen = (HotSpotLIRGenerationResult) lirGenRen;
        LIR lir = gen.getLIR();
        assert gen.getDeoptimizationRescueSlot() == null || frameMap.frameNeedsAllocating() : "method that can deoptimize must have a frame";
        OptionValues options = lir.getOptions();
        DebugContext debug = lir.getDebug();
        boolean omitFrame = CanOmitFrame.getValue(options) && !frameMap.frameNeedsAllocating() && !lir.hasArgInCallerFrame() && !gen.hasForeignCall() &&
                        !((AMD64FrameMap) frameMap).useStandardFrameProlog();

        Stub stub = gen.getStub();
        AMD64MacroAssembler masm = new AMD64HotSpo