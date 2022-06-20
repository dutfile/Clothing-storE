/*
 * Copyright (c) 2022, 2022, Oracle and/or its affiliates.
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
package com.oracle.truffle.llvm.runtime.nodes.memory.move;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.llvm.runtime.NodeFactory;
import com.oracle.truffle.llvm.runtime.library.internal.LLVMAsForeignLibrary;
import com.oracle.truffle.llvm.runtime.library.internal.LLVMCopyTargetLibrary;
import com.oracle.truffle.llvm.runtime.memory.LLVMMemMoveNode;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMExpressionNode;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMLoadNode;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMNode;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMStatementNode;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMStoreNode;
import com.oracle.truffle.llvm.runtime.nodes.intrinsics.llvm.LLVMNoOpNodeGen;
import com.oracle.truffle.llvm.runtime.nodes.literals.LLVMSimpleLiteralNode;
import com.oracle.truffle.llvm.runtime.pointer.LLVMManagedPointer;
import com.oracle.truffle.llvm.runtime.pointer.LLVMNativePointer;
import com.oracle.truffle.llvm.runtime.pointer.LLVMPointer;
import com.oracle.truffle.llvm.runtime.types.PrimitiveType;
import com.oracle.truffle.llvm.runtime.types.Type;

import java.util.ArrayList;
import java.util.List;

public abstract class LLVMPrimitiveMoveNode extends LLVMNode {

    private static final int LENGTH_LIMIT_FOR_REPLACEMENT = 64;
    private static final int LENGTH_ARG_INDEX = 3;
    private static final int DEST_ARG_INDEX = 1;
    private static final int SOURCE_ARG_INDEX = 2;

    @Child private LLVMLoadNode loadNode;
    @Child private LLVMStoreNode storeNode;
    @Child private LLVMPrimitiveMoveNode nextPrimitiveMoveNode;
    private final long step;

    public LLVMPrimitiveMoveNode(LLVMLoadNode loadNode, LLVMStoreNode storeNode, LLVMPrimitiveMoveNode nextPrimitiveMoveNode, long step) {
        this.loadNode = loadNode;
        this.storeNode = storeNode;
        this.nextPrimitiveMoveNode = nextPrimitiveMoveNode;
        this.step = step;
    }

    public abstract void executeWithTarget(LLVMPointer srcPtr, LLVMPointer destPtr, boolean forwardCopy);

    @Specialization(guards = "forwardCopy")
    void moveNormalDir(LLVMPointer srcPtr, LLVMPointer destPtr, @SuppressWarnings("unused") boolean forwardCopy) {
        Object val = loadNode.executeWithTargetGeneric(srcPtr);
        storeNode.executeWithTarget(destPtr, val);
        if (nextPrimitiveMoveNode != null) {
            nextPrimitiveMoveNode.executeWithTarget(srcPtr.increment(step), destPtr.increment(step), forwardCopy);
        }
    }

    @Specialization(guards = "!forwardCopy")
    void moveReverseDir(LLVMPointer src, LLVMPointer dest, @SuppressWarnings("unused") boolean forwardCopy) {
        LLVMPointer srcPtr = src.increment(-step);
        Object val = loadNode.executeWithTargetGeneric(srcPtr);
        LLVMPointer destPtr = dest.increment(-step);
        storeNode.executeWithTarget(destPtr, val);
        if (nextPrimitiveMoveNode != null) {
            nextPrimitiveMoveNode.executeWithTarget(srcPtr, destPtr, forwardCopy);
        }
    }

    public static LLVMExpressionNode createSerialMoves(LLVMExpressionNode[] args, NodeFactory nodeFactory, LLVMMemMoveNode memMoveNode) {
        LLVMExpressionNode sourceNode = args[SOURCE_ARG_INDEX];
        LLVMExpressionNode destNode = args[DEST_ARG_INDEX];

        LLVMExpressionNode lengthArgNode = args[LENGTH_ARG_INDEX];
        if (lengthArgNode instanceof LLVMSimpleLiteralNode) {
            long len = Long.MAX_VALUE;
            if (lengthArgNode instanceof LLVMSimpleLiteralNode.LLVMI64LiteralNode) {
                len = ((LLVMSimpleLiteralNode.LLVMI64LiteralNode) lengthArgNode).doI64();
            } else if (lengthArgNode instanceof LLVMSimpleLiteralNode.LLVMI32LiteralNode) {
                len = ((LLVMSimpleLiteralNode.LLVMI32LiteralNode) lengthArgNode).doI32();
            }

            if (len <= 0) {
                return LLVMNoOpNodeGen.create();
            }

            if (len < LENGTH_LIMIT_FOR_REPLACEMENT) {
                List<Type> moveTypes = new ArrayList<>();

                long m = len;
                long n = m / 8;
                if (n > 0) {
                    for (int i = 0; i < n; i++) {
                        moveTypes.add(PrimitiveType.I64);
                    }
                }
                m = m % 8;
                n = m / 4;
                if (n > 0) {
                    for (int i = 0; i < n; i++) {
                        moveTypes.add(PrimitiveType.I32);
                    }
                }
                m = m % 4;
                n = m / 2;
                if (n > 0) {
                    for (int i = 0; i < n; i++) {
                        moveTypes.add(PrimitiveType.I16);
                    }
                }
                n = m % 2;
                if (n > 0) {
                    moveTypes.add(PrimitiveType.I8);
                }

                return createScalarMemMoveSeries(moveTypes, destNode, sourceNode, nodeFactory, len, memMoveNode);
            }
        }
        return null;
    }

    private static LLVMExpressionNode createScalarMemMoveSeries(List<Type> moveTypes, LLVMExpressionNode dest, LLVMExpressionNode source, NodeFactory nodeFactory, long length,
                    LLVMMemMoveNode memMoveNode) {
        assert !moveTypes.isEmpty();

        LLVMPrimitiveMoveNode primitiveMoveNode = null;
        for (Type memberType : moveTypes) {
            LLVMExpressionNode loadNode = nodeFactory.createExtractValue(memberType, null);
            assert loadNode instanceof LLVMLoadNode;
            LLVMStatementNode storeNode = nodeFactory.createStore(null, null, memberType);
            assert storeNode instanceof LLVMStoreNode;

            try {
                long step = nodeFactory.getDataLayout().getByteSize(memberType);
                primitiveMoveNode = LLVMPrimitiveMoveNodeGen.create((LLVMLoadNode) loadNode, (LLVMStoreNode) storeNode, primitiveMoveNode, step);
            } catch (Type.TypeOverflowException e) {
                throw Type.throwOverflowExceptionAsLLVMException(primitiveMoveNode, e);
            }
        }

        return LLVMPrimitiveMoveNodeGen.HeadNodeGen.create(length, primitiveMoveNode, memMoveNode, source, dest);
    }

    /**
     * The head of the {