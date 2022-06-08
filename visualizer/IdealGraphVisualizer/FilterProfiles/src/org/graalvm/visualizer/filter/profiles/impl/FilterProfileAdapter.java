/*
 * Copyright (c) 2013, 2022, Oracle and/or its affiliates. All rights reserved.
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

package org.graalvm.visualizer.filter.profiles.impl;

import org.graalvm.visualizer.data.ChangedListener;
import org.graalvm.visualizer.filter.CustomFilter;
import org.graalvm.visualizer.filter.Filter;
import org.graalvm.visualizer.filter.FilterChain;
import org.openide.filesystems.FileObject;
import org.openide.util.NbBundle;
import org.openide.util.WeakListeners;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataFolder;
import org.openide.loaders.DataObject;
import org.openide.loaders.DataShadow;
import org.openide.util.RequestProcessor;
import java.beans.PropertyChangeListener;
import org.graalvm.visualizer.filter.profiles.FilterProfile;
import org.graalvm.visualizer.filter.FilterSequence;
import org.graalvm.visualizer.filter.Filters;
import org.graalvm.visualizer.filter.profiles.mgmt.ProfileService;
import org.graalvm.visualizer.filter.profiles.mgmt.ProfileStorage;
import org.openide.filesystems.FileAttributeEvent;
import org.openide.filesystems.FileChangeAdapter;
import org.openide.filesystems.FileChangeListener;
import org.openide.filesystems.FileRenameEvent;
import org.openide.util.Exceptions;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeSupport;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import static org.graalvm.visualizer.filter.profiles.FilterProfile.PROP_NAME;
import org.openide.filesystems.FileEvent;

/**
 * Implements filter operations on a folder - profile. Loads all files in the
 * folder as Filters, but only includes the enabled ones in a
 * {@link #getSelectedFilters()}.
 * <p/>
 * The implementation fires property change events if the name, the order, set of filters
 * change, so code does not need to rely on nonstandard ChangedEvent.
 * 
 * @author sdedic
 */
public class FilterProfileAdapter implements FilterProfile {
    static RequestProcessor REFRESH_RP = new RequestProcessor(FilterProfileAdapter.class);

    public static final String ENABLED_ID = "enabled";
    public static final String DISPLAY_NAME = "name";

    /**
     * The storage folder for the filters.
     */
    private final FileObject profileFolder;

    /**
     * Represents filters to be executed on graphs.
     */
    private final ProfileFilterChain sequence = new ProfileFilterChain();

    /**
     * Represents contents of the profile.
     */
    private final PropertyChangeSupport propSupport = new PropertyChangeSupport(this);
    private final ProfileFilterChain profileFilters = new ProfileFilterChain();
    private final ProfileService root;
    private final ProfileStorage storage;
    private boolean initialized;
    private final L pcl = new L();
    private final FileChangeListener weakFL;
    private final DataFolder dFolder;
    private final ChangedL chainsListener = new ChangedL();
    private final Map<FileObject, FileChangeListener> weakRL = new WeakHashMap<>();

    public FilterProfileAdapter(FileObject filterFolder, ProfileService root, ProfileStorage storage) {
        this.root = root;
        this.storage = storage;
        this.profileFolder = filterFolder;

        // Will listen on DataFolder, as it creates listeners for us, otherwise
        // would need to do bookkeeping of file-listener, which is done in datasystems already
        dFolder = DataFolder.findFolder(filterFolder);
        assert dFolder != null;
        // force data folder to listen...
        weakFL = WeakListeners.create(FileChangeListener.class, pcl, profileFolder);
        profileFilters.getChangedEvent().addListener(chainsListener);
        sequence.getChangedEvent().addListener(chainsListener);
        
        profileFolder.addFileChangeListener(weakFL);
    }
    
    private class L extends FileChangeAdapter implements PropertyChangeListener {
        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            postRefresh();
        }

        @Override
        public void fileAttributeChanged(FileAttributeEvent fe) {
            super.fileAttributeChanged(fe);
            String an = fe.getName();
            if (an == null || "displayName".equals(an)) { // NOI18N
                if (fe.getFile().isFolder()) {
                    // change of the profile's own name, refire as a display name change
                    propSupport.firePropertyChange(PROP_NAME, null, getName());
                    return;
                } else {
                    // change of a contained filter's def
                    Object nv = fe.getNewValue();
                    if (nv == null) {
                        nv = fe.getFile().getAttribute("displayName"); // NOI18N
                    }
                    if (nv != null) {
                        handleFileRenamed(fe.getFile(), nv.toString());
                    }
                }
            }
            if (an == null || "position".equals(an)) { // NOI18N
                if (fe.getFile().isData()) {
                    postRefresh();
                }
            }
        }
        
        @Override
        public void fileRenamed(FileRenameEvent fe) {
            if (fe.getFile().isData()) {
                super.fileRenamed(fe);
                handleFileRenamed(fe.getFile(), fe.getFile().getName());
            }
        }

        @Override
        public void fileDeleted(FileEvent fe) {
            super.fileDeleted(fe);
            if (fe.getFile().isData()) {
                postRefresh();
            }
        }

        @Override
        public void fileDataCreated(FileEvent fe) {
            super.fileDataCreated(fe);
            postRefresh();
        }
        
    }
    
    private RequestProcessor.Task refreshTask;

    @Override
    public void addPropertyChangeListener(PropertyChangeListener l) {
        propSupport.addPropertyChangeListener(l);
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener l) {
        propSupport.removePropertyChangeListener(l);
    }

    /**
     * Returns display name of the folder to support localization.
     *
     * @return
     */
    @Override
    public String getName() {
        return dFolder.getNodeDelegate().getDisplayName();
    }

    @Override
    public void