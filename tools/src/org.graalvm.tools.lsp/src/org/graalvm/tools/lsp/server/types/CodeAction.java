
/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.tools.lsp.server.types;

import com.oracle.truffle.tools.utils.json.JSONArray;
import com.oracle.truffle.tools.utils.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A code action represents a change that can be performed in code, e.g. to fix a problem or to
 * refactor code.
 *
 * A CodeAction must set either `edit` and/or a `command`. If both are supplied, the `edit` is
 * applied first, then the `command` is executed.
 */
public class CodeAction extends JSONBase {

    CodeAction(JSONObject jsonData) {
        super(jsonData);
    }

    /**
     * A short, human-readable, title for this code action.
     */
    public String getTitle() {
        return jsonData.getString("title");
    }

    public CodeAction setTitle(String title) {
        jsonData.put("title", title);
        return this;
    }

    /**
     * The kind of the code action.
     *
     * Used to filter code actions.
     */
    public CodeActionKind getKind() {
        return CodeActionKind.get(jsonData.optString("kind", null));
    }

    public CodeAction setKind(CodeActionKind kind) {
        jsonData.putOpt("kind", kind != null ? kind.getStringValue() : null);
        return this;
    }

    /**
     * The diagnostics that this code action resolves.
     */
    public List<Diagnostic> getDiagnostics() {
        final JSONArray json = jsonData.optJSONArray("diagnostics");
        if (json == null) {
            return null;
        }
        final List<Diagnostic> list = new ArrayList<>(json.length());
        for (int i = 0; i < json.length(); i++) {
            list.add(new Diagnostic(json.getJSONObject(i)));
        }
        return Collections.unmodifiableList(list);
    }

    public CodeAction setDiagnostics(List<Diagnostic> diagnostics) {
        if (diagnostics != null) {
            final JSONArray json = new JSONArray();
            for (Diagnostic diagnostic : diagnostics) {
                json.put(diagnostic.jsonData);
            }
            jsonData.put("diagnostics", json);
        }
        return this;
    }

    /**
     * Marks this as a preferred action. Preferred actions are used by the `auto fix` command and
     * can be targeted by keybindings.
     *
     * A quick fix should be marked preferred if it properly addresses the underlying error. A
     * refactoring should be marked preferred if it is the most reasonable choice of actions to
     * take.
     *
     * @since 3.15.0
     */
    @SuppressFBWarnings("NP_BOOLEAN_RETURN_NULL")
    public Boolean getIsPreferred() {
        return jsonData.has("isPreferred") ? jsonData.getBoolean("isPreferred") : null;
    }

    public CodeAction setIsPreferred(Boolean isPreferred) {
        jsonData.putOpt("isPreferred", isPreferred);
        return this;
    }

    /**
     * The workspace edit this code action performs.
     */
    public WorkspaceEdit getEdit() {
        return jsonData.has("edit") ? new WorkspaceEdit(jsonData.optJSONObject("edit")) : null;
    }

    public CodeAction setEdit(WorkspaceEdit edit) {
        jsonData.putOpt("edit", edit != null ? edit.jsonData : null);
        return this;
    }

    /**
     * A command this code action executes. If a code action provides a edit and a command, first
     * the edit is executed and then the command.
     */
    public Command getCommand() {
        return jsonData.has("command") ? new Command(jsonData.optJSONObject("command")) : null;
    }

    public CodeAction setCommand(Command command) {
        jsonData.putOpt("command", command != null ? command.jsonData : null);
        return this;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (this.getClass() != obj.getClass()) {
            return false;
        }
        CodeAction other = (CodeAction) obj;
        if (!Objects.equals(this.getTitle(), other.getTitle())) {
            return false;
        }
        if (this.getKind() != other.getKind()) {
            return false;
        }
        if (!Objects.equals(this.getDiagnostics(), other.getDiagnostics())) {
            return false;