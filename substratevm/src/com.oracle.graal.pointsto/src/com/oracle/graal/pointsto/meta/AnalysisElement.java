
/*
 * Copyright (c) 2022, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.pointsto.meta;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Executable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.graalvm.nativeimage.hosted.Feature.DuringAnalysisAccess;

import com.oracle.graal.pointsto.ObjectScanner;
import com.oracle.graal.pointsto.util.AnalysisFuture;
import com.oracle.graal.pointsto.util.ConcurrentLightHashSet;

import jdk.vm.ci.code.BytecodePosition;
import jdk.vm.ci.meta.ModifiersProvider;

public abstract class AnalysisElement implements AnnotatedElement {

    public abstract AnnotatedElement getWrapped();

    protected abstract AnalysisUniverse getUniverse();

    @Override
    public final boolean isAnnotationPresent(Class<? extends Annotation> annotationClass) {
        return getUniverse().getAnnotationExtractor().hasAnnotation(getWrapped(), annotationClass);
    }

    @Override
    public final <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
        return getUniverse().getAnnotationExtractor().extractAnnotation(getWrapped(), annotationClass, false);
    }

    @Override
    public final <T extends Annotation> T getDeclaredAnnotation(Class<T> annotationClass) {
        return getUniverse().getAnnotationExtractor().extractAnnotation(getWrapped(), annotationClass, true);
    }

    @Override
    public final Annotation[] getAnnotations() {
        return getUniverse().getAnnotationExtractor().extractAnnotations(getWrapped(), false);
    }

    @Override
    public final Annotation[] getDeclaredAnnotations() {
        return getUniverse().getAnnotationExtractor().extractAnnotations(getWrapped(), true);
    }

    /**
     * Contains reachability handlers that are notified when the element is marked as reachable.
     * Each handler is notified only once, and then it is removed from the set.
     */

    private static final AtomicReferenceFieldUpdater<AnalysisElement, Object> reachableNotificationsUpdater = AtomicReferenceFieldUpdater
                    .newUpdater(AnalysisElement.class, Object.class, "elementReachableNotifications");

    @SuppressWarnings("unused") private volatile Object elementReachableNotifications;

    public void registerReachabilityNotification(ElementNotification notification) {
        ConcurrentLightHashSet.addElement(this, reachableNotificationsUpdater, notification);
    }

    public void notifyReachabilityCallback(AnalysisUniverse universe, ElementNotification notification) {
        notification.notifyCallback(universe, this);
        ConcurrentLightHashSet.removeElement(this, reachableNotificationsUpdater, notification);
    }

    protected void notifyReachabilityCallbacks(AnalysisUniverse universe, List<AnalysisFuture<Void>> futures) {
        ConcurrentLightHashSet.forEach(this, reachableNotificationsUpdater, (ElementNotification c) -> futures.add(c.notifyCallback(universe, this)));
        ConcurrentLightHashSet.removeElementIf(this, reachableNotificationsUpdater, ElementNotification::isNotified);
    }

    /**
     * Used to validate the reason why an analysis element is registered as reachable.
     */
    boolean isValidReason(Object reason) {
        if (reason == null) {
            return false;
        }
        if (reason instanceof String) {
            return !((String) reason).isEmpty();
        }
        /*