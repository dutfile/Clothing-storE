/*
 * Copyright (c) 2017, 2023, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.polyglot;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.zone.ZoneRules;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.graalvm.polyglot.HostAccess.TargetMappingPrecedence;
import org.graalvm.polyglot.impl.AbstractPolyglotImpl.AbstractValueDispatch;
import org.graalvm.polyglot.proxy.Proxy;

/**
 * Represents a polyglot value that can be accessed using a set of language agnostic operations.
 * Polyglot values represent values from {@link #isHostObject() host} or guest language. Polyglot
 * values are bound to a {@link Context context}. If the context is closed then all value operations
 * throw an {@link IllegalStateException}.
 * <p>
 * Polyglot values have one of the following type combinations:
 * <ul>
 * <li>{@link #isNull() Null}: This value represents a <code>null</code> like value. Certain
 * languages might use a different name or use multiple values to represent <code>null</code> like
 * values.
 * <li>{@link #isNumber() Number}: This value represents a floating or fixed point number. The
 * number value may be accessed as {@link #asByte() byte}, {@link #asShort() short}, {@link #asInt()
 * int}, {@link #asLong() long}, {@link #asBigInteger()} BigInteger}, {@link #asFloat() float}, or
 * {@link #asDouble() double} value.
 * <li>{@link #isBoolean() Boolean}. This value represents a boolean value. The boolean value can be
 * accessed using {@link #asBoolean()}.
 * <li>{@link #isString() String}: This value represents a string value. The string value can be
 * accessed using {@link #asString()}.
 * <li>{@link #isDate() Date}, {@link #isTime() Time} or {@link #isTimeZone() Timezone}: This value
 * represents a date, time or timezone. Multiple types may return <code>true</code> at the same
 * time.
 * <li>{@link #isDuration() Duration}: This value represents a duration value. The duration value
 * can be accessed using {@link #asDuration()}.
 * <li>{@link #isHostObject() Host Object}: This value represents a value of the host language
 * (Java). The original Java value can be accessed using {@link #asHostObject()}.
 * <li>{@link #isProxyObject() Proxy Object}: This value represents a {@link Proxy proxy} value.
 * <li>{@link #isNativePointer() Native Pointer}: This value represents a native pointer. The native
 * pointer value can be accessed using {@link #asNativePointer()}.
 * <li>{@link #isException() Exception}: This value represents an exception object. The exception
 * can be thrown using {@link #throwException()}.
 * <li>{@link #isMetaObject() Meta-Object}: This value represents a metaobject. Access metaobject
 * operations using {@link #getMetaSimpleName()}, {@link #getMetaQualifiedName()} and
 * {@link #isMetaInstance(Object)}.
 * <li>{@link #isIterator() Iterator}: This value represents an iterator. The iterator can be
 * iterated using {@link #hasIteratorNextElement()} and {@link #getIteratorNextElement()}.
 * </ul>
 * In addition any value may have one or more of the following traits:
 * <ul>
 * <li>{@link #hasArrayElements() Array Elements}: This value may contain array elements. The array
 * indices always start with <code>0</code>, also if the language uses a different style.
 * <li>{@link #hasMembers() Members}: This value may contain members. Members are structural
 * elements of an object. For example, the members of a Java object are all public methods and
 * fields. Members are accessible using {@link #getMember(String)}.
 * <li>{@link #canExecute() Executable}: This value can be {@link #execute(Object...) executed}.
 * This indicates that the value represents an element that can be executed. Guest language examples
 * for executable elements are functions, methods, closures or promises.
 * <li>{@link #canInstantiate() Instantiable}: This value can be {@link #newInstance(Object...)
 * instantiated}. For example, Java classes are instantiable.
 * <li>{@link #hasBufferElements() Buffer Elements}: This value may contain buffer elements. The
 * buffer indices always start with <code>0</code>, also if the language uses a different style.
 * <li>{@link #hasIterator() Iterable}: This value {@link #getIterator() provides} an
 * {@link #isIterator() iterator} which can be used to {@link #getIteratorNextElement() iterate}
 * value elements. For example, Guest language arrays are iterable.
 * <li>{@link #hasHashEntries()} Hash Entries}: This value represents a map.
 * <li>{@link #hasMetaParents()} Meta Parents}: This value represents Array Elements of Meta
 * Objects.
 * </ul>
 * <p>
 * In addition to the language agnostic types, the language specific type can be accessed using
 * {@link #getMetaObject()}. The identity of value objects is unspecified and should not be relied
 * upon. For example, multiple calls to {@link #getArrayElement(long)} with the same index might
 * return the same or different instances of {@link Value}. The {@link #equals(Object) equality} of
 * values is based on the identity of the value instance. All values return a human-readable
 * {@link #toString() string} for debugging, formatted by the original language.
 * <p>
 * Polyglot values may be converted to host objects using {@link #as(Class)}. In addition values may
 * be created from Java values using {@link Context#asValue(Object)}.
 *
 * <h3>Naive and aware dates and times</h3>
 * <p>
 * If a date or time value has a {@link #isTimeZone() timezone} then it is called <i>aware</i>,
 * otherwise <i>naive</i>.
 * <p>
 * An aware time and date has sufficient knowledge of applicable algorithmic and political time
 * adjustments, such as time zone and daylight saving time information, to locate itself relative to
 * other aware objects. An aware object is used to represent a specific moment in time that is not
 * open to interpretation.
 * <p>
 * A naive time and date does not contain enough information to unambiguously locate itself relative
 * to other date/time objects. Whether a naive object represents Coordinated Universal Time (UTC),
 * local time, or time in some other timezone is purely up to the program, just like it is up to the
 * program whether a particular number represents metres, miles, or mass. Naive objects are easy to
 * understand and to work with, at the cost of ignoring some aspects of reality.
 *
 * <h3>Scoped Values</h3>
 *
 * In the case of a guest-to-host callback, a value may be passed as a parameter. These values may
 * represent objects that are only valid during the invocation of the callback function, i.e. they
 * are scoped, with the scope being the callback function. If enabled via the corresponding settings
 * in {@link HostAccess}, such values are released when the function returns, with all future
 * invocations of value operations throwing an exception.
 *
 * If an embedder wishes to extend the scope of the value beyond the callback's return, the value
 * can be {@linkplain Value#pin() pinned}, such that it is not released automatically.
 *
 * @see Context
 * @see Engine
 * @see PolyglotException
 * @since 19.0
 */
