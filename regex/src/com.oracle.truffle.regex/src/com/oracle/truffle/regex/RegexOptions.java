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
    private static final int REGRESSION_TEST_MODE = 1 << 1;
    public static final String REGRESSION_TEST_MODE_NAME = "RegressionTestMode";
    private static final int DUMP_AUTOMATA = 1 << 2;
    public static final String DUMP_AUTOMATA_NAME = "DumpAutomata";
    private static final int STEP_EXECUTION = 1 << 3;
    public static final String STEP_EXECUTION_NAME = "StepExecution";
    private static final int ALWAYS_EAGER = 1 << 4;
    public static final String ALWAYS_EAGER_NAME = "AlwaysEager";
    private static final int UTF_16_EXPLODE_ASTRAL_SYMBOLS = 1 << 5;
    public static final String UTF_16_EXPLODE_ASTRAL_SYMBOLS_NAME = "UTF16ExplodeAstralSymbols";
    private static final int VALIDATE = 1 << 6;
    public static final String VALIDATE_NAME = "Validate";
    private static final int IGNORE_ATOMIC_GROUPS = 1 << 7;
    public static final String IGNORE_ATOMIC_GROUPS_NAME = "IgnoreAtomicGroups";
    private static final int GENERATE_DFA_IMMEDIATELY = 1 << 8;
    private static final String GENERATE_DFA_IMMEDIATELY_NAME = "GenerateDFAImmediately";
    private static final int BOOLEAN_MATCH = 1 << 9;
    private static final String BOOLEAN_MATCH_NAME = "BooleanMatch";
    private static final int MUST_ADVANCE = 1 << 10;
    public static final String MUST_ADVANCE_NAME = "MustAdvance";

    public static final String FLAVOR_NAME = "Flavor";
    public static final String FLAVOR_PYTHON = "Python";
    public static final String FLAVOR_RUBY = "Ruby";
    public static final String FLAVOR_ECMASCRIPT = "ECMAScript";
    private static final String[] FLAVOR_OPTIONS = {FLAVOR_PYTHON, FLAVOR_RUBY, FLAVOR_ECMASCRIPT};

    public static final String ENCODING_NAME = "Encoding";

    public static final String PYTHON_METHOD_NAME = "PythonMethod";
    public static final String PYTHON_METHOD_SEARCH = "search";
    public static final String PYTHON_METHOD_MATCH = "match";
    public static final String PYTHON_METHOD_FULLMATCH = "fullmatch";
    private static final String[] PYTHON_METHOD_OPTIONS = {PYTHON_METHOD_SEARCH, PYTHON_METHOD_MATCH, PYTHON_METHOD_FULLMATCH};

    public static final String PYTHON_LOCALE_NAME = "PythonLocale";

    public static final RegexOptions DEFAULT = new RegexOptions(0, ECMAScriptFlavor.INSTANCE, Encodings.UTF_16_RAW, null, null);

    private final int options;
    private final RegexFlavor flavor;
    private final Encodings.Encoding encoding;
    private final PythonMethod pythonMethod;
    private final String pythonLocale;

    private RegexOptions(int options, RegexFlavor flavor, Encodings.Encoding encoding, PythonMethod pythonMethod, String pythonLocale) {
        this.options = options;
        this.flavor = flavor;
        this.encoding = encoding;
        this.pythonMethod = pythonMethod;
        this.pythonLocale = pythonLocale;
    }

    public static Builder builder(Source source, String sourceString) {
        return new Builder(source, sourceString);
    }

    private boolean isBitSet(int bit) {
        return (options & bit) != 0;
    }

    public boolean isU180EWhitespace() {
        return isBitSet(U180E_WHITESPACE);
    }

    public boolean isRegressionTestMode() {
        return isBitSet(REGRESSION_TEST_MODE);
    }

    /**
     * Produce ASTs and automata in JSON, DOT (GraphViz) and LaTeX formats.
     */
    public boolean isDumpAutomata() {
        return isBitSet(DUMP_AUTOMATA);
    }

    public boolean isDumpAutomataWithSourceSections() {
        return isDumpAutomata() && getFlavor() == ECMAScriptFlavor.INSTANCE;
    }

    /**
     * Trace the execution of automata in JSON files.
     */
    public boolean isStepExecution() {
        return isBitSet(STEP_EXECUTION);
    }

    /**
     * Generate DFA matchers immediately after parsing the expression.
     */
    public boolean isGenerateDFAImmediately() {
        return isBitSet(GENERATE_DFA_IMMEDIATELY);
    }

    /**
     * Don't track capture groups, just return a boolean match result instead.
     */
    public boolean isBooleanMatch() {
        return isBitSet(BOOLEAN_MATCH);
    }

    /**
     * Always match capture groups eagerly.
     */
    public boolean isAlwaysEager() {
        return isBitSet(ALWAYS_EAGER);
    }

    /**
     * Explode astral symbols ({@code 0x10000 - 0x10FFFF}) into sub-automata where every state
     * matches one {@code char} as opposed to one code point.
     */
    public boolean isUTF16ExplodeAstralSymbols() {
        return isBitSet(UTF_16_EXPLODE_ASTRAL_SYMBOLS);
    }

    /**
     * Do not generate an actual regular expression matcher, just check the given regular expression
     * for syntax errors.
     */
    public boolean isValidate() {
        return isBitSet(VALIDATE);
    }

    /**
     * Ignore atomic groups (found e.g. in Ruby regular expressions), treat them as regular groups.
     */
    public boolean isIgnoreAtomicGroups() {
        return isBitSet(IGNORE_ATOMIC_GROUPS);
    }

    /**
     * Do not return zero-width matches at the beginning of the search string. The matcher must
     * advance by at least one character by either finding a match of non-zero width or finding a
     * match after advancing skipping several characters.
     */
    public boolean isMustAdvance() {
        return isBitSet(MUST_ADVANCE);
    }

    public RegexFlavor getFlavor() {
        return flavor;
    }

    public Encodings.Encoding getEncoding() {
        return encoding;
    }

    public PythonMethod getPythonMethod() {
        return pythonMethod;
    }

    public String getPythonLocale() {
        return pythonLocale;
    }

    public RegexOptions withEncoding(Encodings.Encoding newEnc) {
        return newEnc == encoding ? this : new RegexOptions(options, flavor, newEnc, pythonMethod, pythonLocale);
    }

    public RegexOptions withoutPythonMethod() {
        return pythonMethod == null ? this : new RegexOptions(options, flavor, encoding, null, pythonLocale);
    }

    public RegexOptions withBooleanMatch() {
        return new RegexOptions(options | BOOLEAN_MATCH, flavor, encoding, pythonMethod, pythonLocale);
    }

    public RegexOptions withoutBooleanMatch() {
        return new RegexOptions(options & ~BOOLEAN_MATCH, flavor, encoding, pythonMethod, pythonLocale);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int hash = options;
        hash = prime * hash + Objects.hashCode(flavor);
        hash = prime * hash + encoding.hashCode();
        hash = prime * hash + Objects.hashCode(pythonMethod);
        hash = prime * hash + Objects.hashCode(pythonLocale);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
   