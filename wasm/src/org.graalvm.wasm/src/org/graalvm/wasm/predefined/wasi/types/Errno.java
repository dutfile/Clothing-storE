
/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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

/*
 * This file has been automatically generated from wasi_snapshot_preview1.witx.
 */

package org.graalvm.wasm.predefined.wasi.types;

/**
 * Error codes returned by functions. Not all of these error codes are returned by the functions
 * provided by this API; some are used in higher-level library layers, and others are provided
 * merely for alignment with POSIX.
 */
public enum Errno {

    /** 0: No error occurred. System call completed successfully. */
    Success,

    /** 1: Argument list too long. */
    TooBig,

    /** 2: Permission denied. */
    Acces,

    /** 3: Address in use. */
    Addrinuse,

    /** 4: Address not available. */
    Addrnotavail,

    /** 5: Address family not supported. */
    Afnosupport,

    /** 6: Resource unavailable, or operation would block. */
    Again,

    /** 7: Connection already in progress. */
    Already,

    /** 8: Bad file descriptor. */
    Badf,

    /** 9: Bad message. */
    Badmsg,

    /** 10: Device or resource busy. */
    Busy,

    /** 11: Operation canceled. */
    Canceled,

    /** 12: No child processes. */
    Child,

    /** 13: Connection aborted. */
    Connaborted,

    /** 14: Connection refused. */
    Connrefused,

    /** 15: Connection reset. */
    Connreset,

    /** 16: Resource deadlock would occur. */
    Deadlk,

    /** 17: Destination address required. */
    Destaddrreq,

    /** 18: Mathematics argument out of domain of function. */
    Dom,

    /** 19: Reserved. */
    Dquot,

    /** 20: File exists. */
    Exist,

    /** 21: Bad address. */
    Fault,

    /** 22: File too large. */
    Fbig,

    /** 23: Host is unreachable. */
    Hostunreach,

    /** 24: Identifier removed. */
    Idrm,

    /** 25: Illegal byte sequence. */
    Ilseq,

    /** 26: Operation in progress. */
    Inprogress,

    /** 27: Interrupted function. */
    Intr,

    /** 28: Invalid argument. */
    Inval,

    /** 29: I/O error. */
    Io,

    /** 30: Socket is connected. */
    Isconn,

    /** 31: Is a directory. */
    Isdir,

    /** 32: Too many levels of symbolic links. */
    Loop,

    /** 33: File descriptor value too large. */
    Mfile,

    /** 34: Too many links. */
    Mlink,

    /** 35: Message too large. */
    Msgsize,

    /** 36: Reserved. */
    Multihop,

    /** 37: Filename too long. */
    Nametoolong,

    /** 38: Network is down. */
    Netdown,

    /** 39: Connection aborted by network. */
    Netreset,
