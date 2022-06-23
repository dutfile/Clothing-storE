/*
 * Copyright (c) 2012, 2018, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.graalvm.compiler.graph.Node.NodeIntrinsic;
import org.graalvm.compiler.java.LambdaUtils;
import org.graalvm.compiler.nodes.BreakpointNode;
import org.graalvm.compiler.serviceprovider.JavaVersionUtil;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.RecomputeFieldValue.Kind;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.util.ReflectionUtil;
import com.oracle.svm.util.StringUtil;

import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Signature;
import jdk.vm.ci.services.Services;

public class SubstrateUtil {

    /**
     * Field that is true during native image generation, but false at run time.
     */
    public static final boolean HOSTED;

    static {
        /*
         * Static initializer runs on the hosting VM, setting field value to true during native
         * image generation. At run time, the substituted value from below is used, setting the
         * field value to false at run time.
         */
        HOSTED = true;
    }

    public static String getArchitectureName() {
        String arch = System.getProperty("os.arch");
        switch (arch) {
            case "x86_64":
                arch = "amd64";
                break;
            case "arm64":
                arch = "aarch64";
                break;
        }
        return arch;
    }

    /**
     * @return true if the standalone libgraal is being built instead of a normal SVM image.
     */
    public static boolean isBuildingLibgraal() {
        return Services.IS_BUILDING_NATIVE_IMAGE;
    }

    /**
     * @return true if running in the standalone libgraal image.
     */
    public static boolean isInLibgraal() {
        return Services.IS_IN_NATIVE_IMAGE;
    }

    public static boolean isRunningInCI() {
        return System.console() == null || System.getenv("CI") != null;
    }

    /**
     * Pattern for a single shell command argument that does not need to be quoted.
     */
    private static final Pattern SAFE_SHELL_ARG = Pattern.compile("[A-Za-z0-9@%_\\-+=:,./]+");

    /**
     * Reliably quote a string as a single shell command argument.
     */
    public static String quoteShellArg(String arg) {
        if (arg.isEmpty()) {
            return "''";
        }
        Matcher m = SAFE_SHELL_ARG.matcher(arg);
        if (m.matches()) {
            return arg;
        }
        return "'" + arg.replace("'", "'\"'\"'") + "'";
    }

    public static String getShellCommandString(List<String> cmd, boolean multiLine) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cmd.size(); i++) {
            if (i > 0) {
                sb.append(multiLine ? " \\\n" : " ");
            }
            sb.append(quoteShellArg(cmd.get(i)));
        }
        return sb.toString();
    }

    @TargetClass(com.oracle.svm.core.SubstrateUtil.class)
    static final class Target_com_oracle_svm_core_SubstrateUtil {
        @Alias @RecomputeFieldValue(kind = Kind.FromAlias, isFinal = true)//
        private static boolean HOSTED = false;
    }

    @TargetClass(java.io.FileOutputStream.class)
    static final class Target_java_io_FileOutputStream {
        @Alias//
        FileDescriptor fd;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static FileDescriptor getFileDescriptor(FileOutputStream out) {
        return SubstrateUtil.cast(out, Target_java_io_FileOutputStream.class).fd;
    }

    /**
     * Convert C-style to Java-style command line arguments. The first C-style argument, which is
     * always the executable file name, is ignored.
     *
     * @param argc the number of arguments in the {@code argv} array.
     * @param argv a C {@code char**}.
     *
     * @return the command line argument strings in a Java string array.
     */
    public static String[] convertCToJavaArgs(int argc, CCharPointerPointer argv) {
        String[] args = new String[argc - 1];
        for (int i = 1; i < argc; ++i) {
            args[i - 1] = CTypeConversion.toJavaString(argv.read(i));
        }
        return args;
    }

    /**
     * Returns the length of a C {@code char*} string.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static UnsignedWord strlen(CCharPointer str) {
        UnsignedWord n = WordFactory.zero();
        while (((Pointer) str).readByte(n) != 0) {
            n = n.add(1);
        }
        return n;
    }

    /**
     * Returns a pointer to the matched character or NULL if the character is not found.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static CCharPointer strchr(CCharPointer str, int c) {
        int index = 0;
        while (true) {
            byte b = str.read(index);
            if (b == c) {
                return str.addressOf(index);
            }
            if (b == 0) {
                return WordFactory.zero();
            }
            index += 1;
        }
    }

    /**
     * The same as {@link Class#cast}. This method is available for use in places where either the
     * Java compiler or static analysis tools would complain about a cast because the cast appears
     * to violate the Java type system rules.
     *
     * The most prominent example are casts between a {@link TargetClass} and the original class,
     * i.e., two classes that appear to be unrelated from the Java type system point of view, but
     * are actually the same class.
     */
    @SuppressWarnings({"unused", "unchecked"})
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static <T> T cast(Object obj, Class<T> toType) {
        return (T) obj;
    }

    /**
     * Checks whether assertions are enabled in the VM.
     *
     * @return true if assertions are enabled.
     */
    @SuppressWarnings("all")
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static boolean assertionsEnabled() {
        boolean assertionsEnabled = false;
        assert assertionsEnabled = true;
        return assertionsEnabled;
    }

    /**
     * Emits a node that triggers a breakpoint in debuggers.
     *
     * @param arg0 value to inspect when the breakpoint hits
     * @see BreakpointNode how to use breakpoints and inspect breakpoint values in the debugger
     */
    @NodeIntrinsic(BreakpointNode.class)
    public static native void breakpoint(Object arg0);

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static boolean isPowerOf2(long value) {
        return (value & (value - 1)) == 0;
    }

    /** The functional interface for a "thunk". */
    @FunctionalInterface
    public interface Thunk {

        /** The method to be supplied by the implementor. */
        void invoke();
    }

    /**
     * Similar to {@link String#split(String)} but with a fixed separator string instead of a
     * regular expression. This avoids making regular expression cod