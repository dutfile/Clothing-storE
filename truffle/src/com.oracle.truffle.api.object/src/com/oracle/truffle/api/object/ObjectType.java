
/*
 * Copyright (c) 2013, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.object;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.library.DynamicDispatchLibrary;

/**
 * An extensible object type descriptor for {@link DynamicObject}s.
 *
 * @since 0.8 or earlier
 * @deprecated no replacement; dynamic type no longer needs to inherit from {@link ObjectType}.
 * @see Shape.Builder#dynamicType(Object)
 * @see DynamicObjectLibrary#getDynamicType(DynamicObject)
 * @see DynamicObjectLibrary#setDynamicType(DynamicObject, Object)
 */
@Deprecated(since = "22.2")
public class ObjectType {
    static final ObjectType DEFAULT = new ObjectType();

    /**
     * Default constructor.
     *
     * @since 0.8 or earlier
     */
    @Deprecated(since = "22.2")
    public ObjectType() {
    }

    /**
     * Delegate method for {@link DynamicObject#equals(Object)}.
     *
     * @since 0.8 or earlier
     */
    @Deprecated(since = "22.2")
    public boolean equals(DynamicObject object, Object other) {
        return object == other;
    }

    /**
     * Delegate method for {@link DynamicObject#hashCode()}.
     *
     * @since 0.8 or earlier
     */
    @Deprecated(since = "22.2")
    public int hashCode(DynamicObject object) {
        return System.identityHashCode(object);
    }

    /**
     * Delegate method for {@link DynamicObject#toString()}.
     *
     * @since 0.8 or earlier
     */
    @Deprecated(since = "22.2")
    @TruffleBoundary
    public String toString(DynamicObject object) {
        return "DynamicObject<" + this.toString() + ">@" + Integer.toHexString(hashCode(object));
    }

    /**
     * Returns the exports class that this object type is dispatched to using
     * {@link DynamicDispatchLibrary dynamic dispatch}.
     *
     * @since 19.0
     */
    @Deprecated(since = "22.2")
    public Class<?> dispatch() {
        return null;
    }

}