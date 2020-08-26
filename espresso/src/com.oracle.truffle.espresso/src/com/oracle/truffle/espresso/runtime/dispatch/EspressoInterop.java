/*
 * Copyright (c) 2020, 2023, Oracle and/or its affiliates. All rights reserved.
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

package com.oracle.truffle.espresso.runtime.dispatch;

import static com.oracle.truffle.espresso.impl.Klass.STATIC_TO_CLASS;
import static com.oracle.truffle.espresso.runtime.InteropUtils.isAtMostByte;
import static com.oracle.truffle.espresso.runtime.InteropUtils.isAtMostFloat;
import static com.oracle.truffle.espresso.runtime.InteropUtils.isAtMostInt;
import static com.oracle.truffle.espresso.runtime.InteropUtils.isAtMostLong;
import static com.oracle.truffle.espresso.runtime.InteropUtils.isAtMostShort;
import static com.oracle.truffle.espresso.runtime.InteropUtils.isNegativeZero;
import static com.oracle.truffle.espresso.runtime.StaticObject.CLASS_TO_STATIC;
import static com.oracle.truffle.espresso.runtime.StaticObject.notNull;
import static com.oracle.truffle.espresso.vm.InterpreterToVM.instanceOf;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Exclusive;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.espresso.EspressoLanguage;
import com.oracle.truffle.espresso.impl.ArrayKlass;
import com.oracle.truffle.espresso.impl.EmptyKeysArray;
import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.impl.KeysArray;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.nodes.interop.CandidateMethodWithArgs;
import com.oracle.truffle.espresso.nodes.interop.InvokeEspressoNode;
import com.oracle.truffle.espresso.nodes.interop.LookupInstanceFieldNode;
import com.oracle.truffle.espresso.nodes.interop.LookupVirtualMethodNode;
import com.oracle.truffle.espresso.nodes.interop.MethodArgsUtils;
import com.oracle.truffle.espresso.nodes.interop.OverLoadedMethodSelectorNode;
import com.oracle.truffle.espresso.nodes.interop.ToEspressoNode;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.EspressoException;
import com.oracle.truffle.espresso.runtime.EspressoFunction;
import com.oracle.truffle.espresso.runtime.InteropUtils;
import com.oracle.truffle.espresso.runtime.StaticObject;

/**
 * BaseInterop (isNull, is/asString, meta-instance, identity, exceptions, toDisplayString) Support
 * Espresso and foreign objects and null.
 */
@ExportLibrary(value = InteropLibrary.class, receiverType = StaticObject.class)
@SuppressWarnings("truffle-abstract-export") // TODO GR-44080 Adopt BigInteger Interop
public class EspressoInterop extends BaseInterop {
    // region ### is/as checks/conversions

    static final Object[] EMPTY_ARGS = new Object[]{};

    public static Meta getMeta() {
        CompilerAsserts.neverPartOfCompilation();
        return EspressoContext.get(null).getMeta();
    }

    @ExportMessage
    static boolean isBoolean(StaticObject receiver) {
        if (receiver.isForeignObject()) {
            return false;
        }
        if (isNull(receiver)) {
            return false;
        }
        return receiver.getKlass() == receiver.getKlass().getMeta().java_lang_Boolean;
    }

    @ExportMessage
    static boolean asBoolean(StaticObject receiver) throws UnsupportedMessageException {
        receiver.checkNotForeign();
        if (!isBoolean(receiver)) {
            throw UnsupportedMessageException.create();
        }
        return (boolean) receiver.getKlass().getMeta().java_lang_Boolean_value.get(receiver);
    }

    @ExportMessage
    static boolean isNumber(StaticObject receiver) {
        if (receiver.isForeignObject()) {
            return false;
        }
        if (isNull(receiver)) {
            return false;
        }
        Meta meta = receiver.getKlass().getMeta();
        return receiver.getKlass() == meta.java_lang_Byte || receiver.getKlass() == meta.java_lang_Short || receiver.getKlass() == meta.java_lang_Integer ||
                        receiver.getKlass() == meta.java_lang_Long || receiver.getKlass() == meta.java_lang_Float ||
                        receiver.getKlass() == meta.java_lang_Double;
    }

