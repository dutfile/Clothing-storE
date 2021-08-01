
/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.nativebridge;

import org.graalvm.jniutils.HSObject;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a bridge class field as a foreign reference handle.
 *
 * When the bridged type is a class, the handle to a foreign object has to be stored in a
 * non-private field annotated by the {@link EndPointHandle}. Annotation processor uses this field
 * to obtain the foreign object reference. For HotSpot to native calls, the field type must be
 * assignable to {@link NativeObject}. For native to HotSpot calls, the field type must be
 * assignable to {@link HSObject}.
 *
 * Example:
 *
 * <pre>
 * &#64;GenerateHotSpotToNativeBridge(jniConfig = ExampleJNIConfig.class)
 * abstract class NativeCalculator extends Calculator {
 *
 *     &#64;EndPointHandle final NativeObject delegate;
 *
 *     NativeCalculator(NativeObject delegate) {
 *         this.delegate = delegate;
 *     }
 * }
 * </pre>
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface EndPointHandle {
}