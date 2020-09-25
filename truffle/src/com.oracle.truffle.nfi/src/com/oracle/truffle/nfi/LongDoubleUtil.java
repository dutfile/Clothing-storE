/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.nfi;

import java.math.BigInteger;
import java.nio.ByteOrder;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidBufferOffsetException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.nfi.api.SerializableLibrary;

final class LongDoubleUtil {

    static Object interopToFP80(Object number) {
        assert InteropLibrary.getUncached().isNumber(number);
        return new FP80Number(number);
    }

    static Object fp80ToNumber(Object buffer) {
        assert InteropLibrary.getUncached().hasBufferElements(buffer);
        return new FP80Buffer(buffer);
    }

    static Object interopToFP128(Object number) {
        assert InteropLibrary.getUncached().isNumber(number);
        return new FP128Number(number);
    }

    static Object fp128ToNumber(Object buffer) {
        assert InteropLibrary.getUncached().hasBufferElements(buffer);
        return new FP128Buffer(buffer);
    }

    private static final class DoubleHelper {

        private static final int FRACTION_BITS = 52;
        private static final int EXPONENT_BITS = 11;

        private static final long EXPONENT_MASK = ((1L << EXPONENT_BITS) - 1) << FRACTION_BITS;
        private static final long FRACTION_MASK = (1L << FRACTION_BITS) - 1;

        private static final int EXPONENT_BIAS = 1023;
    }

    @ExportLibrary(value = SerializableLibrary.class, useForAOT = false)
    static final class FP80Number implements TruffleObject {

        private static final int FRACTION_BITS = 64;

        private static final int SIGN_MASK = 1 << 15;
        private static final int EXPONENT_MASK = SIGN_MASK - 1;
        private static final int EXPONENT_BIAS = 16383;

        private static final long INF_FRACTION = 0x8000_0000_0000_0000L;
        private static final long NAN_FRACTION = 0xc000_0000_0000_0000L;

        final Object number;

        private FP80Number(Object number) {
            this.number = number;
        }

        @ExportMessage
        @SuppressWarnings("static-method")
        boolean isSerializable() {
            return true;
        }

        @ExportMessage
        static class Serialize {

            @Specialization(limit = "1", guards = "numberInterop.fitsInLong(self.number)")
            static void doLong(FP80Number self, Object buffer,
                            @CachedLibrary("self.number") InteropLibrary numberInterop,
                            @CachedLibrary("buffer") InteropLibrary bufferInterop) {
                try {
                    long number = numberInterop.asLong(self.number);
                    if (number == 0) {
                        bufferInterop.writeBufferLong(buffer, ByteOrder.nativeOrder(), 0, 0);
                        bufferInterop.writeBufferShort(buffer, ByteOrder.nativeOrder(), 8, (short) 0);
                        return;
                    }

                    int sign = number < 0 ? SIGN_MASK : 0;
                    long val = Math.abs(number);

                    int leadingOnePosition = Long.SIZE - Long.numberOfLeadingZeros(val);
                    int exponent = FP80Number.EXPONENT_BIAS + (leadingOnePosition - 1);
                    assert (exponent & FP80Number.EXPONENT_MASK) == exponent : "exponent out of range";

                    long fractionMask;
                    if (leadingOnePosition == Long.SIZE || leadingOnePosition == Long.SIZE - 1) {
                        fractionMask = 0xffffffff;
                    } else {
                        fractionMask = (1L << leadingOnePosition + 1) - 1;
                    }
                    long maskedFractionValue = val & fractionMask;
                    long fraction = maskedFractionValue << (Long.SIZE - leadingOnePosition);

                    bufferInterop.writeBufferLong(buffer, ByteOrder.nativeOrder(), 0, fraction);
                    bufferInterop.writeBufferShort(buffer, ByteOrder.nativeOrder(), 8, (short) (sign | exponent));
                } catch (UnsupportedMessageException | InvalidBufferOffsetException ex) {
                    throw CompilerDirectives.shouldNotReachHere(ex);
                }
            }

            @Specialization(limit = "1", guards = "numberInterop.fitsInDouble(self.number)")
            static void doDouble(FP80Number self, Object buffer,
                            @CachedLibrary("self.number") InteropLibrary numberInterop,
                            @CachedLibrary("buffer") InteropLibrary bufferInterop) {
                try {
                    double number = numberInterop.asDouble(self.number);
                    long rawValue = Double.doubleToRawLongBits(number);
                    int sign = rawValue < 0 ? SIGN_MASK : 0;

                    long absRaw = Math.abs(rawValue);
                    if (absRaw == 0) {
                        // positive or negative zero
                        bufferInterop.writeBufferLong(buffer, ByteOrder.nativeOrder(), 0, 0);
                        bufferInterop.writeBufferShort(buffer, ByteOrder.nativeOrder(), 8, (short) sign);
                        return;
                    }

                    if ((absRaw & DoubleHelper.EXPONENT_MASK) == DoubleHelper.EXPONENT_MASK) {
                        if ((absRaw & DoubleHelper.FRACTION_MASK) == 0) {
                            // infinity
                            bufferInterop.writeBufferLong(buffer, ByteOrder.nativeOrder(), 0, FP80Number.INF_FRACTION);
                        } else {
                            // NaN
                            bufferInterop.writeBufferLong(buffer, ByteOrder.nativeOrder(), 0, FP80Number.NAN_FRACTION);
                        }
                        bufferInterop.writeBufferShort(buffer, ByteOrder.nativeOrder(), 8, (short) (sign | FP80Number.EXPONENT_MASK));
                    }

                    long doubleExponent = (absRaw & DoubleHelper.EXPONENT_MASK) >> DoubleHelper.FRACTION_BITS;
                    int fp80Exponent = (int) doubleExponent - DoubleHelper.EXPONENT_BIAS + FP80Number.EXPONENT_BIAS;

                    long doubleFraction = rawValue & DoubleHelper.FRACTION_MASK;
                    long shiftedDoubleFraction = doubleFraction << (63 - DoubleHelper.FRACTION_BITS);
                    long leadingOne = 1L << 63;
                    long fp80Fraction = leadingOne | shiftedDoubleFraction;

                    bufferInterop.writeBufferLong(buffer, ByteOrder.nativeOrder(), 0, fp80Fraction);
                    bufferInterop.writeBufferShort(buffer, ByteOrder.nativeOrder(), 8, (short) (sign | fp80Exponent));
                } catch (UnsupportedMessageException | InvalidBufferOffsetException ex) {
                    throw CompilerDirectives.shouldNotReachHere(ex);
                }
            }
        }
    }

