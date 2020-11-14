/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.tools.dap.test;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

import org.graalvm.polyglot.Source;

import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;

@Ignore("GR-43473")
public final class SimpleLanguageDAPTest {

    private static final String FACTORIAL = "function factorial(n) {\n" +
                    "  f = 1;\n" +
                    "  i = 2;\n" +
                    "  while (i <= n) {\n" +
                    "    f2 = f * i;\n" +
                    "    i = i + 1;\n" +
                    "    f = f2;\n" +
                    "  }\n" +
                    "  return f;\n" +
                    "}";
    private static final String CODE1 = "function main() {\n" +
                    "  a = 10;\n" +
                    "  b = factorial(a/2) / 60;\n" +
                    "  while (b > 0) {\n" +
                    "    c = a + b;\n" +
                    "    b = b - c/10;\n" +
                    "  }\n" +
                    "  return b;\n" +
                    "}\n" + FACTORIAL;
    private static final String CODE2 = "function main() {\n" +
                    "  n = 10;\n" +
                    "  i = 0;\n" +
                    "  while (i < n) {\n" +
                    "    fceWithBP(i);\n" +
                    "    i = i + 1;\n" +
                    "  }\n" +
                    "}\n" +
                    "function fceWithBP(i) {\n" +
                    "  i2 = i*i;\n" +
                    "  return i2;\n" +
                    "}";
    private static final String CODE3 = "function main() {\n" +
                    "  n = 10;\n" +
                    "  testLocations(n);\n" +
                    "}\n" +
                    "function testLocations(n) {\n" +
                    "  \n" +
                    "  x =\n" +
                    "    n * n;\n" +
                    "  y =\n" +
                    "    n / 2;\n" +
                    "  \n" +
                    "  x = x + y; y = x / y; return x * y;\n" +
                    "  \n" +
                    "}";
    private static final String CODE_RET_VAL = "function main() {\n" +
                    "  a = addThem(1, 2);\n" +
                    "  println(a);\n" +
                    "}\n" +
                    "function addThem(a, b) {\n" +
                    "  a = fn(a);\n" +
                    "  b = fn(b);\n" +
                    "  return a + b;\n" +
                    "}\n" +
                    "\n" +
                    "function fn(n) {\n" +
                    "  return n;\n" +
                    "}\n";
    private static final String CODE_THROW = "function main() {\n" +
                    "  i = \"0\";\n" +
                    "  return invert(i);\n" +
                    "}\n" +
                    "function invert(n) {\n" +
                    "  x = 10 / n;\n" +
                    "  return x;\n" +
                    "}\n";
    private static final String CODE_VARS = "function main() {\n" +
                    "  n = 2;\n" +
                    "  m = 2 * n;\n" +
                    "  b = n > 0;\n" +
                    "  bb = m > 0;\n" +
                    "  big = 12345678901234567890;\n" +
                    "  str = \"A String\";\n" +
                    "  //obj = new();\n" +
                    "  f = fn;\n" +
                    "  f2 = 0;\n" +
                    "  while (b) {\n" +
                    "    n = n - 1;\n" +
                    "    //obj.a = n;\n" +
                    "    big = big * big;\n" +
                    "    b = n > 0;\n" +
                    "    b;\n" +
                    "  }\n" +
                    "  return b;\n" +
                    "}\n" +
                    "\n" +
                    "function fn() {\n" +
                    "  return 2;\n" +
                    "}\n";
    private static final String GUEST_FUNCTIONS = "function main() {\n" +
                    "  foo0();\n" +
                    "  foo1();\n" +
                    "  foo0();\n" +
                    "  foo1();\n" +
                    "}\n" +
                    "function foo0() {\n" +
                    "  n = 0;" +
                    "}\n" +
                    "function foo1() {\n" +
                    "  n = 1;" +
                    "}\n";
    private static final String BUILTIN_FUNCTIONS = "function main() {\n" +
                    "  isExecutable(a);\n" +
                    "  nanoTime();\n" +
                    "  isNull(a);\n" +
                    "  isExecutable(a);\n" +
                    "  isNull(b);\n" +
                    "  nanoTime();\n" +
                    "}\n";

    private static final URI testURI = URI.create("file:///test/SLTest.sl");
    private static final File testFile = new File(testURI);
    private static final String testFilePath = getFilePath(testFile);
    private static final String NL = replaceNewLines(System.getProperty("line.separator"));

    private DAPTester tester;

    @After
    public void tearDown() {
        tester = null;
    }

