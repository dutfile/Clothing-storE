/*
 * Copyright (c) 2017, 2019, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.llvm.parser.metadata;

import java.math.BigInteger;
import java.util.ArrayDeque;

public final class DwarfOpcode {

    public static final long ADDR = 0x3;
    public static final long DEREF = 0x6;
    public static final long CONST1U = 0x8;
    public static final long CONST1S = 0x9;
    public static final long CONST2U = 0xa;
    public static final long CONST2S = 0xb;
    public static final long CONST4U = 0xc;
    public static final long CONST4S = 0xd;
    public static final long CONST8U = 0xe;
    public static final long CONST8S = 0xf;
    public static final long CONSTU = 0x10;
    public static final long CONSTS = 0x11;
    public static final long DUP = 0x12;
    public static final long DROP = 0x13;
    public static final long OVER = 0x14;
    public static final long PICK = 0x15;
    public static final long SWAP = 0x16;
    public static final long ROT = 0x17;
    public static final long XDEREF = 0x18;
    public static final long ABS = 0x19;
    public static final long AND = 0x1a;
    public static final long DIV = 0x1b;
    public static final long MINUS = 0x1c;
    public st