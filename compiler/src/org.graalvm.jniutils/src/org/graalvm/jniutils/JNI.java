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
package org.graalvm.jniutils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.nativeimage.c.function.InvokeCFunctionPointer;
import org.graalvm.nativeimage.c.function.CFunction.Transition;
import org.graalvm.nativeimage.c.struct.CField;
import org.graalvm.nativeimage.c.struct.CPointerTo;
import org.graalvm.nativeimage.c.struct.CStruct;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CDoublePointer;
import org.graalvm.nativeimage.c.type.CFloatPointer;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.nativeimage.c.type.CLongPointer;
import org.graalvm.nativeimage.c.type.CShortPointer;
import org.graalvm.nativeimage.c.type.VoidPointer;
import org.graalvm.word.PointerBase;

import jdk.vm.ci.services.Services;

public final class JNI {

    public static final int JNI_OK = 0;
    public static final int JNI_ERR = -1; /* unknown error */
    public static final int JNI_EDETACHED = -2; /* thread detached from the VM */
    public static final int JNI_EVERSION = -3; /* JNI version error */
    public static final int JNI_ENOMEM = -4; /* not enough memory */
    public static final int JNI_EEXIST = -5; /* VM already created */
    public static final int JNI_EINVAL = -6; /* invalid arguments */
    public static final int JNI_VERSION_10 = 0x000a0000;

    private JNI() {
        throw new IllegalStateException("No instance allowed");
    }

    public interface JMethodID extends PointerBase {
    }

    public interface JFieldID extends PointerBase {
    }

    public interface JObject extends PointerBase {
    }

    public interface JArray extends JObject {
        int MODE_WRITE_RELEASE = 0;
        int MODE_WRITE = 1;
        int MODE_RELEASE = 2;
    }

    public interface JBooleanArray extends JArray {
    }

    public interface JByteArray extends JArray {
    }

    public interface JCharArray extends JArray {
    }

    public interface JShortArray extends JArray {
    }

    public interface JIntArray extends JArray {
    }

    public interface JLongArray extends JArray {
    }

    public interface JFloatArray extends JArray {
    }

    public interface JDoubleArray extends JArray {
    }

    public interface JObjectArray extends JArray {
    }

    public interface JClass extends JObject {
    }

    public interface JString extends JObject {
    }

    public interface JThrowable extends JObject {
    }

    public interface JWeak extends JObject {
    }

    /**
     * Access to the {@code jvalue} JNI union.
     *
     * <pre>
     * typedef union jvalue {
     *    jboolean z;
     *    jbyte    b;
     *    jchar    c;
     *    jshort   s;
     *    jint     i;
     *    jlong    j;
     *    jfloat   f;
     *    jdouble  d;
     *    jobject  l;
     * } jvalue;
     * </pre>
     */
    @CContext(JNIHeaderDirectives.class)
    @CStruct("jvalue")
    public interface JValue extends PointerBase {
        // @formatter:off
        @CField("z")    boolean getBoolean();
        @CField("b")    byte    getByte();
        @CField("c")    char    getChar();
        @CField("s")    short   getShort();
        @CField("i")    int     getInt();
        @CField("j")    long    getLong();
        @CField("f")    float   getFloat();
        @CField("d")    double  getDouble();
        @CField("l")    JObject getJObject();

        @CField("z")    void setBoolean(boolean b);
        @CField("b")    void setByte(byte b);
        @CField("c")    void setChar(char ch);
        @CField("s")    void setShort(short s);
        @CField("i")    void setInt(int i);
        @CField("j")    void setLong(long l);
        @CField("f")    void setFloat(float f);
        @CField("d")    void setDouble(double d);
        @CField("l")    void setJObject(JObject obj);
        // @formatter:on

        /**
         * Gets JValue in an array of JValues pointed to by this object.
         */
        JValue addressOf(int index);
    }

    @CContext(JNIHeaderDirectives.class)
    @CStruct(value = "JNIEnv_", addStructKeyword = true)
    public interface JNIEnv extends PointerBase {
        @CField("functions")
        JNINativeInterface getFunctions();
    }

    @CPointerTo(JNIEnv.class)
    public interface JNIEnvPointer extends PointerBase {
        JNIEnv readJNIEnv();

        void writeJNIEnv(JNIEnv env);
    }

