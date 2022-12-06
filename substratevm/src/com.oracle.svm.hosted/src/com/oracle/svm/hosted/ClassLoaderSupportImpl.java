
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

package com.oracle.svm.hosted;

import static com.oracle.svm.core.jdk.Resources.RESOURCES_INTERNAL_PATH_SEPARATOR;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.oracle.svm.core.ClassLoaderSupport;
import com.oracle.svm.core.util.ClasspathUtils;
import com.oracle.svm.core.util.UserError;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.util.ClassUtil;

import jdk.internal.module.Modules;

public class ClassLoaderSupportImpl extends ClassLoaderSupport {

    private final NativeImageClassLoaderSupport classLoaderSupport;

    private final ClassLoader imageClassLoader;
    private final URLClassLoader classPathClassLoader;

    private final Map<String, Set<Module>> packageToModules;

    public ClassLoaderSupportImpl(NativeImageClassLoaderSupport classLoaderSupport) {
        this.classLoaderSupport = classLoaderSupport;
        imageClassLoader = classLoaderSupport.getClassLoader();
        /*
         * Only if imageClassLoader is not the URLClassLoader we need to also remember its parent as
         * classPathClassLoader (for use in isNativeImageClassLoaderImpl). Otherwise, there is only
         * the URLClassLoader (already stored in imageClassLoader, extra classPathClassLoader field
         * can be set to null).
         */
        classPathClassLoader = imageClassLoader instanceof URLClassLoader ? null : (URLClassLoader) imageClassLoader.getParent();
        packageToModules = new HashMap<>();
        buildPackageToModulesMap(classLoaderSupport);
    }

    @Override
    protected boolean isNativeImageClassLoaderImpl(ClassLoader loader) {
        if (loader == imageClassLoader) {
            return true;
        }
        if (classPathClassLoader != null && loader == classPathClassLoader) {
            return true;
        }
        if (loader instanceof NativeImageSystemClassLoader) {
            return true;
        }
        return false;
    }

    @Override
    public void collectResources(ResourceCollector resourceCollector) {
        /* Collect resources from modules */
        NativeImageClassLoaderSupport.allLayers(classLoaderSupport.moduleLayerForImageBuild).stream()
                        .flatMap(moduleLayer -> moduleLayer.configuration().modules().stream())
                        .forEach(resolvedModule -> collectResourceFromModule(resourceCollector, resolvedModule));

        /* Collect remaining resources from classpath */
        for (Path classpathFile : classLoaderSupport.classpath()) {
            try {
                if (Files.isDirectory(classpathFile)) {
                    scanDirectory(classpathFile, resourceCollector);
                } else if (ClasspathUtils.isJar(classpathFile)) {
                    scanJar(classpathFile, resourceCollector);
                }
            } catch (IOException ex) {
                throw UserError.abort("Unable to handle classpath element '%s'. Make sure that all classpath entries are either directories or valid jar files.", classpathFile);
            }
        }
    }

    private static void collectResourceFromModule(ResourceCollector resourceCollector, ResolvedModule resolvedModule) {
        ModuleReference moduleReference = resolvedModule.reference();
        try (ModuleReader moduleReader = moduleReference.open()) {
            String moduleName = resolvedModule.name();
            List<String> foundResources = moduleReader.list()
                            .filter(resourceName -> resourceCollector.isIncluded(moduleName, resourceName, moduleReference.location().orElse(null)))
                            .collect(Collectors.toList());

            for (String resName : foundResources) {
                Optional<InputStream> content = moduleReader.open(resName);
                if (content.isEmpty()) {
                    continue;
                }
                try (InputStream is = content.get()) {
                    resourceCollector.addResource(moduleName, resName, is, false);
                }
            }
        } catch (IOException e) {
            throw VMError.shouldNotReachHere(e);
        }
    }

    private static void scanDirectory(Path root, ResourceCollector collector) throws IOException {
        Map<String, List<String>> matchedDirectoryResources = new HashMap<>();
        Set<String> allEntries = new HashSet<>();

        ArrayDeque<Path> queue = new ArrayDeque<>();
        queue.push(root);
        while (!queue.isEmpty()) {
            Path entry = queue.pop();

            /* Resources always use / as the separator, as do our resource inclusion patterns */
            String relativeFilePath;
            if (entry != root) {
                relativeFilePath = root.relativize(entry).toString().replace(File.separatorChar, RESOURCES_INTERNAL_PATH_SEPARATOR);
                allEntries.add(relativeFilePath);
            } else {
                relativeFilePath = "";
            }

            if (Files.isDirectory(entry)) {
                if (collector.isIncluded(null, relativeFilePath, Path.of(relativeFilePath).toUri())) {
                    matchedDirectoryResources.put(relativeFilePath, new ArrayList<>());
                }
                try (Stream<Path> pathStream = Files.list(entry)) {
                    Stream<Path> filtered = pathStream;
                    if (ClassUtil.CLASS_MODULE_PATH_EXCLUDE_DIRECTORIES_ROOT.equals(entry)) {
                        filtered = filtered.filter(Predicate.not(ClassUtil.CLASS_MODULE_PATH_EXCLUDE_DIRECTORIES::contains));