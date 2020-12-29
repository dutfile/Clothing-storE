/*
 * Copyright (c) 2016, 2021, Oracle and/or its affiliates.
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
package com.oracle.truffle.llvm.runtime.nodes.op;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMExpressionNode;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMNode;
import com.oracle.truffle.llvm.runtime.nodes.memory.LLVMNativePointerSupport;
import com.oracle.truffle.llvm.runtime.nodes.op.LLVMPointerCompareNodeGen.LLVMNegateNodeGen;
import com.oracle.truffle.llvm.runtime.nodes.op.ToComparableValue.ManagedToComparableValue;
import com.oracle.truffle.llvm.runtime.nodes.util.LLVMSameObjectNode;
import com.oracle.truffle.llvm.runtime.pointer.LLVMManagedPointer;
import com.oracle.truffle.llvm.runtime.pointer.LLVMNativePointer;

@NodeChild(type = LLVMExpressionNode.class)
@NodeChild(type = LLVMExpressionNode.class)
public abstract class LLVMPointerCompareNode extends LLVMAbstractCompareNode {
    private final NativePointerCompare op;

    public LLVMPointerCompareNode(NativePointerCompare op) {
        this.op = op;
    }

    // the first two cases are redundant but much more efficient than the ones below

    @Specialization
    boolean doCompare(long a, long b) {
        return op.compare(a, b);
    }

    @Specialization
    boolean doCompare(LLVMNativePointer a, LLVMNativePointer b) {
        return op.compare(a.asNative(), b.asNative());
    }

    @Specialization(guards = {"isPointerA.execute(a)", "isPointerB.execute(b)"}, rewriteOn = UnsupportedMessageException.class)
    protected boolean doPointerPointer(Object a, Object b,
                    @SuppressWarnings("unused") @Cached LLVMNativePointerSupport.IsPointerNode isPointerA,
                    @Cached LLVMNativePointerSupport.AsPointerNode asPointerA,
                    @SuppressWarnings("unused") @Cached LLVMNativePointerSupport.IsPointerNode isPointerB,
                    @Cached LLVMNativePointerSupport.AsPointerNode asPointerB) throws UnsupportedMessageException {
        return op.compare(asPointerA.execute(a), asPointerB.execute(b));
    }

    @Specialization(guards = {"isPointerA.execute(a)", "isPointerB.execute(b)"})
    protected boolean doPointerPointerException(Object a, Object b,
                    @SuppressWarnings("unused") @Cached LLVMNativePointerSupport.IsPointerNode isPointerA,
                    @Cached LLVMNativePointerSupport.AsPointerNode asPointerA,
                    @SuppressWarnings("unused") @Cached LLVMNativePointerSupport.IsPointerNode isPointerB,
                    @Cached LLVMNativePointerSupport.AsPointerNode asPointerB,
                    @Cached LLVMManagedCompareNode managedCompare) {
        try {
            return doPointerPointer(a, b, isPointerA, asPointerA, isPointerB, asPointerB);
        } catch (UnsupportedMessageException ex) {
            // even though both say isPointer == true, one of them threw an exception in asPointer
            // this is the same as if one of the objects has isPointer == false
            return doOther(a, b, isPointerA, asPointerA, isPointerB, asPointerB, managedCompare);
        }
    }

    @Specialization(guards = "!isPointerA.execute(a) || !isPointerB.execute(b)")
    protected boolean doOther(Object a, Object b,
                    @SuppressWarnings("unused") @Cached LLVMNativePointerSupport.IsPointerNode isPointerA,
                    @Cached LLVMNativePointerSupport.AsPointerNode asPointerA,
                    @SuppressWarnings("unused") @Cached LLVMNativePointerSupport.IsPointerNode isPointerB,
                    @Cached LLVMNativePointerSupport.AsPointerNode asPointerB,
                    @Cached LLVMManagedCompareNode managedCompare) {
        return managedCompare.execute(a, b, isPointerA, asPointerA, isPointerB, asPointerB, op);
    }

    public static LLVMAbstractCompareNode create(Kind kind, LLVMExpressionNode l, LLVMExpressionNode r) {
        switch (kind) {
            case SLT:
                return LLVMPointerCompareNodeGen.create(new NativePointerCompare() {

                    @Override
                    public boolean compare(long a, long b) {
                        return a < b;
                    }
                }, l, r);
            case SLE:
                return LLVMPointerCompareNodeGen.create(new NativePointerCompare() {

                    @Override
                    public boolean compare(long a, long b) {
                        return a <= b;
                    }
                }, l, r);
            case ULE:
                return LLVMPointerCompareNodeGen.create(new NativePointerCompare() {

                    @Override
                    public boolean compare(long a, long b) {
                        return Long.compareUnsigned(a, b) <= 0;
                    }
                }, l, r);
            case ULT:
                return LLVMPointerCompareNodeGen.create(new NativePointerCompare() {

                    @Override
                    public boolean compare(long a, long b) {
                        return Long.compareUnsigned(a, b) < 0;
                    }
                }, l, r);
            case EQ:
                return LLVMAddressEqualsNodeGen.create(l, r);
            case NEQ:
                return LLVMNegateNode.create(LLVMAddressEqualsNodeGen.create(l, r));
            default:
                throw new AssertionError();

        }
    }

    public enum Kind {
        ULT,
        ULE,
        SLT,
        SLE,
        EQ,
        NEQ,
    }

    protected abstract static class NativePointerCompare {
        abstract boolean compare(long a, long b);
    }

    /**
     * Whenever we used {@link ManagedToComparableValue} in this class, we only convert the managed
     * object to a long value and ignore the pointer offset (including the offset would increase the
     * probability of collisions). However, we can only ignore the offset safely when we know that
     * both pointers do not point to the same object.
     */
    abstract static class LLVMManagedCompareNode extends LLVMNode {
        private static final long TYPICAL_POINTER = 0x00007f0000000000L;

        abstract boolean execute(Object a, Object b, LLVMNativePointerSupport.IsPointerNode isPointerA, LLVMNativePointerSupport.AsPointerNode asPointerA,
                        LLVMNativePointerSupport.IsPointerNode isPointerB, LLVMNativePointerSupport.AsPointerNode asPointerB, NativePointerCompare op);

        @Specialization(guards = {"pointToSameObject.execute(a.getObject(), b.getObject())"})
        protected boolean doForeignSameObject(LLVMManagedPointer a, LLVMManagedPointer b, @SuppressWarnings("unused") LLVMNativePointerSupport.IsPointerNode isPointerA,
                        @SuppressWarnings("unused") LLVMNativePointerSupport.AsPointerNode asPointerA, @SuppressWarnings("unused") LLVMNativePointerSupport.IsPointerNode isPointerB,
                        @SuppressWarnings("unused") LLVMNativePointerSupport.AsPointerNode asPointerB, NativePointerCompare op,
                        @SuppressWarnings("unused") @Cached LLVMSameObjectNode pointToSameObject) {
            // when comparing pointers to the same object, it is not sufficient to simply compare
