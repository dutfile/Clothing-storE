/*
 * Copyright (c) 2017, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package com.oracle.truffle.espresso.nodes.helper;

import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Idempotent;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.espresso.EspressoLanguage;
import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.meta.JavaKind;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.nodes.BytecodeNode;
import com.oracle.truffle.espresso.nodes.EspressoFrame;
import com.oracle.truffle.espresso.nodes.EspressoNode;
import com.oracle.truffle.espresso.nodes.interop.ToEspressoNode;
import com.oracle.truffle.espresso.runtime.StaticObject;

public abstract class AbstractGetFieldNode extends EspressoNode {
    final Field field;

    final String fieldName;
    final int slotCount;
    static final int CACHED_LIBRARY_LIMIT = 3;

    AbstractGetFieldNode(Field field) {
        this.field = field;
        this.fieldName = getField().getNameAsString();
        this.slotCount = getField().getKind().getSlotCount();
    }

    Field getField() {
        return field;
    }

    public abstract int getField(VirtualFrame frame, BytecodeNode root, StaticObject receiver, int at, int statementIndex);

    public static AbstractGetFieldNode create(Field f) {
        // @formatter:off
        switch (f.getKind()) {
            case Boolean: return BooleanGetFieldNodeGen.create(f);
            case Byte:    return ByteGetFieldNodeGen.create(f);
            case Short:   return ShortGetFieldNodeGen.create(f);
            case Char:    return CharGetFieldNodeGen.create(f);
            case Int:     return IntGetFieldNodeGen.create(f);
            case Float:   return FloatGetFieldNodeGen.create(f);
            case Long:    return LongGetFieldNodeGen.create(f);
            case Double:  return DoubleGetFieldNodeGen.create(f);
            case Object:  return ObjectGetFieldNodeGen.create(f);
            default:
                throw EspressoError.shouldNotReachHere();
        }
        // @formatter:on
    }

    protected Object getForeignField(StaticObject receiver, InteropLibrary interopLibrary, EspressoLanguage language, Meta meta, BranchProfile error) {
        assert getField().getDeclaringKlass().isAssignableFrom(receiver.getKlass());
        assert !getField().isStatic();
        Object value;
        try {
            value = interopLibrary.readMember(receiver.rawForeignObject(language), fieldName);
        } catch (UnsupportedMessageException | UnknownIdentifierException e) {
            error.enter();
            throw meta.throwExceptionWithMessage(meta.java_lang_NoSuchFieldError, "Foreign object has no readable field %s", fieldName);
        }
        return value;
    }
}

abstract class IntGetFieldNode extends AbstractGetFieldNode {
    IntGetFieldNode(Field f) {
        super(f);
        assert f.getKind() == JavaKind.Int;
    }

    @Override
    public int getField(VirtualFrame frame, BytecodeNode root, StaticObject receiver, int at, int statementIndex) {
        root.notifyFieldAccess(frame, statementIndex, getField(), receiver);
        EspressoFrame.putInt(frame, at, executeGetField(receiver));
        return slotCount;
    }

    abstract int executeGetField(StaticObject receiver);

    @Specialization(guards = "receiver.isEspressoObject()")
    int doEspresso(StaticObject receiver) {
        return getField().getInt(receiver);
    }

    @Specialization(guards = {"receiver.isForeignObject()", "isValueField(meta)"})
    int doForeignValue(StaticObject receiver,
                    @Bind("getMeta()") Meta meta,
                    @CachedLibrary(limit = "CACHED_LIBRARY_LIMIT") InteropLibrary interopLibrary,
                    @Cached BranchProfile error) {
        try {
            return interopLibrary.asInt(receiver.rawForeignObject(getLanguage()));
        } catch (UnsupportedMessageException e) {
            error.enter();
            throw meta.throwExceptionWithMessage(meta.java_lang_ClassCastException, "Foreign object does not fit in int");
        }
    }

    @Specialization(guards = {"receiver.isForeignObject()", "!isValueField(meta)"}, limit = "CACHED_LIBRARY_LIMIT")
    int doForeign(StaticObject receiver,
                    @Bind("getLanguage()") EspressoLanguage language,
                    @Bind("getMeta()") Meta meta,
                    @CachedLibrary("receiver.rawForeignObject(language)") InteropLibrary interopLibrary,
                    @Cached ToEspressoNode toEspressoNode,
                    @Cached BranchProfile error) {
        Object value = getForeignField(receiver, interopLibrary, language, meta, error);
        try {
            return (int) toEspressoNode.execute(value, meta._int);
        } catch (UnsupportedTypeException e) {
            error.enter();
            throw meta.throwExceptionWithMessage(meta.java_lang_ClassCastException, "Foreign field %s cannot be cast to int", fieldName);
        }
    }

    @Idempotent
    boolean isValueField(Meta meta) {
        return getField() == meta.java_lang_Integer_value;
 