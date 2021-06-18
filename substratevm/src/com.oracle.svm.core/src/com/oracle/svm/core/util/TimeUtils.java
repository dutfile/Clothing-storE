/*
 * Copyright (c) 2016, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.util;

import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.jdk.UninterruptibleUtils;
import com.oracle.svm.core.log.Log;

public class TimeUtils {

    public static final long millisPerSecond = 1_000L;
    public static final long microsPerSecond = 1_000_000L;
    public static final long nanosPerSecond = 1_000_000_000L;
    public static final long nanosPerMilli = nanosPerSecond / millisPerSecond;
    public static final long microsPerNano = nanosPerSecond / microsPerSecond;

    /** Convert the given number of seconds to milliseconds. */
    public static long secondsToMillis(long seconds) {
        return multiplyOrMaxValue(seconds, millisPerSecond);
    }

    /** Convert the given number of seconds to nanoseconds. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static long secondsToNanos(long seconds) {
        return multiplyOrMaxValue(seconds, nanosPerSecond);
    }

    /** Convert the given number of milliseconds to nanoseconds. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static long millisToNanos(long millis) {
        return multiplyOrMaxValue(millis, nanosPerMilli);
    }

    /** Convert the given number of microseconds to nanoseconds. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static long microsToNanos(long micros) {
        return multiplyOrMaxValue(micros, microsPerNano);
    }

    /** Nanoseconds since a previous {@link System#nanoTime()} call. */
    public static long nanoSecondsSince(long startNanos) {
        return (System.nanoTime() - startNanos);
    }

    /**
     * Compare two nanosecond times.
     *
     * Do not compare {@link System#nanoTime()} results as signed longs! Only subtract them.
     */
    public static boolean nanoTimeLessThan(long leftNanos, long rightNanos) {
        return ((leftNanos - rightNanos) < 0L);
    }

    /** Return the number of seconds in the given number of nanoseconds. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static long divideNanosToSeconds(long nanos) {
        return (nanos / nanosPerSecond);
    }

    public static double nanosToSecondsDouble(long nanos) {
        return (nanos / (double) nanosPerSecond);
    }

    /** Return the nanoseconds 