    @ExportMessage
    static boolean fitsInByte(StaticObject receiver) {
        receiver.checkNotForeign();
        if (isNull(receiver)) {
            return false;
        }
        Klass klass = receiver.getKlass();
        if (isAtMostByte(klass)) {
            return true;
        }

        Meta meta = klass.getMeta();
        if (klass == meta.java_lang_Short) {
            short content = meta.java_lang_Short_value.getShort(receiver);
            return (byte) content == content;
        }
        if (klass == meta.java_lang_Integer) {
            int content = meta.java_lang_Integer_value.getInt(receiver);
            return (byte) content == content;
        }
        if (klass == meta.java_lang_Long) {
            long content = meta.java_lang_Long_value.getLong(receiver);
            return (byte) content == content;
        }
        if (klass == meta.java_lang_Float) {
            float content = meta.java_lang_Float_value.getFloat(receiver);
            return (byte) content == content && !isNegativeZero(content);
        }
        if (klass == meta.java_lang_Double) {
            double content = meta.java_lang_Double_value.getDouble(receiver);
            return (byte) content == content && !isNegativeZero(content);
        }
        return false;
    }

    @ExportMessage
    static boolean fitsInShort(StaticObject receiver) {
        receiver.checkNotForeign();
        if (isNull(receiver)) {
            return false;
        }
        Klass klass = receiver.getKlass();
        if (isAtMostShort(klass)) {
            return true;
        }

        Meta meta = klass.getMeta();
        if (klass == meta.java_lang_Integer) {
            int content = meta.java_lang_Integer_value.getInt(receiver);
            return (short) content == content;
        }
        if (klass == meta.java_lang_Long) {
            long content = meta.java_lang_Long_value.getLong(receiver);
            return (short) content == content;
        }
        if (klass == meta.java_lang_Float) {
            float content = meta.java_lang_Float_value.getFloat(receiver);
            return (short) content == content && !isNegativeZero(content);
        }
        if (klass == meta.java_lang_Double) {
            double content = meta.java_lang_Double_value.getDouble(receiver);
            return (short) content == content && !isNegativeZero(content);
        }
        return false;
    }

    @ExportMessage
    static boolean fitsInInt(StaticObject receiver) {
        receiver.checkNotForeign();
        if (isNull(receiver)) {
            return false;
        }
        Klass klass = receiver.getKlass();
        if (isAtMostInt(klass)) {
            return true;
        }

        Meta meta = klass.getMeta();
        if (klass == meta.java_lang_Long) {
            long content = meta.java_lang_Long_value.getLong(receiver);
            return (int) content == content;
        }
        if (klass == meta.java_lang_Float) {
            float content = meta.java_lang_Float_value.getFloat(receiver);
            return !isNegativeZero(content) && (int) content == content && (int) content != Integer.MAX_VALUE;
        }
        if (klass == meta.java_lang_Double) {
            double content = meta.java_lang_Double_value.getDouble(receiver);
            return (int) content == content && !isNegativeZero(content);
        }
        return false;
    }

    @ExportMessage
    static boolean fitsInLong(StaticObject receiver) {
        receiver.checkNotForeign();
        if (isNull(receiver)) {
            return false;
        }
        Klass klass = receiver.getKlass();
        if (isAtMostLong(klass)) {
            return true;
        }

        Meta meta = klass.getMeta();
        if (klass == meta.java_lang_Float) {
            float content = meta.java_lang_Float_value.getFloat(receiver);
            return !isNegativeZero(content) && (long) content == content && (long) content != Long.MAX_VALUE;
        }
        if (klass == meta.java_lang_Double) {
            double content = meta.java_lang_Double_value.getDouble(receiver);
            return !isNegativeZero(content) && (long) content == content && (long) content != Long.MAX_VALUE;
        }
        return false;
    }

