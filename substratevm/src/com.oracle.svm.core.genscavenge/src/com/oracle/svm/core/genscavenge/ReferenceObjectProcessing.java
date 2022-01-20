/*
 * Copyright (c) 2014, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.genscavenge;

import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.SLOW_PATH_PROBABILITY;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.probability;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;

import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.AlwaysInline;
import com.oracle.svm.core.genscavenge.remset.RememberedSet;
import com.oracle.svm.core.heap.Heap;
import com.oracle.svm.core.heap.ObjectReferenceVisitor;
import com.oracle.svm.core.heap.ReferenceInternals;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.snippets.KnownIntrinsics;
import com.oracle.svm.core.thread.VMOperation;
import com.oracle.svm.core.util.UnsignedUtils;

/** Discovers and handles {@link Reference} objects during garbage collection. */
final class ReferenceObjectProcessing {
    /** Head of the linked list of discovered references that need to be revisited. */
    private static Reference<?> rememberedRefsList;

    /**
     * For a {@link SoftReference}, the longest duration after its last access to keep its referent
     * alive. Determined at the end of a collection to be applied during the next collection.
     */
    private static UnsignedWord maxSoftRefAccessIntervalMs = UnsignedUtils.MAX_VALUE;

    /** Treat all soft references as weak, typically to reclaim space when low on memory. */
    private static boolean softReferencesAreWeak = false;

    /**
     * The first timestamp that was set as {@link SoftReference} clock, for examining references
     * that were created earlier than that.
     */
    private static long initialSoftRefClock = 0;

    private ReferenceObjectProcessing() { // all static
    }

    /*
     * Enables (or disables) reclaiming all objects that are softly reachable only, typically as a
     * last resort to avoid running out of memory.
     */
    public static void setSoftReferencesAreWeak(boolean enabled) {
        assert VMOperation.isGCInProgress();
        softReferencesAreWeak = enabled;
    }

    @AlwaysInline("GC performance")
    public static void discoverIfReference(Object object, ObjectReferenceVisitor refVisitor) {
        assert 