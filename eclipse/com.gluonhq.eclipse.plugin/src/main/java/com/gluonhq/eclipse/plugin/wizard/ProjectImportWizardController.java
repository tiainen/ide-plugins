/*
 * Copyright (c) 2015 the original author or authors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Etienne Studer & Donát Csikós (Gradle Inc.) - initial API and implementation and initial documentation
 */

package com.gluonhq.eclipse.plugin.wizard;

import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.gradleware.tooling.toolingmodel.OmniEclipseProject;
import com.gradleware.tooling.toolingutils.binding.Property;
import com.gradleware.tooling.toolingutils.binding.ValidationListener;
import com.gradleware.tooling.toolingutils.binding.Validator;
import org.eclipse.buildship.core.CorePlugin;
import org.eclipse.buildship.core.configuration.BuildConfiguration;
import org.eclipse.buildship.core.projectimport.ProjectImportConfiguration;
import org.eclipse.buildship.core.util.binding.Validators;
import org.eclipse.buildship.core.util.collections.CollectionsUtils;
import org.eclipse.buildship.core.util.file.FileUtils;
import org.eclipse.buildship.core.util.gradle.GradleDistributionValidator;
import org.eclipse.buildship.core.util.gradle.GradleDistributionWrapper;
import org.eclipse.buildship.core.util.gradle.GradleDistributionWrapper.DistributionType;
import org.eclipse.buildship.core.util.progress.AsyncHandler;
import org.eclipse.buildship.core.workspace.GradleBuild;
import org.eclipse.buildship.core.workspace.NewProjectHandler;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.wizard.IWizard;

import java.io.File;
import java.util.List;

/**
 * Controller class for the {@link GluonProjectWizard}. Contains all non-UI related calculations
 * the wizard has to perform.
 */
public class ProjectImportWizardController {

    // keys to load/store project properties in the dialog setting
    private static final String SETTINGS_KEY_PROJECT_DIR = "project_location"; //$NON-NLS-1$
    private static final String SETTINGS_KEY_GRADLE_DISTRIBUTION_TYPE = "gradle_distribution_type"; //$NON-NLS-1$
    private static final String SETTINGS_KEY_GRADLE_DISTRIBUTION_CONFIGURATION = "gradle_distribution_configuration"; //$NON-NLS-1
    private static final String SETTINGS_KEY_APPLY_WORKING_SETS = "apply_working_sets"; //$NON-NLS-1$
    private static final String SETTINGS_KEY_WORKING_SETS = "working_sets"; //$NON-NLS-1$
    private static final String SETTINGS_KEY_GRADLE_USER_HOME = "gradle_user_home"; //$NON-NLS-1$
    private static final String SETTINGS_KEY_BUILD_SCANS = "build_scans"; //$NON-NLS-1$
    private static final String SETTINGS_KEY_OFFLINE_MODE = "offline_mode"; //$NON-NLS-1$
    private static final String SETTINGS_KEY_AUTO_SYNC = "auto_sync"; //$NON-NLS-1$

    private final ProjectImportConfiguration configuration;

    public ProjectImportWizardController(IWizard projectImportWizard) {
        // assemble configuration object that serves as the data model of the wizard
        Validator<File> projectDirValidator = Validators.and(
                Validators.requiredDirectoryValidator("Project root directory"),
                Validators.nonWorkspaceFolderValidator("Project root directory"));
        Validator<GradleDistributionWrapper> gradleDistributionValidator = GradleDistributionValidator.gradleDistributionValidator();
        Validator<Boolean> applyWorkingSetsValidator = Validators.nullValidator();
        Validator<List<String>> workingSetsValidator = Validators.nullValidator();
        Validator<File> gradleUserHomeValidator = Validators.optionalDirectoryValidator("Gradle user home");

        this.configuration = new ProjectImportConfiguration(projectDirValidator, gradleDistributionValidator, gradleUserHomeValidator, applyWorkingSetsValidator, workingSetsValidator);

        // initialize values from the persisted dialog settings
        IDialogSettings dialogSettings = projectImportWizard.getDialogSettings();
        Optional<File> projectDir = FileUtils.getAbsoluteFile(dialogSettings.get(SETTINGS_KEY_PROJECT_DIR));
        Optional<String> gradleDistributionType = Optional.fromNullable(Strings.emptyToNull(dialogSettings.get(SETTINGS_KEY_GRADLE_DISTRIBUTION_TYPE)));
        Optional<String> gradleDistributionConfiguration = Optional.fromNullable(Strings.emptyToNull(dialogSettings.get(SETTINGS_KEY_GRADLE_DISTRIBUTION_CONFIGURATION)));
        Optional<File> gradleUserHome = FileUtils.getAbsoluteFile(dialogSettings.get(SETTINGS_KEY_GRADLE_USER_HOME));
        boolean applyWorkingSets = dialogSettings.get(SETTINGS_KEY_APPLY_WORKING_SETS) != null && dialogSettings.getBoolean(SETTINGS_KEY_APPLY_WORKING_SETS);
        List<String> workingSets = ImmutableList.copyOf(CollectionsUtils.nullToEmpty(dialogSettings.getArray(SETTINGS_KEY_WORKING_SETS)));
        boolean buildScansEnabled = dialogSettings.getBoolean(SETTINGS_KEY_BUILD_SCANS);
        boolean offlineMode = dialogSettings.getBoolean(SETTINGS_KEY_OFFLINE_MODE);
        boolean autoSync = dialogSettings.getBoolean(SETTINGS_KEY_AUTO_SYNC);

        this.configuration.setProjectDir(projectDir.orNull());
        this.configuration.setOverwriteWorkspaceSettings(false);
        this.configuration.setGradleDistribution(createGradleDistribution(gradleDistributionType, gradleDistributionConfiguration));
        this.configuration.setGradleUserHome(gradleUserHome.orNull());
        this.configuration.setApplyWorkingSets(applyWorkingSets);
        this.configuration.setWorkingSets(workingSets);
        this.configuration.setBuildScansEnabled(buildScansEnabled);
        this.configuration.setOfflineMode(offlineMode);
        this.configuration.setAutoSync(autoSync);

        // store the values every time they change
        saveFilePropertyWhenChanged(dialogSettings, SETTINGS_KEY_PROJECT_DIR, this.configuration.getProjectDir());
        saveGradleWrapperPropertyWhenChanged(dialogSettings, this.configuration.getGradleDistribution());
        saveFilePropertyWhenChanged(dialogSettings, SETTINGS_KEY_GRADLE_USER_HOME, this.configuration.getGradleUserHome());
        saveBooleanPropertyWhenChanged(dialogSettings, SETTINGS_KEY_APPLY_WORKING_SETS, this.configuration.getApplyWorkingSets());
        saveStringArrayPropertyWhenChanged(dialogSettings, SETTINGS_KEY_WORKING_SETS, this.configuration.getWorkingSets());
        saveBooleanPropertyWhenChanged(dialogSettings, SETTINGS_KEY_BUILD_SCANS, this.configuration.getBuildScansEnabled());
        saveBooleanPropertyWhenChanged(dialogSettings, SETTINGS_KEY_OFFLINE_MODE, this.configuration.getOfflineMode());
        saveBooleanPropertyWhenChanged(dialogSettings, SETTINGS_KEY_AUTO_SYNC, this.configuration.getAutoSync());
    }

