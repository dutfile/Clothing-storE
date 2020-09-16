/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
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

package com.oracle.truffle.espresso.vm.structs;

import static com.oracle.truffle.espresso.ffi.NativeType.BOOLEAN;
import static com.oracle.truffle.espresso.ffi.NativeType.INT;
import static com.oracle.truffle.espresso.ffi.NativeType.LONG;
import static com.oracle.truffle.espresso.ffi.NativeType.OBJECT;
import static com.oracle.truffle.espresso.ffi.NativeType.POINTER;

import java.nio.ByteBuffer;

import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.espresso.ffi.NativeAccess;
import com.oracle.truffle.espresso.ffi.RawPointer;
import com.oracle.truffle.espresso.ffi.nfi.NativeUtils;
import com.oracle.truffle.espresso.jni.JNIHandles;
import com.oracle.truffle.espresso.jni.JniEnv;
import com.oracle.truffle.espresso.runtime.StaticObject;
import com.oracle.truffle.espresso.vm.structs.GenerateStructs.KnownStruct;

/**
 * Commodity class that wraps around native pointers to provide an easy and concise way of accessing
 * native structs entirely from the Java world. Apart from the {@link StructWrapper#pointer()}
 * method, methods in this class are not intended to be used by users, only from generated code.
 * <p>
 * The {@link GenerateStructs} annotation below will generate wrappers with member accessors for
 * each struct declared in the annotation.
 * <p>
 * the processor will generate two classes for each of them:
 * <ul>
 * <li>A {@link StructStorage storage class} to store the size of the struct, and the offsets of
 * each struct member. It also provides a {@link StructStorage#wrap(JniEnv, TruffleObject) wrap}
 * method, that returns an instance of {@link StructWrapper this class}. These classes are intended
 * to be per-context singletons.</li>
 * <li>A {@link StructWrapper wrapper class}, as described above. This generated class will also
 * have public getters and setters for each member of the struct.</li>
 * </ul>
 * <p>
 * Furthermore, the processor will additionally generate another class that stores the singleton
 * instances for the {@link StructStorage storage class}, the {@link Structs Structs class}.
 * <p>
 * See the {@link JavaMemberOffsetGetter} class for an example of how to use the wrappers.
 */
@GenerateStructs(//
{
                /*-
                 * struct JavaVMAttachArgs {
                 *     jint version;
                 *     char *name;
                 *     jobject group;
                 * };
                 */
                @KnownStruct(structName = "JavaVMAttachArgs", //
                                memberNames = {
                                                "version",
                                                "name",
                                                "group",
                                }, //
                                types = {
                                                INT,
                                                POINTER,
                                                OBJECT,
                                }),
                /*-
                 * struct jdk_version_info {
                 *     unsigned int jdk_version; // <- The only one we are interested in.
                 *     unsigned int update_version : 8;
                 *     unsigned int special_update_version : 8;
                 *     unsigned int reserved1 : 16;
                 *     unsigned int reserved2;
                 *     unsigned int thread_park_blocker : 1;
                 *     unsigned int post_vm_init_hook_enabled : 1;
                 *     unsigned int pending_list_uses_discovered_field : 1;
                 *     unsigned int : 29;
                 *     unsigned int : 32;
                 *     unsigned int : 32;
                 * };
                 */
                @KnownStruct(structName = "jdk_version_info", //
                                memberNames = {
                                                "jdk_version",
                                }, //
                                types = {
                                                INT,
                                }),
                /*-
                 * struct member_info {
                 *     char* id;
                 *     size_t offset;
                 *     struct member_info *next;
                 * };
                 */
                @KnownStruct(structName = "member_info", //
                                memberNames = {
                                                "id",
                                                "offset",
                                                "next",
                                }, //
                                types = {
                                                POINTER,
                                                LONG,
                                                POINTER,
                                }),
                /*-
                 * struct _jvmtiThreadInfo {
                 *     char* name;
                 *     jint priority;
                 *     jboolean is_daemon;
                 *     jthreadGroup thread_group;
                 *     jobject context_class_loader;
                 * };
                 */
                @KnownStruct(structName = "_jvmtiThreadInfo", //
                                memberNames = {
                                                "name",
                                                "priority",
                                                "is_daemon",
                                                "thread_group",
                                                "context_class_loader"
                                }, //
                                types = {
                                                POINTER,
                                                INT,
                                                BOOLEAN,
                                                OBJECT,
                                                OBJECT
                                }),
                /*-
                 * struct _jvmtiMonitorStackDepthInfo {
                 *     jobject monitor;
                 *     jint stack_depth;
                 * };
                 */
                @KnownStruct(structName = "_jvmtiMonitorStackDepthInfo", //
                                memberNames = {
                                                "monitor",
                                                "stack_depth",
                                }, //
                                types = {
                                                OBJECT,
                                                INT,
                                }),
                /*-
                 * struct _jvmtiThreadGroupInfo {
                 *     jthreadGroup parent;
                 *     char* name;
                 *     jint max_priority;
                 *     jboolean is_daemon;
                 * };
                 *
                 */
                @KnownStruct(structName = "_jvmtiThreadGroupInfo", //
                                memberNames = {
                                                "parent",
                                                "name",
                                                "max_priority",
                                                "is_daemon",
                                }, //
                                types = {
                                                OBJECT,
                                                POINTER,
                                                INT,
                                                BOOLEAN,
                                }),
                /*-
                 * struct _jvmtiFrameInfo {
                 *     jmethodID method;
                 *     jlocation location;
                 * };
                 */
                @KnownStruct(structName = "_jvmtiFrameInfo", //
                                memberNames = {
                                                "method",
                                                "location",
                                }, //
                                types = {
                                                LONG,
                                                LONG,
                                }),
                /*-
                 * struct _jvmtiStackInfo {
                 *     jthread thread;
                 *     jint state;
                 *     jvmtiFrameInfo* frame_buffer;
                 *     jint frame_count;
                 * };
                 */
                @KnownStruct(structName = "_jvmtiStackInfo", //
                                memberNames = {
                                                "thread",
                                                "state",
                                                "frame_buffer",
                                                "frame_count",
                                }, //
                                types = {
                                                OBJECT,
                                                INT,
                                                POINTER,
                                                INT,
                                }),
                /*-
                 * struct _jvmtiHeapReferenceInfoField {
                 *     jint index;
                 * };
                 */
                @KnownStruct(structName = "