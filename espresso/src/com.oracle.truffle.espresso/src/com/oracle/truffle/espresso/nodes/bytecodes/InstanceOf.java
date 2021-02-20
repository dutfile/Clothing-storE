/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package com.oracle.truffle.espresso.nodes.bytecodes;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.HostCompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.NeverDefault;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.espresso.analysis.hierarchy.AssumptionGuardedValue;
import com.oracle.truffle.espresso.analysis.hierarchy.ClassHierarchyAssumption;
import com.oracle.truffle.espresso.impl.ArrayKlass;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.impl.PrimitiveKlass;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.nodes.EspressoNode;

/**
 * INSTANCEOF bytecode helper nodes.
 *
 * <p>
 * Provides specialized instanceof checks for: array classes, {@link Object}, final classes,
 * interfaces, primitives and regular classes. Also includes a cached (inline cache) implementation.
 *
 * <p>
 * If the type to check is known in advance e.g. INSTANCEOF and CHECKCAST bytecodes, use
 * {@link InstanceOf} via {@link InstanceOf#create(Klass, boolean)}, which creates a specialized
 * {@link InstanceOf} node for that particular type.
 *
 * If the type to check is not known in advance, or there can be multiple, use
 * {@link InstanceOf.Dynamic} which also takes the type to check as parameter.
 *
 * For un-cached nodes use the stateless {@link InstanceOf.Dynamic}.
 */
@NodeInfo(shortName = "INSTANCEOF constant class")
public abstract class InstanceOf extends EspressoNode {

    public abstract boolean execute(Klass maybeSubtype);

    /**
     * Dynamic instance