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
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"stackFrames\":[{\"line\":2,\"name\":\"main\",\"column\":3,\"id\":1,\"source\":{\"sourceReference\":2,\"path\":\"" + testFilePath + "\",\"name\":\"SLTest.sl\"}}],\"totalFrames\":1},\"type\":\"response\",\"request_seq\":6,\"command\":\"stackTrace\",\"seq\":12}");
        // Next:
        tester.sendMessage("{\"command\":\"next\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":7}");
        tester.compareReceivedMessages(
                "{\"event\":\"continued\",\"body\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"event\"}",
                "{\"success\":true,\"type\":\"response\",\"request_seq\":7,\"command\":\"next\"}",
                "{\"event\":\"stopped\",\"body\":{\"threadId\":1,\"reason\":\"debugger_statement\",\"description\":\"Paused on debugger statement\"},\"type\":\"event\"}"
        );
        tester.sendMessage("{\"command\":\"threads\",\"type\":\"request\",\"seq\":8}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"threads\":[{\"name\":\"testRunner\",\"id\":1}]},\"type\":\"response\",\"request_seq\":8,\"command\":\"threads\",\"seq\":16}");
        tester.sendMessage("{\"command\":\"stackTrace\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":9}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"stackFrames\":[{\"line\":3,\"name\":\"main\",\"column\":3,\"id\":1,\"source\":{\"sourceReference\":2,\"path\":\"" + testFilePath + "\",\"name\":\"SLTest.sl\"}}],\"totalFrames\":1},\"type\":\"response\",\"request_seq\":9,\"command\":\"stackTrace\",\"seq\":17}");
        // Step in:
        tester.sendMessage("{\"command\":\"stepIn\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":10}");
        tester.compareReceivedMessages(
                "{\"event\":\"continued\",\"body\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"event\"}",
                "{\"success\":true,\"type\":\"response\",\"request_seq\":10,\"command\":\"stepIn\"}",
                "{\"event\":\"stopped\",\"body\":{\"threadId\":1,\"reason\":\"debugger_statement\",\"description\":\"Paused on debugger statement\"},\"type\":\"event\"}"
        );
        tester.sendMessage("{\"command\":\"threads\",\"type\":\"request\",\"seq\":11}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"threads\":[{\"name\":\"testRunner\",\"id\":1}]},\"type\":\"response\",\"request_seq\":11,\"command\":\"threads\",\"seq\":21}");
        tester.sendMessage("{\"command\":\"stackTrace\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":12}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"stackFrames\":[{\"line\":11,\"name\":\"factorial\",\"column\":3,\"id\":1,\"source\":{\"sourceReference\":2,\"path\":\"" + testFilePath + "\",\"name\":\"SLTest.sl\"}},{\"line\":3,\"name\":\"main\",\"column\":7,\"id\":2,\"source\":{\"sourceReference\":2,\"path\":\"" + testFilePath + "\",\"name\":\"SLTest.sl\"}}],\"totalFrames\":2},\"type\":\"response\",\"request_seq\":12,\"command\":\"stackTrace\",\"seq\":22}");
        // Step out:
        tester.sendMessage("{\"command\":\"stepOut\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":13}");
        tester.compareReceivedMessages(
                "{\"event\":\"continued\",\"body\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"event\"}",
                "{\"success\":true,\"type\":\"response\",\"request_seq\":13,\"command\":\"stepOut\"}",
                "{\"event\":\"stopped\",\"body\":{\"threadId\":1,\"reason\":\"debugger_statement\",\"description\":\"Paused on debugger statement\"},\"type\":\"event\"}"
        );
        tester.sendMessage("{\"command\":\"threads\",\"type\":\"request\",\"seq\":14}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"threads\":[{\"name\":\"testRunner\",\"id\":1}]},\"type\":\"response\",\"request_seq\":14,\"command\":\"threads\",\"seq\":26}");
        tester.sendMessage("{\"command\":\"stackTrace\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":15}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"stackFrames\":[{\"line\":3,\"name\":\"main\",\"column\":21,\"id\":1,\"source\":{\"sourceReference\":2,\"path\":\"" + testFilePath + "\",\"name\":\"SLTest.sl\"}}],\"totalFrames\":1},\"type\":\"response\",\"request_seq\":15,\"command\":\"stackTrace\",\"seq\":27}");
        // Step out to finish:
        tester.sendMessage("{\"command\":\"stepOut\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":16}");
        tester.compareReceivedMessages(
                "{\"event\":\"continued\",\"body\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"event\"}",
                "{\"success\":true,\"type\":\"response\",\"request_seq\":16,\"command\":\"stepOut\"}"
        );
        tester.finish();
    }

    @Test
    public void testBreakpoints() throws Exception {
        tester = DAPTester.start(false);
        File sourceFile = createTemporaryFile(CODE1);
        Source source = Source.newBuilder("sl", sourceFile).build();
        String sourceJson = "{\"name\":\"" + sourceFile.getName() + "\",\"path\":\"" + getFilePath(sourceFile) + "\"}";
        tester.sendMessage("{\"command\":\"initialize\",\"arguments\":{\"clientID\":\"DAPTester\",\"clientName\":\"DAP Tester\",\"adapterID\":\"graalvm\",\"pathFormat\":\"path\",\"linesStartAt1\":true,\"columnsStartAt1\":true,\"supportsVariableType\":true,\"supportsVariablePaging\":true,\"supportsRunInTerminalRequest\":true,\"locale\":\"en-us\",\"supportsProgressReporting\":true},\"type\":\"request\",\"seq\":1}");
        tester.compareReceivedMessages(
                "{\"event\":\"initialized\",\"type\":\"event\"}",
                "{\"success\":true,\"type\":\"response\",\"body\":{\"supportsConditionalBreakpoints\":true,\"supportsLoadedSourcesRequest\":true,\"supportsFunctionBreakpoints\":true,\"supportsExceptionInfoRequest\":true,\"supportsBreakpointLocationsRequest\":true,\"supportsHitConditionalBreakpoints\":true,\"supportsLogPoints\":true,\"supportsSetVariable\":true,\"supportsConfigurationDoneRequest\":true,\"exceptionBreakpointFilters\":[{\"filter\":\"all\",\"label\":\"All Exceptions\"},{\"filter\":\"uncaught\",\"label\":\"Uncaught Exceptions\"}]},\"request_seq\":1,\"command\":\"initialize\"}"
        );
        tester.sendMessage("{\"command\":\"attach\",\"arguments\":{\"type\":\"graalvm\",\"request\":\"attach\",\"name\":\"Attach\",\"port\":9229,\"protocol\":\"chromeDevTools\"},\"type\":\"request\",\"seq\":2}");
        tester.compareReceivedMessages("{\"event\":\"output\",\"body\":{\"output\":\"Debugger attached.\",\"category\":\"stderr\"},\"type\":\"event\"}", "{\"success\":true,\"type\":\"response\",\"request_seq\":2,\"command\":\"attach\"}");
        tester.sendMessage("{\"command\":\"loadedSources\",\"type\":\"request\",\"seq\":3}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"sources\":[]},\"type\":\"response\",\"request_seq\":3,\"command\":\"loadedSources\",\"seq\":5}");
        tester.sendMessage("{\"command\":\"setBreakpoints\",\"arguments\":{\"source\":" + sourceJson + ",\"lines\":[12],\"breakpoints\":[{\"line\":12}],\"sourceModified\":false},\"type\":\"request\",\"seq\":4}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"breakpoints\":[{\"line\":12,\"verified\":false,\"id\":1}]},\"type\":\"response\",\"request_seq\":4,\"command\":\"setBreakpoints\",\"seq\":6}");
        tester.sendMessage("{\"command\":\"configurationDone\",\"type\":\"request\",\"seq\":5}");
        tester.compareReceivedMessages("{\"success\":true,\"type\":\"response\",\"request_seq\":5,\"command\":\"configurationDone\",\"seq\":7}");
        tester.eval(source);
        tester.compareReceivedMessages("{\"event\":\"thread\",\"body\":{\"threadId\":1,\"reason\":\"started\"},\"type\":\"event\",\"seq\":8}");
        tester.compareReceivedMessages(
                "{\"event\":\"loadedSource\",\"body\":{\"reason\":\"new\",\"source\":{\"sourceReference\":1,\"name\":\"SL builtin\"}},\"type\":\"event\",\"seq\":9}",
                "{\"event\":\"loadedSource\",\"body\":{\"reason\":\"new\",\"source\":" + sourceJson + "},\"type\":\"event\",\"seq\":10}"
        );
        tester.compareReceivedMessages("{\"event\":\"breakpoint\",\"body\":{\"reason\":\"changed\",\"breakpoint\":{\"endLine\":12,\"endColumn\":7,\"line\":12,\"verified\":true,\"column\":3,\"id\":1}},\"type\":\"event\",\"seq\":11}");
        // Suspend at the breakpoint:
        tester.compareReceivedMessages("{\"event\":\"stopped\",\"body\":{\"threadId\":1,\"reason\":\"breakpoint\",\"description\":\"Paused on breakpoint\"},\"type\":\"event\",\"seq\":12}");
        tester.sendMessage("{\"command\":\"threads\",\"type\":\"request\",\"seq\":6}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"threads\":[{\"name\":\"testRunner\",\"id\":1}]},\"type\":\"response\",\"request_seq\":6,\"command\":\"threads\",\"seq\":13}");
        tester.sendMessage("{\"command\":\"stackTrace\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":7}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"stackFrames\":[{\"line\":12,\"name\":\"factorial\",\"column\":3,\"id\":1,\"source\":" + sourceJson + "},{\"line\":3,\"name\":\"main\",\"column\":7,\"id\":2,\"source\":" + sourceJson + "}],\"totalFrames\":2},\"type\":\"response\",\"request_seq\":7,\"command\":\"stackTrace\",\"seq\":14}");
        // Set additional breakpoint:
        tester.sendMessage("{\"command\":\"setBreakpoints\",\"arguments\":{\"source\":" + sourceJson + ",\"lines\":[8,12],\"breakpoints\":[{\"line\":8},{\"line\":12}],\"sourceModified\":false},\"type\":\"request\",\"seq\":8}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"breakpoints\":[{\"endLine\":8,\"endColumn\":10,\"line\":8,\"verified\":true,\"column\":3,\"id\":2},{\"endLine\":12,\"endColumn\":7,\"line\":12,\"verified\":true,\"column\":3,\"id\":1}]},\"type\":\"response\",\"request_seq\":8,\"command\":\"setBreakpoints\",\"seq\":15}");
        // Continue
        tester.sendMessage("{\"command\":\"continue\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":9}");
        tester.compareReceivedMessages(
                "{\"event\":\"continued\",\"body\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"event\"}",
                "{\"success\":true,\"body\":{\"allThreadsContinued\":false},\"type\":\"response\",\"request_seq\":9,\"command\":\"continue\"}",
                "{\"event\":\"stopped\",\"body\":{\"threadId\":1,\"reason\":\"breakpoint\",\"description\":\"Paused on breakpoint\"},\"type\":\"event\"}"
        );
        tester.sendMessage("{\"command\":\"threads\",\"type\":\"request\",\"seq\":10}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"threads\":[{\"name\":\"testRunner\",\"id\":1}]},\"type\":\"response\",\"request_seq\":10,\"command\":\"threads\",\"seq\":19}");
        tester.sendMessage("{\"command\":\"stackTrace\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":11}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"stackFrames\":[{\"line\":8,\"name\":\"main\",\"column\":3,\"id\":1,\"source\":" + sourceJson + "}],\"totalFrames\":1},\"type\":\"response\",\"request_seq\":11,\"command\":\"stackTrace\",\"seq\":20}");
        // Continue to finish:
        tester.sendMessage("{\"command\":\"continue\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":12}");
        tester.compareReceivedMessages(
                "{\"event\":\"continued\",\"body\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"event\"}",
                "{\"success\":true,\"body\":{\"allThreadsContinued\":false},\"type\":\"response\",\"request_seq\":12,\"command\":\"continue\"}"
        );
        tester.finish();
    }

    @Test
    public void testBreakpointsBySourceReferences() throws Exception {
        tester = DAPTester.start(true);
        Source source = Source.newBuilder("sl", CODE1, "SLTest.sl").build();
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
                "{\"event\":\"loadedSource\",\"body\":{\"reason\":\"new\",\"source\":{\"sourceReference\":2,\"name\":\"SLTest.sl\"}},\"type\":\"event\",\"seq\":9}"
        );
        // Suspend at the beginning of the script:
        tester.compareReceivedMessages("{\"event\":\"stopped\",\"body\":{\"threadId\":1,\"reason\":\"debugger_statement\",\"description\":\"Paused on debugger statement\"},\"type\":\"event\",\"seq\":10}");
        tester.sendMessage("{\"command\":\"threads\",\"type\":\"request\",\"seq\":5}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"threads\":[{\"name\":\"testRunner\",\"id\":1}]},\"type\":\"response\",\"request_seq\":5,\"command\":\"threads\",\"seq\":11}");
        tester.sendMessage("{\"command\":\"stackTrace\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":6}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"stackFrames\":[{\"line\":2,\"name\":\"main\",\"column\":3,\"id\":1,\"source\":{\"sourceReference\":2,\"name\":\"SLTest.sl\"}}],\"totalFrames\":1},\"type\":\"response\",\"request_seq\":6,\"command\":\"stackTrace\",\"seq\":12}");
        // Set breakpoint:
        tester.sendMessage("{\"command\":\"setBreakpoints\",\"arguments\":{\"source\":{\"name\":\"SLTest.sl\",\"sourceReference\":2},\"lines\":[12],\"breakpoints\":[{\"line\":12}],\"sourceModified\":false},\"type\":\"request\",\"seq\":7}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"breakpoints\":[{\"endLine\":12,\"endColumn\":7,\"line\":12,\"verified\":true,\"column\":3,\"id\":1}]},\"type\":\"response\",\"request_seq\":7,\"command\":\"setBreakpoints\",\"seq\":13}");
        // Continue and suspend at the breakpoint:
        tester.sendMessage("{\"command\":\"continue\",\"arguments\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"request\",\"seq\":8}");
        tester.compareReceivedMessages(
                "{\"event\":\"continued\",\"body\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"event\"}",
                "{\"success\":true,\"body\":{\"allThreadsContinued\":false},\"type\":\"response\",\"request_seq\":8,\"command\":\"continue\"}",
                "{\"event\":\"stopped\",\"body\":{\"threadId\":1,\"reason\":\"breakpoint\",\"description\":\"Paused on breakpoint\"},\"type\":\"event\"}"
        );
        tester.sendMessage("{\"command\":\"threads\",\"type\":\"request\",\"seq\":9}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"threads\":[{\"name\":\"testRunner\",\"id\":1}]},\"type\":\"response\",\"request_seq\":9,\"command\":\"threads\",\"seq\":17}");
        tester.sendMessage("{\"command\":\"stackTrace\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":10}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"stackFrames\":[{\"line\":12,\"name\":\"factorial\",\"column\":3,\"id\":1,\"source\":{\"sourceReference\":2,\"name\":\"SLTest.sl\"}},{\"line\":3,\"name\":\"main\",\"column\":7,\"id\":2,\"source\":{\"sourceReference\":2,\"name\":\"SLTest.sl\"}}],\"totalFrames\":2},\"type\":\"response\",\"request_seq\":10,\"command\":\"stackTrace\",\"seq\":18}");
        // Set additional breakpoint:
        tester.sendMessage("{\"command\":\"setBreakpoints\",\"arguments\":{\"source\":{\"name\":\"SLTest.sl\",\"sourceReference\":2},\"lines\":[8,12],\"breakpoints\":[{\"line\":8},{\"line\":12}],\"sourceModified\":false},\"type\":\"request\",\"seq\":11}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"breakpoints\":[{\"endLine\":8,\"endColumn\":10,\"line\":8,\"verified\":true,\"column\":3,\"id\":2},{\"endLine\":12,\"endColumn\":7,\"line\":12,\"verified\":true,\"column\":3,\"id\":1}]},\"type\":\"response\",\"request_seq\":11,\"command\":\"setBreakpoints\",\"seq\":19}");
        // Continue
        tester.sendMessage("{\"command\":\"continue\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":12}");
        tester.compareReceivedMessages(
                "{\"event\":\"continued\",\"body\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"event\"}",
                "{\"success\":true,\"body\":{\"allThreadsContinued\":false},\"type\":\"response\",\"request_seq\":12,\"command\":\"continue\"}",
                "{\"event\":\"stopped\",\"body\":{\"threadId\":1,\"reason\":\"breakpoint\",\"description\":\"Paused on breakpoint\"},\"type\":\"event\"}"
        );
        tester.sendMessage("{\"command\":\"threads\",\"type\":\"request\",\"seq\":13}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"threads\":[{\"name\":\"testRunner\",\"id\":1}]},\"type\":\"response\",\"request_seq\":13,\"command\":\"threads\",\"seq\":23}");
        tester.sendMessage("{\"command\":\"stackTrace\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":14}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"stackFrames\":[{\"line\":8,\"name\":\"main\",\"column\":3,\"id\":1,\"source\":{\"sourceReference\":2,\"name\":\"SLTest.sl\"}}],\"totalFrames\":1},\"type\":\"response\",\"request_seq\":14,\"command\":\"stackTrace\",\"seq\":24}");
        // Continue to finish:
        tester.sendMessage("{\"command\":\"continue\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":15}");
        tester.compareReceivedMessages(
                "{\"event\":\"continued\",\"body\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"event\"}",
                "{\"success\":true,\"body\":{\"allThreadsContinued\":false},\"type\":\"response\",\"request_seq\":15,\"command\":\"continue\"}"
        );
        tester.finish();
    }

    @Test
    public void testBreakpointRemoval() throws Exception {
        tester = DAPTester.start(false);
        File sourceFile = createTemporaryFile(CODE2);
        Source source = Source.newBuilder("sl", sourceFile).build();
        String sourceJson = "{\"name\":\"" + sourceFile.getName() + "\",\"path\":\"" + getFilePath(sourceFile) + "\"}";
        tester.sendMessage("{\"command\":\"initialize\",\"arguments\":{\"clientID\":\"DAPTester\",\"clientName\":\"DAP Tester\",\"adapterID\":\"graalvm\",\"pathFormat\":\"path\",\"linesStartAt1\":true,\"columnsStartAt1\":true,\"supportsVariableType\":true,\"supportsVariablePaging\":true,\"supportsRunInTerminalRequest\":true,\"locale\":\"en-us\",\"supportsProgressReporting\":true},\"type\":\"request\",\"seq\":1}");
        tester.compareReceivedMessages(
                "{\"event\":\"initialized\",\"type\":\"event\"}",
                "{\"success\":true,\"type\":\"response\",\"body\":{\"supportsConditionalBreakpoints\":true,\"supportsLoadedSourcesRequest\":true,\"supportsFunctionBreakpoints\":true,\"supportsExceptionInfoRequest\":true,\"supportsBreakpointLocationsRequest\":true,\"supportsHitConditionalBreakpoints\":true,\"supportsLogPoints\":true,\"supportsSetVariable\":true,\"supportsConfigurationDoneRequest\":true,\"exceptionBreakpointFilters\":[{\"filter\":\"all\",\"label\":\"All Exceptions\"},{\"filter\":\"uncaught\",\"label\":\"Uncaught Exceptions\"}]},\"request_seq\":1,\"command\":\"initialize\"}"
        );
        tester.sendMessage("{\"command\":\"attach\",\"arguments\":{\"type\":\"graalvm\",\"request\":\"attach\",\"name\":\"Attach\",\"port\":9229,\"protocol\":\"chromeDevTools\"},\"type\":\"request\",\"seq\":2}");
        tester.compareReceivedMessages("{\"event\":\"output\",\"body\":{\"output\":\"Debugger attached.\",\"category\":\"stderr\"},\"type\":\"event\"}", "{\"success\":true,\"type\":\"response\",\"request_seq\":2,\"command\":\"attach\"}");
        tester.sendMessage("{\"command\":\"loadedSources\",\"type\":\"request\",\"seq\":3}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"sources\":[]},\"type\":\"response\",\"request_seq\":3,\"command\":\"loadedSources\",\"seq\":5}");
        tester.sendMessage("{\"command\":\"setBreakpoints\",\"arguments\":{\"source\":" + sourceJson + ",\"lines\":[4,10],\"breakpoints\":[{\"line\":4},{\"line\":10}],\"sourceModified\":false},\"type\":\"request\",\"seq\":4}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"breakpoints\":[{\"line\":4,\"verified\":false,\"id\":1},{\"line\":10,\"verified\":false,\"id\":2}]},\"type\":\"response\",\"request_seq\":4,\"command\":\"setBreakpoints\",\"seq\":6}");
        tester.sendMessage("{\"command\":\"configurationDone\",\"type\":\"request\",\"seq\":5}");
        tester.compareReceivedMessages("{\"success\":true,\"type\":\"response\",\"request_seq\":5,\"command\":\"configurationDone\",\"seq\":7}");
        tester.eval(source);
        tester.compareReceivedMessages("{\"event\":\"thread\",\"body\":{\"threadId\":1,\"reason\":\"started\"},\"type\":\"event\",\"seq\":8}");
        tester.compareReceivedMessages(
                "{\"event\":\"loadedSource\",\"body\":{\"reason\":\"new\",\"source\":{\"sourceReference\":1,\"name\":\"SL builtin\"}},\"type\":\"event\",\"seq\":9}",
                "{\"event\":\"loadedSource\",\"body\":{\"reason\":\"new\",\"source\":" + sourceJson + "},\"type\":\"event\",\"seq\":10}"
        );
        tester.compareReceivedMessages(
                "{\"event\":\"breakpoint\",\"body\":{\"reason\":\"changed\",\"breakpoint\":{\"endLine\":4,\"endColumn\":14,\"line\":4,\"verified\":true,\"column\":10,\"id\":1}},\"type\":\"event\"}",
                "{\"event\":\"breakpoint\",\"body\":{\"reason\":\"changed\",\"breakpoint\":{\"endLine\":10,\"endColumn\":10,\"line\":10,\"verified\":true,\"column\":3,\"id\":2}},\"type\":\"event\"}");
        // Suspend at the first breakpoint:
        tester.compareReceivedMessages("{\"event\":\"stopped\",\"body\":{\"threadId\":1,\"reason\":\"breakpoint\",\"description\":\"Paused on breakpoint\"},\"type\":\"event\",\"seq\":13}");
        tester.sendMessage("{\"command\":\"threads\",\"type\":\"request\",\"seq\":6}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"threads\":[{\"name\":\"testRunner\",\"id\":1}]},\"type\":\"response\",\"request_seq\":6,\"command\":\"threads\",\"seq\":14}");
        tester.sendMessage("{\"command\":\"stackTrace\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":7}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"stackFrames\":[{\"line\":4,\"name\":\"main\",\"column\":10,\"id\":1,\"source\":" + sourceJson + "}],\"totalFrames\":1},\"type\":\"response\",\"request_seq\":7,\"command\":\"stackTrace\",\"seq\":15}");
        // Step over to while cycle:
        tester.sendMessage("{\"command\":\"next\",\"arguments\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"request\",\"seq\":8}");
        tester.compareReceivedMessages(
                "{\"event\":\"continued\",\"body\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"event\"}",
                "{\"success\":true,\"type\":\"response\",\"request_seq\":8,\"command\":\"next\"}",
                "{\"event\":\"stopped\",\"body\":{\"threadId\":1,\"reason\":\"debugger_statement\",\"description\":\"Paused on debugger statement\"},\"type\":\"event\"}"
        );
        tester.sendMessage("{\"command\":\"threads\",\"type\":\"request\",\"seq\":9}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"threads\":[{\"name\":\"testRunner\",\"id\":1}]},\"type\":\"response\",\"request_seq\":9,\"command\":\"threads\",\"seq\":19}");
        tester.sendMessage("{\"command\":\"stackTrace\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":10}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"stackFrames\":[{\"line\":5,\"name\":\"main\",\"column\":5,\"id\":1,\"source\":" + sourceJson + "}],\"totalFrames\":1},\"type\":\"response\",\"request_seq\":10,\"command\":\"stackTrace\",\"seq\":20}");
        // Step over the method with breakpoint, step over not finished, the second breakpoint is hit:
        tester.sendMessage("{\"command\":\"next\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":11}");
        tester.compareReceivedMessages(
                "{\"event\":\"continued\",\"body\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"event\"}",
                "{\"success\":true,\"type\":\"response\",\"request_seq\":11,\"command\":\"next\"}",
                "{\"event\":\"stopped\",\"body\":{\"threadId\":1,\"reason\":\"breakpoint\",\"description\":\"Paused on breakpoint\"},\"type\":\"event\"}"
        );
        tester.sendMessage("{\"command\":\"threads\",\"type\":\"request\",\"seq\":12}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"threads\":[{\"name\":\"testRunner\",\"id\":1}]},\"type\":\"response\",\"request_seq\":12,\"command\":\"threads\",\"seq\":24}");
        tester.sendMessage("{\"command\":\"stackTrace\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":13}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"stackFrames\":[{\"line\":10,\"name\":\"fceWithBP\",\"column\":3,\"id\":1,\"source\":" + sourceJson + "},{\"line\":5,\"name\":\"main\",\"column\":5,\"id\":2,\"source\":" + sourceJson + "}],\"totalFrames\":2},\"type\":\"response\",\"request_seq\":13,\"command\":\"stackTrace\",\"seq\":25}");
        // Remove the second breakpoint
        tester.sendMessage("{\"command\":\"setBreakpoints\",\"arguments\":{\"source\":" + sourceJson + ",\"lines\":[4],\"breakpoints\":[{\"line\":4}],\"sourceModified\":false},\"type\":\"request\",\"seq\":14}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"breakpoints\":[{\"endLine\":4,\"endColumn\":14,\"line\":4,\"verified\":true,\"column\":10,\"id\":1}]},\"type\":\"response\",\"request_seq\":14,\"command\":\"setBreakpoints\",\"seq\":26}");
        // Continue, the first breakpoint is hit again:
        tester.sendMessage("{\"command\":\"continue\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":15}");
        tester.compareReceivedMessages(
                "{\"event\":\"continued\",\"body\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"event\"}",
                "{\"success\":true,\"body\":{\"allThreadsContinued\":false},\"type\":\"response\",\"request_seq\":15,\"command\":\"continue\"}",
                "{\"event\":\"stopped\",\"body\":{\"threadId\":1,\"reason\":\"breakpoint\",\"description\":\"Paused on breakpoint\"},\"type\":\"event\"}"
        );
        tester.sendMessage("{\"command\":\"threads\",\"type\":\"request\",\"seq\":16}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"threads\":[{\"name\":\"testRunner\",\"id\":1}]},\"type\":\"response\",\"request_seq\":16,\"command\":\"threads\",\"seq\":30}");
        tester.sendMessage("{\"command\":\"stackTrace\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":17}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"stackFrames\":[{\"line\":4,\"name\":\"main\",\"column\":10,\"id\":1,\"source\":" + sourceJson + "}],\"totalFrames\":1},\"type\":\"response\",\"request_seq\":17,\"command\":\"stackTrace\",\"seq\":31}");
        // Step over to while cycle:
        tester.sendMessage("{\"command\":\"next\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":18}");
        tester.compareReceivedMessages(
                "{\"event\":\"continued\",\"body\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"event\"}",
                "{\"success\":true,\"type\":\"response\",\"request_seq\":18,\"command\":\"next\"}",
                "{\"event\":\"stopped\",\"body\":{\"threadId\":1,\"reason\":\"debugger_statement\",\"description\":\"Paused on debugger statement\"},\"type\":\"event\"}"
        );
        tester.sendMessage("{\"command\":\"threads\",\"type\":\"request\",\"seq\":19}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"threads\":[{\"name\":\"testRunner\",\"id\":1}]},\"type\":\"response\",\"request_seq\":19,\"command\":\"threads\",\"seq\":35}");
        tester.sendMessage("{\"command\":\"stackTrace\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":20}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"stackFrames\":[{\"line\":5,\"name\":\"main\",\"column\":5,\"id\":1,\"source\":" + sourceJson + "}],\"totalFrames\":1},\"type\":\"response\",\"request_seq\":20,\"command\":\"stackTrace\",\"seq\":36}");
        // Step over the method with removed breakpoint:
        tester.sendMessage("{\"command\":\"next\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":21}");
        tester.compareReceivedMessages(
                "{\"event\":\"continued\",\"body\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"event\"}",
                "{\"success\":true,\"type\":\"response\",\"request_seq\":21,\"command\":\"next\"}",
                "{\"event\":\"stopped\",\"body\":{\"threadId\":1,\"reason\":\"debugger_statement\",\"description\":\"Paused on debugger statement\"},\"type\":\"event\"}"
        );
        tester.sendMessage("{\"command\":\"threads\",\"type\":\"request\",\"seq\":22}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"threads\":[{\"name\":\"testRunner\",\"id\":1}]},\"type\":\"response\",\"request_seq\":22,\"command\":\"threads\",\"seq\":40}");
        tester.sendMessage("{\"command\":\"stackTrace\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":23}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"stackFrames\":[{\"line\":6,\"name\":\"main\",\"column\":5,\"id\":1,\"source\":" + sourceJson + "}],\"totalFrames\":1},\"type\":\"response\",\"request_seq\":23,\"command\":\"stackTrace\",\"seq\":41}");
        // Remove the firts breakpoint:
        tester.sendMessage("{\"command\":\"setBreakpoints\",\"arguments\":{\"source\":" + sourceJson + ",\"lines\":[],\"breakpoints\":[],\"sourceModified\":false},\"type\":\"request\",\"seq\":24}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"breakpoints\":[]},\"type\":\"response\",\"request_seq\":24,\"command\":\"setBreakpoints\",\"seq\":42}");
        // Continue to finish:
        tester.sendMessage("{\"command\":\"continue\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":25}");
        tester.compareReceivedMessages(
                "{\"event\":\"continued\",\"body\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"event\"}",
                "{\"success\":true,\"body\":{\"allThreadsContinued\":false},\"type\":\"response\",\"request_seq\":25,\"command\":\"continue\"}"
        );
        tester.finish();
    }

    @Test
    public void testGuestFunctionBreakpoints() throws Exception {
        tester = DAPTester.start(true);
        Source source = Source.newBuilder("sl", GUEST_FUNCTIONS, "SLTest.sl").uri(testURI).build();
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
        // Set function breakpoint:
        tester.sendMessage("{\"command\":\"setFunctionBreakpoints\",\"arguments\":{\"breakpoints\":[{\"name\":\"foo0\"}]},\"type\":\"request\",\"seq\":7}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"breakpoints\":[{\"verified\":false,\"id\":1}]},\"type\":\"response\",\"request_seq\":7,\"command\":\"setFunctionBreakpoints\",\"seq\":13}");
        // Continue and suspend at the breakpoint:
        tester.sendMessage("{\"command\":\"continue\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":8}");
        tester.compareReceivedMessages(
                "{\"event\":\"continued\",\"body\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"event\"}",
                "{\"success\":true,\"body\":{\"allThreadsContinued\":false},\"type\":\"response\",\"request_seq\":8,\"command\":\"continue\"}",
                "{\"event\":\"stopped\",\"body\":{\"threadId\":1,\"reason\":\"breakpoint\",\"description\":\"Paused on breakpoint\"},\"type\":\"event\"}"
        );
        tester.sendMessage("{\"command\":\"threads\",\"type\":\"request\",\"seq\":9}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"threads\":[{\"name\":\"testRunner\",\"id\":1}]},\"type\":\"response\",\"request_seq\":9,\"command\":\"threads\",\"seq\":17}");
        tester.sendMessage("{\"command\":\"stackTrace\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":10}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"stackFrames\":[{\"line\":7,\"name\":\"foo0\",\"column\":10,\"id\":1,\"source\":{\"sourceReference\":2,\"path\":\"" + testFilePath + "\",\"name\":\"SLTest.sl\"}},{\"line\":2,\"name\":\"main\",\"column\":3,\"id\":2,\"source\":{\"sourceReference\":2,\"path\":\"" + testFilePath + "\",\"name\":\"SLTest.sl\"}}],\"totalFrames\":2},\"type\":\"response\",\"request_seq\":10,\"command\":\"stackTrace\",\"seq\":18}");
        // Remove the breakpoint:
        tester.sendMessage("{\"command\":\"setFunctionBreakpoints\",\"arguments\":{\"breakpoints\":[]},\"type\":\"request\",\"seq\":11}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"breakpoints\":[]},\"type\":\"response\",\"request_seq\":11,\"command\":\"setFunctionBreakpoints\",\"seq\":19}");
        // Continue to finish:
        tester.sendMessage("{\"command\":\"continue\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":12}");
        tester.compareReceivedMessages(
                "{\"event\":\"continued\",\"body\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"event\"}",
                "{\"success\":true,\"body\":{\"allThreadsContinued\":false},\"type\":\"response\",\"request_seq\":12,\"command\":\"continue\"}"
        );
        tester.finish();
    }

    @Test
    public void testBuiltInFunctionBreakpoints() throws Exception {
        tester = DAPTester.start(true);
        Source source = Source.newBuilder("sl", BUILTIN_FUNCTIONS, "SLTest.sl").uri(testURI).build();
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
        // Set function breakpoint:
        tester.sendMessage("{\"command\":\"setFunctionBreakpoints\",\"arguments\":{\"breakpoints\":[{\"name\":\"isNull\"}]},\"type\":\"request\",\"seq\":7}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"breakpoints\":[{\"verified\":false,\"id\":1}]},\"type\":\"response\",\"request_seq\":7,\"command\":\"setFunctionBreakpoints\",\"seq\":13}");
        // Continue and suspend at the breakpoint:
        tester.sendMessage("{\"command\":\"continue\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":8}");
        tester.compareReceivedMessages(
                "{\"event\":\"continued\",\"body\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"event\"}",
                "{\"success\":true,\"body\":{\"allThreadsContinued\":false},\"type\":\"response\",\"request_seq\":8,\"command\":\"continue\"}",
                "{\"event\":\"stopped\",\"body\":{\"threadId\":1,\"reason\":\"breakpoint\",\"description\":\"Paused on breakpoint\"},\"type\":\"event\"}"
        );
        tester.sendMessage("{\"command\":\"threads\",\"type\":\"request\",\"seq\":9}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"threads\":[{\"name\":\"testRunner\",\"id\":1}]},\"type\":\"response\",\"request_seq\":9,\"command\":\"threads\",\"seq\":17}");
        tester.sendMessage("{\"command\":\"stackTrace\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":10}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"stackFrames\":[{\"line\":4,\"name\":\"main\",\"column\":3,\"id\":1,\"source\":{\"sourceReference\":2,\"path\":\"" + testFilePath + "\",\"name\":\"SLTest.sl\"}}],\"totalFrames\":1},\"type\":\"response\",\"request_seq\":10,\"command\":\"stackTrace\",\"seq\":18}");
        // Remove the breakpoint:
        tester.sendMessage("{\"command\":\"setFunctionBreakpoints\",\"arguments\":{\"breakpoints\":[]},\"type\":\"request\",\"seq\":11}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"breakpoints\":[]},\"type\":\"response\",\"request_seq\":11,\"command\":\"setFunctionBreakpoints\",\"seq\":19}");
        // Continue to finish:
        tester.sendMessage("{\"command\":\"continue\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":12}");
        tester.compareReceivedMessages(
                "{\"event\":\"continued\",\"body\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"event\"}",
                "{\"success\":true,\"body\":{\"allThreadsContinued\":false},\"type\":\"response\",\"request_seq\":12,\"command\":\"continue\"}"
        );
        tester.finish();
    }

    @Test
    public void testScopes() throws Exception {
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
        // Ask for the local scope variables at the beginning of main:
        tester.sendMessage("{\"command\":\"scopes\",\"arguments\":{\"frameId\":1},\"type\":\"request\",\"seq\":7}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"scopes\":[{\"name\":\"Local\",\"variablesReference\":2,\"expensive\":false},{\"name\":\"Global\",\"variablesReference\":3,\"expensive\":true}]},\"type\":\"response\",\"request_seq\":7,\"command\":\"scopes\",\"seq\":13}");
        tester.sendMessage("{\"command\":\"variables\",\"arguments\":{\"variablesReference\":2},\"type\":\"request\",\"seq\":8}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"variables\":[]},\"type\":\"response\",\"request_seq\":8,\"command\":\"variables\",\"seq\":14}");
        // Continue to the breakpoint set at line 5:
        tester.sendMessage("{\"command\":\"setBreakpoints\",\"arguments\":{\"source\":{\"name\":\"SLTest.sl\",\"sourceReference\":2},\"lines\":[5],\"breakpoints\":[{\"line\":5}],\"sourceModified\":false},\"type\":\"request\",\"seq\":9}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"breakpoints\":[{\"endLine\":5,\"endColumn\":13,\"line\":5,\"verified\":true,\"column\":5,\"id\":1}]},\"type\":\"response\",\"request_seq\":9,\"command\":\"setBreakpoints\",\"seq\":15}");
        tester.sendMessage("{\"command\":\"continue\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":10}");
        tester.compareReceivedMessages(
                "{\"event\":\"continued\",\"body\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"event\"}",
                "{\"success\":true,\"body\":{\"allThreadsContinued\":false},\"type\":\"response\",\"request_seq\":10,\"command\":\"continue\"}",
                "{\"event\":\"stopped\",\"body\":{\"threadId\":1,\"reason\":\"breakpoint\",\"description\":\"Paused on breakpoint\"},\"type\":\"event\"}"
        );
        tester.sendMessage("{\"command\":\"threads\",\"type\":\"request\",\"seq\":11}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"threads\":[{\"name\":\"testRunner\",\"id\":1}]},\"type\":\"response\",\"request_seq\":11,\"command\":\"threads\",\"seq\":19}");
        tester.sendMessage("{\"command\":\"stackTrace\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":12}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"stackFrames\":[{\"line\":5,\"name\":\"main\",\"column\":5,\"id\":1,\"source\":{\"sourceReference\":2,\"path\":\"" + testFilePath + "\",\"name\":\"SLTest.sl\"}}],\"totalFrames\":1},\"type\":\"response\",\"request_seq\":12,\"command\":\"stackTrace\",\"seq\":20}");
        // Ask for the local scope variables at line 5:
        tester.sendMessage("{\"command\":\"scopes\",\"arguments\":{\"frameId\":1},\"type\":\"request\",\"seq\":13}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"scopes\":[{\"name\":\"Local\",\"variablesReference\":2,\"expensive\":false},{\"name\":\"Global\",\"variablesReference\":3,\"expensive\":true}]},\"type\":\"response\",\"request_seq\":13,\"command\":\"scopes\",\"seq\":21}");
        tester.sendMessage("{\"command\":\"variables\",\"arguments\":{\"variablesReference\":2},\"type\":\"request\",\"seq\":14}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"variables\":[{\"name\":\"a\",\"variablesReference\":0,\"type\":\"Number\",\"value\":\"10\"},{\"name\":\"b\",\"variablesReference\":0,\"type\":\"Number\",\"value\":\"2\"}]},\"type\":\"response\",\"request_seq\":14,\"command\":\"variables\",\"seq\":22}");
        // Step over:
        tester.sendMessage("{\"command\":\"next\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":15}");
        tester.compareReceivedMessages(
                "{\"event\":\"continued\",\"body\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"event\"}",
                "{\"success\":true,\"type\":\"response\",\"request_seq\":15,\"command\":\"next\"}",
                "{\"event\":\"stopped\",\"body\":{\"threadId\":1,\"reason\":\"debugger_statement\",\"description\":\"Paused on debugger statement\"},\"type\":\"event\"}"
        );
        tester.sendMessage("{\"command\":\"threads\",\"type\":\"request\",\"seq\":16}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"threads\":[{\"name\":\"testRunner\",\"id\":1}]},\"type\":\"response\",\"request_seq\":16,\"command\":\"threads\",\"seq\":26}");
        tester.sendMessage("{\"command\":\"stackTrace\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":17}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"stackFrames\":[{\"line\":6,\"name\":\"main\",\"column\":5,\"id\":1,\"source\":{\"sourceReference\":2,\"path\":\"" + testFilePath + "\",\"name\":\"SLTest.sl\"}}],\"totalFrames\":1},\"type\":\"response\",\"request_seq\":17,\"command\":\"stackTrace\",\"seq\":27}");
        // Ask for the local scope variables at line 6:
        tester.sendMessage("{\"command\":\"scopes\",\"arguments\":{\"frameId\":1},\"type\":\"request\",\"seq\":18}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"scopes\":[{\"name\":\"Block\",\"variablesReference\":2,\"expensive\":false},{\"name\":\"Local\",\"variablesReference\":3,\"expensive\":false},{\"name\":\"Global\",\"variablesReference\":4,\"expensive\":true}]},\"type\":\"response\",\"request_seq\":18,\"command\":\"scopes\",\"seq\":28}");
        tester.sendMessage("{\"command\":\"variables\",\"arguments\":{\"variablesReference\":2},\"type\":\"request\",\"seq\":19}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"variables\":[{\"name\":\"c\",\"variablesReference\":0,\"type\":\"Number\",\"value\":\"12\"}]},\"type\":\"response\",\"request_seq\":19,\"command\":\"variables\",\"seq\":29}");
        tester.sendMessage("{\"command\":\"variables\",\"arguments\":{\"variablesReference\":3},\"type\":\"request\",\"seq\":20}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"variables\":[{\"name\":\"a\",\"variablesReference\":0,\"type\":\"Number\",\"value\":\"10\"},{\"name\":\"b\",\"variablesReference\":0,\"type\":\"Number\",\"value\":\"2\"}]},\"type\":\"response\",\"request_seq\":20,\"command\":\"variables\",\"seq\":30}");
        // Remove the breakpoint:
        tester.sendMessage("{\"command\":\"setBreakpoints\",\"arguments\":{\"source\":{\"name\":\"SLTest.sl\",\"sourceReference\":2},\"lines\":[],\"breakpoints\":[],\"sourceModified\":false},\"type\":\"request\",\"seq\":21}");
        tester.compareReceivedMessages("{\"success\":true,\"body\":{\"breakpoints\":[]},\"type\":\"response\",\"request_seq\":21,\"command\":\"setBreakpoints\",\"seq\":31}");
        // Continue to finish:
        tester.sendMessage("{\"command\":\"continue\",\"arguments\":{\"threadId\":1},\"type\":\"request\",\"seq\":22}");
        tester.compareReceivedMessages(
                "{\"event\":\"continued\",\"body\":{\"threadId\":1,\"allThreadsContinued\":false},\"type\":\"event\"}",
                "{\"success\":true,\"body\":{\"allThreadsContinued\":false},\"type\":\"response\",\"request_seq\":22,\"command\":\"continue\"}"
        );
        tester.finish();
    }

    @Test
    public void testNotSuspended() throws Exception {
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
        tester.compareReceivedMessages("{\"event\":\"stopped\",\"body\":{\"threadId\":1,\"reason\":\"debugger_statement\",\"description\":\"Paused on debugger statement\"},\"type\":\"event\",\"seq\":