public final class Value extends AbstractValue {

    Value(AbstractValueDispatch dispatch, Object context, Object receiver) {
        super(dispatch, context, receiver);
    }

    /**
     * Returns the metaobject that is associated with this value or <code>null</code> if no
     * metaobject is available. The metaobject represents a description of the object, reveals it's
     * kind and it's features. Some information that a metaobject might define includes the base
     * object's type, interface, class, methods, attributes, etc.
     * <p>
     * The returned value returns <code>true</code> for {@link #isMetaObject()} and provides
     * implementations for {@link #getMetaSimpleName()}, {@link #getMetaQualifiedName()}, and
     * {@link #isMetaInstance(Object)}.
     * <p>
     * This method does not cause any observable side-effects.
     *
     * @throws IllegalStateException if the context is already closed.
     * @throws PolyglotException if a guest language error occurred during execution.
     * @see #isMetaObject()
     * @since 19.0 revised in 20.1
     */
    public Value getMetaObject() {
        return dispatch.getMetaObject(this.context, receiver);
    }

    /**
     * Returns <code>true</code> if the value represents a metaobject. Metaobjects may be values
     * that naturally occur in a language or they may be returned by {@link #getMetaObject()}. A
     * metaobject represents a description of the object, reveals its kind and its features. Returns
     * <code>false</code> by default. Metaobjects are often also {@link #canInstantiate()
     * instantiable}, but not necessarily.
     * <p>
     * <b>Sample interpretations:</b> In Java an instance of the type {@link Class} is a metaobject.
     * In JavaScript any function instance is a metaobject. For example, the metaobject of a
     * JavaScript class is the associated constructor function.
     * <p>
     * This method does not cause any observable side-effects. If this method is implemented then
     * also {@link #getMetaQualifiedName()}, {@link #getMetaSimpleName()} and
     * {@link #isMetaInstance(Object)} must be implemented as well.
     *
     * @throws IllegalStateException if the context is already closed.
     * @throws PolyglotException if a guest language error occurred during execution.
     * @see #getMetaQualifiedName()
     * @see #getMetaSimpleName()
     * @see #isMetaInstance(Object)
     * @see #getMetaObject()
     * @since 20.1
     */
    public boolean isMetaObject() {
        return dispatch.isMetaObject(this.context, receiver);
    }

    /**
     * Returns the qualified name of a metaobject as {@link #isStri