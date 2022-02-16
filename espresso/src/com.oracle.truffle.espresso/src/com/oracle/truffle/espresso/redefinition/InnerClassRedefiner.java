/*
 * Copyright (c) 2020, 2020, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.espresso.redefinition;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.oracle.truffle.espresso.classfile.ClassfileParser;
import com.oracle.truffle.espresso.descriptors.Symbol;
import com.oracle.truffle.espresso.impl.ClassRegistry;
import com.oracle.truffle.espresso.impl.ConstantPoolPatcher;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.jdwp.api.ErrorCodes;
import com.oracle.truffle.espresso.jdwp.api.RedefineInfo;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.StaticObject;

public final class InnerClassRedefiner {

    public static final Pattern ANON_INNER_CLASS_PATTERN = Pattern.compile(".*\\$\\d+.*");
    public static final int METHOD_FINGERPRINT_EQUALS = 8;
    public static final int ENCLOSING_METHOD_FINGERPRINT_EQUALS = 4;
    public static final int FIELD_FINGERPRINT_EQUALS = 2;
    public static final int NUMBER_INNER_CLASSES = 1;
    public static final int MAX_SCORE = METHOD_FINGERPRINT_EQUALS + ENCLOSING_METHOD_FINGERPRINT_EQUALS + FIELD_FINGERPRINT_EQUALS + NUMBER_INNER_CLASSES;

    public static final String HOT_CLASS_MARKER = "$hot";

    private final EspressoContext context;

    // map from classloader to a map of class names to inner class infos
    private final Map<StaticObject, Map<Symbol<Symbol.Name>, ImmutableClassInfo>> innerClassInfoMap = new WeakHashMap<>();

    // map from classloader to a map of Type to
    private final Map<StaticObject, Map<Symbol<Symbol