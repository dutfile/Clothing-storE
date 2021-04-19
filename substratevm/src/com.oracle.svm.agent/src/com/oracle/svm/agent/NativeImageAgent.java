/*
 * Copyright (c) 2019, 2021, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.agent;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.ProcessProperties;
import org.graalvm.nativeimage.hosted.Feature;

import com.oracle.svm.agent.conditionalconfig.ConditionalConfigurationPartialRunWriter;
import com.oracle.svm.agent.conditionalconfig.ConditionalConfigurationWriter;
import com.oracle.svm.agent.configwithorigins.ConfigurationWithOriginsTracer;
import com.oracle.svm.agent.configwithorigins.ConfigurationWithOriginsWriter;
import com.oracle.svm.agent.configwithorigins.MethodInfoRecordKeeper;
import com.oracle.svm.agent.ignoredconfig.AgentMetaInfProcessor;
import com.oracle.svm.agent.stackaccess.EagerlyLoadedJavaStackAccess;
import com.oracle.svm.agent.stackaccess.InterceptedState;
import com.oracle.svm.agent.stackaccess.OnDemandJavaStackAccess;
import com.oracle.svm.agent.tracing.ConfigurationResultWriter;
import com.oracle.svm.agent.tracing.TraceFileWriter;
import com.oracle.svm.agent.tracing.core.Tracer;
import com.oracle.svm.agent.tracing.core.TracingResultWriter;
import com.oracle.svm.configure.config.ConfigurationFileCollection;
import com.oracle.svm.configure.config.ConfigurationSet;
import com.oracle.svm.configure.config.conditional.ConditionalConfigurationPredicate;
import com.oracle.svm.configure.filters.ComplexFilter;
import com.oracle.svm.configure.filters.ConfigurationFilter;
import com.oracle.svm.configure.filters.FilterConfigurationParser;
import com.oracle.svm.configure.filters.HierarchyFilterNode;
import com.oracle.svm.configure.trace.AccessAdvisor;
import com.oracle.svm.configure.trace.TraceProcessor;
import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.configure.ConfigurationFile;
import com.oracle.svm.core.jni.headers.JNIEnvironment;
import com.oracle.svm.core.jni.headers.JNIJavaVM;
import com.oracle.svm.core.jni.headers.JNIObjectHandle;
import com.oracle.svm.driver.NativeImage;
import com.oracle.svm.driver.metainf.NativeImageMetaInfWalker;
import com.oracle.svm.jvmtiagentbase.JNIHandleSet;
import com.oracle.svm.jvmtiagentbase.JvmtiAgentBase;
import com.oracle.svm.jvmtiagentbase.Support;
import com.oracle.svm.jvmtiagentbase.jvmti.JvmtiEnv;
import com.oracle.svm.jvmtiagentbase.jvmti.JvmtiEventCallbacks;
import com.oracle.svm.jvmtiagentbase.jvmti.JvmtiInterface;

public final class NativeImageAgent extends JvmtiAgentBase<NativeImageAgentJNIHandleSet> {
    private static final String AGENT_NAME = "native-image-agent";
    private static final TimeZone UTC_TIMEZONE = TimeZone.getTimeZone("UTC");

    private ScheduledThreadPoolExecutor periodicConfigWriterExecutor = null;

    private Tracer tracer;
    private TracingResultWriter tracingResultWriter;

    private Path configOutputDirPath;
    private Path configOutputLockFilePath;
    private FileTime expectedConfigModifiedBefore;

    private static String getTokenValue(String token) {
        return token.substring(token.indexOf('=') + 1);
    }

    private static boolean getBooleanTokenValue(String token) {
        int equalsIndex = token.indexOf('=');
        if (equalsIndex == -1) {
            return true;
        }
        return Boolean.parseBoolean(token.substring(equalsIndex + 1));
    }

    private static boolean isBooleanOption(String token, String option) {
        return token.equals(option) || token.startsWith(option + "=");
    }

    @Override
    protected int getRequiredJvmtiVersion() {
        return JvmtiInterface.JVMTI_VERSION_1_2;
    }

    @Override
    protected JNIHandleSet constructJavaHandles(JNIEnvironment env) {
        return new NativeImageAgentJNIHandleSet(env);
    }

    @Override
    protected int onLoadCallback(JNIJavaVM vm, JvmtiEnv jvmti, JvmtiEventCallbacks callbacks, String options) {
        String traceOutputFile = null;
        String configOutputDir = null;
        ConfigurationFileCollection mergeConfigs = new ConfigurationFileCollection();
        ConfigurationFileCollection omittedConfigs = new ConfigurationFileCollection();
        boolean builtinCallerFilter = true;
        boolean builtinHeuristicFilter = true;
        List<String> callerFilterFiles = new ArrayList<>();
        List<String> accessFilterFiles = new ArrayList<>();
        boolean experimentalClassLoaderSupport = true;
        boolean experimentalClassDefineSupport = false;
        boolean experimentalUnsafeAllocationSupport = false;
        boolean experimentalOmitClasspathConfig = false;
        boolean build = false;
        boolean configurationWithOrigins = false;
        List<String> conditionalConfigUserPackageFilterFiles = new ArrayList<>();
        List<String> conditionalConfigClassNameFilterFiles = new ArrayList<>();
        boolean conditionalConfigPartialRun = false;
        int configWritePeriod = -1; // in seconds
        int configWritePeriodInitialDelay = 1; // in seconds
        boolean trackReflectionMetadata = true;

        String[] tokens = !options.isEmpty() ? options.split(",") : new String[0];
        for (String token : tokens) {
            if (token.startsWith("trace-output=")) {
                if (traceOutputFile != null) {
                    return usage(1, "cannot specify trace-output= more than once.");
                }
                traceOutputFile = getTokenValue(token);
            } else if (token.startsWith("config-output-dir=") || token.startsWith("config-merge-dir=")) {
                if (configOutputDir != null) {
                    return usage(1, "cannot specify more than one of config-output-dir= or config-merge-dir=.");
                }
                configOutputDir = transformPath(getTokenValue(token));
                if (token.startsWith("config-merge-dir=")) {
                    mergeConfigs.addDirectory(Paths.get(configOutputDir));
                }
            } else if (token.startsWith("config-to-omit=")) {
                String omittedConfigDir = getTokenValue(token);
                omittedConfigDir = transformPath(omittedConfigDir);
                omittedConfigs.addDirectory(Paths.get(omittedConfigDir));
            } else if (isBooleanOption(token, "experimental-omit-config-from-classpath")) {
                experimentalOmitClasspathConfig = getBooleanTokenValue(token);
            } else if (token.startsWith("restrict-all-dir") || token.equals("restrict") || token.startsWith("restrict=")) {
                warn("restrict mode is no longer supported, ignoring option: " + token);
            } else if (token.equals("no-builtin-caller-filter")) {
                builtinCallerFilter = false;
            } else if (isBo