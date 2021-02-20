/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.configure.test.config;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import org.graalvm.nativeimage.impl.ConfigurationCondition;
import org.junit.Assert;
import org.junit.Test;

import com.oracle.svm.configure.config.ConfigurationFileCollection;
import com.oracle.svm.configure.config.ConfigurationMemberInfo;
import com.oracle.svm.configure.config.ConfigurationMemberInfo.ConfigurationMemberAccessibility;
import com.oracle.svm.configure.config.ConfigurationMemberInfo.ConfigurationMemberDeclaration;
import com.oracle.svm.configure.config.ConfigurationMethod;
import com.oracle.svm.configure.config.ConfigurationSet;
import com.oracle.svm.configure.config.ConfigurationType;
import com.oracle.svm.configure.config.FieldInfo;
import com.oracle.svm.configure.config.PredefinedClassesConfiguration;
import com.oracle.svm.configure.config.ProxyConfiguration;
import com.oracle.svm.configure.config.ResourceConfiguration;
import com.oracle.svm.configure.config.SerializationConfiguration;
import com.oracle.svm.configure.config.TypeConfiguration;
import com.oracle.svm.core.util.VMError;

public class OmitPreviousConfigTests {

    private static final String PREVIOUS_CONFIG_DIR_NAME = "prev-config-dir";
    private static final String CURRENT_CONFIG_DIR_NAME = "config-dir";

    private static ConfigurationSet loadTraceProcessorFromResourceDirectory(String resourceDirectory, ConfigurationSet omittedConfig) {
        try {
            ConfigurationFileCollection configurationFileCollection = new ConfigurationFileCollection();
            configurationFileCollection.addDirectory(resourceFileName -> {
                try {
                    String resourceName = resourceDirectory + "/" + resourceFileName;
                    URL resourceURL = OmitPreviousConfigTests.class.getResource(resourceName);
                    return (resourceURL != null) ? resourceURL.toURI() : null;
                } catch (Exception e) {
                    throw VMError.shouldNotReachHere("Unexpected error while locating the configuration files.", e);
                }
            });

            Function<IOException, Exception> handler = e -> {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                Assert.fail("Exception occurred while loading configuration: " + e + System.lineSeparator() + sw);
                return e;
            };
            Predicate<String> shouldExcludeClassesWithHash = null;
            if (omittedConfig != null) {
                shouldExcludeClassesWithHash = omittedConfig.getPredefinedClassesConfiguration()::containsClassWithHash;
            }
            return configurationFileCollection.loadConfigurationSet(handler, null, shouldExcludeClassesWithHash);
        } catch (Exception e) {
            throw VMError.shouldNotReachHere("Unexpected error while loading the configuration files.", e);
        }
    }

    @Test
    public void testSameConfig() {
        ConfigurationSet omittedConfig = loadTraceProcessorFromResourceDirectory(PREVIOUS_CONFIG_DIR_NAME, null);
        ConfigurationSet config = loadTraceProcessorFromResourceDirectory(PREVIOUS_CONFIG_DIR_NAME, omittedConfig);
        config = config.copyAndSubtract(omittedConfig);

        assertTrue(config.getJniConfiguration().isEmpty());
        assertTrue(config.getReflectionConfiguration().isEmpty());
        assertTrue(config.getProxyConfiguration().isEmpty());
        assertTrue(config.getResourceConfiguration().isEmpty());
        assertTrue(config.getSerializationConfiguration().isEmpty());
        assertTrue(config.getPredefinedClassesConfiguration().isEmpty());
    }

    @Test
    public void testConfigDifference() {
        ConfigurationSet omittedConfig = loadTraceProcessorFromResourceDirectory(PREVIOUS_CONFIG_DIR_NAME, null);
        ConfigurationSet config = loadTraceProcessorFromResourceDirectory(CURRENT_CONFIG_DIR_NAME, omittedConfig);
        config = config.copyAndSubtract(omittedConfig);

        doTestGeneratedTypeConfig();
        doTestTypeConfig(config.getJniConfiguration());

        doTestProxyConfig(config.getProxyConfiguration());

        doTestResourceConfig(config.getResourceConfiguration());

        doTestSerializationConfig(config.getSerializationConfiguration());

        doTestPredefinedClassesConfig(config.getPredefinedClassesConfiguration());
    }

    private static void doTestGeneratedTypeConfig() {
        TypeMethodsWithFlagsTest typeMethodsWithFlagsTestDeclared = new TypeMethodsWithFlagsTest(ConfigurationMemberDeclaration.DECLARED);
        typeMethodsWithFlagsTestDeclared.doTest();

        TypeMethodsWithFlagsTest typeMethodsWithFlagsTestPublic = new TypeMethodsWithFlagsTest(ConfigurationMemberDeclaration.PUBLIC);
        typeMethodsWithFlagsTestPublic.doTest();

        TypeMethodsWithFlagsTest typeMethodsWithFlagsTestDeclaredPublic = new TypeMethodsWithFlagsTest(ConfigurationMemberDeclaration.DECLARED_AND_PUBLIC);
        typeMethodsWithFlagsTestDeclaredPublic.doTest();
    }

