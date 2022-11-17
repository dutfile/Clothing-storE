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
            public ExecutionEventNode create(EventContext context) {
                Node instrumentedNode = context.getInstrumentedNode();
                if (instrumentedNode.getSourceSection() == null) {
                    logger.warning("Instrumented node " + instrumentedNode + " has null SourceSection.");
                    return null;
                }
                return new StackPushPopNode(ShadowStack.this, instrumenter, context, compiledOnly);
            }
        });
    }

    ArrayList<StackTraceEntry> getInitialStack(Node instrumentedNode) {
        ArrayList<StackTraceEntry> sourceLocations = new ArrayList<>();
        reconstructStack(sourceLocations, instrumentedNode, sourceSectionFilter, initInstrumenter);
        Truffle.getRuntime().iterateFrames(frame -> {
            Node node = frame.getCallNode();
            if (node != null) {
                reconstructStack(sourceLocations, node, sourceSectionFilter, initInstrumenter);
            }
            return null;
        });
        Collections.reverse(sourceLocations);
        return sourceLocations;
    }

    private static void reconstructStack(ArrayList<StackTraceEntry> sourceLocations, Node node, SourceSectionFilter sourceSectionFilter, Instrumenter instrumenter) {
        if (node == null || sourceSectionFilter == null) {
            return;
        }
        // We exclude the node itself as it will be pushed on the stack by the StackPushPopNode
        Node current = node.getParent();
        while (current != null) {
            if (sourceSectionFilter.includes(current) && current.getSourceSection() != null) {
                sourceLocations.add(new StackTraceEntry(instrumenter, current, 0, true));
            }
            current = current.getParent();
        }
    }

    private static class StackPushPopNode extends ExecutionEventNode {

        private final ShadowStack profilerStack;

        private final StackTraceEntry compilationRootLocation;
        private final StackTraceEntry compiledLocation;
        private final StackTraceEntry interpretedLocation;

        private final Thread cachedThread;
        private final ThreadLocalStack cachedStack;

        @CompilationFinal private boolean seenOtherThreads;
        @CompilationFinal final boolean isAttachedToRootTag;
        @CompilationFinal final boolean ignoreInlinedRoots;

        StackPushPopNode(ShadowStack profilerStack, Instrumenter instrumenter, EventContext context, boolean ignoreInlinedRoots) {
            this.profilerStack = profilerStack;
            this.cachedThread = Thread.currentThread();
            this.interpretedLocation = new StackTraceEntry(instrumenter, context, 0, true);
            this.compiledLocation = new StackTraceEntry(interpretedLocation, 2, false);
            this.compilationRootLocation = new StackTraceEntry(interpretedLocation, 2, true);
            this.isAttachedToRootTag = context.hasTag(StandardTags.RootTag.class);
            this.ignoreInlinedRoots = ignoreInlinedRoots;
            this.cachedStack = getStack();
        }

        @Override
        protected void onEnter(VirtualFrame frame) {
            if (CompilerDirectives.inCompiledCode() && ignoreInlinedRoots && isAttachedToRootTag && !CompilerDirectives.inCompilationRoot()) {
                return;
            }
            doOnEnter();
        }

        private void doOnEnter() {
            StackTraceEntry location = CompilerDirectives.inInterpreter() ? interpretedLocation : (CompilerDirectives.inCompilationRoot() ? compiledLocation : compilationRootLocation);
            if (seenOtherThreads) {
                pushSlow(location);
            } else if (cachedThread == Thread.currentThread()) {
                cachedStack.push(location);
            } else {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                seenOtherThreads = true;
                pushSlow(location);
            }
        }

        @TruffleBoundary
        private void pushSlow(StackTraceEntry location) {
            getStack().push(location);
        }

        @Override
        protected void onReturnExceptional(VirtualFrame frame, Throwable exception) {
            onReturnValue(frame, null);
        }

        @Override
        protected void onReturnValue(VirtualFrame frame, Object result) {
            if (ignoreInlinedRoots) {
                if (CompilerDirectives.inCompiledCode()) {
                    if (isAttachedToRootTag && !CompilerDirectives.inCompilationRoot()) {
                        return;
                    }
                } else {
                    // This is needed to control for the case that an invalidation happened in an
                    // inlined root.
                    // Than there should be no stack pop until we exit the original compilation
                    // root.
                    // Not needed if stack overflowed
                    final ThreadLocalStack stack = getStack();
                    if (!stack.hasStackOverflowed() &&
                                    stack.top().getInstrumentedNode() != interpretedLocation.getInstrumentedNode()) {

                        return;
                    }
                }
            }

            if (seenOtherThreads) {
                popSlow(compiledLocation);
            } else if (cachedThread == Thread.currentThread()) {
                cachedStack.pop(compiledLocation);
            } else {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                seenOtherThreads = true;
                popSlow(compiledLocation);
            }
        }

        @TruffleBoundary
        private void popSlow(StackTraceEntry entry) {
            getStack().pop(entry);
        }

        @TruffleBoundary
        private ThreadLocalStack getStack() {
            Thread currentThread = Thread.currentThread();
            ThreadLocalStack stack = profilerStack.stacks.get(currentThread);
            if (stack == null) {
                stack = profilerStack.new ThreadLocalStack(currentThread);
                ThreadLocalStack prevStack = profilerStack.stacks.putIfAbsent(currentThread, stack);
                if (prevStack != null) {
                    stack = prevStack;
                }
            }
            return stack;
        }

        @Override
        public NodeCost getCost() {
            return NodeCost.NONE;
        }

    }

    final class ThreadLocalStack {

        /*
         * Window in which we look ahead and before the current stack index to find the potentially
         * changed top of stack index, after copying.
         */
        privat