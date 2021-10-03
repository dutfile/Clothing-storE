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
     * @see DefaultStaticObjectFactory
     * @since 21.3.0
     */
    public static Builder newBuilder(TruffleLanguage<?> language) {
        Objects.requireNonNull(language);
        return new Builder(language);
    }

    final void setFactory(T factory) {
        assert this.factory == null;
        this.factory = factory;
    }

    /**
     * Returns an instance of the {@linkplain Builder#build() default} or the
     * {@linkplain StaticShape.Builder#build(Class, Class) user-defined} factory interface that must
     * be used to allocate static objects with the current shape.
     *
     * @see StaticShape.Builder#build()
     * @see StaticShape.Builder#build(StaticShape)
     * @see StaticShape.Builder#build(Class, Class)
     * @since 21.3.0
     */
    public final T getFactory() {
        return factory;
    }

    final Class<?> getStorageClass() {
        return storageClass;
    }

    abstract Object getStorage(Object obj, boolean primitive);

    abstract Class<T> getFactoryInterface();

    final <U> U cast(Object obj, Class<U> type, boolean checkCondition) {
        if (safetyChecks) {
            return checkedCast(obj, type);
        } else {
            assert checkedCast(obj, type) != null;
            return SomAccessor.RUNTIME.unsafeCast(obj, type, !checkCondition || type.isInstance(obj), false, false);
        }
    }

    private static <U> U checkedCast(Object obj, Class<U> type) {
        try {
            return type.cast(obj);
        } catch (ClassCastException e) {
            throw new IllegalArgumentException("Object '" + obj + "' of class '" + obj.getClass().getName() + "' does not have the expected shape", e);
        }
    }

    private static Unsafe getUnsafe() {
        try {
            return Unsafe.getUnsafe();
        } catch (SecurityException e) {
        }
        try {
            Field theUnsafeInstance = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafeInstance.setAccessible(true);
            return (Unsafe) theUnsafeInstance.get(Unsafe.class);
        } catch (Exception e) {
            throw new RuntimeException("exception while trying to get Unsafe.theUnsafe via reflection:", e);
        }
    }

    /**
     * Builder class to construct {@link StaticShape} instances. The builder instance is not
     * thread-safe and must not be used from multiple threads at the same time.
     *
     * @see StaticShape#newBuilder(TruffleLanguage)
     * @since 21.3.0
     */
    public static final class Builder {
        private static final int MAX_NUMBER_OF_PROPERTIES = 65535;
        private static final int MAX_PROPERTY_ID_BYTE_LENGTH = 65535;
        private static final String DELIMITER = "$$";
        private static final AtomicInteger counter = new AtomicInteger();
        private final String storageClassName;
        private final HashMap<String, StaticProperty> staticProperties = new LinkedHashMap<>();
        private final TruffleLanguage<?> language;
        boolean hasLongPropertyId = false;
        boolean isActive = true;

        Builder(TruffleLanguage<?> language) {
            this.language = language;
            storageClassName = storageClassName();
        }

        static String storageClassName() {
            return ShapeGenerator.class.getPackage().getName().replace('.', '/') + "/GeneratedStaticObject" + DELIMITER + counter.incrementAndGet();
        }

        /**
         * Adds a {@link StaticProperty} to the static shape to be constructed. The
         * {@linkplain StaticProperty#getId() property id} cannot be null or an empty String. It is
         * not allowed to add two {@linkplain StaticProperty properties} with the same
         * {@linkplain StaticProperty#getId() id} to the same builder, or to add the same
         * {@linkplain StaticProperty property} to more than one builder. Static shapes that
         * {@linkplain StaticShape.Builder#build(StaticShape) extend a parent shape} can have
         * {@linkplain StaticProperty properties} with the same {@linkplain StaticProperty#getId()
         * id} of those in the parent shape.
         *
         * Only property accesses that match the specified type are allowed. Property values can be
         * optionally stored in a final field. Accesses to such values might be specially optimized
         * by the compiler. For example, reads might be constant-folded. It is up to the user to
         * enforce that property values stored as final are not assigned more than once.
         *
         * @see DefaultStaticProperty
         * @param property the {@link StaticProperty} to be added
         * @param type the type of the {@link StaticProperty} to be added.
         * @param storeAsFinal if this property value can be stored in a final field
         * @return the Builder instance
         * @throws IllegalArgumentException if more than 65535 properties are added, or if the
         *             {@linkplain StaticProperty#getId() property id} is an empty string or it is
         *             equal to the id of another static property already registered to this
         *             builder.
         * @throws IllegalStateException if this method is invoked after building a static shape
         * @throws NullPointerException if the {@linkplain StaticProperty#getId() property id} is
         *             null
         * @since 21.3.0
         */
        public Builder property(StaticProperty property, Class<?> type, boolean storeAsFinal) {
            CompilerAsserts.neverPartOfCompilation();
            StaticPropertyValidator.validate(type);
            checkStatus();
            property.init(type, storeAsFinal);
            staticProperties.put(validateAndGetId(property), property);
            return this;
        }

        /**
         * Builds a new {@linkplain StaticShape static shape} using the configuration of this
         * builder. The factory class returned by {@link StaticShape#getFactory()} implements
         * {@link DefaultStaticObjectFactory} and static objects extend {@link Object}.
         *
         * @see DefaultStaticObjectFactory
         * @see StaticShape.Builder#build(StaticShape)
         * @see StaticShape.Builder#build(Class, Class)
         * @return the new {@link StaticShape}
         * @throws IllegalStateException if a static property was added to more than one builder or
         *             multiple times to the same builder, if this method is invoked more than once,
         *             or if one of the static property types is not visible to the class loader
         *             that loaded the default factory interface.
         * @since 21.3.0
         */
        public StaticShape<DefaultStaticObjectFactory> build() {
            return 