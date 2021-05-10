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
     * Returns the qualified name of a metaobject as {@link #isString() String}.
     * <p>
     * <b>Sample interpretations:</b> The qualified name of a Java class includes the package name
     * and its class name. JavaScript does not have the notion of qualified name and therefore
     * returns the {@link #getMetaSimpleName() simple name} instead.
     *
     * @throws UnsupportedOperationException if and only if {@link #isMetaObject()} returns
     *             <code>false</code> for the same value.
     * @throws PolyglotException if a guest language error occurred during execution.
     * @since 20.1
     */
    public String getMetaQualifiedName() {
        return dispatch.getMetaQualifiedName(this.context, receiver);
    }

    /**
     * Returns the simple name of a metaobject as {@link #isString() string}.
     * <p>
     * <b>Sample interpretations:</b> The simple name of a Java class is the class name.
     *
     * @throws UnsupportedOperationException if and only if {@link #isMetaObject()} returns
     *             <code>false</code> for the same value.
     * @throws PolyglotException if a guest language error occurred during execution.
     * @since 20.1
     */
    public String getMetaSimpleName() {
        return dispatch.getMetaSimpleName(this.context, receiver);
    }

    /**
     * Returns <code>true</code> if the given instance is an instance of this value, else
     * <code>false</code>. The instance value is subject to polyglot value mapping rules as
     * described in {@link Context#asValue(Object)}.
     * <p>
     * <b>Sample interpretations:</b> A Java object is an instance of its returned
     * {@link Object#getClass() class}.
     * <p>
     *
     * @param instance the instance object to check.
     * @throws UnsupportedOperationException if and only if {@link #isMetaObject()} returns
     *             <code>false</code> for the same value.
     * @throws PolyglotException if a guest language error occurred during execution.
     * @since 20.1
     */
    public boolean isMetaInstance(Object instance) {
        return dispatch.isMetaInstance(this.context, receiver, instance);
    }

    /**
     * Returns <code>true</code> if the value represents a metaobject and the metaobject has meta
     * parents. Returns <code>false</code> by default.
     * <p>
     * <b>Sample interpretations:</b> In Java an instance of the type {@link Class} is a metaobject.
     * Further, the superclass and the implemented interfaces types of that type constitute the meta
     * parents. In JavaScript any function instance is a metaobject. For example, the metaobject of
     * a JavaScript class is the associated constructor function.
     * <p>
     * This method does not cause any observable side-effects. If this method is implemented then
     * also {@link #getMetaParents()} must be implemented as well.
     *
     * @throws IllegalStateException if the context is already closed.
     * @throws PolyglotException if a guest language error occurred during execution.
     * @see #getMetaParents()
     * @since 22.2
     */
    public boolean hasMetaParents() {
        return dispatch.hasMetaParents(this.context, receiver);
    }

    /**
     * Returns the meta parents of a meta object as an array object {@link #hasArrayElements()}.
     * This method does not cause any observable side-effects. If this method is implemented then
     * also {@link #hasMetaParents()} must be implemented as well.
     *
     * @throws IllegalStateException if the context is already closed.
     * @throws UnsupportedOperationException if the value does not have any
     *             {@link #hasMetaParents()} meta parents.
     * @throws PolyglotException if a guest language error occurred during execution.
     * @see #hasMetaParents()
     * @since 22.2
     */
    public Value getMetaParents() {
        return dispatch.getMetaParents(this.context, receiver);
    }

    /**
     * Returns <code>true</code> if this polyglot value has array elements. In this case array
     * elements can be accessed using {@link #getArrayElement(long)},
     * {@link #setArrayElement(long, Object)}, {@link #removeArrayElement(long)} and the array size
     * can be queried using {@link #getArraySize()}.
     *
     * @throws IllegalStateException if the context is already closed.
     * @throws PolyglotException if a guest language error occurred during execution.
     * @since 19.0
     */
    public boolean hasArrayElements() {
        return dispatch.hasArrayElements(this.context, receiver);
    }

    /**
     * Returns the array element of a given index. Polyglot arrays start with index <code>0</code>,
     * independent of the guest language. The given array index must be greater or equal to 0.
     *
     * @throws ArrayIndexOutOfBoundsException if the array index does not exist.
     * @throws UnsupportedOperationException if the value does not have any
     *             {@link #hasArrayElements() array elements} or if the index exists but is not
     *             readable.
     * @throws IllegalStateException if the context is already closed.
     * @throws PolyglotException if a guest language error occurred during execution.
     * @since 19.0
     */
    public Value getArrayElement(long index) {
        return dispatch.getArrayElement(this.context, receiver, index);
    }

    /**
     * Sets the value at a given index. Polyglot arrays start with index <code>0</code>, independent
     * of the guest language. The array element value is subject to polyglot value mapping rules as
     * described in {@link Context#asValue(Object)}.
     *
     * @throws ArrayIndexOutOfBoundsException if the array index does not exist.
     * @throws ClassCastException if the provided value type is not allowed to be written.
     * @throws UnsupportedOperationException if the value does not have any
     *             {@link #hasArrayElements() array elements} or if the index exists but is not
     *             modifiable.
     * @throws IllegalStateException if the context is already closed.
     * @throws PolyglotException if a guest language error occurred during execution.
     * @since 19.0
     */
    public void setArrayElement(long index, Object value) {
        dispatch.setArrayElement(this.context, receiver, index, value);
    }

    /**
     * Removes an array element at a given index. Returns <code>true</code> if the underlying array
     * element could be removed, otherwise <code>false</code>.
     *
     * @throws ArrayIndexOutOfBoundsException if the array index does not exist.
     * @throws UnsupportedOperationException if the value does not have any
     *             {@link #hasArrayElements() array elements} or if the index exists but is not
     *             removable.
     * @throws IllegalStateException if the context is already closed.
     * @throws PolyglotException if a guest language error occurred during execution.
     * @since 19.0
     */
    public boolean removeArrayElement(long index) {
        return dispatch.removeArrayElement(this.context, receiver, index);
    }

    /**
     * Returns the array size for values with array elements.
     *
     * @throws UnsupportedOperationException if the value does not have any
     *             {@link #hasArrayElements() array elements}.
     * @throws IllegalStateException if the context is already closed.
     * @throws PolyglotException if a guest language error occurred during execution.
     * @since 19.0
     */
    public long getArraySize() {
        return dispatch.getArraySize(this.context, receiver);
    }

    // region Buffer Methods

    /**
     * Returns {@code true} if the receiver may have buffer elements. In this case, the buffer size
     * can be queried using {@link #getBufferSize()} and elements can be read using
     * {@link #readBufferByte(long)}, {@link #readBufferShort(ByteOrder, long)},
     * {@link #readBufferInt(ByteOrder, long)}, {@link #readBufferLong(ByteOrder, long)},
     * {@link #readBufferFloat(ByteOrder, long)} and {@link #readBufferDouble(ByteOrder, long)}. If
     * {@link #isBufferWritable()} returns {@code true}, then buffer elements can also be written
     * using {@link #writeBufferByte(long, byte)},
     * {@link #writeBufferShort(ByteOrder, long, short)},
     * {@link #writeBufferInt(ByteOrder, long, int)},
     * {@link #writeBufferLong(ByteOrder, long, long)},
     * {@link #writeBufferFloat(ByteOrder, long, float)} and
     * {@link #writeBufferDouble(ByteOrder, long, double)}.
     * <p>
     * Invoking this method does not cause any observable side-effects.
     *
     * @throws IllegalStateException if the context is already closed.
     * @throws PolyglotException if a guest language error occurred during execution.
     * @see #hasBufferElements()
     * @since 21.1
     */
    public boolean hasBufferElements() {
        return dispatch.hasBufferElements(this.context, receiver);
    }

    /**
     * Returns true if the receiver object is a modifiable buffer. In this case, elements can be
     * written using {@link #writeBufferByte(long, byte)},
     * {@link #writeBufferShort(ByteOrder, long, short)},
     * {@link #writeBufferInt(ByteOrder, long, int)},
     * {@link #writeBufferLong(ByteOrder, long, long)},
     * {@link #writeBufferFloat(ByteOrder, long, float)} and
     * {@link #writeBufferDouble(ByteOrder, long, double)}.
     * <p>
     * Invoking this method does not cause any observable side-effects.
     *
     * @throws UnsupportedOperationException if the value does not have {@link #hasBufferElements
     *             buffer elements}.
     * @since 21.1
     */
    public boolean isBufferWritable() throws UnsupportedOperationException {
        return dispatch.isBufferWritable(this.context, receiver);
    }

    /**
     * Returns the buffer size in bytes for values with buffer elements.
     * <p>
     * Invoking this method does not cause any observable side-effects.
     *
     * @throws UnsupportedOperationException if the value does not have {@link #hasBufferElements
     *             buffer elements}.
     * @since 21.1
     */
    public long getBufferSize() throws UnsupportedOperationException {
        return dispatch.getBufferSize(this.context, receiver);
    }

    /**
     * Reads the byte at the given byte offset from the start of the buffer.
     * <p>
     * The access is <em>not</em> guaranteed to be atomic. Therefore, this method is <em>not</em>
     * thread-safe.
     * <p>
     * Invoking this method does not cause any observable side-effects.
     *
     * @param byteOffset the offset, in bytes, from the start of the buffer at which the byte will
     *            be read.
     * @return the byte at the given byte offset from the start of the buffer.
     * @throws IndexOutOfBoundsException if and only if
     *             <code>byteOffset < 0 || byteOffset >= </code>{@link #getBufferSize()}.
     * @throws UnsupportedOperationException if the value does not have {@link #hasBufferElements
     *             buffer elements}.
     * @throws IllegalStateException if the context is already closed.
     * @throws PolyglotException if a guest language error occurred during execution.
     * @since 21.1
     */
    public byte readBufferByte(long byteOffset) throws UnsupportedOperationException, IndexOutOfBoundsException {
        return dispatch.readBufferByte(this.context, receiver, byteOffset);
    }

    /**
     * Writes the given byte at the given byte offset from the start of the buffer.
     * <p>
     * The access is <em>not</em> guaranteed to be atomic. Therefore, this method is <em>not</em>
     * thread-safe.
     *
     * @param byteOffset the offset, in bytes, from the start of the buffer at which the byte will
     *            be written.
     * @param value the byte value to be written.
     * @throws IndexOutOfBoundsException if and only if
     *             <code>byteOffset < 0 || byteOffset >= </code>{@link #getBufferSize()}.
     * @throws UnsupportedOperationException if the value does not have {@link #hasBufferElements
     *             buffer elements} or is not {@link #isBufferWritable() modifiable}.
     * @throws IllegalStateException if the context is already closed.
     * @throws PolyglotException if a guest language error occurred during execution.
     * @since 21.1
     */
    public void writeBufferByte(long byteOffset, byte value) throws UnsupportedOperationException, IndexOutOfBoundsException {
        dispatch.writeBufferByte(this.context, receiver, byteOffset, value);
    }

    /**
     * Reads the short at the given byte offset from the start of the buffer in the given byte
     * order.
     * <p>
     * Unaligned accesses are supported.
     * <p>
     * The access is <em>not</em> guaranteed to be atomic. Therefore, this method is <em>not</em>
     * thread-safe.
     * <p>
     * Invoking this method does not cause any observable side-effects.
     *
     * @param order the order in which to read the individual bytes of the short.
     * @param byteOffset the offset, in bytes, from the start of the buffer from which the short
     *            will be read.
     * @return the short at the given byte offset from the start of the buffer.
     * @throws IndexOutOfBoundsException if and only if
     *             <code>byteOffset < 0 || byteOffset >= {@link #getBufferSize()} - 1</code>.
     * @throws UnsupportedOperationException if the value does not have {@link #hasBufferElements
     *             buffer elements}.
     * @throws IllegalStateException if the context is already closed.
     * @throws PolyglotException if a guest language error occurred during execution.
     * @since 21.1
     */
    public short readBufferShort(ByteOrder order, long byteOffset) throws UnsupportedOperationException, IndexOutOfBoundsException {
        return dispatch.readBufferShort(this.context, receiver, order, byteOffset);
    }

    /**
     * Writes the given short in the given byte order at the given byte offset from the start of the
     * buffer.
     * <p>
     * Unaligned accesses are supported.
     * <p>
     * The access is <em>not</em> guaranteed to be atomic. Therefore, this method is <em>not</em>
     * thread-safe.
     *
     * @param order the order in which to write the individual bytes of the short.
     * @param byteOffset the offset, in bytes, from the start of the buffer from which the short
     *            will be written.
     * @param value the short value to be written.
     * @throws IndexOutOfBoundsException if and only if
     *             <code>byteOffset < 0 || byteOffset >= {@link #getBufferSize()} - 1</code>.
     * @throws UnsupportedOperationException if the value does not have {@link #hasBufferElements
     *             buffer elements} or is not {@link #isBufferWritable() modifiable}.
     * @throws IllegalStateException if the context is already closed.
     * @throws PolyglotException if a guest language error occurred during execution.
     * @since 21.1
     */
    public void writeBufferShort(ByteOrder order, long byteOffset, short value) throws UnsupportedOperationException, IndexOutOfBoundsException {
        dispatch.writeBufferShort(this.context, receiver, order, byteOffset, value);
    }

    /**
     * Reads the int at the given byte offset from the start of the buffer in the given byte order.
     * <p>
     * Unaligned accesses are supported.
     * <p>
     * The access is <em>not</em> guaranteed to be atomic. Therefore, this method is <em>not</em>
     * thread-safe.
     * <p>
     * Invoking this method does not cause any observable side-effects.
     *
     * @param order the order in which to read the individual bytes of the int.
     * @param byteOffset the offset, in bytes, from the start of the buffer from which the int will
     *            be read.
     * @return the int at the given byte offset from the start of the buffer.
     * @throws IndexOutOfBoundsException if and only if
     *             <code>byteOffset < 0 || byteOffset >= {@link #getBufferSize()} - 3</code>.
     * @throws UnsupportedOperationException if the value does not have {@link #hasBufferElements
     *             buffer elements}.
     * @throws IllegalStateException if the context is already closed.
     * @throws PolyglotException if a guest language error occurred during execution.
     * @since 21.1
     */
    public int readBufferInt(ByteOrder order, long byteOffset) throws UnsupportedOperationException, IndexOutOfBoundsException {
        return dispatch.readBufferInt(this.context, receiver, order, byteOffset);
    }

    /**
     * Writes the given int in the given byte order at the given byte offset from the start of the
     * buffer.
     * <p>
     * Unaligned accesses are supported.
     * <p>
     * The access is <em>not</em> guaranteed to be atomic. Therefore, this method is <em>not</em>
     * thread-safe.
     *
     * @param order the order in which to write the individual bytes of the int.
     * @param byteOffset the offset, in bytes, from the start of the buffer from which the int will
     *            be written.
     * @param value the int value to be written.
     * @throws IndexOutOfBoundsException if and only if
     *             <code>byteOffset < 0 || byteOffset >= {@link #getBufferSize()} - 3</code>.
     * @throws UnsupportedOperationException if the value does not have {@link #hasBufferElements
     *             buffer elements} or is not {@link #isBufferWritable() modifiable}.
     * @throws IllegalStateException if the context is already closed.
     * @throws PolyglotException if a guest language error occurred during execution.
     * @since 21.1
     */
    public void writeBufferInt(ByteOrder order, long byteOffset, int value) throws UnsupportedOperationException, IndexOutOfBoundsException {
        dispatch.writeBufferInt(this.context, receiver, order, byteOffset, value);
    }

    /**
     * Reads the long at the given byte offset from the start of the buffer in the given byte order.
     * <p>
     * Unaligned accesses are supported.
     * <p>
     * The access is <em>not</em> guaranteed to be atomic. Therefore, this method is <em>not</em>
     * thread-safe.
     * <p>
     * Invoking this method does not cause any observable side-effects.
     *
     * @param order the order in which to read the individual bytes of the long.
     * @param byteOffset the offset, in bytes, from the start of the buffer from which the int will
     *            be read.
     * @return the int at the given byte offset from the start of the buffer.
     * @throws IndexOutOfBoundsException if and only if
     *             <code>byteOffset < 0 || byteOffset >= {@link #getBufferSize()} - 7</code>.
     * @throws UnsupportedOperationException if the value does not have {@link #hasBufferElements
     *             buffer elements}.
     * @throws IllegalStateException if the context is already closed.
     * @throws PolyglotException if a guest language error occurred during execution.
     * @since 21.1
     */
    public long readBufferLong(ByteOrder order, long byteOffset) throws UnsupportedOperationException, IndexOutOfBoundsException {
        return dispatch.readBufferLong(this.context, receiver, order, byteOffset);
    }

    /**
     * Writes the given long in the given byte order at the given byte offset from the start of the
     * buffer.
     * <p>
     * Unaligned accesses are supported.
     * <p>
     * The access is <em>not</em> guaranteed to be atomic. Therefore, this method is <em>not</em>
     * thread-safe.
     *
     * @param order the order in which to write the individual bytes of the long.
     * @param byteOffset the offset, in bytes, from the start of the buffer from which the int will
     *            be written.
     * @param value the int value to be written.
     * @throws IndexOutOfBoundsException if and only if
     *             <code>byteOffset < 0 || byteOffset >= {@link #getBufferSize()} - 7</code>.
     * @throws UnsupportedOperationException if the value does not have {@link #hasBufferElements
     *             buffer elements} or is not {@link #isBufferWritable() modifiable}.
     * @throws IllegalStateException if the context is already closed.
     * @throws PolyglotException if a guest language error occurred during execution.
     * @since 21.1
     */
    public void writeBufferLong(ByteOrder order, long byteOffset, long value) throws UnsupportedOperationException, IndexOutOfBoundsException {
        dispatch.writeBufferLong(this.context, receiver, order, byteOffset, value);
    }

    /**
     * Reads the float at the given byte offset from the start of the buffer in the given byte
     * order.
     * <p>
     * Unaligned accesses are supported.
     * <p>
     * The access is <em>not</em> guaranteed to be atomic. Therefore, this method is <em>not</em>
     * thread-safe.
     * <p>
     * Invoking this method does not cause any observable side-effects.
     *
     * @param order the order in which to read the individual bytes of the float.
     * @param byteOffset the offset, in bytes, from the start of the buffer from which the float
     *            will be read.
     * @return the float at the given byte offset from the start of the buffer.
     * @throws IndexOutOfBoundsException if and only if
     *             <code>byteOffset < 0 || byteOffset >= {@link #getBufferSize()} - 3</code>.
     * @throws UnsupportedOperationException if the value does not have {@link #hasBufferElements
     *             buffer elements}.
     * @throws IllegalStateException if the context is already closed.
     * @throws PolyglotException if a guest language error occurred during execution.
     * @since 21.1
     */
    public float readBufferFloat(ByteOrder order, long byteOffset) throws UnsupportedOperationException, IndexOutOfBoundsException {
        return dispatch.readBufferFloat(this.context, receiver, order, byteOffset);
    }

    /**
     * Writes the given float in the given byte order at the given byte offset from the start of the
     * buffer.
     * <p>
     * Unaligned accesses are supported.
     * <p>
     * The access is <em>not</em> guaranteed to be atomic. Therefore, this method is <em>not</em>
     * thread-safe.
     *
     * @param order the order in which to read the individual bytes of the float.
     * @param byteOffset the offset, in bytes, from the start of the buffer from which the float
     *            will be written.
     * @param value the float value to be written.
     * @throws IndexOutOfBoundsException if and only if
     *             <code>byteOffset < 0 || byteOffset >= {@link #getBufferSize()} - 3</code>.
     * @throws UnsupportedOperationException if the value does not have {@link #hasBufferElements
     *             buffer elements} or is not {@link #isBufferWritable() modifiable}.
     * @throws IllegalStateException if the context is already closed.
     * @throws PolyglotException if a guest language error occurred during execution.
     * @since 21.1
     */
    public void writeBufferFloat(ByteOrder order, long byteOffset, float value) throws UnsupportedOperationException, IndexOutOfBoundsException {
        dispatch.writeBufferFloat(this.context, receiver, order, byteOffset, value);
    }

    /**
     * Reads the double at the given byte offset from the start of the buffer in the given byte
     * order.
     * <p>
     * Unaligned accesses are supported.
     * <p>
     * The access is <em>not</em> guaranteed to be atomic. Therefore, this method is <em>not</em>
     * thread-safe.
     * <p>
     * Invoking this method does not cause any observable side-effects.
     *
     * @param order the order in which to write the individual bytes of the double.
     * @param byteOffset the offset, in bytes, from the start of the buffer from which the double
     *            will be read.
     * @return the double at the given byte offset from the start of the buffer.
     * @throws IndexOutOfBoundsException if and only if
     *             <code>byteOffset < 0 || byteOffset >= {@link #getBufferSize()} - 7</code>.
     * @throws UnsupportedOperationException if the value does not have {@link #hasBufferElements
     *             buffer elements}.
     * @throws IllegalStateException if the context is already closed.
     * @throws PolyglotException if a guest language error occurred during execution.
     * @since 21.1
     */
    public double readBufferDouble(ByteOrder order, long byteOffset) throws UnsupportedOperationException, IndexOutOfBoundsException {
        return dispatch.readBufferDouble(this.context, receiver, order, byteOffset);
    }

    /**
     * Writes the given double in the given byte order at the given byte offset from the start of
     * the buffer.
     * <p>
     * Unaligned accesses are supported.
     * <p>
     * The access is <em>not</em> guaranteed to be atomic. Therefore, this method is <em>not</em>
     * thread-safe.
     *
     * @param order the order in which to write the individual bytes of the double.
     * @param byteOffset the offset, in bytes, from the start of the buffer from which the double
     *            will be written.
     * @param value the double value to be written.
     * @throws IndexOutOfBoundsException if and only if
     *             <code>byteOffset < 0 || byteOffset >= {@link #getBufferSize()} - 7</code>.
     * @throws UnsupportedOperationException if the value does not have {@link #hasBufferElements
     *             buffer elements} or is not {@link #isBufferWritable() modifiable}.
     * @throws IllegalStateException if the context is already closed.
     * @throws PolyglotException if a guest language error occurred during execution.
     * @since 21.1
     */
    public void writeBufferDouble(ByteOrder order, long byteOffset, double value) throws UnsupportedOperationException, IndexOutOfBoundsException {
        dispatch.writeBufferDouble(this.context, receiver, order, byteOffset, value);
    }

    // endregion

    /**
     * Returns <code>true</code> if this value generally supports containing members. To check
     * whether a value has <i>no</i> members use
     * <code>{@link #getMemberKeys() getMemberKeys()}.{@link Set#isEmpty() isEmpty()}</code>
     * instead. If polyglot value has members, it may also support {@link #getMember(String)},
     * {@link #putMember(String, Object)} and {@link #removeMember(String)}.
     *
     * @see #hasMember(String) To check the existence of members.
     * @see #getMember(String) To read members.
     * @see #putMember(String, Object) To write members.
     * @see #removeMember(String) To remove a member.
     * @see #getMemberKeys() For a list of members.
     * @throws IllegalStateException if the context is already closed.
     * @throws PolyglotExcepti