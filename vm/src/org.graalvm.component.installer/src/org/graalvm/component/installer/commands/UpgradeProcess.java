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
package org.graalvm.component.installer.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.graalvm.component.installer.Archive;
import org.graalvm.component.installer.BundleConstants;
import org.graalvm.component.installer.CommandInput;
import org.graalvm.component.installer.Commands;
import org.graalvm.component.installer.CommonConstants;
import org.graalvm.component.installer.ComponentCatalog;
import org.graalvm.component.installer.ComponentCollection;
import org.graalvm.component.installer.ComponentInstaller;
import org.graalvm.component.installer.ComponentIterable;
import org.graalvm.component.installer.ComponentParam;
import org.graalvm.component.installer.FailedOperationException;
import org.graalvm.component.installer.Feedback;
import org.graalvm.component.installer.FileOperations;
import org.graalvm.component.installer.SystemUtils;
import org.graalvm.component.installer.UnknownVersionException;
import org.graalvm.component.installer.Version;
import org.graalvm.component.installer.model.ComponentInfo;
import org.graalvm.component.installer.model.ComponentRegistry;
import org.graalvm.component.installer.model.DistributionType;
import org.graalvm.component.installer.persist.DirectoryStorage;
import org.graalvm.component.installer.persist.MetadataLoader;
import org.graalvm.component.installer.remote.CatalogIterable;

/**
 * Drives the GraalVM core upgrade process.
 * 
 * @author sdedic
 */
public class UpgradeProcess implements AutoCloseable {
    private final CommandInput input;
    private final Feedback feedback;
    private final ComponentCollection catalog;

    private final Set<String> existingComponents = new HashSet<>();
    private final Set<ComponentParam> addComponents = new HashSet<>();
    private final Set<ComponentInfo> migrated = new HashSet<>();
    private final Set<String> explicitIds = new HashSet<>();

    private ComponentInfo targetInfo;
    private Path newInstallPath;
    private Path newGraalHomePath;
    private MetadataLoader metaLoader;
    private boolean allowMissing;
    private ComponentRegistry newGraalRegistry;
    private Version minVersion = Version.NO_VERSION;
    private String editionUpgrade;
    private Set<String> acceptedLicenseIDs = new HashSet<>();

    public UpgradeProcess(CommandInput input, Feedback feedback, ComponentCollection catalog) {
        this.input = input;
        this.feedback = feedback.withBundle(UpgradeProcess.class);
        this.catalog = catalog;
        resetExistingComponents();
    }

    final void resetExistingComponents() {
        existingComponents.clear();
        existingComponents.addAll(input.getLocalRegistry().getComponentIDs().stream().filter((id) -> {
            ComponentInfo info = input.getLocalRegistry().findComponent(id);
            // only auto-include the 'leaf' components
            return info != null && input.getLocalRegistry().findDependentComponents(info, false).isEmpty();
        }).collect(Collectors.toList()));
        existingComponents.remove(BundleConstants.GRAAL_COMPONENT_ID);
    }

    public String getEditionUpgrade() {
        return editionUpgrade;
    }

    public void setEditionUpgrade(String editionUpgrade) {
        this.editionUpgrade = editionUpgrade;
    }

    /**
     * Adds a component to install to the upgraded core.
     * 
     * @param info the component to install.
     */
    public void addComponent(ComponentParam info) throws IOException {
        addComponents.add(info);
        explicitIds.add(info.createMetaLoader().getComponentInfo().getId().toLowerCase(Locale.ENGLISH));
    }

    public Set<ComponentParam> addedComponents() {
        return addComponents;
    }

    public boolean isAllowMissing() {
        return allowMissing;
    }

    public void setAllowMissing(boolean allowMissing) {
        this.allowMissing = allowMissing;
    }

    Path getNewInstallPath() {
        return newInstallPath;
    }

    public List<ComponentParam> allComponents() throws IOException {
        Set<String> ids = new HashSet<>();
        ArrayList<ComponentParam> allComps = new ArrayList<>(addedComponents());
        for (ComponentParam p : allComps) {
            ids.add(p.createMetaLoader().getComponentInfo().getId());
        }
        for (ComponentInfo mig : migrated) {
            if (ids.contains(mig.getId())) {
                continue;
            }
            allComps.add(input.existingFiles().createParam(mig.getId(), mig));
        }
        return allComps;
    }

    /**
     * Access to {@link ComponentRegistry} in the new instance.
     * 
     * @return registry in the new instance.
     */
    public ComponentRegistry getNewGraalRegistry() {
        return newGraalRegistry;
    }

