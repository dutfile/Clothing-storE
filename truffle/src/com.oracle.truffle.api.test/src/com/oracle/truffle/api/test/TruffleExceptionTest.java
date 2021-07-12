/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.test;

import static com.oracle.truffle.api.test.RootNodeTest.verifyStackTraceElementGuestObject;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLogger;
import com.oracle.truffle.api.TruffleStackTrace;
import com.oracle.truffle.api.TruffleStackTraceElement;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.ExceptionType;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.test.polyglot.AbstractPolyglotTest;
import com.oracle.truffle.api.test.polyglot.ProxyLanguage;
import com.oracle.truffle.tck.tests.TruffleTestAssumptions;

public class TruffleExceptionTest extends AbstractPolyglotTest {

    @BeforeClass
    public static void runWithWeakEncapsulationOnly() {
        TruffleTestAssumptions.assumeWeakEncapsulation();
    }

    private VerifyingHandler verifyingHandler;

    @Before
    public void setUp() {
        verifyingHandler = new VerifyingHandler(AbstractTruffleException.class);
    }

    @Test
    public void testTruffleException() {
        setupEnv(createContext(verifyingHandler), new ProxyLanguage() {
            @Override
            protected CallTarget parse(TruffleLanguage.ParsingRequest request) throws Exception {
                return createAST(AbstractTruffleException.class, languageInstance, (n) -> new TruffleExceptionImpl("Test exception", n), false);
            }
        });
        verifyingHandler.expect(BlockNode.Kind.TRY, BlockNode.Kind.CATCH, BlockNode.Kind.FINALLY);
        context.eval(ProxyLanguage.ID, "Test");
    }

    @Test
    public void testTruffleExceptionCustomGuestObject() {
        setupEnv(createContext(verifyingHandler), new ProxyLanguage() {
            @Override
            protected CallTarget parse(TruffleLanguage.ParsingRequest request) throws Exception {
                return createAST(AbstractTruffleException.class, languageInstance, (n) -> new TruffleExceptionImpl("Test exception", n), true);
            }
        });
        verifyingHandler.expect(BlockNode.Kind.TRY, BlockNode.Kind.CATCH, BlockNode.Kind.FINALLY);
        context.eval(ProxyLanguage.ID, "Test");
    }

    @Test
    public void testPolyglotStackTrace() {
        testStackTraceImpl(new ProxyLanguage() {
            @Override
            protected CallTarget parse(TruffleLanguage.ParsingRequest request) throws Exception {
                ThrowNode throwNode = new ThrowNode((n) -> {
                    return new TruffleExceptionImpl("Test exception", n);
                });
                return new TestRootNode(languageInstance, "test", null, throwNode).getCallTarget();
            }
        },
                        "<proxyLanguage> test",
                        "(org.graalvm.sdk/)?org.graalvm.polyglot.Context.eval");
    }

    @Test
    public void testPolyglotStackTrace2() {
        testStackTraceImpl(new ProxyLanguage() {
            @Override
            protected CallTarget parse(TruffleLanguage.ParsingRequest request) throws Exception {
                ThrowNode throwNode = new ThrowNode((n) -> {
                    return new TruffleExceptionImpl("Test exception", n);
                });
                CallTarget throwTarget = new TestRootNode(languageInstance, "test-throw", null, throwNode).getCallTarget();
                CallTarget innerInvokeTarget = new TestRootNode(languageInstance, "test-call-inner", null, new InvokeNode(throwTarget)).getCallTarget();
                CallTarget outerInvokeTarget = new TestRootNode(languageInstance, "test-call-outer", null, new InvokeNode(innerInvokeTarget)).getCallTarget();
                return outerInvokeTarget;
            }
        },
                        "<proxyLanguage> test-throw",
                        "<proxyLanguage> test-call-inner",
                        "<proxyLanguage> test-call-outer",
                        "(org.graalvm.sdk/)?org.graalvm.polyglot.Context.eval");
    }

    @Test
    public void testPolyglotStackTraceInternalFrame() {
        testStackTraceImpl(new ProxyLanguage() {
            @Override
            protected CallTarget parse(TruffleLanguage.ParsingRequest request) throws Exception {
                ThrowNode throwNode = new ThrowNode((n) -> {
                    return new TruffleExceptionImpl("Test exception", n);
                });
                CallTarget throwTarget = new TestRootNode(languageInstance, "test-throw-internal", null, true, throwNode).getCallTarget();
                CallTa