/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.llvm.tests.bitcode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Pattern;

import com.oracle.truffle.llvm.tests.Platform;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.llvm.runtime.except.LLVMParserException;
import com.oracle.truffle.llvm.runtime.types.VariableBitWidthType;
import com.oracle.truffle.llvm.tests.options.TestOptions;
import com.oracle.truffle.tck.TruffleRunner;

public class AllocationLimitsTest {

    private static final Path TEST_DIR = new File(TestOptions.getTestDistribution("SULONG_EMBEDDED_TEST_SUITES"), "other").toPath();
    private static final String FILENAME = "bitcode-O0.bc";
    public static final BaseMatcher<String> EXCEEDS_LIMIT = new BaseMatcher<>() {
        private final Pattern compile = Pattern.compile(".*exceeds.*limit.*");

        @Override
        public boolean matches(Object item) {
            return compile.matcher(item.toString()).matches();
        }

        @Override
        public void describeTo(Description description) {
            description.appendText(" matching '" + compile.pattern() + "'");
        }
    };

    @ClassRule public static TruffleRunner.RunWithPolyglotRule runWithPolyglot = new TruffleRunner.RunWithPolyglotRule();

    protected static Object loadTestBitcodeInternal(String name) {
        File file = TEST_DIR.resolve(name).resolve(FILENAME).toFile();
        TruffleFile tf = runWithPolyglot.getTruffleTestEnv().getPublicTruffleFile(file.toURI());
        com.oracle.truffle.api.source.Source source;
        try {
            source = com.oracle.truffle.api.source.Source.newBuilder("llvm", tf).build();
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
        CallTarget target = runWithPolyglot.getTruffleTestEnv().parsePublic(source);
        return target.call();
    }

    protected static Value loadTestBitcodeValue(String name) {
        File file = TEST_DIR.resolve(name).resolve(FILENAME).toFile();
        org.graalvm.polyglot.Source source;
        try {
            source = org.graalvm.polyglot.Source.newBuilder("llvm", file).build();
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
        return runWithPolyglot.getPolyglotContext().eval(source);
    }

    @Rule public ExpectedException exception = ExpectedException.none();

    public static Value library;

    @Before
    public void setup() {
        TestOptions.assumeBundledLLVM();
        Assume.assumeTrue("Skipping linux/amd64 only test", Platform.isLinux() && Platform.isAMD64());
        library = loadTestBitcodeValue("allocation_limits.ll.dir");
    }

    @Test
    public void allocaMaxSize() {
        exception.expect(PolyglotException.class);
        exception.expectMessage("unsupported value range");
        library.getMember("alloca_max_size").execute();
    }

    @Test
    public void allocaMaxSizeI1() {
        exception.expect(PolyglotException.class);
        exception.expectMessage(EXCEEDS_LIMIT);
        library.getMember("alloca_max_size_i1").execute();
    }

    @Test
    public void allocaMaxSizeI64() {
        exception.expect(PolyglotException.class);
        exception.expectMessage(EXCEEDS_LIMIT);
        library.getMember("alloca_max_size_i64").execute();
    }

    @Test
    public void allocaParameter() {
        Value v = library.getMember("alloca_parameter").execute(16L);
        Assert.assertNotNull(v);
    }

    @Test
    public void allocaParameterMaxSize() {
        exception.expect(PolyglotException.class);
        exception.expectMessage(EXCEEDS_LIMIT);
        library.getMember("alloca_parameter").execute(0xFFFF_FFFF_FFFF_FFFFL);
    }

    @Test
    public void allocaParameterOverflowInt() {
        exception.expect(PolyglotException.class);
        exception.expectMessage(EXCEEDS_LIMIT);
        library.getMember("a