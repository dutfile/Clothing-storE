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

package org.graalvm.visualizer.filterwindow.actions;

import org.graalvm.visualizer.filter.profiles.FilterProfile;
import org.graalvm.visualizer.filter.profiles.mgmt.ProfileService;
import org.graalvm.visualizer.filter.profiles.mgmt.SimpleProfileSelector;
import org.graalvm.visualizer.util.GraphTypes;
import org.openide.NotificationLineSupport;
import org.openide.explorer.ExplorerManager;
import org.openide.explorer.view.NodeRenderer;
import org.openide.nodes.FilterNode;
import org.openide.nodes.Node;
import org.openide.util.Lookup;
import org.openide.util.NbBundle;
import java.awt.Component;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.openide.util.RequestProcessor;

/**
 *
 * @author sdedic
 */
public class EditProfilePanel extends javax.swing.JPanel implements ExplorerManager.Provider {
    private static final RequestProcessor DELAYER_RP = new RequestProcessor(EditProfilePanel.class);
    
    private final ProfileService profiles;
    private final ExplorerManager typeEM = new ExplorerManager();
    
    private FilterProfile profile;
    private NotificationLineSupport notifier;
    private boolean inputValid;
    private SimpleProfileSelector selector;
    
    private RequestProcessor.Task delayedInputValidation;
    private final DocumentListener dl = new DocumentListener() {
        public void insertUpdate(DocumentEvent e) {
            inputChanged();
        }
        public void removeUpdate(DocumentEvent e) {
            inputChanged();
        }
        public void changedUpdate(DocumentEvent e) {}
    };
    
    /**
     * Creates new form EditProfilePanel
     */
    @NbBundle.Messages({
        "PROFILE_AllGraphTypes=<html><i>&nbsp;&nbsp;all graph types</i></html>"
    })
    public EditProfilePanel(ProfileService profiles) {
        this.profiles = profiles;
        initComponents();
        GraphTypes types = Lookup.getDefault().lookup(GraphTypes.class);
        
        DefaultComboBoxModel model = new DefaultComboBoxModel();
        model.addElement(Bundle.PROFILE_AllGraphTypes());
        for (Node n : types.getCategoryNode().getChildren().getNodes(true)) {
            model.addElement(n);
        }
        typeChooser.setModel(model);
        typeChooser.setRenderer(new R());
        
        nameText.getDocument().addDocumentListener(dl);
        groupNameText.getDocument().addDocumentListener(dl);
        priorityNumber.addChangeListener(e -> inputChanged());
    }
    
    private void inputChanged() {
        if (delayedInputValidation != null) {
            delayedInputValidation.cancel();
        }
        delayedInputValidation = DELAYER_RP.post(() -> SwingUtilities.invokeLater(this::validateInputs), 200);
    }
    
    static class R extends DefaultListCellRenderer {
        private final ListCellRenderer delegate = new NodeRenderer();

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            if (value instanceof Node) {
                return delegate.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            }
            return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
        }
    }

    public boolean isInputValid() {
        return inputValid;
    }

    public SimpleProfileSelector getSelector() {
        return selector;
    }

    public void setSelector(SimpleProfileSelector selector) {
        this.selector = selector;
    }

    @Override
    public ExplorerManager getExplorerManager() {
        return typeEM;
    }

    public void setProfile(FilterProfile profile) {
        this.profile = profile;
    }
    
    public void setNotifier(NotificationLineSupport notifier) {
        this.notifier = notifier;
    }
    
    public void editOnly() {
        profileText.setEditable(false);
    }
    
    public String getProfileName() {
        return profileText.getText();
    }
    
    public String getNameRegexp() {
        return nameText.getText();
    }
    
    public int getPriority() {
        return (Integer)priorityNumber.getValue();
    }
    
    @Override
    public void addNotify() {
        super.addNotify();
        
        profileText.setText(profile.getName());
        
        boolean selEnable = selector.isValid();
        typeChooser.setEnabled(selEnable);
        nameText.setEnabled(selEnable);
        groupNameText.setEnabled(selEnable);
        priorityNumber.setEnabled(selEnable);
        graphRegexp.setEnabled(selEnable);
        groupRegexp.setEnabled(selEnable);
        
        graphRegexp.setSelected(selector.isGraphNameRegexp());
        groupRegexp.setSelected(selector.isOwnerNameRegexp());
        
        if (!selEnable) {
            return;
        }
        nameText.setText(selector.getGraphName());
        groupNameText.setText(selector.getOwnerName());
        priorityNumber.setValue(selector.getOrder());
        
        if (selector.getGraphType() == null) {
            typeChooser.setSelectedIndex(0);
        } else for (int i = 1; i < typeChooser.getItemCount(); i++) {
            Object o = typeChooser.getItemAt(i);
            if (o instanceof Node) {
                Node n = (Node)o;
                if (n.getName().equals(selector.getGraphType())) {
                    typeChooser.setSelectedIndex(i);
                    break;
                }
            }
        }
    }
    
    void updateSelector() {
        String s = groupNameText.getText();
        selector.setOwnerName(s.trim());
        s = nameText.getText();
        selector.setGraphName(s.trim());
        Object o = priorityNumber.getValue();
        if (o instanceof Integer) {
            selector.setOrder((Integer)o);
        } else {
            selector.setOrder(0);
        }
        o = typeChooser.getSelectedItem();
        if (o instanceof Node) {
            selector.setGraphType(((Node)o).getName());
        } else {
            selector.setGraphType(null);
        }
        selector.setGraphNameRegexp(graphRegexp.isSelected());
        selector.setOwnerNameRegexp(groupRegexp.isSelected());
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        jLabel1 = new javax.swing.JLabel();
        jLabel2 = new javax.swing.JLabel();
        typeChooser = new javax.swing.JComboBox();
        jLabel3 = new javax.swing.JLabel();
        jLabel4 = new javax.swing.JLabel();
        priorityNumber = new javax.swing.JSpinner();
        profileText = new javax.swing.JTextField();
        nameText = new javax.swing.JTextField();
        jLabel5 = new javax.swing.JLabel();
        groupNameText = new javax.swing.JTextField();
        graphRegexp = new javax.swing.JCheckBox();
        groupRegexp = new javax.swing.JCheckBox();

        org.openide.awt.Mnemonics.setLocalizedText(jLabel1, org.openide.util.NbBundle.getMessage(EditProfilePanel.class, "EditProfilePanel.jLabel1.text")); // NOI18N

        org.openide.awt.Mnemonics.setLocalizedText(jLabel2, org.openide.util.NbBundle.getMessage(EditProfilePanel.class, "EditProfilePanel.jLabel2.text")); // NOI18N

        typeChooser.setToolTipText(org.openide.util.NbBundle.getMessage(EditProfilePanel.class, "EditProfilePanel.typeChooser.toolTipText")); // NOI18N

        org.openide.awt.Mnemonics.setLocalizedText(jLabel3, org.openide.util.NbBundle.getMessage(EditProfilePanel.class, "EditProfilePanel.jLabel3.text")); // NOI18N

        org.openide.awt.Mnemonics.setLocalizedText(jLabel4, org.openide.util.NbBundle.getMessage(EditProfilePanel.class, "EditProfilePanel.jLabel4.text")); // NOI18N

        priorityNumber.setFont(new java.awt.Font("Dialog", 0, 12)); // NOI18N
        priorityNumber.setModel(new javax.swing.SpinnerNumberModel(1, 0, null, 1));
        priorityNumber.addFocusListener(new java.awt.even