    /**
     * Finds parent path for the new GraalVM installation. Note that on MacOs X the "JAVA_HOME" is
     * below the installation root, so on MacOS X the installation root is returned.
     * 
     * @return installation root for the core package.
     */
    Path findGraalVMParentPath() {
        Path vmRoot = input.getGraalHomePath().normalize();
        if (vmRoot.getNameCount() == 0) {
            return null;
        }
        Path skipPath = SystemUtils.getGraalVMJDKRoot(input.getLocalRegistry());
        Path skipped = vmRoot;
        while (skipPath != null && skipped != null && skipPath.getNameCount() > 0 &&
                        Objects.equals(skipPath.getFileName(), skipped.getFileName())) {
            skipPath = skipPath.getParent();
            skipped = skipped.getParent();
        }
        if (skipPath == null || skipPath.getNameCount() == 0) {
            vmRoot = skipped;
        }
        Path parent = vmRoot.getParent();
        // ensure the parent directory is still writable:
        if (parent != null && !Files.isWritable(parent)) {
            throw feedback.failure("UPGRADE_DirectoryNotWritable", null, parent);
        }
        return parent;
    }

    /**
     * Defines name for the install path. The GraalVM core package may define "edition" capability,
     * which places "ee" in the name.
     * 
     * @param graal new Graal core component
     * @return Path to the installation directory
     */
    Path createInstallName(ComponentInfo graal) {
        String targetDir = input.optValue(Commands.OPTION_TARGET_DIRECTORY);
        Path base;

        if (targetDir != null) {
            base = SystemUtils.fromUserString(targetDir);
        } else {
            base = findGraalVMParentPath();
        }
        // "provides" java_version
        String jv = graal.getProvidedValue(CommonConstants.CAP_JAVA_VERSION, String.class);
        if (jv == null) {
            // if not present, then at least "requires" which is autogenerated from release file.
            jv = graal.getRequiredGraalValues().get(CommonConstants.CAP_JAVA_VERSION);
        }
        if (jv == null) {
            jv = input.getLocalRegistry().getGraalCapabilities().get(CommonConstants.CAP_JAVA_VERSION);
        }
        String ed = graal.getProvidedValue(CommonConstants.CAP_EDITION, String.class);
        if (ed == null) {
            // maybe we do install a specific edition ?
            if (editionUpgrade != null) {
                ed = editionUpgrade;
            } else {
                ed = input.getLocalRegistry().getGraalCapabilities().get(CommonConstants.CAP_EDITION);
            }
        }
        String dirName = feedback.l10n(
                        ed == null ? "UPGRADE_GraalVMDirName@" : "UPGRADE_GraalVMDirNameEdition@",
                        graal.getVersion().displayString(),
                        ed,
                        jv);
        return base.resolve(SystemUtils.fileName(dirName));
    }

    /**
     * Prepares the installation of the core Component. Returns {@code false} if the upgrade is not
     * necessary or not found.
     * 
     * @param info
     * @return true, if the graalvm should be updated.
     * @throws IOException
     */
    boolean prepareInstall(ComponentInfo info) throws IOException {
        Version min = input.getLocalRegistry().getGraalVersion();
        if (info == null) {
            feedback.message("UPGRADE_NoUpdateFound", min.displayString());
            return false;
        }
        int cmp = min.compareTo(info.getVersion());
        if ((cmp > 0) || ((editionUpgrade == null) && (cmp == 0))) {
            feedback.message("UPGRADE_NoUpdateLatestVersion", min.displayString());
            migrated.clear();
            return false;
        }

        Path reported = createInstallName(info);
        // there's a slight chance this will be different from the final name ...
        feedback.output("UPGRADE_PreparingInstall", info.getVersion().displayString(), reported);
        failIfDirectotyExistsNotEmpty(reported);

        ComponentParam coreParam = createGraalComponentParam(info);
        // reuse License logic from the installer command:
        InstallCommand cmd = new InstallCommand();
        cmd.init(input, feedback);

        // ask the InstallCommand to process/accept the licenses, if there are any.
        MetadataLoader ldr = coreParam.createMetaLoader();
        cmd.addLicenseToAccept(ldr);
        cmd.acceptLicenses();
        acceptedLicenseIDs = cmd.getProcessedLicenses();

        // force download
        ComponentParam param = input.existingFiles().createParam("core", info);
        metaLoader = param.createFileLoader();
        ComponentInfo completeInfo = metaLoader.completeMetadata();
        newInstallPath = createInstallName(completeInfo);
        newGraalHomePath = newInstallPath;
        failIfDirectotyExistsNotEmpty(newInstallPath);

        if (!reported.equals(newInstallPath)) {
            feedback.error("UPGRADE_WarningEditionDifferent", null, info.getVersion().displayString(), newInstallPath);
        }

        existingComponents.addAll(input.getLocalRegistry().getComponentIDs());
        existingComponents.remove(BundleConstants.GRAAL_COMPONENT_ID);
        return true;
    }

