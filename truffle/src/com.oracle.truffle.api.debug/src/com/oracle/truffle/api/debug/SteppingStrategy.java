/*
 * Copyright (c) 2015, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.debug;

import com.oracle.truffle.api.instrumentation.EventContext;
import com.oracle.truffle.api.instrumentation.ProbeNode;

/**
 * Implementation of a strategy for a debugger <em>action</em> that allows execution to continue
 * until it reaches another location e.g "step in" vs. "step over".
 */
abstract class SteppingStrategy {

    /*
     * Indicates that a stepping strategy was consumed by an suspended event.
     */
    private boolean consumed;
    private SteppingStrategy next;

    void consume() {
        consumed = true;
    }

    boolean isConsumed() {
        return consumed;
    }

    void notifyCallEntry() {
    }

    void notifyCallExit() {
    }

    @SuppressWarnings("unused")
    void notifyNodeEntry(EventContext context) {
    }

    @SuppressWarnings("unused")
    void notifyNodeExit(EventContext context) {
    }

    Object notifyOnUnwind() {
        return null;
    }

    boolean isStopAfterCall() {
        return true;
    }

    boolean isCollectingInputValues() {
        return false;
    }

    /**
     * Like {@link #isActive(EventContext, SuspendAnchor)}, but is called on a node entry/return
     * only. It allows to include the node entry/return events to call entry/exit events for cases
     * when the step over/out is not determined by pushed frames only, but pushed nodes also.
     */
    final boolean isActiveOnStepTo(EventContext context, SuspendAnchor suspendAnchor) {
        if (SuspendAnchor.BEFORE == suspendAnchor) {
            notifyNodeEntry(context);
        } else {
            notifyNodeExit(context);
        }
        return isActive(context, suspendAnchor);
    }

    /**
     * Test if the strategy is active at this context. If yes,
     * {@link #step(DebuggerSession, EventContext, SuspendAnchor)} will be called.
     */
    @SuppressWarnings("unused")
    boolean isActive(EventContext context, SuspendAnchor suspendAnchor) {
        return true;
    }

    abstract boolean step(DebuggerSession steppingSession, EventContext context, SuspendAnchor suspendAnchor);

    @SuppressWarnings("unused")
    void initialize(SuspendedContext context, SuspendAnchor suspendAnchor) {
    }

    boolean isDone() {
        return false;
    }

    boolean isUnwind() {
        return false;
    }

    boolean isKill() {
        return false;
    }

    boolean isComposable() {
        return false;
    }

    @SuppressWarnings("unused")
    void add(SteppingStrategy nextStrategy) {
        throw new UnsupportedOperationException("Not composable.");
    }

    static SteppingStrategy createKill() {
        return new Kill();
    }

    static SteppingStrategy createAlwaysHalt() {
        return new AlwaysHalt();
    }

    static SteppingStrategy createContinue() {
        return new Continue();
    }

    static SteppingStrategy createStepInto(DebuggerSession session, StepConfig config) {
        return new StepInto(session, config);
    }

    static SteppingStrategy createStepOut(DebuggerSession session, StepConfig config) {
        return new StepOut(session, config);
    }

    static SteppingStrategy createStepOver(DebuggerSession session, StepConfig config) {
        return new StepOver(session, config);
    }

    static SteppingStrategy createUnwind(int depth, DebugValue returnValue) {
        return new Unwind(depth, returnValue);
    }

    static SteppingStrategy createComposed(SteppingStrategy strategy1, SteppingStrategy strategy2) {
        return new ComposedStrategy(strategy1, strategy2);
    }

    private static final class Kill extends SteppingStrategy {

        @Override
        boolean step(DebuggerSession steppingSession, EventContext context, SuspendAnchor suspendAnchor) {
            return true;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public boolean isKill() {
            return true;
        }

        @Override
        public String toString() {
            return "KILL";
        }

    }

    private static final class AlwaysHalt extends SteppingStrategy {

        @Override
        boolean isActive(EventContext context, SuspendAnchor suspendAnchor) {
            return SuspendAnchor.BEFORE == suspendAnchor;
        }

        @Override
        boolean step(DebuggerSession steppingSession, EventContext context, SuspendAnchor suspendAnchor) {
            return Sus