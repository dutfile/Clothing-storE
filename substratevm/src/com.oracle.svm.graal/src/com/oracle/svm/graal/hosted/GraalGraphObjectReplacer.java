/*
 * Copyright (c) 2013, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.graal.hosted;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.graalvm.compiler.api.replacements.SnippetReflectionProvider;
import org.graalvm.compiler.api.runtime.GraalRuntime;
import org.graalvm.compiler.core.common.spi.ConstantFieldProvider;
import org.graalvm.compiler.core.common.spi.ForeignCallsProvider;
import org.graalvm.compiler.debug.MetricKey;
import org.graalvm.compiler.graph.NodeClass;
import org.graalvm.compiler.hotspot.GraalHotSpotVMConfig;
import org.graalvm.compiler.hotspot.HotSpotBackendFactory;
import org.graalvm.compiler.hotspot.SnippetResolvedJavaMethod;
import org.graalvm.compiler.hotspot.SnippetResolvedJavaType;
import org.graalvm.compiler.nodes.FieldLocationIdentity;
import org.graalvm.nativeimage.c.function.RelocatedPointer;
import org.graalvm.nativeimage.hosted.Feature.CompilationAccess;

import com.oracle.graal.pointsto.constraints.UnsupportedFeatureException;
import com.oracle.graal.pointsto.meta.AnalysisField;
import com.oracle.graal.pointsto.meta.AnalysisMetaAccess;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.meta.AnalysisUniverse;
import com.oracle.svm.common.meta.MultiMethod;
import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.graal.nodes.SubstrateFieldLocationIdentity;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.meta.ReadableJavaField;
import com.oracle.svm.core.util.HostedStringDeduplication;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.graal.SubstrateGraalRuntime;
import com.oracle.svm.graal.meta.SubstrateField;
import com.oracle.svm.graal.meta.SubstrateMethod;
import com.oracle.svm.graal.meta.SubstrateSignature;
import com.oracle.svm.graal.meta.SubstrateType;
import com.oracle.svm.hosted.SVMHost;
import com.oracle.svm.hosted.ameta.AnalysisConstantFieldProvider;
import com.oracle.svm.hosted.ameta.AnalysisConstantReflectionProvider;
import com.oracle.svm.hosted.analysis.AnnotationsProcessor;
import com.oracle.svm.hosted.meta.HostedConstantFieldProvider;
import com.oracle.svm.hosted.meta.HostedField;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedType;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.util.ReflectionUtil;

import jdk.vm.ci.code.Architecture;
import jdk.vm.ci.hotspot.HotSpotJVMCIRuntime;
import jdk.vm.ci.hotspot.HotSpotObjectConstant;
import jdk.vm.ci.hotspot.HotSpotResolvedJavaField;
import jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod;
import jdk.vm.ci.hotspot.HotSpotResolvedJavaType;
import jdk.vm.ci.hotspot.HotSpotSignature;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Signature;

/**
 * Replaces Graal related objects during analysis in the universe.
 *
 * It is mainly used to replace the Hosted* meta data with the Substrate* meta data.
 */
public class GraalGraphObjectReplacer implements Function<Object, Object> {

    private final AnalysisUniverse aUniverse;
    private final AnalysisMetaAccess aMetaAccess;
    private final HashMap<AnalysisMethod, SubstrateMethod> methods = new HashMap<>();
    private final HashMap<AnalysisField, SubstrateField> fields = new HashMap<>();
    private final HashMap<FieldLocationIdentity, SubstrateFieldLocationIdentity> fieldLocationIdentities = new HashMap<>();
    private final HashMap<AnalysisType, SubstrateType> types = new HashMap<>();
    private final HashMap<Signature, SubstrateSignature> signatures = new HashMap<>();
    private final SubstrateProviders sProviders;
    private SubstrateGraalRuntime sGraalRuntime;

    private final HostedStringDeduplication stringTable;

    private final Field substrateFieldAnnotationsEncodingField;
    private final Field substrateFieldTypeField;
    private final Field substrateFieldDeclaringClassField;
    private final Field dynamicHubMetaTypeField;
    private final Field substrateTypeRawAllInstanceFieldsField;
    private final Field substrateMethodImplementationsField;
    private final Field substrateMethodAnnotationsEncodingField;

