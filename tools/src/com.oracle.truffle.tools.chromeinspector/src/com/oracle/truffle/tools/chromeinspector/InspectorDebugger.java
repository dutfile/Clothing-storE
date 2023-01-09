/*
 * Copyright (c) 2017, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.tools.chromeinspector;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Phaser;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.graalvm.collections.Pair;

import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.DebugException;
import com.oracle.truffle.api.debug.DebugScope;
import com.oracle.truffle.api.debug.DebugStackFrame;
import com.oracle.truffle.api.debug.DebugStackTraceElement;
import com.oracle.truffle.api.debug.DebugValue;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.debug.SourceElement;
import com.oracle.truffle.api.debug.StepConfig;
import com.oracle.truffle.api.debug.SuspendAnchor;
import com.oracle.truffle.api.debug.SuspendedCallback;
import com.oracle.truffle.api.debug.SuspendedEvent;
import com.oracle.truffle.api.debug.SuspensionFilter;
import com.oracle.truffle.api.nodes.LanguageInfo;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.utilities.TriState;
import com.oracle.truffle.tools.chromeinspector.InspectorExecutionContext.CancellableRunnable;
import com.oracle.truffle.tools.chromeinspector.InspectorExecutionContext.NoSuspendedThreadException;
import com.oracle.truffle.tools.chromeinspector.InspectorExecutionContext.SuspendedThreadExecutor;
import com.oracle.truffle.tools.chromeinspector.ScriptsHandler.LoadScriptListener;
import com.oracle.truffle.tools.chromeinspector.commands.Params;
import com.oracle.truffle.tools.chromeinspector.commands.Result;
import com.oracle.truffle.tools.chromeinspector.domains.DebuggerDomain;
import com.oracle.truffle.tools.chromeinspector.events.Event;
import com.oracle.truffle.tools.chromeinspector.server.CommandProcessException;
import com.oracle.truffle.tools.chromeinspector.server.InspectServerSession.CommandPostProcessor;
import com.oracle.truffle.tools.chromeinspector.types.CallArgument;
import com.oracle.truffle.tools.chromeinspector.types.CallFrame;
import com.oracle.truffle.tools.chromeinspector.types.ExceptionDetails;
import com.oracle.truffle.tools.chromeinspector.types.Location;
import com.oracle.truffle.tools.chromeinspector.types.RemoteObject;
import com.oracle.truffle.tools.chromeinspector.types.Scope;
import com.oracle.truffle.tools.chromeinspector.types.Script;
import com.oracle.truffle.tools.chromeinspector.types.StackTrace;
import com.oracle.truffle.tools.chromeinspector.util.LineSearch;
import com.oracle.truffle.tools.utils.json.JSONArray;
import com.oracle.truffle.tools.utils.json.JSONObject;

public final class InspectorDebugger extends DebuggerDomain {

    private static final StepConfig STEP_CONFIG = StepConfig.newBuilder().suspendAnchors(SourceElement.ROOT, SuspendAnchor.AFTER).build();

    // Generic matcher of completion function
    // (function(x){var a=[];for(var o=x;o!==null&&typeof o !== 'undefined';o=o.__proto__){
    // a.push(Object.getOwnPropertyNames(o))
    // };return a})(obj)
    private static final Pattern FUNCTION_COMPLETION_PATTERN = Pattern.compile(
                    "\\(function\\s*\\((?<x>\\w+)\\)\\s*\\{\\s*var\\s+(?<a>\\w+)\\s*=\\s*\\[\\];\\s*" +
                                    "for\\s*\\(var\\s+(?<o>\\w+)\\s*=\\s*\\k<x>;\\s*\\k<o>\\s*\\!==\\s*null\\s*&&\\s*typeof\\s+\\k<o>\\s*\\!==\\s*.undefined.;\\k<o>\\s*=\\s*\\k<o>\\.__proto__\\)\\s*\\{" +
                                    "\\s*\\k<a>\\.push\\(Object\\.getOwnPropertyNames\\(\\k<o>\\)\\)" +
                                    "\\};\\s*return\\s+\\k<a>\\}\\)\\((?<object>.*)\\)$");

    private final InspectorExecutionContext context;
    private final Object suspendLock = new Object();
    private volatile SuspendedCallbackImpl suspendedCallback;
    private volatile DebuggerSession debuggerSession;
    private volatile ScriptsHandler scriptsHandler;
    private volatile BreakpointsHandler breakpointsHandler;
    // private Scope globalScope;
    private volatile DebuggerSuspendedInfo suspendedInfo; // Set when suspended
    private boolean running = true;
    private boolean runningUnwind = false;
    private boolean silentResume = false;
    private volatile CommandLazyResponse commandLazyResponse;
    private final AtomicBoolean delayUnlock = new AtomicBoolean();
    private final Phaser onSuspendPhaser = new Phaser();
    private final BlockingQueue<CancellableRunnable> suspendThreadExecutables = new LinkedBlockingQueue<>();
    private final ReadWriteLock domainLock;

    public InspectorDebugger(InspectorExecutionContext context, boolean suspend, ReadWriteLock domainLock) {
        this.context = context;
        this.domainLock = domainLock;
        context.setSuspendThreadExecutor(new SuspendedThreadExecutor() {
            @Override
            public void execute(CancellableRunnable executable) throws NoSuspendedThreadException {
                try {
                    synchronized (suspendLock) {
                        if (running) {
                            NoSuspendedThreadException.raise();
                        }
                        suspendThreadExecutables.put(executable);
                        suspendLock.notifyAll();
                    }
                } catch (InterruptedException iex) {
                    throw new RuntimeException(iex);
                }
            }
        });
        if (suspend) {
            Lock lock = domainLock.writeLock();
            lock.lock();
            try {
                startSession();
            } finally {
                lock.unlock();
            }
            debuggerSession.suspendNextExecution();
        }
    }

    private void startSession() {
        Debugger tdbg = context.getEnv().lookup(context.getEnv().getInstruments().get("debugger"), Debugger.class);
        suspendedCallback = new SuspendedCallbackImpl();
        debuggerSession = tdbg.startSession(suspendedCallback, SourceElement.ROOT, SourceElement.STATEMENT);
        debuggerSession.setSourcePath(context.getSourcePath());
        debuggerSession.setSteppingFilter(SuspensionFilter.newBuilder().ignoreLanguageContextInitialization(!context.isInspectInitialization()).includeInternal(context.isInspectInternal()).build());
        scriptsHandler = context.acquireScriptsHandler(debuggerSession);
        breakpointsHandler = new BreakpointsHandler(debuggerSession, scriptsHandler, () -> eventHandler);
    }

    @Override
    public void doEnable() {
        if (debuggerSession == null) {
            startSession();
        }
        scriptsHandler.addLoadScriptListener(new LoadScriptListenerImpl());
    }

    @Override
    public void doDisable() {
        assert debuggerSession != null;
        scriptsHandler.setDebuggerSession(null);
        debuggerSession.close();
        debuggerSession = null;
        suspendedCallback.dispose();
        suspendedCallback = null;
        context.releaseScriptsHandler();
        scriptsHandler = null;
        breakpointsHandler = null;
        synchronized (suspendLock) {
            if (!running) {
                running = true;
                suspendLock.notifyAll();
            }
        }
    }

    @Override
    protected void notifyDisabled() {
        // We might call startSession() in the constructor, without doEnable().
        // That means that doDisable() might not have been called.
        if (debuggerSession != null) {
            doDisable();
        }
    }

    @Override
    public void setAsyncCallStackDepth(int maxDepth) throws CommandProcessException {
        if (maxDepth >= 0) {
            debuggerSession.setAsynchronousStackDepth(maxDepth);
        } else {
            throw new CommandProcessException("Invalid async call stack depth: " + maxDepth);
        }
    }

    @Override
    public void setBlackboxPatterns(String[] patterns) {
        final Pattern[] compiledPatterns = new Pattern[patterns.length];
        for (int i = 0; i < patterns.length; i++) {
            compiledPatterns[i] = Pattern.compile(patterns[i]);
        }
        debuggerSession.setSteppingFilter(SuspensionFilter.newBuilder().ignoreLanguageContextInitialization(!context.isInspectInitialization()).includeInternal(context.isInspectInternal()).sourceIs(
                        source -> !sourceMatchesBlackboxPatterns(source, compiledPatterns)).build());
    }

    @Override
    public void setPauseOnExceptions(String state) throws CommandProcessException {
        switch (state) {
            case "none":
                breakpointsHandler.setExceptionBreakpoint(false, false);
                break;
            case "uncaught":
                breakpointsHandler.setExceptionBreakpoint(false, true);
                break;
            case "all":
                breakpointsHandler.setExceptionBreakpoint(true, true);
                break;
            default:
                throw new CommandProcessException("Unknown Pause on exceptions mode: " + state);
        }

    }

    @Override
    public Params getPossibleBreakpoints(Location start, Location end, boolean restrictToFunction) throws CommandProcessException {
        if (start == null) {
            throw new CommandProcessException("Start location required.");
        }
        int scriptId = start.getScriptId();
        if (end != null && scriptId != end.getScriptId()) {
            throw new CommandProcessException("Different location scripts: " + scriptId + ", " + end.getScriptId());
        }
        Script script = scriptsHandler.getScript(scriptId);
        if (script == null) {
            throw new CommandProcessException("Unknown scriptId: " + scriptId);
        }
        JSONObject json = new JSONObject();
        JSONArray arr = new JSONArray();
        Source source = script.getSourceLoaded();
        if (source.hasCharacters() && source.getLength() > 0) {
            int lc = source.getLineCount();
            int l1 = start.getLine();
            int c1 = start.getColumn();
            if (c1 <= 0) {
                c1 = 1;
            }
            if (l1 > lc) {
                l1 = lc;
                c1 = source.getLineLength(l1);
            }
            int l2;
            int c2;
            if (end != null) {
                l2 = end.getLine();
                c2 = end.getColumn();
                // The end should be exclusive, but not all clients adhere to that.
                if (l1 != l2 || c1 != c2) {
                    // Only when start != end consider end as exclusive:
                    if (l2 > lc) {
                        l2 = lc;
                        c2 = source.getLineLength(l2);
                    } else {
                        if (c2 <= 1) {
                            l2 = l2 - 1;
                            if (l2 <= 0) {
                                l2 = 1;
                            }
                            c2 = source.getLineLength(l2);
                        } else {
                            c2 = c2 - 1;
                        }
                    }
                    if (l1 > l2) {
                        l1 = l2;
                    }
                }
            } else {
                l2 = l1;
                c2 = source.getLineLength(l2);
            }
            if (c2 == 0) {
                c2 = 1; // 1-based column on zero-length line
            }
            if (l1 == l2 && c2 < c1) {
                c1 = c2;
            }
            SourceSection range = source.createSection(l1, c1, l2, c2);
            Iterable<SourceSection> locations = SuspendableLocationFinder.findSuspendableLocations(range, restrictToFunction, debuggerSession, context.getEnv());
            for (SourceSection ss : locations) {
                arr.put(new Location(scriptId, ss.getStartLine(), ss.getStartColumn()).toJSON());
            }
        }
        json.put("locations", arr);
        return new Params(json);
    }

    @Override
    public Params getScriptSource(String scriptId) throws CommandProcessException {
        if (scriptId == null) {
            throw new CommandProcessException("A scriptId required.");
        }
        CharSequence characters = getScript(scriptId).getCharacters();
        JSONObject json = new JSONObject();
        json.put("scriptSource", characters.toString());
        return new Params(json);
    }

    private 