    @ExportMessage
    static boolean fitsInFloat(StaticObject receiver) {
        receiver.checkNotForeign();
        if (isNull(receiver)) {
            return false;
        }
        Klass klass = receiver.getKlass();
        if (isAtMostFloat(klass)) {
            return true;
        }

        Meta meta = klass.getMeta();
        /*
         * We might lose precision when we convert an int or a long to a float, however, we still
         * perform the conversion. This is consistent with Truffle interop, see GR-22718 for more
         * details.
         */
        if (klass == meta.java_lang_Integer) {
            int content = meta.java_lang_Integer_value.getInt(receiver);
            float floatContent = content;
            return content != Integer.MAX_VALUE && (int) floatContent == content;
        }
        if (klass == meta.java_lang_Long) {
            long content = meta.java_lang_Long_value.getLong(receiver);
            float floatContent = content;
            return content != Long.MAX_VALUE && (long) floatContent == content;
        }
        if (klass == meta.java_lang_Double) {
            double content = meta.java_lang_Double_value.getDouble(receiver);
            return !Double.isFinite(content) || (float) content == content;
        }
        return false;
    }

    @ExportMessage
    static boolean fitsInDouble(StaticObject receiver) {
        receiver.checkNotForeign();
        if (isNull(receiver)) {
            return false;
        }
        Klass klass = receiver.getKlass();
        Meta meta = klass.getMeta();
        if (isAtMostInt(klass) || klass == meta.java_lang_Double) {
            return true;
        }
        if (klass == meta.java_lang_Long) {
            long content = meta.java_lang_Long_value.getLong(receiver);
            double doubleContent = content;
            return content != Long.MAX_VALUE && (long) doubleContent == content;
        }
        if (klass == meta.java_lang_Float) {
            float content = meta.java_lang_Float_value.getFloat(receiver);
            return !Float.isFinite(content) || (double) content == content;
        }
        return false;
    }

