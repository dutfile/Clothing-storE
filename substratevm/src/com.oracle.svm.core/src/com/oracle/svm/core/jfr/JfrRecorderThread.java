/*
 * Copyright (c) 2020, 2023, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.jfr;

import org.graalvm.compiler.core.common.SuppressFBWarnings;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.UnsignedWord;

import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.jdk.UninterruptibleUtils;
import com.oracle.svm.core.locks.VMCondition;
import com.oracle.svm.core.locks.VMMutex;
import com.oracle.svm.core.locks.VMSemaphore;
import com.oracle.svm.core.sampler.SamplerBuffer;
import com.oracle.svm.core.sampler.SamplerBuffersAccess;
import com.oracle.svm.core.util.VMError;

/**
 * A daemon thread that is created during JFR startup and torn down by
 * {@link SubstrateJVM#destroyJFR}.
 * 
 * This class is primarily used for persisting the {@link JfrGlobalMemory} buffers to a file.
 * Besides that, it is also used for processing full {@link SamplerBuffer}s. As
 * {@link SamplerBuffer}s may also be filled in a signal handler, a {@link VMSemaphore} is used for
 * notification because it is async-signal-safe.
 */
public class JfrRecorderThread extends Thread {
    private static final int BUFFER_FULL_ENOUGH_PERCENTAGE = 50;

    private final JfrGlobalMemory globalMemory;
    private final JfrUnlockedChunkWriter unlockedChunkWriter;

    private final VMSemaphore semaphore;
    private final UninterruptibleUtils.AtomicBoolean atomicNotify;

    private final VMMutex mutex;
    private final VMCondition condition;
    private volatile boolean notified;
    private volatile boolean stopped;

    @Platforms(Platform.HOSTED_ONLY.class)
    public JfrRecorderThread(JfrGlobalMemory globalMemory, JfrUnlockedChunkWriter unlockedChunkWriter) {
        super("JFR recorder");
        this.globalMemory = globalMemory;
        this.unlockedChunkWriter = unlockedChunkWriter;
        this.mutex = new VMMutex("jfrRecorder");
        this.condition = new VMCondition(mutex);
        this.semaphore = new VMSemaphore();
        this.atomicNotify = new UninterruptibleUtils.AtomicBoolean(false);
        setDaemon(true);
    }

    @Override
    public void run() {
        try {
            while (!stopped) {
                if (await()) {
                    run0();
                }
            }
        } catch (Throwable e) {
            VMError.shouldNotReachHere("No exception must by thrown in the JFR recorder thread as this could break file IO operations.");
        }
    }

    private boolean await() {
        if (Platform.includedIn(Platform.DARWIN.class)) {
            /*
