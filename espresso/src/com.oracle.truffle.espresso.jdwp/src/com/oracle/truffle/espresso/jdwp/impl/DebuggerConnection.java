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
        commandProcessor.start();
        activeThreads.add(commandProcessor);

        jdwpTransport = new Thread(new JDWPTransportThread(), "jdwp-transport");
        jdwpTransport.setDaemon(true);
        jdwpTransport.start();
        activeThreads.add(jdwpTransport);

        if (suspend) {
            // check if this is called from a guest thread
            Object guestThread = context.asGuestThread(Thread.currentThread());
            if (guestThread == null) {
                // a reconnect, meaning no suspend
                return;
            }
            // only a JDWP resume/resumeAll command can resume this thread
            controller.suspend(null, context.asGuestThread(Thread.currentThread()), SuspendStrategy.EVENT_THREAD, Collections.singletonList(job), null, false);
        }
    }

    public void close() {
        try {
            connection.close(controller);
            controller.getEventListener().setConnection(null);
        } catch (IOException e) {
            throw new RuntimeException("Closing socket connection failed", e);
        }
    }

    @Override
    public void stepInto(Object thread, RequestFilter filter) {
        DebuggerCommand debuggerCommand = new DebuggerCommand(DebuggerCommand.Kind.STEP_INTO, filter);
        controller.setCommandRequestId(thread, filter.getRequestId(), filter.getSuspendPolicy(), false, false, DebuggerCommand.Kind.STEP_INTO);
        addBlocking(debuggerCommand);
    }

    @Override
    public void stepOver(Object thread, RequestFilter filter) {
        DebuggerCommand debuggerCommand = new DebuggerCommand(DebuggerCommand.Kind.STEP_OVER, filter);
        controller.setCommandRequestId(thread, filter.getRequestId(), filter.getSuspendPolicy(), false, false, DebuggerCommand.Kind.STEP_OVER);
        addBlocking(debuggerCommand);
    }

    @Override
    public void stepOut(Object thread, RequestFilter filter) {
        DebuggerCommand debuggerCommand = new DebuggerCommand(DebuggerCommand.Kind.STEP_OUT, filter);
        controller.setCommandRequestId(thread, filter.getRequestId(), filter.getSuspendPolicy(), false, false, DebuggerCommand.Kind.STEP_OUT);
        addBlocking(debuggerCommand);
    }

    // the suspended event instance is only valid while suspended, so
    // to avoid a race, we have to block until we're sure that the debugger
    // command was prepared on the suspended event instance
    private void addBlocking(DebuggerCommand command) {
        queue.add(command);
        synchronized (command) {
            while (!command.isSubmitted()) {
                try {
                    command.wait();
                } catch (InterruptedException e) {
                    controller.warning(() -> "could not submit debugger command due to " + e.getMessage());
                }
            }
        }
    }

    @Override
    public Callable<Void> createLineBreakpointCommand(BreakpointInfo info) {
        return new Callable<>() {
            @Override
            public Void call() {
                LineBreakpointInfo lineInfo = (LineBreakpointInfo) info;
                DebuggerCommand debuggerCommand = new DebuggerCommand(DebuggerCommand.Kind.SUBMIT_LINE_BREAKPOINT, info.getFilter());
                debuggerCommand.setSourceLocation(new SourceLocation(lineInfo.getSlashName(), (int) lineInfo.getLine(), context));
                debuggerCommand.setBreakpointInfo(info);
                addBlocking(debuggerCommand);
                return null;
            }
        };
    }

    @Override
    public Callable<Void> createExceptionBreakpoint(BreakpointInfo info) {
        return new Callable<>() {
            @Override
            public Void call() {
                DebuggerCommand debuggerCommand = new DebuggerCommand(DebuggerCommand.Kind.SUBMIT_EXCEPTION_BREAKPOINT, null);
                debuggerCommand.setBreakpointInfo(info);
                addBlocking(debuggerCommand);
                return null;
            }
        };
    }

    boolean isDebuggerThread(Thread thread) {
        return thread == jdwpTransport || thread == commandProcessor;
    }

    private class CommandProcessorThread implements Runnable {

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                DebuggerCommand debuggerCommand = awaitNextCommand(); // blocking

                if (debuggerCommand != null) {
                    switch (debuggerCommand.kind) {
                        case SUBMIT_LINE_BREAKPOINT:
                            controller.submitLineBreakpoint(debuggerCommand);
                            break;
                        case SUBMIT_EXCEPTION_BREAKPOINT:
                            controller.submitExceptionBreakpoint(debuggerCommand);
                            break;
                        case STEP_OUT:
                            controller.stepOut(debuggerCommand.getRequestFilter());
                            break;
                        default:
                            break;
                    }
                    synchronized (debuggerCommand) {
                        debuggerCommand.markSubmitted();
                        debuggerCommand.notifyAll();
                    }
                }
            }
        }

        private DebuggerCommand awaitNextCommand() {
            DebuggerCommand debuggerCommand = null;
            try {
                debuggerCommand = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return debuggerCommand;
        }
    }

    private class JDWPTransportThread implements Runnable {
        private RequestedJDWPEvents requestedJDWPEvents = new RequestedJDWPEvents(controller);

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    processPacket(Packet.fromByteArray(connection.readPacket()));
                } catch (IOException e) {
                    if (!Thread.currentThread().isInterrupted()) {
                        controller.warning(() -> "Failed to process jdwp packet with message: " + e.getMessage());
                    }
                } catch (ConnectionClosedException e) {
                    // we closed the session, so let the thread run dry
                }
            }
        }

        private void processPacket(Packet packet) {
            CommandResult result = null;
            try {
                if (packet.flags == Packet.Reply) {
                    //