    private static Number readNumberValue(StaticObject receiver) throws UnsupportedMessageException {
        assert receiver.isEspressoObject();
        Klass klass = receiver.getKlass();
        Meta meta = klass.getMeta();
        if (klass == meta.java_lang_Byte) {
            return (Byte) meta.java_lang_Byte_value.get(receiver);
        }
        if (klass == meta.java_lang_Short) {
            return (Short) meta.java_lang_Short_value.get(receiver);
        }
        if (klass == meta.java_lang_Integer) {
            return (Integer) meta.java_lang_Integer_value.get(receiver);
        }
        if (klass == meta.java_lang_Long) {
            return (Long) meta.java_lang_Long_value.get(receiver);
        }
        if (klass == meta.java_lang_Float) {
            return (Float) meta.java_lang_Float_value.get(receiver);
        }
        if (klass == meta.java_lang_Double) {
            return (Double) meta.java_lang_Double_value.get(receiver);
        }
        CompilerDirectives.transferToInterpreter();
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
    static byte asByte(StaticObject receiver) throws UnsupportedMessageException {
        receiver.checkNotForeign();
        if (!fitsInByte(receiver)) {
            CompilerDirectives.transferToInterpreter();
            throw UnsupportedMessageException.create();
        }
        return readNumberValue(receiver).byteValue();
    }

    @ExportMessage
    static short asShort(StaticObject receiver) throws UnsupportedMessageException {
        receiver.checkNotForeign();
        if (!fitsInShort(receiver)) {
            CompilerDirectives.transferToInterpreter();
            throw UnsupportedMessageException.create();
        }
        return readNumberValue(receiver).shortValue();
    }

    @ExportMessage
    static int asInt(StaticObject receiver) throws UnsupportedMessageException {
        receiver.checkNotForeign();
        if (!fitsInInt(receiver)) {
            CompilerDirectives.transferToInterpreter();
            throw UnsupportedMessageException.create();
        }
        return readNumberValue(receiver).intValue();
    }

    @ExportMessage
    static long asLong(StaticObject receiver) throws UnsupportedMessageException {
        receiver.checkNotForeign();
        if (!fitsInLong(receiver)) {
            CompilerDirectives.transferToInterpreter();
            throw UnsupportedMessageException.create();
        }
        return readNumberValue(receiver).longValue();
    }

    @ExportMessage
    static float asFloat(StaticObject receiver) throws UnsupportedMessageException {
        receiver.checkNotForeign();
        if (!fitsInFloat(receiver)) {
            CompilerDirectives.transferToInterpreter();
            throw UnsupportedMessageException.create();
        }
        return readNumberValue(receiver).floatValue();
    }

    @ExportMessage
    static double asDouble(StaticObject receiver) throws UnsupportedMessageException {
        receiver.checkNotForeign();
        if (!fitsInDouble(receiver)) {
            CompilerDirectives.transferToInterpreter();
            throw UnsupportedMessageException.create();
        }
        return readNumberValue(receiver).doubleValue();
    }

    // endregion ### is/as checks/conversions

    // region ### Arrays

    @ExportMessage
    static long getArraySize(StaticObject receiver,
                    @CachedLibrary("receiver") InteropLibrary receiverLib,
                    @Shared("error") @Cached BranchProfile error) throws UnsupportedMessageException {
        receiver.checkNotForeign();
        if (!receiver.isArray()) {
            error.enter();
            throw UnsupportedMessageException.create();
        }
        return receiver.length(EspressoLanguage.get(receiverLib));
    }

    @ExportMessage
    static boolean hasArrayElements(StaticObject receiver) {
        if (receiver.isForeignObject()) {
            return false;
        }
        return receiver.isArray();
    }

    @ExportMessage
    abstract static class ReadArrayElement {
        @Specialization(guards = {"isBooleanArray(receiver)", "receiver.isEspressoObject()"})
        static boolean doBoolean(StaticObject receiver, long index,
                        @CachedLibrary("receiver") InteropLibrary receiverLib,
                        @Shared("error") @Cached BranchProfile error) throws InvalidArrayIndexException {
            EspressoLanguage language = EspressoLanguage.get(receiverLib);
            if (index < 0 || receiver.length(language) <= index) {
                error.enter();
                throw InvalidArrayIndexException.create(index);
            }
            return receiver.<byte[]> unwrap(language)[(int) index] != 0;
        }

        @Specialization(guards = {"isCharArray(receiver)", "receiver.isEspressoObject()"})
        static char doChar(StaticObject receiver, long index,
                        @CachedLibrary("receiver") InteropLibrary receiverLib,
                        @Shared("error") @Cached BranchProfile error) throws InvalidArrayIndexException {
            EspressoLanguage language = EspressoLanguage.get(receiverLib);
            if (index < 0 || receiver.length(language) <= index) {
                error.enter();
                throw InvalidArrayIndexException.create(index);
            }
            return receiver.<char[]> unwrap(language)[(int) index];
        }

        @Specialization(guards = {"isByteArray(receiver)", "receiver.isEspressoObject()"})
        static byte doByte(StaticObject receiver, long index,
                        @CachedLibrary("receiver") InteropLibrary receiverLib,
                        @Shared("error") @Cached BranchProfile error) throws InvalidArrayIndexException {
            EspressoLanguage language = EspressoLanguage.get(receiverLib);
            if (index < 0 || receiver.length(language) <= index) {
                error.enter();
                throw InvalidArrayIndexException.create(index);
            }
            return receiver.<byte[]> unwrap(language)[(int) index];
        }

        @Specialization(guards = {"isShortArray(receiver)", "receiver.isEspressoObject()"})
        static short doShort(StaticObject receiver, long index,
                        @CachedLibrary("receiver") InteropLibrary receiverLib,
                        @Shared("error") @Cached BranchProfile error) throws InvalidArrayIndexException {
            EspressoLanguage language = EspressoLanguage.get(receiverLib);
            if (index < 0 || receiver.length(language) <= index) {
                error.enter();
                throw InvalidArrayIndexException.create(index);
            }
            return receiver.<short[]> unwrap(language)[(int) index];
        }

        @Specialization(guards = {"isIntArray(receiver)", "receiver.isEspressoObject()"})
        static int doInt(StaticObject receiver, long index,
                        @CachedLibrary("receiver") InteropLibrary receiverLib,
                        @Shared("error") @Cached BranchProfile error) throws InvalidArrayIndexException {
            EspressoLanguage language = EspressoLanguage.get(receiverLib);
            if (index < 0 || receiver.length(language) <= index) {
                error.enter();
                throw InvalidArrayIndexException.create(index);
            }
            return receiver.<int[]> unwrap(language)[(int) index];
        }

        @Specialization(guards = {"isLongArray(receiver)", "receiver.isEspressoObject()"})
        static long doLong(StaticObject receiver, long index,
                        @CachedLibrary("receiver") InteropLibrary receiverLib,
                        @Shared("error") @Cached BranchProfile error) throws InvalidArrayIndexException {
            EspressoLanguage language = EspressoLanguage.get(receiverLib);
            if (index < 0 || receiver.length(language) <= index) {
                error.enter();
                throw InvalidArrayIndexException.create(index);
            }
            return receiver.<long[]> unwrap(language)[(int) index];
        }

        @Specialization(guards = {"isFloatArray(receiver)", "receiver.isEspressoObject()"})
        static float doFloat(StaticObject receiver, long index,
                        @CachedLibrary("receiver") InteropLibrary receiverLib,
                        @Shared("error") @Cached BranchProfile error) throws InvalidArrayIndexException {
            EspressoLanguage language = EspressoLanguage.get(receiverLib);
            if (index < 0 || receiver.length(language) <= index) {
                error.enter();
                throw InvalidArrayIndexException.create(index);
            }
            return receiver.<float[]> unwrap(language)[(int) index];
        }

        @Specialization(guards = {"isDoubleArray(receiver)", "receiver.isEspressoObject()"})
        static double doDouble(StaticObject receiver, long index,
                        @CachedLibrary("receiver") InteropLibrary receiverLib,
                        @Shared("error") @Cached BranchProfile error) throws InvalidArrayIndexException {
            EspressoLanguage language = EspressoLanguage.get(receiverLib);
            if (index < 0 || receiver.length(language) <= index) {
                error.enter();
                throw InvalidArrayIndexException.create(index);
            }
            return receiver.<double[]> unwrap(language)[(int) index];
        }

        @Specialization(guards = {"receiver.isArray()", "receiver.isEspressoObject()", "!isPrimitiveArray(receiver)"})
        static Object doObject(StaticObject receiver, long index,
                        @CachedLibrary("receiver") InteropLibrary receiverLib,
                        @Shared("error") @Cached BranchProfile error) throws InvalidArrayIndexException {
            EspressoLanguage language = EspressoLanguage.get(receiverLib);
            if (index < 0 || receiver.length(language) <= index) {
                error.enter();
                throw InvalidArrayIndexException.create(index);
            }
            return receiver.<StaticObject[]> unwrap(language)[(int) index];
        }

        @SuppressWarnings("unused")
        @Fallback
        static Object doOther(StaticObject receiver, long index) throws UnsupportedMessageException {
            throw UnsupportedMessageException.create();
        }
    }

    @ExportMessage
    abstract static class WriteArrayElement {
        @Specialization(guards = {"isBooleanArray(receiver)", "receiver.isEspressoObject()"})
        static void doBoolean(StaticObject receiver, long index, Object value,
                        @CachedLibrary("receiver") InteropLibrary receiverLib,
                        @CachedLibrary(limit = "3") InteropLibrary valueLib, // GR-37680
                        @Shared("error") @Cached BranchProfile error) throws InvalidArrayIndexException, UnsupportedTypeException {
            EspressoLanguage language = EspressoLanguage.get(receiverLib);
            if (index < 0 || receiver.length(language) <= index) {
                error.enter();
                throw InvalidArrayIndexException.create(index);
            }
            boolean boolValue;
            try {
                boolValue = valueLib.asBoolean(value);
            } catch (UnsupportedMessageException e) {
                error.enter();
                throw UnsupportedTypeException.create(new Object[]{value}, getMessageBoundary(e));
            }
            receiver.<byte[]> unwrap(language)[(int) index] = boolValue ? (byte) 1 : (byte) 0;
        }

        @Specialization(guards = {"isCharArray(receiver)", "receiver.isEspressoObject()"})
        static void doChar(StaticObject receiver, long index, Object value,
                        @CachedLibrary("receiver") InteropLibrary receiverLib,
                        @CachedLibrary(limit = "3") InteropLibrary valueLib, // GR-37680
                        @Shared("error") @Cached BranchProfile error) throws InvalidArrayIndexException, UnsupportedTypeException {
            EspressoLanguage language = EspressoLanguage.get(receiverLib);
            if (index < 0 || receiver.length(language) <= index) {
                error.enter();
                throw InvalidArrayIndexException.create(index);
            }
            char charValue;
            try {
                String s = valueLib.asString(value);
                if (s.length() != 1) {
                    error.enter();
                    String message = EspressoError.format("Expected a string of length 1 as an element of char array, got %s", s);
                    throw UnsupportedTypeException.create(new Object[]{value}, message);
                }
                charValue = s.charAt(0);
            } catch (UnsupportedMessageException e) {
                error.enter();
                throw UnsupportedTypeException.create(new Object[]{value}, getMessageBoundary(e));
            }
            receiver.<char[]> unwrap(language)[(int) index] = charValue;
        }

        @Specialization(guards = {"isByteArray(receiver)", "receiver.isEspressoObject()"})
        static void doByte(StaticObject receiver, long index, Object value,
                        @CachedLibrary("receiver") InteropLibrary receiverLib,
                        @CachedLibrary(limit = "3") InteropLibrary valueLib, // GR-37680
                        @Shared("error") @Cached BranchProfile error) throws InvalidArrayIndexException, UnsupportedTypeException {
            EspressoLanguage language = EspressoLanguage.get(receiverLib);
            if (index < 0 || receiver.length(language) <= index) {
                error.enter();
                throw InvalidArrayIndexException.create(index);
            }
            byte byteValue;
            try {
                byteValue = valueLib.asByte(value);
            } catch (UnsupportedMessageException e) {
                error.enter();
                throw UnsupportedTypeException.create(new Object[]{value}, getMessageBoundary(e));
            }
            receiver.<byte[]> unwrap(language)[(int) index] = byteValue;
        }

        @Specialization(guards = {"isShortArray(receiver)", "receiver.isEspressoObject()"})
        static void doShort(StaticObject receiver, long index, Object value,
                        @CachedLibrary("receiver") InteropLibrary receiverLib,
                        @CachedLibrary(limit = "3") InteropLibrary valueLib, // GR-37680
                        @Shared("error") @Cached BranchProfile error) throws InvalidArrayIndexException, UnsupportedTypeException {
            EspressoLanguage language = EspressoLanguage.get(receiverLib);
            if (index < 0 || receiver.length(language) <= index) {
                error.enter();
                throw InvalidArrayIndexException.create(index);
            }
            short shortValue;
            try {
                shortValue = valueLib.asShort(value);
            } catch (UnsupportedMessageException e) {
                error.enter();
                throw UnsupportedTypeException.create(new Object[]{value}, getMessageBoundary(e));
            }
            receiver.<short[]> unwrap(language)[(int) index] = shortValue;
        }

        @Specialization(guards = {"isIntArray(receiver)", "receiver.isEspressoObject()"})
        static void doInt(StaticObject receiver, long index, Object value,
                        @CachedLibrary("receiver") InteropLibrary receiverLib,
                        @CachedLibrary(limit = "3") InteropLibrary valueLib, // GR-37680
                        @Shared("error") @Cached BranchProfile error) throws InvalidArrayIndexException, UnsupportedTypeException {
            EspressoLanguage language = EspressoLanguage.get(receiverLib);
            if (index < 0 || receiver.length(language) <= index) {
                error.enter();
                throw InvalidArrayIndexException.create(index);
            }
            int intValue;
            try {
                intValue = valueLib.asInt(value);
            } catch (UnsupportedMessageException e) {
                error.enter();
                throw UnsupportedTypeException.create(new Object[]{value}, getMessageBoundary(e));
            }
            receiver.<int[]> unwrap(language)[(int) index] = intValue;
        }

        @Specialization(guards = {"isLongArray(receiver)", "receiver.isEspressoObject()"})
        static void doLong(StaticObject receiver, long index, Object value,
                        @CachedLibrary("receiver") InteropLibrary receiverLib,
                        @CachedLibrary(limit = "3") InteropLibrary valueLib, // GR-37680
                        @Shared("error") @Cached BranchProfile error) throws InvalidArrayIndexException, UnsupportedTypeException {
            EspressoLanguage language = EspressoLanguage.get(receiverLib);
            if (index < 0 || receiver.length(language) <= index) {
                error.enter();
                throw InvalidArrayIndexException.create(index);
            }
            long longValue;
            try {
                longValue = valueLib.asLong(value);
            } catch (UnsupportedMessageException e) {
                error.enter();
                throw UnsupportedTypeException.create(new Object[]{value}, getMessageBoundary(e));
            }
            receiver.<long[]> unwrap(language)[(int) index] = longValue;
        }

        @Specialization(guards = {"isFloatArray(receiver)", "receiver.isEspressoObject()"})
        static void doFloat(StaticObject receiver, long index, Object value,
                        @CachedLibrary("receiver") InteropLibrary receiverLib,
                        @CachedLibrary(limit = "3") InteropLibrary valueLib, // GR-37680
                        @Shared("error") @Cached BranchProfile error) throws InvalidArrayIndexException, UnsupportedTypeException {
            EspressoLanguage language = EspressoLanguage.get(receiverLib);
            if (index < 0 || receiver.length(language) <= index) {
                error.enter();
                throw InvalidArrayIndexException.create(index);
            }
            float floatValue;
            try {
                floatValue = valueLib.asFloat(value);
            } catch (UnsupportedMessageException e) {
                error.enter();
                throw UnsupportedTypeException.create(new Object[]{value}, getMessageBoundary(e));
            }
            receiver.<float[]> unwrap(language)[(int) index] = floatValue;
        }

        @Specialization(guards = {"isDoubleArray(receiver)", "receiver.isEspressoObject()"})
        static void doDouble(StaticObject receiver, long index, Object value,
                        @CachedLibrary("receiver") InteropLibrary receiverLib,
                        @CachedLibrary(limit = "3") InteropLibrary valueLib, // GR-37680
                        @Shared("error") @Cached BranchProfile error) throws InvalidArrayIndexException, UnsupportedTypeException {
            EspressoLanguage language = EspressoLanguage.get(receiverLib);
            if (index < 0 || receiver.length(language) <= index) {
                error.enter();
                throw InvalidArrayIndexException.create(index);
            }
            double doubleValue;
            try {
                doubleValue = valueLib.asDouble(value);
            } catch (UnsupportedMessageException e) {
                error.enter();
                throw UnsupportedTypeException.create(new Object[]{value}, getMessageBoundary(e));
            }
            receiver.<double[]> unwrap(language)[(int) index] = doubleValue;
        }

        @Specialization(guards = {"isStringArray(receiver)", "receiver.isEspressoObject()"})
        static void doString(StaticObject receiver, long index, Object value,
                        @CachedLibrary("receiver") InteropLibrary receiverLib,
                        @CachedLibrary(limit = "3") InteropLibrary valueLib, // GR-37680
                        @Shared("error") @Cached BranchProfile error) throws InvalidArrayIndexException, UnsupportedTypeException {
            EspressoLanguage language = EspressoLanguage.get(receiverLib);
            if (index < 0 || receiver.length(language) <= 