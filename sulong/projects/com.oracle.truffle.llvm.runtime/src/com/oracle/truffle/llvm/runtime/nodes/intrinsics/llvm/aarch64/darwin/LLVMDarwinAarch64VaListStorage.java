/*
 * Copyright (c) 2022, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.llvm.runtime.nodes.intrinsics.llvm.aarch64.darwin;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateAOT;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.llvm.runtime.LLVMLanguage;
import com.oracle.truffle.llvm.runtime.LLVMVarArgCompoundValue;
import com.oracle.truffle.llvm.runtime.debug.value.LLVMSourceTypeFactory;
import com.oracle.truffle.llvm.runtime.global.LLVMGlobalContainer;
import com.oracle.truffle.llvm.runtime.library.internal.LLVMManagedReadLibrary;
import com.oracle.truffle.llvm.runtime.nodes.intrinsics.llvm.va.LLVMVaListLibrary;
import com.oracle.truffle.llvm.runtime.nodes.intrinsics.llvm.va.LLVMVaListStorage;
import com.oracle.truffle.llvm.runtime.nodes.memory.NativeProfiledMemMove;
import com.oracle.truffle.llvm.runtime.nodes.memory.load.LLVMDoubleLoadNode.LLVMDoubleOffsetLoadNode;
import com.oracle.truffle.llvm.runtime.nodes.memory.load.LLVMI16LoadNode.LLVMI16OffsetLoadNode;
import com.oracle.truffle.llvm.runtime.nodes.memory.load.LLVMI32LoadNode.LLVMI32OffsetLoadNode;
import com.oracle.truffle.llvm.runtime.nodes.memory.load.LLVMI64LoadNode.LLVMI64OffsetLoadNode;
import com.oracle.truffle.llvm.runtime.nodes.memory.load.LLVMI8LoadNode.LLVMI8OffsetLoadNode;
import com.oracle.truffle.llvm.runtime.nodes.memory.load.LLVMPointerLoadNode.LLVMPointerOffsetLoadNode;
import com.oracle.truffle.llvm.runtime.nodes.memory.store.LLVM80BitFloatStoreNode.LLVM80BitFloatOffsetStoreNode;
import com.oracle.truffle.llvm.runtime.nodes.memory.store.LLVMI32StoreNode.LLVMI32OffsetStoreNode;
import com.oracle.truffle.llvm.runtime.nodes.memory.store.LLVMI64StoreNode.LLVMI64OffsetStoreNode;
import com.oracle.truffle.llvm.runtime.nodes.memory.store.LLVMPointerStoreNode.LLVMPointerOffsetStoreNode;
import com.oracle.truffle.llvm.runtime.pointer.LLVMManagedPointer;
import com.oracle.truffle.llvm.runtime.pointer.LLVMMaybeVaPointer;
import com.oracle.truffle.llvm.runtime.pointer.LLVMPointer;
import com.oracle.truffle.llvm.runtime.types.PointerType;
import com.oracle.truffle.llvm.runtime.types.PrimitiveType;
import com.oracle.truffle.llvm.runtime.types.Type;
import com.oracle.truffle.llvm.spi.NativeTypeLibrary;

@ExportLibrary(value = LLVMManagedReadLibrary.class, useForAOT = true, useForAOTPriority = 3)
@ExportLibrary(value = LLVMVaListLibrary.class, useForAOT = true, useForAOTPriority = 2)
@ExportLibrary(value = NativeTypeLibrary.class, useForAOT = true, useForAOTPriority = 1)
@ExportLibrary(InteropLibrary.class)
public final class LLVMDarwinAarch64VaListStorage extends LLVMVaListStorage {
    // va_list is an alias for char*
    public static final PointerType VA_LIST_TYPE = PointerType.I8;

    DarwinAArch64ArgsArea argsArea;
    private int consumedBytes;

    public LLVMDarwinAarch64VaListStorage() {
        super(null);
    }

    static final class DarwinAArch64ArgsArea extends ArgsArea {
        private final int numOfExpArgs;

        DarwinAArch64ArgsArea(Object[] args, int numOfExpArgs) {
            super(args);
            this.numOfExpArgs = numOfExpArgs;
        }

        @Override
        protected long offsetToIndex(long offset) {
            if (offset < 0) {
                return -1;
            }

            long argIndex = offset / Long.BYTES + numOfExpArgs;
            long argOffset = offset % Long.BYTES;
            return argIndex + (argOffset << Integer.SIZE);
        }
    }

    @ExportMessage
    static void initialize(LLVMDarwinAarch64VaListStorage self, Object[] realArguments, int numberOfExplicitArguments, Frame frame,
                    @Cached.Exclusive @Cached(parameters = "KEEP_32BIT_PRIMITIVES_IN_STRUCTS") ArgumentListExpander argsExpander,
                    @Cached.Exclusive @Cached StackAllocationNode stackAllocationNode) {
        Object[][][] expansionsOutArg = new Object[1][][];
        self.realArguments = argsExpander.expand(realArguments, expansionsOutArg);
        self.numberOfExplicitArguments = numberOfExplicitArguments;
        self.argsArea = new DarwinAArch64ArgsArea(self.realArguments, numberOfExplicitArguments);

        long stackSize = 0;
        for (int i = numberOfExplicitArguments; i < self.realArguments.length; i++) {
            Object o = self.realArguments[i];
            if (o instanceof LLVMVarArgCompoundValue) {
                stackSize += alignUp(((LLVMVarArgCompoundValue) o).getSize());
            } else {
                stackSize += Long.BYTES;
            }
        }
        self.vaListStackPtr = stackAllocationNode.executeWithTarget(stackSize, frame);
        self.nativized =