    public GraalGraphObjectReplacer(AnalysisUniverse aUniverse, AnalysisMetaAccess aMetaAccess, SubstrateProviders sProviders) {
        this.aUniverse = aUniverse;
        this.aMetaAccess = aMetaAccess;
        this.sProviders = sProviders;
        this.stringTable = HostedStringDeduplication.singleton();
        substrateFieldAnnotationsEncodingField = ReflectionUtil.lookupField(SubstrateField.class, "annotationsEncoding");
        substrateFieldTypeField = ReflectionUtil.lookupField(SubstrateField.class, "type");
        substrateFieldDeclaringClassField = ReflectionUtil.lookupField(SubstrateField.class, "declaringClass");
        dynamicHubMetaTypeField = ReflectionUtil.lookupField(DynamicHub.class, "metaType");
        substrateTypeRawAllInstanceFieldsField = ReflectionUtil.lookupField(SubstrateType.class, "rawAllInstanceFields");
        substrateMethodImplementationsField = ReflectionUtil.lookupField(SubstrateMethod.class, "implementations");
        substrateMethodAnnotationsEncodingField = ReflectionUtil.lookupField(SubstrateMethod.class, "annotationsEncoding");
    }

    public void setGraalRuntime(SubstrateGraalRuntime sGraalRuntime) {
        assert this.sGraalRuntime == null;
        this.sGraalRuntime = sGraalRuntime;
    }

    @Override
    public Object apply(Object source) {

        if (source == null) {
            return null;
        }

        Object dest = source;

        if (source instanceof RelocatedPointer) {
            return dest;
        }

        if (source instanceof SnippetResolvedJavaMethod || source instanceof SnippetResolvedJavaType) {
            return source;
        }
        if (source instanceof MetaAccessProvider) {
            dest = sProviders.getMetaAccessProvider();
        } else if (source instanceof HotSpotJVMCIRuntime) {
            throw new UnsupportedFeatureException("HotSpotJVMCIRuntime should not appear in the image: " + source);
        } else if (source instanceof GraalHotSpotVMConfig) {
            throw new UnsupportedFeatureException("GraalHotSpotVMConfig should not appear in the image: " + source);
        } else if (source instanceof HotSpotBackendFactory) {
            HotSpotBackendFactory factory = (HotSpotBackendFactory) source;
            Architecture hostArch = HotSpotJVMCIRuntime.runtime().getHostJVMCIBackend().getTarget().arch;
            if (!factory.getArchitecture().equals(hostArch.getClass())) {
                throw new UnsupportedFeatureException("Non-host archtecture HotSpotBackendFactory should not appear in the image: " + source);
            }
        } else if (source instanceof GraalRuntime) {
            dest = sGraalRuntime;
        } else if (source instanceof AnalysisConstantReflectionProvider) {
            dest = sProviders.getConstantReflectionProvider();
        } else if (source instanceof AnalysisConstantFieldProvider) {
            dest = sProviders.getConstantFieldProvider();
        } else if (source instanceof ForeignCallsProvider) {
            dest = sProviders.getForeignCallsProvider();
        } else if (source instanceof SnippetReflectionProvider) {
            dest = sProviders.getSnippetReflectionProvider();

        } else if (source instanceof MetricKey) {
            /* Ensure lazily initialized name fields are computed. */
            ((MetricKey) source).getName();
        } else if (source instanceof NodeClass) {
            /* Ensure lazily initialized shortName field is computed. */
            ((NodeClass<?>) source).shortName();

        } else if (source instanceof HotSpotResolvedJavaMethod) {
            throw new UnsupportedFeatureException(source.toString());
        } else if (source instanceof HotSpotResolvedJavaField) {
            throw new UnsupportedFeatureException(source.toString());
        } else if (source instanceof HotSpotResolvedJavaType) {
            throw new UnsupportedFeatureException(source.toString());
        } else if (source instanceof HotSpotSignature) {
            throw new UnsupportedFeatureException(source.toString());
        } else if (source instanceof HotSpotObjectConstant) {
            throw new UnsupportedFeatureException(source.toString());
        } else if (source instanceof ResolvedJavaMethod && !(source instanceof SubstrateMethod)) {
            dest = createMethod((ResolvedJavaMethod) source);
        } else if (source instanceof ResolvedJavaField && !(source instanceof SubstrateField)) {
            dest = createField((ResolvedJavaField) source);
        } else if (source instanceof ResolvedJavaType && !(source instanceof SubstrateType)) {
            dest = createType((ResolvedJavaType) source);
        } else if (source instanceof FieldLocationIdentity && !(source instanceof SubstrateFieldLocationIdentity)) {
            dest = createFieldLocationIdentity((FieldLocationIdentity) source);
        }

        assert dest != null;
        String className = dest.getClass().getName();
        assert SubstrateUtil.isBuildingLibgraal() || !className.contains(".hotspot.") || className.contains(".svm.jtt.hotspot.") : "HotSpot object in image " + className;
        assert !className.contains(".graal.reachability") : "Analysis meta object in image " + className;
        assert !className.contains(".pointsto.meta.") : "Analysis meta object in image " + className;
        assert !className.contains(".hosted.meta.") : "Hosted meta object in image " + className;
        assert !SubstrateUtil.isBuildingLibgraal() || !className.contains(".svm.hosted.snippets.") : "Hosted snippet object in image " + className;

        return dest;
    }