    void failIfDirectotyExistsNotEmpty(Path target) throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        if (!Files.isDirectory(target)) {
            throw feedback.failure("UPGRADE_TargetExistsNotDirectory", null, target);
        }
        Path ghome = target.resolve(SystemUtils.getGraalVMJDKRoot(input.getLocalRegistry()));
        Path relFile = ghome.resolve("release");
        if (Files.isReadable(relFile)) {
            Version targetVersion = null;
            try {
                ComponentRegistry reg = createRegistryFor(ghome);
                targetVersion = reg.getGraalVersion();
            } catch (FailedOperationException ex) {
                // ignore
            }
            if (targetVersion != null) {
                throw feedback.failure("UPGRADE_TargetExistsContainsGraalVM", null, target, targetVersion.displayString());
            }
        }
        if (Files.list(target).findFirst().isPresent()) {
            throw feedback.failure("UPGRADE_TargetExistsNotEmpty", null, target);
        }
    }

    /**
     * Completes the component info, loads symlinks, permissions. Same as
     * {@link InstallCommand#createInstaller}.
     */
    GraalVMInstaller createGraalVMInstaller(ComponentInfo info) throws IOException {
        ComponentParam p = createGraalComponentParam(info);
        MetadataLoader ldr = p.createFileLoader();
        ldr.loadPaths();
        if (p.isComplete()) {
            Archive a;
            a = ldr.getArchive();
            a.verifyIntegrity(input);
        }
        ComponentInfo completeInfo = ldr.getComponentInfo();
        targetInfo = completeInfo;
        metaLoader = ldr;
        GraalVMInstaller gvmInstaller = new GraalVMInstaller(feedback,
                        input.getFileOperations(),
                        input.getLocalRegistry(), completeInfo, catalog,
                        metaLoader.getArchive());
        // do not create symlinks if disabled, or target directory is given.
        boolean disableSymlink = input.hasOption(Commands.OPTION_NO_SYMLINK) ||
                        input.hasOption(Commands.OPTION_TARGET_DIRECTORY);
        gvmInstaller.setDisableSymlinks(disableSymlink);
        // will make registrations for bundled components, too.
        gvmInstaller.setAllowFilesInComponentDir(true);
        gvmInstaller.setCurrentInstallPath(input.getGraalHomePath());
        gvmInstaller.setInstallPath(newInstallPath);
        gvmInstaller.setPermissions(ldr.loadPermissions());
        gvmInstaller.setSymlinks(ldr.loadSymlinks());
        newGraalHomePath = gvmInstaller.getInstalledPath();
        return gvmInstaller;
    }

    /**
     * Cached parameter for the core. It will cache MetaLoader and FileLoader for subsequent
     * operations.
     */
    private ComponentParam graalCoreParam;
    private GraalVMInstaller coreInstaller;

    ComponentParam createGraalComponentParam(ComponentInfo info) {
        if (graalCoreParam == null) {
            graalCoreParam = input.existingFiles().createParam(info.getId(), info);
        }
        return graalCoreParam;
    }

    public boolean installGraalCore(ComponentInfo info) throws IOException {
        if (!prepareInstall(info)) {
            return false;
        }

        GraalVMInstaller gvmInstaller = createGraalVMInstaller(info);

        feedback.output("UPGRADE_InstallingCore", info.getVersion().displayString(), newInstallPath.toString());

        gvmInstaller.install();
        // allow symlink to be created in close(); the symlink won't obscure paths
        // that might contain the symlink during installation of components.
        coreInstaller = gvmInstaller;

        Path installed = gvmInstaller.getInstalledPath();
        newGraalRegistry = createRegistryFor(installed);
        migrateLicenses();
        return true;
    }

    private ComponentRegistry createRegistryFor(Path home) {
        DirectoryStorage dst = new DirectoryStorage(
                        feedback.withBundle(ComponentInstaller.class),
                        home.resolve(SystemUtils.fromCommonRelative(CommonConstants.PATH_COMPONENT_STORAGE)),
                        home);
        dst.setJavaVersion(input.getLocalRegistry().getJavaVersion());
        return new ComponentRegistry(feedback, dst);
    }

    /**
     * Checks if the candidate GraalVM satisfies all dependencies of added components. Added
     * components are those specified on the commandline;
     * 
     * @param candidate candidate GraalVM component
     * @return broken components
     */
    Collection<ComponentInfo> satisfiedAddedComponents(ComponentInfo candidate) throws IOException {
        List<ComponentInfo> broken = new ArrayList<>();
        Version gv = candidate.getVersion();
        Version.Match satisfies = gv.match(Version.Match.Type.COMPATIBLE);
        for (ComponentParam param : addComponents) {
            ComponentInfo in = param.createMetaLoader().getComponentInfo();
            String vs = in.getRequiredGraalValues().get(BundleConstants.GRAAL_VERSION);
            Version cv = Version.fromString(vs);
            if (!sat