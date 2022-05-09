/*
 * Copyright (c) 2020, 2020, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.jvmtiagentbase;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

/**
 * Minimal data extractor for the Java constant pool. See Java Virtual Machine Specification 4.4.
 */
public class ConstantPoolTool {
    private static final int INVALID_LENGTH = -1;

    @SuppressWarnings("unused")
    enum ConstantKind {
        // Keep this order: ordinals must match constant pool tag values.
        UNUSED_0(INVALID_LENGTH),
        UTF8(INVALID_LENGTH), // variable length
        UNUSED_2(INVALID_LENGTH),
        INTEGER(4),
        FLOAT(4),
        LONG(8, 2), // double-entry constant
        DOUBLE(8, 2), // double-entry constant
        CLASS(2),
        STRING(2),
        FIELDREF(4),
        METHODREF(4),
        INTERFACEMETHODREF(4),
        NAMEANDTYPE(4),
        UNUSED_13(INVALID_LENGTH),
        UNUSED_14(INVALID_LENGTH),
        METHODHANDLE(3),
        METHODTYPE(2),
        DYNAMIC(4),
        INVOKEDYNAMIC(4),
        MODULE(2),
     