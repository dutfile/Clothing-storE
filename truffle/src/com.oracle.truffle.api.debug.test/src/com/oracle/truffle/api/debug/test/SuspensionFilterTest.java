/*
 * Copyright (c) 2017, 2019, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.debug.test;

import java.util.Collections;
import java.util.HashMap;
import java.util.function.Predicate;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.debug.SuspendedEvent;
import com.oracle.truffle.api.debug.SuspensionFilter;
import com.oracle.truffle.api.instrumentation.test.InstrumentationTestLanguage;

import org.graalvm.polyglot.Source;

public class SuspensionFilterTest extends AbstractDebugTest {

    @After
    public void tearDown() {
        InstrumentationTestLanguage.envConfig = null;
    }

    @Test
    public void testSuspendInInitialization() {
        Source initSource = Source.newBuilder(InstrumentationTestLanguage.ID, "STATEMENT(EXPRESSION)", "<init>").buildLiteral();
        InstrumentationTestLanguage.envConfig = Collections.singletonMap("initSource", initSource);
        final Source source = testSource("ROOT(\n" +
                        "  DEFINE(foo, \n" +
                        "    STATEMENT(CONSTANT(42))\n" +
                        "  ), \n" +
                        "  STATEMENT(CALL(foo))\n" +
                        ")\n");

        SuspensionFilter suspensionFilter = SuspensionFilter.newBuilder().build();
        // Empty filter does not filter anything
        try (DebuggerSession session = startSession()) {
            session.setSteppingFilter(suspensionFilter);
            session.suspendNextExecution();
            startEval(source);

            expectSuspended((SuspendedEvent event) -> {
                checkState(event, 1, true, "STATEMENT(EXPRESSION)").prepareContinue();
                Assert.assertFalse(event.isLanguageContextInitialized());
            });
            expectDone();
        }
    }

    @Test
    public void testSuspendAfterInitialization() {
        Source initSource = Source.newBuilder(InstrumentationTestLanguage.ID, "STATEMENT(EXPRESSION)", "<init>").buildLiteral();
        InstrumentationTestLanguage.envConfig = Collections.singletonMap("initSource", initSource);
        final Source source = testSource("ROOT(\n" +
                        "  STATEMENT(CONSTANT(42))\n" +
                        ")\n");

        SuspensionFilter suspensionFilter = SuspensionFilter.newBuilder().ignoreLanguageContextInitialization(true).build();
        try (DebuggerSession session = startSession()) {
            session.setSteppingFilter(suspensionFilter);
            session.suspendNextExecution();
            startEval(source);

            expectSuspended((SuspendedEvent event) -> {
                checkState(event, 2, true, "STATEMENT(CONSTANT(42))").prepareContinue();
                Assert.assertTrue(event.isLanguageContextInitialized());
            });
            expectDone();
        }
    }

    @Test
    public void testSuspendAfterInitialization2() {
        // Suspend after initialization code finishes,
        // but can step into the same code that was executed during initialization, later on.
        Source initSource = Source.newBuilder(InstrumentationTestLanguage.ID, "STATEMENT(EXPRESSION)", "<init>").buildLiteral();
        InstrumentationTestLanguage.envConfig = new HashMap<>();
        InstrumentationTestLanguage.envConfig.put("initSource", initSource);
        InstrumentationTestLanguage.envConfig.put("runInitAfterExec", true);
        final Source source = testSource("ROOT(\n" +
                        "  STATEMENT(CONSTANT(42))\n" +
                        ")\n");

        SuspensionFilter suspensionFilter = SuspensionFilter.newBuilder().ignoreLanguageContextInitialization(true).build();
        try (DebuggerSession session = startSession()) {
            session.setSteppingFilter(suspensionFilter);
            session.suspendNextExecution();
            startEval(source);

            expectSuspended((SuspendedEvent event) -> {
                checkState(event, 2, true, "STATEMENT(CONSTANT(42))").prepareStepOver(1);
                Assert.assertTrue(event.isLanguageContextInitialized());
                session.suspendNextExecution();
            });
            expectSuspended((SuspendedEvent event) -> {
                checkState(event, 1, true, "STATEMENT(EXPRESSION)").prepareContinue();
                Assert.assertTrue(event.isLanguageContextInitialized());
            });
            expectDone();
        }
    }

    @Test
    public void testInitializationFilterChange() {
        // Set to skip the initialization, but put two breakpoints there.
        // Verify that step just skips the code to the next breakpoint.
        // After second breakpoint is hit, change the filter to allow stepping
        // in the initialization code.
        String initCode = "ROOT(\n" +
                        "  DEFINE(initFoo, \n" +
                        "    STATEMENT(EXPRESSION),\n" +    // Skipped by suspensionFilter
                        "    STATEMENT(EXPRESSION),\n" +    // l. 4 Breakpoint
                        "    STATEMENT(CONSTANT(2)),\n" +   // Skipped by suspensionFilter
                        "    STATEMENT(EXPRESSION),\n" +    // l. 6 Breakpoint, filter changed
                        "    LOOP(2,\n" +
                        "      STATEMENT(CONSTANT(1)))\n" + // l. 8 Step stops here
                        "  ), \n" +
                        "  STATEMENT(CALL(initFoo))\n