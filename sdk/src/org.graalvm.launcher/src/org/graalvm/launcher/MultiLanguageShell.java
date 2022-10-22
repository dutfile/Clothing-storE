/*
 * Copyright (c) 2017, 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.launcher;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Language;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.PolyglotException.StackFrame;
import org.graalvm.polyglot.Source;
import org.graalvm.shadowed.org.jline.keymap.KeyMap;
import org.graalvm.shadowed.org.jline.reader.EndOfFileException;
import org.graalvm.shadowed.org.jline.reader.History;
import org.graalvm.shadowed.org.jline.reader.LineReader;
import org.graalvm.shadowed.org.jline.reader.LineReaderBuilder;
import org.graalvm.shadowed.org.jline.reader.Reference;
import org.graalvm.shadowed.org.jline.reader.UserInterruptException;
import org.graalvm.shadowed.org.jline.reader.impl.history.DefaultHistory;
import org.graalvm.shadowed.org.jline.terminal.Terminal;

class MultiLanguageShell implements Closeable {
    private static final String WIDGET_NAME = "CHANGE_LANGUAGE_WIDGET";
    private final Map<Language, History> histories = new HashMap<>();
    private final Context context;
    private final String startLanguage;
    private final List<Language> languages;
    private final Map<String, Language> prompts;
    private final StringBuilder promptsString = new StringBuilder();
    private final Terminal terminal;
    private LineReader reader;
    private Language currentLanguage;
    private boolean verboseErrors = false;
    private String input = "";

    MultiLanguageShell(Context context, String defaultStartLanguage, Terminal terminal) {
        this.context = context;
        this.languages = languages();
        this.prompts = prompts();
        this.terminal = terminal;
        this.startLanguage = defaultStartLanguage == null ? languages.get(0).getId() : defaultStartLanguage;
        currentLanguage = context.getEngine().getLanguages().get(startLanguage);
        if (currentLanguage == null) {
            throw new Launcher.AbortException("Error: could not find language '" + startLanguage + "'", 1);
        }
        resetLineReader();
    }

    private static String createBufferPrompt(String prompt) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < prompt.length() - 2; i++) {
            b.append(" ");
        }
        return b.append("+ ").toString();
    }

    private static String createPrompt(Language currentLanguage) {
        return String.format("%s> ", currentLanguage.getId());
    }

    public int runRepl() {
        printHeader();
        for (;;) {
            try {
                input += reader.readLine(prompt()) + "\n";
                if (handleBuiltins() || eval()) {
                    reader.getHistory().add(input);
                    input = "";
                }
            } catch (ChangeLanguageException e) {
                handle(e);
            } catch (PolyglotException e) {
                handle(e);
                if (e.isExit()) {
                    return e.getExitStatus();
                }
            } catch (UserInterruptException | EndOfFileException e) {
                // interrupted by ctrl-c or ctrl-d
                break;
            } catch (Throwable e) {
                handleInternal(e);
            }
        }
        return 0;
    }

    private String prompt() {
        final String prompt = createPrompt(currentLanguage);
        return input.equals("") ? prompt : createBufferPrompt(prompt);
    }

    private boolean eval() throws IOException {
        Source source = Source.newBuilder(currentLanguage.getId(), input, "<shell>").interactive(true).build();
        context.eval(source);
        return true;
    }

    private void handle(ChangeLanguageException e) {
        histories.put(currentLanguage, reader.getHistory());
        currentLanguage = e.getLanguage() == null ? languages.get((languages.indexOf(currentLanguage) + 1) % languages.size()) : e.getLanguage();
        resetLineReader();
        input = "";
    }

    private void handleInternal(Throwable e) {
        println("Internal error occurred: " + e.toString());
        if (verboseErrors) {
            e.printStackTrace(terminal.writer());
        } else {
            println("Run with --verbose to see the full stack trace.");
        }
    }

    private void handle(PolyglotException e) {
        if (e.isIncompleteSource()) {
            return;
        }
        input = "";
        if (e.isInternalError()) {
            handleInternal(e);
        } else if (e.isCancelled()) {
            println("Execution got cancelled.");
        } else if (e.isSyntaxError()) {
            println(e.getMessage());
        } else {
            List<StackFrame> trace = new ArrayList<>();
            for (StackFrame stackFrame : e.getPolyglotStackTrace()) {
                trace.add(stackFrame);
            }
            // remove trailing host frames
            for (int i = trace.size() - 1; i >= 0; i--) {
                if (trace.get(i).isHostFrame()) {
                    trace.remove(i);
                } else {
                    break;
                }
            }
            if (e.isHostException()) {
                println(e.asHostException().toString());
            } else {
                println(String.valueOf(e.getMessage()));
            }
            // no need to print stack traces with single entry
            if (trace.size() > 1) {
                for (StackFrame stackFrame : trace) {
                    print("        at ");
                    println(stackFrame.toString());
                }
            }
        }
    }

    private boolean handleBuiltins(