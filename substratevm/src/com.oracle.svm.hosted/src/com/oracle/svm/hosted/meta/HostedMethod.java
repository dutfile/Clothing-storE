/*
 * Copyright (c) 2012, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted.meta;

import static com.oracle.svm.core.util.VMError.shouldNotReachHere;
import static com.oracle.svm.core.util.VMError.unimplemented;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;

import org.graalvm.collections.Pair;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.JavaMethodContext;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.graal.pointsto.constraints.UnsupportedFeatureException;
import com.oracle.graal.pointsto.infrastructure.GraphProvider;
import com.oracle.graal.pointsto.infrastructure.OriginalMethodProvider;
import com.oracle.graal.pointsto.infrastructure.WrappedJavaMethod;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.HostedProviders;
import com.oracle.graal.pointsto.results.StaticAnalysisResults;
import com.oracle.svm.common.meta.MultiMethod;
import com.oracle.svm.core.AlwaysInline;
import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.deopt.Deoptimizer;
import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.graal.code.StubCallingConvention;
import com.oracle.svm.core.meta.MethodPointer;
import com.oracle.svm.core.meta.SharedMethod;
import com.oracle.svm.core.meta.SubstrateMethodPointerConstant;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.code.CompilationInfo;
import com.oracle.svm.hosted.code.SubstrateCompilationDirectives;

import jdk.internal.vm.annotation.ForceInline;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.ConstantPool;
import jdk.vm.ci.meta.ExceptionHandler;
import jdk.vm.ci.meta.JavaMethod;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.LineNumberTable;
import jdk.vm.ci.meta.Local;
import jdk.vm.ci.meta.LocalVariableTable;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Signature;
import jdk.vm.ci.meta.SpeculationLog;

public final class HostedMethod extends HostedElement implements SharedMethod, WrappedJavaMethod, GraphProvider, JavaMethodContext, OriginalMethodProvider, MultiMethod {

    public static final String METHOD_NAME_COLLISION_SEPARATOR = "%";

    public final AnalysisMethod wrapped;

    private final HostedType holder;
    private final Signature signature;
    private final ConstantPool constantPool;
    private final ExceptionHandler[] handlers;
    StaticAnalysisResults staticAnalysisResults;
    int vtableIndex = -1;

    /**
     * The address offset of the compiled code relative to the code of the first method in the
     * buffer.
     */
    private int codeAddressOffset;
    private boolean codeAddressOffsetValid;
    private boolean compiled;

    /**
     * All concrete methods that can actually be called when calling this method. This includes all
     * overridden methods in subclasses, as well as this method if it is non-abstract.
     */
    HostedMethod[] implementations;

    public final CompilationInfo compilationInfo;
    private final LocalVariableTable localVariableTable;

    private final String name;
    private final String uniqueShortName;

    private final MultiMethodKey multiMethodKey;

    /**
     * Map from a key to the corresponding implementation. All multi-method implementations for a
     * given Java method share the same map. This allows one to easily switch between different
     * implementations when needed. When {@code multiMethodMap} is null, then
     * {@link #multiMethodKey} points to {@link #ORIGINAL_METHOD} and no other implementations exist
     * for the method. This is done to reduce the memory overhead in the common case when only this
     * one implementation is present.
     */
    private volatile Map<MultiMethodKey, MultiMethod> multiMethodMap;

    @SuppressWarnings("rawtypes") //
    private static final AtomicReferenceFieldUpdater<HostedMethod, Map> MULTIMETHOD_MAP_UPDATER = AtomicReferenceFieldUpdater.newUpdater(HostedMethod.class, Map.class,
                    "multiMethodMap");

    public static final HostedMethod[] EMPTY_ARRAY = new HostedMethod[0];

    static HostedMethod create(HostedUniverse universe, AnalysisMethod wrapped, HostedType holder, Signature signature,
                    ConstantPool constantPool, ExceptionHandler[] handlers) {
        LocalVariableTable localVariableTable = createLocalVariableTable(universe, wrapped);

        return create0(wrapped, holder, signature, constantPool, handlers, wrapped.getMultiMethodKey(), null, localVariableTable);
    }

    private static HostedMethod create0(AnalysisMethod wrapped, HostedType holder, Signature signature,
                    ConstantPool constantPool, ExceptionHandler[] handlers, MultiMethodKey key, Map<MultiMethodKey, MultiMethod> multiMethodMap, LocalVariableTable localVariableTable) {
        Function<Integer, Pair<String, String>> nameGenerator = (collisionCount) -> {
            String name = wrapped.wrapped.getName(); // want name w/o any multimethodkey suffix
            if (key != ORIGINAL_METHOD) {
                name += MULTI_METHOD_KEY_SEPARATOR