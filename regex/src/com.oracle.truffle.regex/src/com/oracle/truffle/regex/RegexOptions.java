/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.regex;

import java.util.Arrays;
import java.util.Objects;

import com.oracle.truffle.api.ArrayUtils;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.regex.result.RegexResult;
import com.oracle.truffle.regex.tregex.parser.flavors.ECMAScriptFlavor;
import com.oracle.truffle.regex.tregex.parser.flavors.PythonFlavor;
import com.oracle.truffle.regex.tregex.parser.flavors.PythonMethod;
import com.oracle.truffle.regex.tregex.parser.flavors.RegexFlavor;
import com.oracle.truffle.regex.tregex.parser.flavors.RubyFlavor;
import com.oracle.truffle.regex.tregex.string.Encodings;

/**
 * These options define how TRegex should interpret a given parsing request.
 * <p>
 * Available options:
 * <ul>
 * <li><b>Flavor</b>: specifies the regex dialect to use. Possible values:
 * <ul>
 * <li><b>ECMAScript</b>: ECMAScript/JavaScript syntax (default).</li>
 * <li><b>Python</b>: Python 3 syntax</li>
 * <li><b>Ruby</b>: Ruby syntax.</li>
 * </ul>
 * </li>
 * <li><b>Encoding</b>: specifies the string encoding to match against. Possible values:
 * <ul>
 * <li><b>UTF-8</b></li>
 * <li><b>UTF-16</b></li>
 * <li><b>UTF-32</b></li>
 * <li><b>LATIN-1</b></li>
 * <li><b>BYTES</b> (equivalent to LATIN-1)</li>
 * </ul>
 * </li>
 * <li><b>PythonMethod</b>: specifies which Python {@code Pattern} method was called (Python flavors
 * only). Possible values:
 * <ul>
 * <li><b>search</b></li>
 * <li><b>match</b></li>
 * <li><b>fullmatch</b></li>
 * </ul>
 * </li>
 * <li><b>PythonLocale</b>: specifies which locale is to be used by this locale-sensitive Python
 * regexp</li>
 * <li><b>Validate</b>: don't generate a regex matcher object, just check the regex for syntax
 * errors.</li>
 * <li><b>U180EWhitespace</b>: treat 0x180E MONGOLIAN VOWEL SEPARATOR as part of {@code \s}. This is
 * a legacy feature for languages using a Unicode standard older than 6.3, such as ECMAScript 6 and
 * older.</li>
 * <li><b>UTF16ExplodeAstralSymbols</b>: generate one DFA states per (16 bit) {@code char} instead
 * of per-codepoint. This may improve performance in certain scenarios, but increases the likelihood
 * of DFA state explosion.</li>
 * <li><b>AlwaysEager</b>: do not generate any lazy regex matchers (lazy in the sense that they may
 * lazily compute properties of a {@link RegexResult}).</li>
 * <li><b>RegressionTestMode</b>: exercise all supported regex matcher variants, and check if they
 * produce the same results.</li>
 * <li><b>DumpAutomata</b>: dump all generated parser trees, NFA, and DFA to disk. This will
 * generate debugging dumps of most relevant data structures in JSON, GraphViz and LaTex
 * format.</li>
 * <li><b>StepExecution</b>: dump tracing information about all DFA matcher runs.</li>
 * <li><b>IgnoreAtomicGroups</b>: treat atomic groups as ordinary groups (experimental).</li>
 * <li><b>MustAdvance</b>: force the matcher to advance by at least one character, either by finding
 * a non-zero-width match or by skipping at least one character before matching.</li>
 * </ul>
 * All options except {@code Flavor}, {@code Encoding} and {@code PythonMethod} are boolean and
 * {@code false} by default.
 */
public final class RegexOptions {

    private static final int U180E_WHITESPACE = 1;
    public static final String U180E_WHITESPACE_NAME = "U180EWhitespace";
    private s