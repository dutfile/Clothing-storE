/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.dsl.processor.generator;

import java.util.List;

import javax.lang.model.type.TypeMirror;

import com.oracle.truffle.dsl.processor.ProcessorContext;
import com.oracle.truffle.dsl.processor.generator.FlatNodeGenFactory.FrameState;
import com.oracle.truffle.dsl.processor.generator.FlatNodeGenFactory.LocalVariable;
import com.oracle.truffle.dsl.processor.java.model.CodeTree;
import com.oracle.truffle.dsl.processor.java.model.CodeTreeBuilder;

final class BitSet {

    private final BitStateList states;
    private final String name;
    private final long allMask;
    private final TypeMirror type;

    BitSet(String name, BitStateList states) {
        this.name = name;
        this.states = states;
        int bitCount = states.getBitCount();
        if (bitCount <= 32) {
            type = ProcessorContext.getInstance().getType(int.class);
        } else if (bitCount <= 64) {
            type = ProcessorContext.getInstance().getType(long.class);
        } else {
            throw new UnsupportedOperationException("State space too big " + bitCount + ". Only <= 64 supported.");
        }
        this.allMask = createMask(StateQuery.create(null, getStates().queryKeys(null)));
    }

    public BitStateList getStates() {
        return states;
    }

    public int getBitCount() {
        return states.getBitCount();
    }

    public TypeMirror getType() {
        return type;
    }

    public boolean contains(StateQuery element) {
        return getStates().contains(element);
    }

    public boolean contains(StateQuery... element) {
        for (StateQuery stateQuery : element) {
            if (getStates().contains(stateQuery)) {
                return true;
            }
        }
        return false;
    }

    private CodeTree createLocalReference(FrameState frameState) {
        LocalVariable var = frameState != null ? frameState.get(getName()) : null;
        if (var != null) {
            return var.createReference();
        } else {
            return null;
        }
    }

    public boolean hasLocal(FrameState frameState) {
        return frameState.get(getName()) != null;
    }

    public CodeTree createReference(FrameState frameState) {
        CodeTree ref = createLocalReference(frameState);
        if (ref == null) {
            CodeTreeBuilder builder = CodeTreeBuilder.createBuilder();
            builder.string(getName(), "_");
            ref = FlatNodeGenFactory.createInlinedAccess(frameState, null, builder.build(), null);
        }
        return ref;
    }

    /**
     * Filters passed elements to return only elements contained in this set.
     */
    public StateQuery filter(StateQuery elements) {
        return getStates().filter(elements);
    }

    public CodeTree createLoad(FrameState frameState) {
        return createLoad(frameState, false);
    }

    public CodeTree createLoad(FrameState frameState, boolean forceLoad) {
        LocalVariable var = frameState.get(name);
        if (!forceLoad && var != null) {
            // already loaded
            return CodeTreeBuilder.singleString("");
        }

        String fieldName = name + "_";
        CodeTreeBuilder builder = CodeTreeBuilder.createBuilder();
        CodeTreeBuilder init = builder.create();
        init.string("this.").tree(CodeTreeBuilder.singleString(fieldName));

        CodeTree inlinedAccess = FlatNodeGenFactory.createInlinedAccess(frameState, null, init.build(), null);

        if (var == null) {
            var = new LocalVariable(type, name, null);
            frameState.set(name, var);
            builder.tree(var.createDeclaration(inlinedAccess));
        } else {
            builder.startStatement();
            builder.string(name).string(" = ").tree(inlinedAccess);
            builder.end();
        }

        return builder.build();
    }

    public void clearLoaded(FrameState frameState) {
        frameState.clear(name);
    }

    public CodeTree createContainsOnly(FrameState frameState, int offset, int length, StateQuery selectedElements, StateQuery allElements) {
        long mask = ~createMask(offset, length, selectedElements) & createMask(allElements);
        if (mask == 0) {
            return null;
        }
        CodeTreeBuilder builder = CodeTreeBuilder.createBuilder();
        builder.tree(createMaskedReference(frameState, mask));
        builder.string(" == 0");
        builder.string(" /* only-active ", getStates().toString(selectedElements, " && "), " */");
        return builder.build();
    }

    public CodeTree createIs(FrameState frameState, StateQuery selectedElements, StateQuery maskedElements) {
        CodeTreeBuilder builder = CodeTreeBuilder.createBuilder();
        builder.tree(createMaskedReference(frameState, maskedElements));
        builder.string(" == ").string(formatMask(createMask(selectedElements)));
        return builder.build();
    }

    private CodeTree createMaskedReference(CodeTree receiver, long maskedElements) {
        if (maskedElements == this.allMask) {
            // no masking needed
            return receiver;
        } else {
            CodeTreeBuilder builder = CodeTreeBuilder.createBuilder();
            // masking needed we use the state bitset for guards as well
            builder.string("(").tree(receiver).string(" & ").string(formatMask(maskedElements)).string(")");
            return builder.build();
        }
    }

    private CodeTree createMaskedReference(FrameState frameState, long maskedElements) {
        return createMaskedReference(createReference(frameState), maskedElements);
    }

    public CodeTree createMaskedReference(CodeTree receiver, StateQuery... maskedElements) {
        return createMaskedReference(receiver, createMask(maskedElements));
    }

    public CodeTree createMaskedReference(FrameState frameState, StateQuery... maskedElements) {
        return createMaskedReference(frameState, createMask(maskedElements));
    }

    public CodeTree createIsNotAny(FrameState frameState, StateQuery elements) {
        CodeTreeBuilder builder = CodeTreeBuilder.createBuilder();
        builder.tree(createMaskedReference(frameState, elements));
        builder.string(" != 0 ");
        builder.string(" /* is-not ", getS