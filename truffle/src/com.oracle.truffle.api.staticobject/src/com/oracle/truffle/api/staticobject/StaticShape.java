/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.staticobject;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.nativeimage.ImageInfo;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A StaticShape is an immutable descriptor of the layout of a static object and is a good entry
 * point to learn about the Static Object Model. Here is an overview:
 * <ul>
 * <li>{@link StaticShape#newBuilder(TruffleLanguage)} returns a {@link StaticShape.Builder} object
 * that can be used to {@linkplain StaticShape.Builder#property(StaticProperty, Class, boolean)
 * register} {@linkplain StaticProperty static properties} and to generate a new static shape by
 * calling one of its {@linkplain Builder#build() build methods}.
 * <li>{@link StaticShape#getFactory()} returns an implementation of the {@linkplain Builder#build()
 * default} or the {@linkplain StaticShape.Builder#build(Class, Class) user-defined} factory
 * interface that must be used to allocate static objects with the current shape.
 * <li>Property values stored in a static object of a given shape can be accessed by the
 * {@link StaticProperty} instances registered to the builder that generated that shape or one of
 * its {@linkplain StaticShape.Builder#build(StaticShape) parent shapes}. Note that static shapes do
 * not store the list of {@linkplain StaticProperty static properties} associated to them. It is up
 * to the user to store this information when required, for example in a class that contains
 * references to the static shape and the list of {@linkplain StaticProperty static properties}.
 * </ul>
 *
 * <p>
 * StaticShape cannot be subclassed by custom implementations and, when required, it allows
 * {@linkplain StaticProperty static properties} to check that the receiver object matches the
 * expected shape.
 *
 * @see StaticShape#newBuilder(TruffleLanguage)
 * @see StaticShape.Builder
 * @see StaticProperty
 * @see DefaultStaticProperty
 * @see DefaultStaticObjectFactory
 * @param <T> the {@linkplain Builder#build() default} or the
 *            {@linkplain StaticShape.Builder#build(Class, Class) user-defined} factory interface to
 *            allocate static objects
 * @since 21.3.0
 */
public abstract class StaticShape<T> {
    enum StorageStrategy {
        ARRAY_BASED,
        FIELD_BASED,
        POD_BASED
    }

    static final Unsafe UNSAFE = getUnsafe();
    final Class<?> storageClass;
    final boolean safetyChecks;
    @CompilationFinal //
    T factory;

    StaticShape(Class<?> storageClass, boolean safetyChecks) {
        this.storageClass = storageClass;
        this.safetyChecks = safetyChecks;
    }

    /**
     * Creates a new static shape builder.
     *
     * The builder instance is not thread-safe and must not be used from multiple threads at the
     * same time. Users of the Static Object Model are expected to define custom subtypes of
     * {@link StaticProperty} or use {@link DefaultStaticProperty}, a trivial default
     * implementation. In both cases, static properties must be registered to a static shape builder
     * using {@link StaticShape.Builder#property(StaticProperty, Class, boolean)}. Then, after
     * allocating a {@link StaticShape} instance with one of the {@link StaticShape.Builder#build()}
     * methods and allocating a static object using the factory class provided by
     * {@link StaticShape#getFactory()}, users can call the accessor methods defined in
     * {@link StaticProperty} to get and set property values stored in a static object instance.
     *
     * @param language an instance of the {@link TruffleLanguage} that uses the Static Object Model
     * @return a new static shape builder
     * @throws NullPointerException if language is null
     *
     * @see StaticShape
     * @see StaticProperty
     * @see DefaultStaticProperty
     * @see DefaultS