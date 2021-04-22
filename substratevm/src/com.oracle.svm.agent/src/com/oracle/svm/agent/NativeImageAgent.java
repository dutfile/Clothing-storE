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
            } else if (isBooleanOption(token, "builtin-caller-filter")) {
                builtinCallerFilter = getBooleanTokenValue(token);
            } else if (token.equals("no-builtin-heuristic-filter")) {
                builtinHeuristicFilter = false;
            } else if (isBooleanOption(token, "builtin-heuristic-filter")) {
                builtinHeuristicFilter = getBooleanTokenValue(token);
            } else if (isBooleanOption(token, "no-filter")) { // legacy
                builtinCallerFilter = !getBooleanTokenValue(token);
                builtinHeuristicFilter = builtinCallerFilter;
            } else if (token.startsWith("caller-filter-file=")) {
                callerFilterFiles.add(getTokenValue(token));
            } else if (token.startsWith("access-filter-file=")) {
                accessFilterFiles.add(getTokenValue(token));
            } else if (isBooleanOption(token, "experimental-class-loader-support")) {
                experimentalClassLoaderSupport = getBooleanTokenValue(token);
            } else if (isBooleanOption(token, "experimental-class-define-support")) {
                experimentalClassDefineSupport = getBooleanTokenValue(token);
            } else if (isBooleanOption(token, "experimental-unsafe-allocation-support")) {
                experimentalUnsafeAllocationSupport = getBooleanTokenValue(token);
            } else if (token.startsWith("config-write-period-secs=")) {
                configWritePeriod = parseIntegerOrNegative(getTokenValue(token));
                if (configWritePeriod <= 0) {
                    return usage(1, "config-write-period-secs must be an integer greater than 0");
                }
            } else if (token.startsWith("config-write-initial-delay-secs=")) {
                configWritePeriodInitialDelay = parseIntegerOrNegative(getTokenValue(token));
                if (configWritePeriodInitialDelay < 0) {
                    return usage(1, "config-write-initial-delay-secs must be an integer greater or equal to 0");
                }
            } else if (isBooleanOption(token, "build")) {
                build = getBooleanTokenValue(token);
            } else if (isBooleanOption(token, "experimental-configuration-with-origins")) {
                configurationWithOrigins = getBooleanTokenValue(token);
            } else if (token.startsWith("experimental-conditional-config-filter-file=")) {
                conditionalConfigUserPackageFilterFiles.add(getTokenValue(token));
            } else if (token.startsWith("conditional-config-class-filter-file=")) {
                conditionalConfigClassNameFilterFiles.add(getTokenValue(token));
            } else if (isBooleanOption(token, "experimental-conditional-config-part")) {
                conditionalConfigPartialRun = getBooleanTokenValue(token);
            } else if (isBooleanOption(token, "track-reflection-metadata")) {
                trackReflectionMetadata = getBooleanTokenValue(token);
            } else {
                return usage(1, "unknown option: '" + token + "'.");
            }
        }

        if (traceOutputFile == null && configOutputDir == null && !build) {
            configOutputDir = transformPath(AGENT_NAME + "_config-pid{pid}-{datetime}/");
            inform("no output/build options provided, tracking dynamic accesses and writing configuration to directory: " + configOutputDir);
        }

        if (configurationWithOrigins && !conditionalConfigUserPackageFilterFiles.isEmpty()) {
            return error(5, "The agent can only be used in either the configuration with origins mode or the predefined classes mode.");
        }

        if (configurationWithOrigins && !mergeConfigs.isEmpty()) {
            configurationWithOrigins = false;
            inform("using configuration with origins with configuration merging is currently unsupported. Disabling configuration with origins mode.");
        }

        if (configurationWithOrigins) {
            warn("using experimental configuration with origins mode. Note that native-image cannot process these files, and this flag may change or be removed without a warning!");
        }

        ComplexFilter callerFilter = null;
        HierarchyFilterNode callerFilterHierarchyFilterNode = null;
        if (!builtinCallerFilter) {
            callerFilterHierarchyFilterNode = HierarchyFilterNode.createInclusiveRoot();
            callerFilter = new ComplexFilter(callerFilterHierarchyFilterNode);
        }

        if (!callerFilterFiles.isEmpty()) {
            if (callerFilterHierarchyFilterNode == null) {
                callerFilterHierarchyFilterNode = AccessAdvisor.copyBuiltinCallerFilterTree();
                callerFilter = new ComplexFilter(callerFilterHierarchyFilterNode);
            }
            if (!parseFilterFiles(callerFilter, callerFilterFiles)) {
                return 1;
            }
        }

        ComplexFilter accessFilter = null;
        if (!accessFilterFiles.isEmpty()) {
            accessFilter = new ComplexFilter(AccessAdvisor.copyBuiltinAccessFilterTree());
            if (!parseFilterFiles(accessFilter, accessFilterFiles)) {
                return 1;
            }
        }

        if (!conditionalConfigUserPackageFilterFiles.isEmpty() && conditionalConfigPartialRun) {
            return error(6, "The agent can generate conditional configuration either for the current run or in the partial mode but not both at the same time.");
        }

        boolean isConditionalConfigurationRun = !conditionalConfigUserPackageFilterFiles.isEmpty() || conditionalConfigPartialRun;
        boolean shouldTraceOriginInformation = configurationWithOrigins || isConditionalConfigurationRun;
        final MethodInfoRecordKeeper recordKeeper = new MethodInfoRecordKeeper(shouldTraceOriginInformation);
        final Supplier<InterceptedState> interceptedStateSupplier = shouldTraceOriginInformation ? EagerlyLoadedJavaStackAccess.stackAccessSupplier()
                        : OnDemandJavaStackAccess.stackAccessSupplier();

        if (configOutputDir != null) {
            if (traceOutputFile != null) {
                return usage(1, "can only once specify exactly one of trace-output=, config-output-dir= or config-merge-dir=.");
            }
            try {
                configOutputDirPath = Files.createDirectories(Path.of(configOutputDir));
                configOutputLockFilePath = configOutputDirPath.resolve(ConfigurationFile.LOCK_FILE_NAME);
                try {
                    Files.writeString(configOutputLockFilePath, Long.toString(ProcessProperties.getProcessID()),
                                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                } catch (FileAlreadyExistsException e) {
                    String process;
                    try {
                        process = Files.readString(configOutputLockFilePath).stripTrailing();
                    } catch (Exception ignored) {
                        process = "(unknown)";
                    }
                    return error(2, "Output directory '" + configOutputDirPath + "' is locked by process " + process + ", " +
                                    "which means another agent instance is already writing to this directory. " +
                                    "Only one agent instance can safely write to a specific target directory at the same time. " +
                                    "Unless file '" + ConfigurationFile.LOCK_FILE_NAME + "' is a leftover from an earlier process that terminated abruptly, it is unsafe to delete it. " +
                                    "For running multiple processes with agents at the same time to create a single configuration, read AutomaticMetadataCollection.md " +
                                    "or https://www.graalvm.org/dev/reference-manual/native-image/metadata/AutomaticMetadataCollection/ on how to use the native-image-configure tool.");
                }
                if (experimentalOmitClasspathConfig) {
                    ignoreConfigFromClasspath(jvmti, omittedConfigs);
                }
                AccessAdvisor advisor = createAccessAdvisor(builtinHeuristicFilter, callerFilter, accessFilter);
                TraceProcessor processor = new TraceProcessor(advisor);
                ConfigurationSet omittedConfiguration = new ConfigurationSet();
                Predicate<String> shouldExcludeClassesWithHash = null;
                if (!omittedConfigs.isEmpty()) {
                    Function<IOException, Exception> ignore = e -> {
                        warn("Failed to load omitted config: " + e);
                        return null;
                    };
                    omittedConfiguration = omittedConfigs.loadConfigurationSet(ignore, null, null);
                    shouldExcludeClassesWithHash = omittedConfiguration.getPredefinedClassesConfiguration()::containsClassWithHash;
                }

                if (shouldTraceOriginInformation) {
                    ConfigurationWithOriginsTracer configWithOriginsTracer = new ConfigurationWithOriginsTracer(processor, recordKeeper);
                    tracer = configWithOriginsTracer;

                    if (isConditionalConfigurationRun) {
                        if (conditionalConfigPartialRun) {
                            tracingResultWriter = new ConditionalConfigurationPartialRunWriter(configWithOriginsTracer);
                        } else {
                            ComplexFilter userCodeFilter = new ComplexFilter(HierarchyFilterNode.createRoot());
                            if (!parseFilterFiles(userCodeFilter, conditionalConfigUserPackageFilterFiles)) {
                                return 2;
                            }
                            ComplexFilter classNameFilter;
                            if (!conditionalConfigClassNameFilterFiles.isEmpty()) {
                                classNameFilter = new ComplexFilter(HierarchyFilterNode.createRoot());
                                if (!parseFilterFiles(classNameFilter, conditionalConfigClassNameFilterFiles)) {
                                    return 3;
                                }
                            } else {
                                classNameFilter = new ComplexFilter(HierarchyFilterNode.createInclusiveRoot());
                            }

                            ConditionalConfigurationPredicate predicate = new ConditionalConfigurationPredicate(classNameFilter);
                            tracingResultWriter = new ConditionalConfigurationWriter(configWithOriginsTracer, userCodeFilter, predicate);
                        }
                    } else {
                        tracingResultWriter = new ConfigurationWithOriginsWriter(configWithOriginsTracer);
                    }
                } else {
                    Path[] predefinedClassDestDirs = {Files.createDirectories(configOutputDirPath.resolve(ConfigurationFile.PREDEFINED_CLASSES_AGENT_EXTRACTED_SUBDIR))};
                    Function<IOException, Exception> handler = e -> {
                        if (e instanceof NoSuchFileException) {
                            warn("file " + ((NoSuchFileException) e).getFile() + " for merging could not be found, skipping");
                            return null;
                        } else if (e instanceof FileNotFoundException) {
                            warn("could not open configuration file: " + e);
                            return null;
                        }
                        return e; // rethrow
                    };

                    ConfigurationSet configuration = mergeConfigs.loadConfigurationSet(handler, predefinedClassDestDirs, shouldExcludeClassesWithHash);
                    ConfigurationResultWriter writer = new ConfigurationResultWriter(processor, configuration, omittedConfiguration);
                    tracer = writer;
                    tracingResultWriter = writer;
                }
                expectedConfigModifiedBefore = getMostRecentlyModified(configOutputDirPath, getMostRecentlyModified(configOutputLockFilePath, null));
            } catch (Throwable t) {
                return error(2, t.toString());
            }
        } else if (traceOutputFile != null) {
            try {
                Path path = Paths.get(transformPath(traceOutputFile));
                TraceFileWriter writer = new TraceFileWriter(path);
                tracer = writer;
                tracingResultWriter = writer;
            } catch (Throwable t) {
                return error(2, t.toString());
            }
        }

        if (build) {
            int status = buildImage(jvmti);
            if (status == 0) {
                System.exit(status);
            }
            return status;
        }

        try {
            BreakpointInterceptor.onLoad(jvmti, callbacks, tracer, this, interceptedStateSupplier,
                            experimentalClassLoaderSupport, experimentalClassDefineSupport, experimentalUnsafeAllocationSupport, trackReflectionMetadata);
        } catch (Throwable t) {
            return error(3, t.toString());
        }
        try {
            JniCallInterceptor.onLoad(tracer, this, interceptedStateSupplier);