    @ExportLibrary(value = SerializableLibrary.class, useForAOT = false)
    static final class FP128Number implements TruffleObject {

        static final long DOUBLE_FRACTION_BIT_WIDTH = 52;
        private static final long SIGN_MASK = 1L << 63;
        private static final int EXPONENT_BIAS = 16383;
        private static final int FRACTION_BIT_WIDTH = 112;
        public static final int EXPONENT_POSITION = FRACTION_BIT_WIDTH - Long.SIZE; // 112 - 64 = 48
        public static final long EXPONENT_MASK = 0b111111111111111L << EXPONENT_POSITION;
        public static final long FRACTION_MASK = (1L << EXPONENT_POSITION) - 1;
        public static final int DOUBLE_SIGN_POS = 63;

        final Object number;

        private FP128Number(Object number) {
            this.number = number;
        }

        @ExportMessage
        @SuppressWarnings("static-method")
        boolean isSerializable() {
            return true;
        }

        @ExportMessage
        static class Serialize {

            @Specialization(limit = "1", guards = "numberInterop.fitsInLong(self.number)")
            static void doLong(FP128Number self, Object buffer,
                            @CachedLibrary("self.number") InteropLibrary numberInterop,
                            @CachedLibrary("buffer") InteropLibrary bufferInterop) {
                try {

                    long number = numberInterop.asLong(self.number);
                    if (number == 0) {
                        bufferInterop.writeBufferLong(buffer, ByteOrder.nativeOrder(), 0, 0);
                        bufferInterop.writeBufferLong(buffer, ByteOrder.nativeOrder(), 8, 0);
                        return;
                    }

                    long sign = number < 0 ? SIGN_MASK : 0;
                    long val = Math.abs(number);

                    int leadingOnePosition = Long.SIZE - Long.numberOfLeadingZeros(val);
                    long exponent = EXPONENT_BIAS + (leadingOnePosition - 1);
                    long shiftAmount = FRACTION_BIT_WIDTH - leadingOnePosition + 1;
                    long fraction;
                    long exponentFraction;

                    if (shiftAmount >= Long.SIZE) { // TODO: Need to test both cases.
                        exponentFraction = (exponent << EXPONENT_POSITION) | ((val << (shiftAmount - Long.SIZE)) & FRACTION_MASK);
                        fraction = 0;
                    } else {
                        exponentFraction = (exponent << EXPONENT_POSITION) | ((val >> (Long.SIZE - shiftAmount)) & FRACTION_MASK);
                        fraction = val << (shiftAmount);
                    }

                    bufferInterop.writeBufferLong(buffer, ByteOrder.nativeOrder(), 0, fraction);
                    bufferInterop.writeBufferLong(buffer, ByteOrder.nativeOrder(), 8, (sign | exponentFraction));
                } catch (UnsupportedMessageException | InvalidBufferOffsetException ex) {
                    throw CompilerDirectives.shouldNotReachHere(ex);
                }
            }

            @Specialization(limit = "1", guards = "numberInterop.fitsInDouble(self.number)")
            static void doDouble(FP128Number self, Object buffer,
                            @CachedLibrary("self.number") InteropLibrary numberInterop,
                            @CachedLibrary("buffer") InteropLibrary bufferInterop) {
                try {
                    double number = numberInterop.asDouble(self.number);
                    long rawValue = Double.doubleToRawLongBits(number);
                    long sign = rawValue < 0 ? SIGN_MASK : 0;

                    long absRaw = Math.abs(rawValue);
                    if (absRaw == 0) {
                        // positive or negative zero
                        bufferInterop.writeBufferLong(buffer, ByteOrder.nativeOrder(), 0, 0);
                        bufferInterop.writeBufferLong(buffer, ByteOrder.nativeOrder(), 8, sign);
                        return;
                    }

                    int doubleExponent = Math.getExponent(number);
                    int biasedExponent = doubleExponent + EXPONENT_BIAS;
                    long doubleFraction = rawValue & DoubleHelper.FRACTION_MASK;
                    // 112 - 52 = 60
                    long shiftAmount = FRACTION_BIT_WIDTH - DOUBLE_FRACTION_BIT_WIDTH;
                    long fraction = doubleFraction << (s