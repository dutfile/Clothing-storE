/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.polybench.instruments;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.graalvm.options.OptionCategory;
import org.graalvm.options.OptionDescriptors;
import org.graalvm.options.OptionKey;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Option;
import com.oracle.truffle.api.TruffleContext;
import com.oracle.truffle.api.instrumentation.ContextsListener;
import com.oracle.truffle.api.instrumentation.ThreadsListener;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.LanguageInfo;
import com.oracle.truffle.api.nodes.Node;
import com.sun.management.ThreadMXBean;

@TruffleInstrument.Registration(id = MemoryUsageInstrument.ID, name = "Polybench Memory Usage Instrument")
public final class MemoryUsageInstrument extends TruffleInstrument {

    public static final String ID = "memory-usage";

    @Option(name = "", help = "Enable the Memory Usage Instrument (default: false).", category = OptionCategory.EXPERT) //
    static final OptionKey<Boolean> ENABLED = new OptionKey<>(false);

    private static final ThreadMXBean THREAD_BEAN = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private final Set<Thread> threads = new HashSet<>();
    /*
     * Used for lock-free asynchronous traversal.
     */
    private volatile Thread[] threadsArray;
    private final ConcurrentHashMap<TruffleContext, MemoryTracking> memoryTrackedContexts = new ConcurrentHashMap<>();

    private Env currentEnv;

    final Map<String, TruffleObject> functions = new HashMap<>();
    {
        functions.put("getAllocatedBytes", new GetAllocatedBytesFunction());
        functions.put("getContextHeapSize", new GetContextHeapSize());
        functions.put("startContextMemoryTracking", new StartContextMemoryTrackingFunction());
        functions.put("stopContextMemoryTracking", new StopContextMemoryTrackingFunction());
    }

    @Override
    protected OptionDescriptors getOptionDescriptors() {
        return new MemoryUsageInstrumentOptionDescriptors();
    }

    @TruffleBoundary
    public long getContextHeapSize() {
        TruffleContext context = currentEnv.getEnteredContext();
        AtomicBoolean b = new AtomicBoolean();
        return currentEnv.calculateContextHeapSize(context, Long.MAX_VALUE, b);
    }

    @Override
    protected synchronized void onCreate(Env env) {
        this.currentEnv = env;
        env.getInstrumenter().attachContextsListener(new ContextsListener() {
            @Override
            public void onContextCreated(TruffleContext c) {
                try {
                    Object polyglotBindings;
                    Object prev = c.enter(null);
                    try {
                        polyglotBindings = env.getPolyglotBindings();
                    } finally {
                        c.leave(null, prev);
                    }
                    InteropLibrary interop = InteropLibrary.getUncached(polyglotBindings);
                    for (Map.Entry<String, TruffleObject> function : functions.entrySet()) {
                        String key = function.getKey();
                        if (!interop.isMemberExisting(polyglotBindings, key)) {
                            interop.writeMember(polyglotBindings, key, function.getValue());
                        }
                    }
                } catch (UnsupportedMessageException | UnknownIdentifierException | UnsupportedTypeException e) {
                    throw CompilerDirectives.shouldNotReachHere("Exception during interop.");
                }
            }

            @Override
            public void onLanguageContextCreated(TruffleContext context, LanguageInfo language) {
            }

            @Override
            public void onLanguageContextInitialized(TruffleContext context, LanguageInfo language) {
            }

            @Override
            public void onLanguageContextFinalized(TruffleContext context, LanguageInfo language) {
            }

            @Override
            public void onLanguageContextDisposed(TruffleContext context, LanguageInfo language) {
            }

            @Override
            public void onContextClosed(TruffleContext context) {
            }
        }, true);

        env.getInstrumenter().attachThreadsListener(new ThreadsListener() {
            @Override
            publ