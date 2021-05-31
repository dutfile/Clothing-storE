/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.espresso.ffi.nfi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Objects;

import com.oracle.truffle.espresso.substitutions.Collect;
import org.graalvm.home.HomeFinder;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.espresso.EspressoLanguage;
import com.oracle.truffle.espresso.ffi.Buffer;
import com.oracle.truffle.espresso.ffi.NativeAccess;
import com.oracle.truffle.espresso.ffi.NativeSignature;
import com.oracle.truffle.espresso.ffi.NativeType;
import com.oracle.truffle.espresso.ffi.Pointer;
import com.oracle.truffle.espresso.ffi.TruffleByteBuffer;
import com.oracle.truffle.espresso.impl.EmptyKeysArray;
import com.oracle.truffle.espresso.meta.EspressoError;

/**
 * Isolated native linking namespace based on glibc's dlmopen.
 *
 * <p>
 * dlmopen was ported from Solaris, but the glibc port is buggy and crash-prone; to improve the
 * situation, a tiny native library (libeden.so) is used to hook certain methods and avoid crashes.
 *
 * The isolated namespaces have limitations:
 * <ul>
 * <li>Maximum of 16 namespaces (hardcoded in glibc), isolated namespaces cannot be reused.
 * <li>malloc/free cannot cross the namespace boundary e.g. malloc outside, free inside.
 * <li>External threads TLS storage is not initialized correctly for libraries inside the linking
 * namespaces.
 * <li>Spurious crashes when binding non-existing symbols. Use <code>LD_DEBUG=unused</code> as a
 * workaround.
 * </ul>
 */
public final class NFIIsolatedNativeAccess extends NFINativeAccess {

    private final @Pointer TruffleObject edenLibrary;
    private final @Pointer TruffleObject malloc;
    private final @Pointer TruffleObject free;
    private final @Pointer TruffleObject realloc;
    private final @Pointer TruffleObject ctypeInit;
    private final @Pointer TruffleObject dlsym;
    private final DefaultLibrary defaultLibrary;

    NFIIsolatedNativeAccess(TruffleLanguage.Env env) {
        super(env);
        // libeden.so must be the first library loaded in the isolated namespace.
        Path espressoHome = HomeFinder.getInstance().getLanguageHomes().get(EspressoLanguage.ID);
        Path espressoLibraryPath = espressoHome.