    private static void doTestTypeConfig(TypeConfiguration typeConfig) {
        doTestExpectedMissingTypes(typeConfig);

        doTestTypeFlags(typeConfig);

        doTestFields(typeConfig);

        doTestMethods(typeConfig);
    }

    private static void doTestExpectedMissingTypes(TypeConfiguration typeConfig) {
        Assert.assertNull(typeConfig.get(ConfigurationCondition.alwaysTrue(), "FlagTestA"));
        Assert.assertNull(typeConfig.get(ConfigurationCondition.alwaysTrue(), "FlagTestB"));
    }

    private static void doTestTypeFlags(TypeConfiguration typeConfig) {
        ConfigurationType flagTestHasDeclaredType = getConfigTypeOrFail(typeConfig, "FlagTestC");
        Assert.assertTrue(ConfigurationType.TestBackdoor.haveAllDeclaredClasses(flagTestHasDeclaredType) || ConfigurationType.TestBackdoor.haveAllDeclaredFields(flagTestHasDeclaredType) ||
                        ConfigurationType.TestBackdoor.getAllDeclaredConstructors(flagTestHasDeclaredType) == ConfigurationMemberAccessibility.ACCESSED);

        ConfigurationType flagTestHasPublicType = getConfigTypeOrFail(typeConfig, "FlagTestD");
        Assert.assertTrue(ConfigurationType.TestBackdoor.haveAllPublicClasses(flagTestHasPublicType) || ConfigurationType.TestBackdoor.haveAllPublicFields(flagTestHasPublicType) ||
                        ConfigurationType.TestBackdoor.getAllPublicConstructors(flagTestHasPublicType) == ConfigurationMemberAccessibility.ACCESSED);
    }

    private static void doTestFields(TypeConfiguration typeConfig) {
        ConfigurationType fieldTestType = getConfigTypeOrFail(typeConfig, "MethodAndFieldTest");

        Assert.assertNull(ConfigurationType.TestBackdoor.getFieldInfoIfPresent(fieldTestType, "SimpleField"));
        Assert.assertNull(ConfigurationType.TestBackdoor.getFieldInfoIfPresent(fieldTestType, "AllowWriteField"));

        FieldInfo newField = ConfigurationType.TestBackdoor.getFieldInfoIfPresent(fieldTestType, "NewField");
        Assert.assertFalse(newField.isFinalButWritable());

        FieldInfo newWritableField = getFieldInfoOrFail(fieldTestType, "NewAllowWriteField");
        Assert.assertTrue(newWritableField.isFinalButWritable());

        FieldInfo newlyWritableField = getFieldInfoOrFail(fieldTestType, "NewNowWritableField");
        Assert.assertTrue(newlyWritableField.isFinalButWritable());
    }

    private static void doTestMethods(TypeConfiguration typeConfig) {
        ConfigurationType methodTestType = getConfigTypeOrFail(typeConfig, "MethodAndFieldTest");

        Assert.assertNull(ConfigurationType.TestBackdoor.getMethodInfoIfPresent(methodTestType, new ConfigurationMethod("<init>", "(I)V")));
        Assert.assertNotNull(ConfigurationType.TestBackdoor.getMethodInfoIfPresent(methodTestType, new ConfigurationMethod("method", "()V")));
    }

    private static void doTestProxyConfig(ProxyConfiguration proxyConfig) {
        ConfigurationCondition condition = ConfigurationCondition.alwaysTrue();
        Assert.assertFalse(proxyConfig.contains(condition, "testProxySeenA", "testProxySeenB", "testProxySeenC"));
        Assert.assertTrue(proxyConfig.contains(condition, "testProxyUnseen"));
    }

    private static void doTestResourceConfig(ResourceConfiguration resourceConfig) {
        Assert.assertFalse(resourceConfig.anyResourceMatches("seenResource.txt"));
        Assert.assertTrue(resourceConfig.anyResourceMatches("unseenResource.txt"));

        ConfigurationCondition condition = ConfigurationCondition.alwaysTrue();
        Assert.assertFalse(resourceConfig.anyBundleMatches(condition, "seenBundle"));
        Assert.assertTrue(resourceConfig.anyBundleMatches(condition, "unseenBundle"));
    }

    private static void doTestSerializationConfig(SerializationConfiguration serializationConfig) {
        ConfigurationCondition condition = ConfigurationCondition.alwaysTrue();
        Assert.assertFalse(serializationConfig.contains(condition, "seenType", null));
        Assert.assertTrue(serializationConfig.contains(condition, "unseenType", null));
    }

    private static ConfigurationType getConfigTypeOrFail(TypeConfiguration typeConfig, String typeName) {
        ConfigurationType type = typeConfig.get(ConfigurationCondition.alwaysTrue(), typeName);
        Assert.assertNotNull(type);
        return 