    private GradleDistributionWrapper createGradleDistribution(Optional<String> gradleDistributionType, Optional<String> gradleDistributionConfiguration) {
        DistributionType distributionType = DistributionType.valueOf(gradleDistributionType.or(DistributionType.WRAPPER.name()));
        String distributionConfiguration = gradleDistributionConfiguration.orNull();
        return GradleDistributionWrapper.from(distributionType, distributionConfiguration);
    }

    private void saveBooleanPropertyWhenChanged(final IDialogSettings settings, final String settingsKey, final Property<Boolean> target) {
        target.addValidationListener(new ValidationListener() {

            @Override
            public void validationTriggered(Property<?> source, Optional<String> validationErrorMessage) {
                settings.put(settingsKey, target.getValue());
            }
        });
    }

    private void saveStringArrayPropertyWhenChanged(final IDialogSettings settings, final String settingsKey, final Property<List<String>> target) {
        target.addValidationListener(new ValidationListener() {

            @Override
            public void validationTriggered(Property<?> source, Optional<String> validationErrorMessage) {
                List<String> value = target.getValue();
                settings.put(settingsKey, value.toArray(new String[value.size()]));
            }
        });
    }

    private void saveFilePropertyWhenChanged(final IDialogSettings settings, final String settingsKey, final Property<File> target) {
        target.addValidationListener(new ValidationListener() {

            @Override
            public void validationTriggered(Property<?> source, Optional<String> validationErrorMessage) {
                settings.put(settingsKey, FileUtils.getAbsolutePath(target.getValue()).orNull());
            }
        });
    }

    private void saveGradleWrapperPropertyWhenChanged(final IDialogSettings settings, final Property<GradleDistributionWrapper> target) {
        target.addValidationListener(new ValidationListener() {

            @Override
            public void validationTriggered(Property<?> source, Optional<String> validationErrorMessage) {
                settings.put(SETTINGS_KEY_GRADLE_DISTRIBUTION_TYPE, target.getValue().getType().name());
                settings.put(SETTINGS_KEY_GRADLE_DISTRIBUTION_CONFIGURATION, target.getValue().getConfiguration());
            }
        });
    }

    public ProjectImportConfiguration getConfiguration() {
        return this.configuration;
    }

    public boolean performImportProject(AsyncHandler initializer, NewProjectHandler newProjectHandler) {
        BuildConfiguration buildConfig = this.configuration.toBuildConfig();
        ImportWizardNewProjectHandler workingSetsAddingNewProjectHandler = new ImportWizardNewProjectHandler(newProjectHandler, this.configuration);
        GradleBuild build = CorePlugin.gradleWorkspaceManager().getGradleBuild(buildConfig);
        build.synchronize(workingSetsAddingNewProjectHandler, initializer);
        return true;
    }

    /**
     * A delegating {@link NewProjectHandler} which adds workingsets to the imported projects and
     * ensures that the Gradle views are visible.
     *
     * @author Stefan Oehme
     */
    private static final class ImportWizardNewProjectHandler implements NewProjectHandler {

        private final ProjectImportConfiguration configuration;
        private final NewProjectHandler importedBuildDelegate;

        private volatile boolean gradleViewsVisible;

        private ImportWizardNewProjectHandler(NewProjectHandler delegate, ProjectImportConfiguration configuration) {
            this.importedBuildDelegate = delegate;
            this.configuration = configuration;
        }

        @Override
        public boolean shouldImport(OmniEclipseProject projectModel) {
            return this.importedBuildDelegate.shouldImport(projectModel);
        }

        @Override
        public void afterImport(IProject project, OmniEclipseProject projectModel) {
            this.importedBuildDelegate.afterImport(project, projectModel);
        }
    }

}
