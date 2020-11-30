/*
 * Copyright (c) 2019, 2019, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.stack;

import org.graalvm.compiler.api.replacements.Fold;
import org.graalvm.compiler.options.Option;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.type.WordPointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.core.thread.VMOperation;
import com.oracle.svm.core.threadlocal.FastThreadLocalWord;

/**
 * This interface provides functions related to stack overflow checking that are invoked by other
 * parts of Substrate VM. Most of the actual stack overflow handling is done using Graal nodes and
 * lowerings, i.e., in the Graal-specific implementation class {@code StackOverflowCheckImpl}.
 *
 * Substrate VM uses explicit stack overflow checks: at the beginning of every method, the current
 * stack pointer is compared with the allowed boundary of the stack pointer. The boundary is stored
 * in a {@link FastThreadLocalWord thread local variable}. So the fast-path cost of a stack overflow
 * check is one memory load, a comparison, and a conditional branch instruction.
 *
 * When a stack overflow is detected, a {@link StackOverflowError} is thrown. Allocating the error
 * object, filling in the stack trace, and performing the stack unwinding to find the matching
 * exception handler is all implemented in Java code too. Therefore, a stack overflow must be
 * detected while there is still sufficient stack memory available to execute these Java functions
 * (and they of course must not recursively throw a {@link StackOverflowError} again). Therefore, we
 * designate two zones toward the end of the stack: the "yellow zone" and the "red zone".
 *
 * The yellow zone can be made available to regular Java code by modifying the thread local variable
 * that holds the boundary. Calling {@link #makeYellowZoneAvailable()} makes the yellow zone
 * available, and a matching call of {@link #protectYellowZone()} makes the yellow zone unavailable
 * again for usage. The yellow zone is fairly commonly used by Substrate VM Java code, but it is not
 * intended for application code, i.e., the yellow zone should not be made available in places where
 * the VM calls back to application code. {@link VMOperation} such as the GC always make the yellow
 * zone available: a {@link StackOverflowError} during GC would be a fatal error because
 * interrupting GC leaves the heap in an inconsistent state. So it is better to make the yellow zone
 * available upfront. The yellow zone is sized (by empirical measurement and testing) so that a GC
 * can be executed in it.
 *
 * The red zone is reserved for last-resort error handling on the Java and C side. Code marked as
 * {@link Uninterruptible} can use the red zone because such methods do not start with a stack
 * overflow check. C code can also use the red zone, e.g., for exiting the VM in case of a fatal
 * error. The red zone is small and sized just to do something slightly better than a segmentation
 * fault.
 */
public interface StackOverflowCheck {

    class Options {
        @Option(help = "Size (in bytes) of the yellow zone reserved at the end of the stack. This stack space is reserved for VM use and cannot be used by the application.")//
        public static final HostedOptionKey<Integer> StackYellowZoneSize = new HostedOptionKey<>(32 * 1024);

        @Option(help = "Size (in bytes) of the red zone r