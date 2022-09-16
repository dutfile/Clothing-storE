/*
 * Copyright (c) 2013, 2019, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.posix.thread;

import org.graalvm.compiler.core.common.SuppressFBWarnings;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.ObjectHandle;
import org.graalvm.nativeimage.Platform.HOSTED_ONLY;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.function.CEntryPoint.Publish;
import org.graalvm.nativeimage.c.function.CEntryPointLiteral;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.nativeimage.c.type.CTypeConversion.CCharPointerHolder;
import org.graalvm.nativeimage.c.type.WordPointer;
import org.graalvm.nativeimage.impl.UnmanagedMemorySupport;
import org.graalvm.word.Pointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordBase;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.annotate.Inject;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.c.CGlobalData;
import com.oracle.svm.core.c.CGlobalDataFactory;
import com.oracle.svm.core.c.function.CEntryPointActions;
import com.oracle.svm.core.c.function.CEntryPointErrors;
import com.oracle.svm.core.c.function.CEntryPointOptions;
import com.oracle.svm.core.c.function.CEntryPointSetup.LeaveDetachThreadEpilogue;
import com.oracle.svm.core.feature.AutomaticallyRegisteredImageSingleton;
import com.oracle.svm.core.graal.stackvalue.UnsafeStackValue;
import com.oracle.svm.core.os.IsDefined;
import com.oracle.svm.core.posix.PosixUtils;
import com.oracle.svm.core.posix.headers.Errno;
import com.oracle.svm.core.posix.headers.Pthread;
import com.oracle.svm.core.posix.headers.Pthread.pthread_attr_t;
import com.oracle.svm.core.posix.headers.Pthread.pthread_cond_t;
import com.oracle.svm.core.posix.headers.Pthread.pthread_mutex_t;
import com.oracle.svm.core.posix.headers.Sched;
import com.oracle.svm.core.posix.headers.Time;
import com.oracle.svm.core.posix.headers.Unistd;
import com.oracle.svm.core.posix.headers.darwin.DarwinPthread;
import com.oracle.svm.core.posix.headers.linux.LinuxPthread;
import com.oracle.svm.core.posix.pthread.PthreadConditionUtils;
import com.oracle.svm.core.stack.StackOverflowCheck;
import com.oracle.svm.core.thread.Parker;
import com.oracle.svm.core.thread.Parker.ParkerFactory;
import com.oracle.svm.core.thread.PlatformThreads;
import com.oracle.svm.core.util.UnsignedUtils;
import com.oracle.svm.core.util.VMError;

import jdk.internal.misc.Unsafe;

@AutomaticallyRegisteredImageSingleton(PlatformThreads.class)
public final class PosixPlatformThreads extends PlatformThreads {

    @SuppressFBWarnings(value = "BC", justification = "Cast for @TargetClass")
    private static Target_java_lang_Thread toTarget(Thread thread) {
        return Target_java_lang_Thread.class.cast(thread);
    }

    @Platforms(HOSTED_ONLY.class)
    PosixPlatformThreads() {
    }

    @Override
    protected boolean doStartThread(Thread thread, long stackSize) {
        pthread_attr_t attributes = UnsafeStackValue.get(pthread_attr_t.class);
        if (Pthread.pthread_attr_init(attributes) != 0) {
            return false;
        }
        try {
            if (Pthread.pthread_attr_setdetachstate(attributes, Pthread.PTHREAD_CREATE_JOINABLE()) != 0) {
                return false;
            }

            UnsignedWord threadStackSize = WordFactory.unsigned(stackSize);
            /* If there is a chosen stack size, use it as the stack size. */
            if (threadStackSize.notEqual(WordFactory.zero())) {
                /* Make sure the chosen stack size is large enough. */
                threadStackSize = UnsignedUtils.max(threadStackSize, Pthread.PTHREAD_STACK_MIN());
                /* Make sure the chosen stack size is a multiple of the system page size. */
                threadStackSize = UnsignedUtils.roundUp(threadStackSize, WordFactory.unsigned(Unistd.getpagesize()));

                if (Pthread.pthread_attr_setstacksize(attributes, threadStackSize) != 0) {
                    return false;
                }
            }

            ThreadStartData startData = prepareStart(thread, SizeOf.get(ThreadStartData.class));

            Pthread.pthread_tPointer newThread = UnsafeStackValue.get(Pthread.pthread_tPointer.class);
            if (Pthread.pthread_create(newThread, attributes, PosixPlatformThreads.pthreadStartRoutine.getFunctionPointer(), startData) != 0) {
                undoPrepareStartOnError(thread, startData);
                return false;
            }

            setPthreadIdentifier(thread, newThread.read());
            return true;
        } finally {
            Pthread.pthread_attr_destroy(attributes);
        }
    }

    private static void setPthreadIdentifier(Thread thread, Pthread.pthread_t pthread) {
        toTarget(thread).hasPthreadIdentifier = true;
        toTarget(thread).pthreadIdentifier = pthread;
    }

    static Pthread.pthread_t getPthreadIdentifier(Thread thread) {
        return toTarget(thread).pthreadIdentifier;
    }

    static boolean hasThreadIdentifier(Thread thread) {
        return toTarget(thread).hasPthreadIdentifier;
    }

    /**
     * Try to set the native name of the current thread.
     *
     * Failures are ignored.
     */
    @Override
    protected void setNativeName(Thread thread, String name) {
        if (!hasThreadIdentifier(thread)) {
            /*
             * The thread was not started from Java code, but started from C code and attached
             * manually to SVM. We do not want to interfere with such threads.
             */
            return;
        }

        if (IsDefined.isDarwin() && thread != Thread.currentThread()) {
            /* Darwin only allows setting the name of the current thread. */
            return;
        }

        /* Use at most 15 characters from the right end of the name. */
        final int startIndex = Math.max(0, name.length() - 15);
        final String pthreadName = name.substring(startIndex);
        assert pthreadName.length() < 16 : "thread name for pthread has a maximum length of 16 characters including the terminating 0";
        try (CCharPointerHolder threadNameHolder = CTypeConversion.toCString(pthreadName)) {
            if (IsDefined.isLinux()) {
                LinuxPthread.pthread_setname_np(getPthreadIdentifier(thread), threadNameHolder.get());
            } else if (IsDefined.isDarwin()) {
                assert thread == Thread.currentThread() : "Darwin only allows setting the name of the current thread";
                DarwinPthread.pthread_setname_np(threadNameHolder.get());
            } else {
                VMError.unsupportedFeature("PosixPlatformThreads.setNativeName on unknown OS");
            }
        }
    }

    @Override
    protected void yieldCurrent() {
        Sched.sched_yield();
    }

    private static final CEntryPointLiteral<CFunctionPointer> pthreadStartRoutine = CEntryPointLiteral.create(PosixPlatformThreads.class, "pthreadStartRoutine", ThreadStartData.class);

    private static class PthreadStartRoutinePrologue implements CEntryPointOptions.Prologue {
        private static final CGlobalData<CCharPointer> errorMessage = CGlobalDataFactory.createCString("Failed to attach a newly launched thread.");

        @SuppressWarnings("unused")
        @Uninterruptible(reason = "prologue")
        static void enter(ThreadStartData data) {
            int code = CEntryPointActions.enterAttachThread(data.getIsolate(), true, false);
            if (code != CEntryPointErrors.NO_ERROR) {
                CEntryPointActions.failFatally(code, errorMessage.get());
            }
        }
    }

    @CEntryPoint(include = CEntryPoint.NotIncludedAutomatical