    // @formatter:off   The default formatting makes unnecessarily big indents and illogical line breaks
    // CheckStyle: stop line length check
    @Test
    public void testInitialSuspendAndSource() throws Exception {
        tester = DAPTester.start(true);
        Source source = Source.newBuilder("sl", CODE1, "SLTest.sl").uri(testURI).build();
        tester.sendMessage("{\"command\":\"initialize\",\"arguments\":{\"clientID\":\"DAPTester\",\"clientName\":\"DAP Tester\",\"adapterID\":\"graalvm\",\"pathFormat\":\"path\",\"linesStartAt1\":true,\"columnsStartAt1\":true,\"supportsVariableType\":true,\"supportsVariablePaging\":true,\"supportsRunInTerminalRequest\":true,\"locale\":\"en-us\",\"supportsProgressReporting\":true},\"type\":\"request\",\"seq\":1}");
        tester.compareReceivedMessages(
                "{\"event\":\"initialized\",\"type\":\"event\"}",
                "{\"success\":true,\"type\":\"response\",\"body\":{\"supportsConditionalBreakpoints\":true,\"supportsLoadedSourcesRequest\":true,\"supportsFunctionBreakpoints\":true,\"supportsExceptionInfoRequest\":true,\"supportsBreakpointLocationsRequest\":true,\"supportsHitConditionalBreakpoints\":true,\"supportsLogPoints\":true,\"supportsSetVariable\":true,\"supportsConfigurationDoneRequest\":true,\"exceptionBreakpointFilters\":[{\"filter\":\"all\",\"label\":\"All Exceptions\"},{\"filter\":\"uncaught\",\"label\":\"Uncaught Exceptions\"}]},\"request_seq\":1,\"command\":\"initialize\"}"
        );
        tester.sendMessage("{\"command\":\"attach\",\"arguments\":{\"type\":\"graalvm\",\"request\":\"attach\",\"name\":\"Attach\",\"port\":9229,\"protocol\":\"chromeDevTools\"},\"type\":\"request\",\"seq\":2}");
        tester.compareReceivedMessages("{\"event\":\"output\",\"body\":{\"output\":\"Debugger attached.\",\"category\":\"stderr\"},\"type\":\"event\"}", "{\"success\":true,\"type\":\"response\",\"request_seq\":2,\"command\":\"attach\"}");
        tester.sendMessage("{\"command\":\"loadedSources\",\"type\":\"request\",\"seq\":3}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"sources\":[]},\"type\":\"response\",\"request_seq\":3,\"command\":\"loadedSources\",\"seq\":5}");
        tester.sendMessage("{\"command\":\"configurationDone\",\"type\":\"request\",\"seq\":4}");
        tester.compareReceivedMessages("{\"success\":true,\"type\":\"response\",\"request_seq\":4,\"command\":\"configurationDone\",\"seq\":6}");
        tester.eval(source);
        tester.compareReceivedMessages("{\"event\":\"thread\",\"body\":{\"threadId\":1,\"reason\":\"started\"},\"type\":\"event\",\"seq\":7}");
        tester.compareReceivedMessages(
                "{\"event\":\"loadedSource\",\"body\":{\"reason\":\"new\",\"source\":{\"sourceReference\":1,\"name\":\"SL builtin\"}},\"type\":\"event\",\"seq\":8}",
                "{\"event\":\"loadedSource\",\"body\":{\"reason\":\"new\",\"source\":{\"sourceReference\":2,\"path\":\"" + testFilePath + "\",\"name\":\"SLTest.sl\"}},\"type\":\"event\",\"seq\":9}"
        );
        // Suspend at the beginning of the script:
        tester.compareReceivedMessages("{\"event\":\"stopped\",\"body\":{\"threadId\":1,\"reason\":\"debugger_statement\",\"description\":\"Paused on debugger statement\"},\"type\":\"event\",\"seq\":10}");
        tester.sendMessage("{\"command\":\"threads\",\"type\":\"request\",\"seq\":5}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"threads\":[{\"name\":\"testRunner\",\"id\":1}]},\"type\":\"response\",\"request_seq\":5,\"command\":\"threads\",\"seq\":11}");
        tester.sendMessage("{\"command\":\"stackTrace\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":6}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"stackFrames\":[{\"line\":2,\"name\":\"main\",\"column\":3,\"id\":1,\"source\":{\"sourceReference\":2,\"path\":\"" + testFilePath + "\",\"name\":\"SLTest.sl\"}}],\"totalFrames\":1},\"type\":\"response\",\"request_seq\":6,\"command\":\"stackTrace\",\"seq\":12}");
        // Get loaded sources
        tester.sendMessage("{\"command\":\"loadedSources\",\"type\":\"request\",\"seq\":7}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"sources\":[{\"sourceReference\":1,\"name\":\"SL builtin\"},{\"sourceReference\":2,\"path\":\"" + testFilePath + "\",\"name\":\"SLTest.sl\"}]},\"type\":\"response\",\"request_seq\":7,\"command\":\"loadedSources\",\"seq\":13}");
        // Get the script code:
        tester.sendMessage("{\"command\":\"source\",\"arguments\":{\"source\":{\"sourceReference\":2,\"path\":\"" + testFilePath + "\",\"name\":\"SLTest.sl\"},\"sourceReference\":2},\"type\":\"request\",\"seq\":8}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"content\":\"" + replaceNewLines(source.getCharacters().toString()) + "\"},\"type\":\"response\",\"request_seq\":8,\"command\":\"source\",\"seq\":14}");
        // Continue to finish
        tester.sendMessage("{\"command\":\"continue\",\"arguments\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"request\",\"seq\":9}");
        tester.compareReceivedMessages(
                "{\"event\":\"continued\",\"body\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"event\"}",
                "{\"success\":true,\"body\":{\"allThreadsContinued\":false},\"type\":\"response\",\"request_seq\":9,\"command\":\"continue\"}"
        );
        tester.finish();
    }

    @Test
    public void testStepping() throws Exception {
        tester = DAPTester.start(true);
        Source source = Source.newBuilder("sl", CODE1, "SLTest.sl").uri(testURI).build();
        tester.sendMessage("{\"command\":\"initialize\",\"arguments\":{\"clientID\":\"DAPTester\",\"clientName\":\"DAP Tester\",\"adapterID\":\"graalvm\",\"pathFormat\":\"path\",\"linesStartAt1\":true,\"columnsStartAt1\":true,\"supportsVariableType\":true,\"supportsVariablePaging\":true,\"supportsRunInTerminalRequest\":true,\"locale\":\"en-us\",\"supportsProgressReporting\":true},\"type\":\"request\",\"seq\":1}");
        tester.compareReceivedMessages(
                "{\"event\":\"initialized\",\"type\":\"event\"}",
                "{\"success\":true,\"type\":\"response\",\"body\":{\"supportsConditionalBreakpoints\":true,\"supportsLoadedSourcesRequest\":true,\"supportsFunctionBreakpoints\":true,\"supportsExceptionInfoRequest\":true,\"supportsBreakpointLocationsRequest\":true,\"supportsHitConditionalBreakpoints\":true,\"supportsLogPoints\":true,\"supportsSetVariable\":true,\"supportsConfigurationDoneRequest\":true,\"exceptionBreakpointFilters\":[{\"filter\":\"all\",\"label\":\"All Exceptions\"},{\"filter\":\"uncaught\",\"label\":\"Uncaught Exceptions\"}]},\"request_seq\":1,\"command\":\"initialize\"}"
        );
        tester.sendMessage("{\"command\":\"attach\",\"arguments\":{\"type\":\"graalvm\",\"request\":\"attach\",\"name\":\"Attach\",\"port\":9229,\"protocol\":\"chromeDevTools\"},\"type\":\"request\",\"seq\":2}");
        tester.compareReceivedMessages("{\"event\":\"output\",\"body\":{\"output\":\"Debugger attached.\",\"category\":\"stderr\"},\"type\":\"event\"}", "{\"success\":true,\"type\":\"response\",\"request_seq\":2,\"command\":\"attach\"}");
        tester.sendMessage("{\"command\":\"loadedSources\",\"type\":\"request\",\"seq\":3}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"sources\":[]},\"type\":\"response\",\"request_seq\":3,\"command\":\"loadedSources\",\"seq\":5}");
        tester.sendMessage("{\"command\":\"configurationDone\",\"type\":\"request\",\"seq\":4}");
        tester.compareReceivedMessages("{\"success\":true,\"type\":\"response\",\"request_seq\":4,\"command\":\"configurationDone\",\"seq\":6}");
        tester.eval(source);
        tester.compareReceivedMessages("{\"event\":\"thread\",\"body\":{\"threadId\":1,\"reason\":\"started\"},\"type\":\"event\",\"seq\":7}");
        tester.compareReceivedMessages(
                "{\"event\":\"loadedSource\",\"body\":{\"reason\":\"new\",\"source\":{\"sourceReference\":1,\"name\":\"SL builtin\"}},\"type\":\"event\",\"seq\":8}",
                "{\"event\":\"loadedSource\",\"body\":{\"reason\":\"new\",\"source\":{\"sourceReference\":2,\"path\":\"" + testFilePath + "\",\"name\":\"SLTest.sl\"}},\"type\":\"event\",\"seq\":9}"
        );
        // Suspend at the beginning of the script:
        tester.compareReceivedMessages("{\"event\":\"stopped\",\"body\":{\"threadId\":1,\"reason\":\"debugger_statement\",\"description\":\"Paused on debugger statement\"},\"type\":\"event\",\"seq\":10}");
        tester.sendMessage("{\"command\":\"threads\",\"type\":\"request\",\"seq\":5}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"threads\":[{\"name\":\"testRunner\",\"id\":1}]},\"type\":\"response\",\"request_seq\":5,\"command\":\"threads\",\"seq\":11}");
        tester.sendMessage("{\"command\":\"stackTrace\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":6}");
   