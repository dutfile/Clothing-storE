/*
 * Copyright (c) 2018, 2020, Oracle and/or its affiliates. All rights reserved.
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

package com.oracle.truffle.espresso.vm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameInstanceVisitor;
import com.oracle.truffle.espresso.descriptors.Symbol;
import com.oracle.truffle.espresso.descriptors.Symbol.Name;
import com.oracle.truffle.espresso.descriptors.Symbol.Signature;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.nodes.EspressoRootNode;
import com.oracle.truffle.espresso.runtime.StaticObject;
import com.oracle.truffle.espresso.substitutions.JavaType;
import com.oracle.truffle.espresso.substitutions.Target_java_lang_invoke_MethodHandleNatives;

public final class StackWalk {
    // -1 and 0 are reserved values.
    private final AtomicLong walkerIds = new AtomicLong(1);

    /**
     * Contains frame walkers that are currently anchored (ie: the call to callStackWalk has not yet
     * returned).
     */
    private final Map<Long, FrameWalker> walkers = new ConcurrentHashMap<>();

    private static final long DEFAULT_MODE = 0x0;
    private static final long FILL_CLASS_REFS_ONLY = 0x2;
    private static final long GET_CALLER_CLASS = 0x04;
    private static final long SHOW_HIDDEN_FRAMES = 0x20;
    private static final long FILL_LIVE_STACK_FRAMES = 0x100;

    static boolean getCallerClass(long mode) {
        return (mode & GET_CALLER_CLASS) != 0;
    }

    static boolean skipHiddenFrames(long mode) {
        return (mode & SHOW_HIDDEN_FRAMES) == 0;
    }

    static boolean liveFrameInfo(long mode) {
        return (mode & FILL_LIVE_STACK_FRAMES) != 0;
    }

    static boolean needMethodInfo(long mode) {
        return (mode & FILL_CLASS_REFS_ONLY) == 0;
    }

    private static boolean synchronizedConstants(Meta meta) {
        Klass stackStreamFactory = meta.java_lang_StackStreamFactory;
        StaticObject statics = stackStreamFactory.tryInitializeAndGetStatics();
        assert DEFAULT_MODE == getConstantField(stackStreamFactory, statics, "DEFAULT_MODE", meta);
        assert FILL_CLASS_REFS_ONLY == getConstantField(stackStreamFactory, statics, "FILL_CLASS_REFS_ONLY", meta);
        assert GET_CALLER_CLASS == getConstantField(stackStreamFactory, statics, "GET_CALLER_CLASS", meta);
        assert SHOW_HIDDEN_FRAMES == getConstantField(stackStreamFactory, statics, "SHOW_HIDDEN_FRAMES", meta);
        assert FILL_LIVE_STACK_FRAMES == getConstantField(stackStreamFactory, statics, "FILL_LIVE_STACK_FRAMES", meta);
        return true;
    }

    private static int getConstantField(Klass stackStreamFactory, StaticObject statics, String name, Meta meta) {
        return stackStreamFactory.lookupDeclaredField(meta.getNames().getOrCreate(name), Symbol.Type._int).getInt(statics);
    }

    public StackWalk() {
    }

    /**
     * initializes the stack walking, and anchors the Frame Walker instance to a particular frame
     * and fetches the first batch of frames requested by guest.
     * 
     * Upon return, unanchors the Frame Walker, and it is then not possible to continue walking for
     * this walker anymore.
     * 
     * @return The result of invoking guest
     *         {@code java.lang.StackStreamFactory.AbstractStackWalker#doStackWalk(long, int, int, int,
     *         int)} .
     */
    public StaticObject fetchFirstBatch(@JavaType(internalName = "Ljava/lang/StackStreamFactory;") StaticObject stackStream, long mode, int skipframes,
                    int batchSize, int startIndex,
                    @JavaType(Object[].class) StaticObject frames,
                    Meta meta) {
        assert synchronizedConstants(meta);
        FrameWalker fw = new FrameWalker(meta, mode);
        fw.init(skipframes, batchSize, startIndex);
        Integer decodedOrNull = fw.doStackWalk(frames);
        int decoded = decodedOrNull == null ? fw.decoded() : decodedOrNull;
        if (decoded < 1) {
            throw meta.throwException(meta.java_lang_InternalError);
        }
        register(fw);
        Object result = meta.java_lang_StackStreamFactory_AbstractStackWalker_doStackWalk.invokeDirect(stackStream, fw.anchor, skipframes, batchSize, startIndex, startIndex + decoded);
        unAnchor(fw);
        return (StaticObject) result;
    }

    /**
     * After {@link #fetchFirstBatch(StaticObject, long, int, int, int, StaticObject, Meta)}, this
     * method allows to continue frame walking, starting from where the previous calls left off.
     * 
 