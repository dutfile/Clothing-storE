/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.tools.chromeinspector.types;

import java.io.PrintWriter;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import com.oracle.truffle.api.debug.DebugException;
import com.oracle.truffle.api.debug.DebugValue;
import com.oracle.truffle.api.nodes.LanguageInfo;
import com.oracle.truffle.tools.chromeinspector.types.TypeInfo.SUBTYPE;
import com.oracle.truffle.tools.chromeinspector.types.TypeInfo.TYPE;
import com.oracle.truffle.tools.utils.json.JSONArray;
import com.oracle.truffle.tools.utils.json.JSONObject;

final class ObjectPreview {

    private static final int OVERFLOW_LIMIT_PROPERTIES = 5;
    private static final int OVERFLOW_LIMIT_ARRAY_ELEMENTS = 100;

    private ObjectPreview() {
    }

    static JSONObject create(DebugValue debugValue, TYPE type, SUBTYPE subtype, boolean allowToStringSideEffects, LanguageInfo language, PrintWriter err) {
        return create(debugValue, type, subtype, allowToStringSideEffects, language, err, false);
    }

    private static JSONObject create(DebugValue debugValue, boolean allowToStringSideEffects, LanguageInfo language, PrintWriter err, boolean isMapEntryKV) {
        TypeInfo typeInfo = TypeInfo.fromValue(debugValue, null, language, err);
        return create(debugValue, typeInfo.type, typeInfo.subtype, allowToStringSideEffects, language, err, isMapEntryKV);
    }

    private static JSONObject create(DebugValue debugValue, TYPE type, SUBTYPE subtype, boolean allowToStringSideEffects, LanguageInfo language, PrintWriter err, boolean isMapEntryKV) {
        JS