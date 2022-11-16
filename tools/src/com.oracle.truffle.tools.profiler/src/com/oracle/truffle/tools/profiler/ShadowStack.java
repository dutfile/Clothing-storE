/*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.tools.profiler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLogger;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.EventBinding;
import com.oracle.truffle.api.instrumentation.EventContext;
import com.oracle.truffle.api.instrumentation.ExecutionEventNode;
import com.oracle.truffle.api.instrumentation.ExecutionEventNodeFactory;
import com.oracle.truffle.api.instrumentation.Instrumenter;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeCost;

/**
 * Custom more efficient stack representations for profilers.
 *
 * @since 0.30
 */
final class ShadowStack {

    private final ConcurrentHashMap<Thread, ThreadLocalStack> stacks = new ConcurrentHashMap<>();
    private final int stackLimit;
    private final SourceSectionFilter sourceSectionFilter;
    private final Instrumenter initInstrumenter;
    private final TruffleLogger logger;

    ShadowStack(int stackLimit, SourceSectionFilter sourceSectionFilter, Instrumenter instrumenter, TruffleLogger logger) {
        this.stackLimit = stackLimit;
        this.sourceSectionFilter = sourceSectionFilter;
        this.initInstrumenter = instrumenter;
        this.logger = logger;
    }

    ThreadLocalStack getStack(Thread thread) {
        return stacks.get(thread);
    }

    Collection<ThreadLocalStack> getStacks() {
        return stacks.values();
    }

    EventBinding<?> install(Instrumenter instrumenter, SourceSectionFilter filter, boolean compiledOnly) {
        return instrumenter.attachExecutionEventFactory(filter, new ExecutionEventNodeFactory() {
            public ExecutionEventNode create(E