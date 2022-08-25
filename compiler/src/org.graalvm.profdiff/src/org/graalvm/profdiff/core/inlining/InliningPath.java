
/*
 * Copyright (c) 2022, 2023, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.profdiff.core.inlining;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.graalvm.collections.Pair;
import org.graalvm.collections.UnmodifiableMapCursor;
import org.graalvm.profdiff.core.optimization.Optimization;

/**
 * A path in the {@link InliningTree}.
 *
 * Consider the following inlining tree:
 *
 * <pre>
 *          a()
 *       at bci -1
 *      ___/  \_____
 *     /            \
 *    b()           c()
 * at bci 0       at bci 1
 *               __/  \__
 *             /         \
 *           d()         e()
 *        at bci 0     at bci 2
 * </pre>
 *
 * As an example, the right-most path in the inlining tree is
 * {@code a() at bci -1, c() at bci 1, e() at bci 2}.
 */
public final class InliningPath {
    /**
     * A single element of an inlining path. It is composed by a method name and the bci of the
     * method's callsite.
     */
    public static final class PathElement {
        /**
         * The name of the method.
         */
        private final String methodName;

        /**
         * The bci of the method's callsite.
         */
        private final int callsiteBCI;

        public PathElement(String methodName, int callsiteBCI) {
            this.methodName = methodName;
            this.callsiteBCI = callsiteBCI;
        }

        /**
         * Returns {@code true} iff the path element matches another path element. A path element
         * matches another element iff the method names are equals and the byte code indexes match
         * ({@link Optimization#UNKNOWN_BCI} is treated as a wildcard). The relation is not
         * transitive due to the possibility of a wildcard.
         *
         * @param otherElement the other path element
         * @return {@code true} if the path elements match
         */
        public boolean matches(PathElement otherElement) {
            if (!Objects.equals(methodName, otherElement.methodName)) {
                return false;
            }
            return callsiteBCI == Optimization.UNKNOWN_BCI || otherElement.callsiteBCI == Optimization.UNKNOWN_BCI || callsiteBCI == otherElement.callsiteBCI;
        }

        /**
         * Gets the method name.
         */
        public String getMethodName() {
            return methodName;
        }