    public synchronized SubstrateMethod createMethod(ResolvedJavaMethod original) {
        assert !(original instanceof SubstrateMethod) : original;

        AnalysisMethod aMethod;
        if (original instanceof AnalysisMethod) {
            aMethod = (AnalysisMethod) original;
        } else if (original instanceof HostedMethod) {
            aMethod = ((HostedMethod) original).wrapped;
        } else {
            aMethod = aUniverse.lookup(original);
        }
        aMethod = aMethod.getMultiMethod(MultiMethod.ORIGINAL_METHOD);
        assert aMethod != null;

        SubstrateMethod sMethod = methods.get(aMethod);
        if (sMethod == null) {
            assert !(original instanceof HostedMethod) : "too late to create new method";
            sMethod = new SubstrateMethod(aMethod, stringTable);
            methods.put(aMethod, sMethod);

            /*
             * The links to other meta objects must be set after adding to the methods to avoid
             * infinite recursion.
             */
            sMethod.setLinks(createSignature(aMethod.getSignature()), createType(aMethod.getDeclaringClass()));

            /*
             * Annotations are updated in every analysis iteration, but this is a starting point. It
             * also ensures that all types used by annotations are created eagerly.
             */
            setAnnotationsEncoding(aMethod, sMethod, null);
        }
        return sMethod;
    }

    public synchronized SubstrateField createField(ResolvedJavaField original) {
        assert !(original instanceof SubstrateField) : original;

        AnalysisField aField;
        if (original instanceof AnalysisField) {
            aField = (AnalysisField) original;
        } else if (original instanceof HostedField) {
            aField = ((HostedField) original).wrapped;
        } else {
            throw new InternalError(original.toString());
        }
        SubstrateField sField = fields.get(aField);

        if (sField == null) {
            assert !(original instanceof HostedField) : "too late to create new field";

            int modifiers = aField.getModifiers();
            if (ReadableJavaField.injectFinalForRuntimeCompilation(aField.wrapped)) {
                modifiers = modifiers | Modifier.FINAL;
            }
            sField = new SubstrateField(aField, modifiers, stringTable);
            fields.put(aField, sField);

            sField.setLinks(createType(aField.getType()), createType(aField.getDeclaringClass()));
            aUniverse.getHeapScanner().rescanField(sField, substrateFieldTypeField);
            aUniverse.getHeapScanner().rescanField(sField, substrateFieldDeclaringClassField);

            /*
             * Annotations are updated in every analysis iteration, but this is a starting point. It
             * also ensures that all types used by annotations are created eagerly.
             */
            setAnnotationsEncoding(aField, sField, null);
        }
        return sField;
    }

    private synchronized SubstrateFieldLocationIdentity createFieldLocationIdentity(FieldLocationIdentity original) {
        assert !(original instanceof SubstrateFieldLocationIdentity) : original;

        SubstrateFieldLocationIdentity dest = fieldLocationIdentities.get(original);
        if (dest == null) {
            SubstrateField destField = createField(original.getField());
   