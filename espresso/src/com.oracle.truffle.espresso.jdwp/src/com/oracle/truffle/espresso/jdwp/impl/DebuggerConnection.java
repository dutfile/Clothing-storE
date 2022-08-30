/*
 * Copyright (c) 2019, 2023, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.espresso.jdwp.impl;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;

import com.oracle.truffle.espresso.jdwp.api.ErrorCodes;
import com.oracle.truffle.espresso.jdwp.api.JDWPContext;

public final class DebuggerConnection implements Commands {

    private final DebuggerController controller;
    private final JDWPContext context;
    private final SocketConnection connection;
    private final BlockingQueue<DebuggerCommand> queue = new ArrayBlockingQueue<>(512);
    private Thread commandProcessor;
    private Thread jdwpTransport;

    public DebuggerConnection(SocketConnection connection, DebuggerController controller) {
        this.connection = connection;
        this.controller = controller;
        this.context = controller.getContext();
    }

    public void doProcessCommands(boolean suspend, Collection<Thread> activeThreads, Callable<Void> job) {
        // fire up two threads, one for the low-level connection to receive packets
        // and one for processing the debugger commands from a queue
        commandProcessor = new Thread(new CommandProcessorThread(), "jdwp-command-processor");
        commandProcessor.setDaemon(true);
