/*
 * Copyright (c) 2018, 2021, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.replacements.test;

import org.graalvm.compiler.test.AddExports;
import org.junit.Test;

@AddExports("java.base/jdk.internal.misc")
public class UnsafeReplacementsTest extends MethodSubstitutionTest {

    static class Container {
        public volatile boolean booleanField;
        public volatile byte byteField = -17;
        public volatile char charField = 1025;
        public volatile short shortField = -2232;
        public volatile int intField = 0xcafebabe;
        public volatile long longField = 0xdedababafafaL;
        public volatile float floatField = 0.125f;
        public volatile double doubleField = 0.125;
        public byte[] byteArrayField = new byte[16];
    }

    static jdk.internal.misc.Unsafe unsafe = jdk.internal.misc.Unsafe.getUnsafe();
    static Container dummyValue = new Container();
    static Container newDummyValue = new Container();
    static long booleanOffset;
    static long byteOffset;
    static long charOffset;
    static long shortOffset;
    static long intOffset;
    static long longOffset;
    static long floatOffset;
    static long doubleOffset;
    static long byteArrayBaseOffset;

    static final int WEAK_ATTEMPTS = 10;

    static {
        try {
            booleanOffset = unsafe.objectFieldOffset(Container.class.getDeclaredField("booleanField"));
            byteOffset = unsafe.objectFieldOffset(Container.class.getDeclaredField("byteField"));
            charOffset = unsafe.objectFieldOffset(Container.class.getDeclaredField("charField"));
            shortOffset = unsafe.objectFieldOffset(Container.class.getDeclaredField("shortField"));
            intOffset = unsafe.objectFieldOffset(Container.class.getDeclaredField("intField"));
            longOffset = unsafe.objectFieldOffset(Container.class.getDeclaredField("longField"));
            floatOffset = unsafe.objectFieldOffset(Container.class.getDeclaredField("floatField"));
            doubleOffset = unsafe.objectFieldOffset(Container.class.getDeclaredField("doubleField"));
            byteArrayBaseOffset = unsafe.arrayBaseOffset(byte[].class);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean unsafeCompareAndSetBoolean() {
        Container container = new Container();
        return unsafe.compareAndSetBoolean(container, booleanOffset, false, true);
    }

    public static boolean unsafeCompareAndSetByte() {
        Container container = new Container();
        return unsafe.compareAndSetByte(container, byteOffset, (byte) -17, (byte) 121);
    }

    public static boolean unsafeCompareAndSetByteWithIntArgs(int expectedValue, int newValue) {
        Container container = new Container();
        return unsafe.compareAndSetByte(container, byteOffset, (byte) expectedValue, (byte) newValue);
    }

    public static boolean unsafeCompareAndSetChar() {
        Container container = new Container();
        return unsafe.compareAndSetChar(container, charOffset, (char) 1025, (char) 1777);
    }

    public static boolean unsafeCompareAndSetCharWithIntArgs(int expectedValue, int newValue) {
        Container container = new Container();
        return unsafe.compareAndSetChar(container, charOffset, (char) expectedValue, (char) newValue);
    }

    public static boolean unsafeCompareAndSetShort() {
        Container container = new Container();
        return unsafe.compareAndSetShort(container, shortOffset, (short) -2232, (short) 12111);
    }

    public static boolean unsafeCompareAndSetShortWithIntArgs(int expectedValue, int newValue) {
        Container container = new Container();
        return unsafe.compareAndSetShort(container, shortOffset, (short) expectedValue, (short) newValue);
    }

    public static boolean unsafeCompareAndSetInt() {
        Container container = new Container();
        return unsafe.compareAndSetInt(container, intOffset, 0xcafebabe, 0xbabefafa);
    }

    public static boolean unsafeCompareAndSetLong() {
        Container container = new Container();
        return unsafe.compareAndSetLong(container, longOffset, 0xdedababafafaL, 0xfafacecafafadedaL);
    }

    public static boolean unsafeCompareAndSetFloat() {
        Container container = new Container();
        return unsafe.compareAndSetFloat(container, floatOffset, 0.125f, 0.25f);
    }

    public static boolean unsafeCompareAndSetDouble() {
        Container container = new Container();
        return unsafe.compareAndSetDouble(container, doubleOffset, 0.125, 0.25);
    }

    @Test
    public void testCompareAndSet() {
        testGraph("unsafeCompareAndSetBoolean");
        testGraph("unsafeCompareAndSetByte");
        testGraph("unsafeCompareAndSetByteWithIntArgs");
        testGraph("unsafeCompareAndSetChar");
        testGraph("unsafeCompareAndSetCharWithIntArgs");
        testGraph("unsafeCompareAndSetShort");
        testGraph("unsafeCompareAndSetShortWithIntArgs");
        testGraph("unsafeCompareAndSetInt");
        testGraph("unsafeCompareAndSetLong");
        testGraph("unsafeCompareAndSetFloat");
        testGraph("unsafeCompareAndSetDouble");

        test("unsafeCompareAndSetBoolean");
        test("unsafeCompareAndSetByte");
        test("unsafeCompareAndSetByteWithIntArgs", -17, 121);
        test("unsafeCompareAndSetChar");
        test("unsafeCompareAndSetCharWithIntArgs", 1025, 1777);
        test("unsafeCompareAndSetShort");
        test("unsafeCompareAndSetShortWithIntArgs", -2232, 12111);
        test("unsafeCompareAndSetInt");
        test("unsafeCompareAndSetLong");
        test("unsafeCompareAndSetFloat");
        test("unsafeCompareAndSetDouble");
    }

    public static boolean unsafeWeakCompareAndSetBoolean() {
        Container container = new Container();
        boolean success = false;
        for (int c = 0; c < WEAK_ATTEMPTS && !success; c++) {
            success = unsafe.weakCompareAndSetBoolean(container, booleanOffset, false, true);
        }
        return success;
    }

    public static boolean unsafeWeakCompareAndSetBooleanAcquire() {
        Container container = new Container();
        boolean success = false;
        for (int c = 0; c < WEAK_ATTEMPTS && !success; c++) {
            success = unsafe.weakCompareAndSetBooleanAcquire(container, booleanOffset, false, true);
        }
        return success;
    }

    public static boolean unsafeWeakCompareAndSetBooleanPlain() {
        Container container = new Container();
        boolean success = false;
        for (int c = 0; c < WEAK_ATTEMPTS && !success; c++) {
            success = unsafe.weakCompareAndSetBooleanPlain(container, booleanOffset, false, true);
        }
        return success;
    }

    public static boolean unsafeWeakCompareAndSetBooleanRelease() {
        Container container = new Container();
        boolean success = false;
        for (int c = 0; c < WEAK_ATTEMPTS && !success; c++) {
            unsafe.weakCompareAndSetBooleanRelease(container, booleanOffset, false, true);
        }
        return success;
    }

    public static boolean unsafeWeakCompareAndSetByte() {
        Container container = new Container();
        boolean success = false;
        for (int c = 0; c < WEAK_ATTEMPTS && !success; c++) {
            success = unsafe.weakCompareAndSetByte(container, byteOffset, (byte) -17, (byte) 121);
        }
        return success;
    }

    public static boolean unsafeWeakCompareAndSetByteAcquire() {
        Container container = new Container();
        boolean success = false;
        for (int c = 0; c < WEAK_ATTEMPTS && !success; c++) {
            success = unsafe.weakCompareAndSetByteAcquire(container, byteOffset, (byte) -17, (byte) 121);
        }
        return success;
    }

    public static boolean unsafeWeakCompareAndSetBytePlain() {
        Container container = new Container();
        boolean success = false;
        for (int c = 0; c < WEAK_ATTEMPTS && !success; c++) {
            success = unsafe.weakCompareAndSetBytePlain(container, byteOffset, (byte) -17, (byte) 121);
        }
        return success;
    }

    public static boolean unsafeWeakCompareAndSetByteRelease() {
        Container container = new Container();
        boolean success = false;
        for (int c = 0; c < WEAK_ATTEMPTS && !success; c++) {
            success = unsafe.weakCompareAndSetByteRelease(container, byteOffset, (byte) -17, (byte) 121);
        }
        return success;
    }

    public static boolean unsafeWeakCompareAndSetChar() {
        Container container = new Container();
        boolean success = false;
        for (int c = 0; c < WEAK_ATTEMPTS && !success; c++) {
            success = unsafe.weakCompareAndSetChar(container, charOffset, (char) 1025, (char) 1777);
        }
        return success;
    }

    public static boolean unsafeWeakCompareAndSetCharAcquire() {
        Container container = new Container();
        boolean success = false;
        for (int c = 0; c < WEAK_ATTEMPTS && !success; c++) {
            success = unsafe.weakCompareAndSetCharAcquire(container, charOffset, (char) 1025, (char) 1777);
        }
        return success;
    }

    public static boolean unsafeWeakCompareAndSetCharPlain() {
        Container container = new Container();
        boolean success = false;
        for (int c = 0; c < WEAK_ATTEMPTS && !success; c++) {
            success = unsafe.weakCompareAndSetCharPlain(container, charOffset, (char) 1025, (char) 1777);
        }
        return success;
    }

    public static boolean unsafeWeakCompareAndSetCharRelease() {
        Container container = new Container();
        boolean success = false;
        for (int c = 0; c < WEAK_ATTEMPTS && !success; c++) {
            success = unsafe.weakCompareAndSetCharRelease(container, charOffset, (char) 1025, (char) 1777);
        }
        return success;
    }

    public static boolean unsafeWeakCompareAndSetShort() {
        Container container = new Container();
        boolean success = false;
        for (int c = 0; c < WEAK_ATTEMPTS && !success; c++) {
            success = unsafe.weakCompareAndSetShort(container, shortOffset, (short) -2232, (short) 12111);
        }
        return success;
    }

    public static boolean unsafeWeakCompareAndSetShortAcquire() {
        Container container = new Container();
        boolean success = false;
        for (int c = 0; c < WEAK_ATTEMPTS && !success; c++) {
            success = unsafe.weakCompareAndSetShortAcquire(container, shortOffset, (short) -2232, (short) 12111);
        }
        return success;
    }

    public static boolean unsafeWeakCompareAndSetShortPlain() {
        Container container = new Container();
        boolean success = false;
        for (int c = 0; c < WEAK_ATTEMPTS && !success; c++) {
            success = unsafe.weakCompareAndSetShortPlain(container, shortOffset, (short) -2232, (short) 12111);
        }
        return success;
    }

    public static boolean unsafeWeakCompareAndSetShortRelease() {
        Container container = new Container();
        boolean success = false;
        for (int c = 0; c < WEAK_ATTEMPTS && !success; c++) {
            success = unsafe.weakCompareAndSetShortRelease(container, shortOffset, (short) -2232, (short) 12111);
        }
        return success;
    }

    public static boolean unsafeWeakCompareAndSetInt() {
        Container container = new Container();
        boolean success = false;
        for (int c = 0; c < WEAK_ATTEMPTS && !success; c++) {
            success = unsafe.weakCompareAndSetInt(container, intOffset, 0xcafebabe, 0xbabefafa);
        }
        return success;
    }

    public static boolean unsafeWeakCompareAndSetIntAcquire() {
        Container container = new Container();
        boolean success = false;
        for (int c = 0; c < WEAK_ATTEMPTS && !success; c++) {
            success = unsafe.weakCompareAndSetIntAcquire(container, intOffset, 0xcafebabe, 0xbabefafa);
        }
        return success;
    }

    public static boolean unsafeWeakCompareAndSetIntPlain() {
        Container container = new Container();
        boolean success = false;
        for (int c = 0; c < WEAK_ATTEMPTS && !success; c++) {
            success = unsafe.weakCompareAndSetIntPlain(container, intOffset, 0xcafebabe, 0xbabefafa);
        }
        return success;