    @CContext(JNIHeaderDirectives.class)
    @CStruct(value = "JNINativeInterface_", addStructKeyword = true)
    public interface JNINativeInterface extends PointerBase {

        @CField("NewString")
        NewString getNewString();

        @CField("GetStringLength")
        GetStringLength getGetStringLength();

        @CField("GetStringChars")
        GetStringChars getGetStringChars();

        @CField("ReleaseStringChars")
        ReleaseStringChars getReleaseStringChars();

        @CField("NewStringUTF")
        NewStringUTF8 getNewStringUTF();

        @CField("GetStringUTFLength")
        GetStringUTFLength getGetStringUTFLength();

        @CField("GetStringUTFChars")
        GetStringUTFChars getGetStringUTFChars();

        @CField("ReleaseStringUTFChars")
        ReleaseStringUTFChars getReleaseStringUTFChars();

        @CField("GetArrayLength")
        GetArrayLength getGetArrayLength();

        @CField("NewObjectArray")
        NewObjectArray getNewObjectArray();

        @CField("NewBooleanArray")
        NewBooleanArray getNewBooleanArray();

        @CField("NewByteArray")
        NewByteArray getNewByteArray();

        @CField("NewCharArray")
        NewCharArray getNewCharArray();

        @CField("NewShortArray")
        NewShortArray getNewShortArray();

        @CField("NewIntArray")
        NewIntArray getNewIntArray();

        @CField("NewLongArray")
        NewLongArray getNewLongArray();

        @CField("NewFloatArray")
        NewFloatArray getNewFloatArray();

        @CField("NewDoubleArray")
        NewDoubleArray getNewDoubleArray();

        @CField("GetObjectArrayElement")
        GetObjectArrayElement getGetObjectArrayElement();

        @CField("SetObjectArrayElement")
        SetObjectArrayElement getSetObjectArrayElement();

        @CField("GetBooleanArrayElements")
        GetBooleanArrayElements getGetBooleanArrayElements();

        @CField("GetByteArrayElements")
        GetByteArrayElements getGetByteArrayElements();

        @CField("GetCharArrayElements")
        GetCharArrayElements getGetCharArrayElements();

        @CField("GetShortArrayElements")
        GetShortArrayElements getGetShortArrayElements();

        @CField("GetIntArrayElements")
        GetIntArrayElements getGetIntArrayElements();

        @CField("GetLongArrayElements")
        GetLongArrayElements getGetLongArrayElements();

        @CField("GetFloatArrayElements")
        GetFloatArrayElements getGetFloatArrayElements();

        @CField("GetDoubleArrayElements")
        GetDoubleArrayElements getGetDoubleArrayElements();

        @CField("ReleaseBooleanArrayElements")
        ReleaseBooleanArrayElements getReleaseBooleanArrayElements();

        @CField("ReleaseByteArrayElements")
        ReleaseByteArrayElements getReleaseByteArrayElements();

        @CField("ReleaseCharArrayElements")
        ReleaseCharArrayElements getReleaseCharArrayElements();

        @CField("ReleaseShortArrayElements")
        ReleaseShortArrayElements getReleaseShortArrayElements();

        @CField("ReleaseIntArrayElements")
        ReleaseIntArrayElements getReleaseIntArrayElements();

        @CField("ReleaseLongArrayElements")
        ReleaseLongArrayElements getReleaseLongArrayElements();

        @CField("ReleaseFloatArrayElements")
        ReleaseFloatArrayElements getReleaseFloatArrayElements();

        @CField("ReleaseDoubleArrayElements")
        ReleaseDoubleArrayElements getReleaseDoubleArrayElements();

        @CField("GetBooleanArrayRegion")
        GetBooleanArrayRegion getGetBooleanArrayRegion();

        @CField("GetByteArrayRegion")
        GetByteArrayRegion getGetByteArrayRegion();

        @CField("GetCharArrayRegion")
        GetCharArrayRegion getGetCharArrayRegion();

        @CField("GetShortArrayRegion")
        GetShortArrayRegion getGetShortArrayRegion();

        @CField("GetIntArrayRegion")
        GetIntArrayRegion getGetIntArrayRegion();

        @CField("GetLongArrayRegion")
        GetLongArrayRegion getGetLongArrayRegion();

        @CField("GetFloatArrayRegion")
        GetFloatArrayRegion getGetFloatArrayRegion();

        @CField("GetDoubleArrayRegion")
        