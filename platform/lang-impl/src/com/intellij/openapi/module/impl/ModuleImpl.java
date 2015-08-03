/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.module.impl;

import com.intellij.ide.highlighter.ModuleFileType;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.impl.ApplicationInfoImpl;
import com.intellij.openapi.components.*;
import com.intellij.openapi.components.impl.ModuleServiceManagerImpl;
import com.intellij.openapi.components.impl.PlatformComponentManagerImpl;
import com.intellij.openapi.components.impl.stores.FileBasedStorage;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.AreaInstance;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.module.ModifiableModuleModel;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleComponent;
import com.intellij.openapi.module.OptionManager;
import com.intellij.openapi.module.impl.scopes.ModuleScopeProviderImpl;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.impl.storage.ClasspathStorage;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.PathUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.picocontainer.MutablePicoContainer;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * @author max
 */
public class ModuleImpl extends PlatformComponentManagerImpl implements ModuleEx {
  private static final Logger LOG = Logger.getInstance("#com.intellij.openapi.module.impl.ModuleImpl");

  @NotNull private final Project myProject;
  private boolean isModuleAdded;

  private String myName;

  private final ModuleScopeProvider myModuleScopeProvider;

  public ModuleImpl(@NotNull String filePath, @NotNull Project project) {
    super(project, "Module " + moduleNameByFileName(PathUtil.getFileName(filePath)));

    getPicoContainer().registerComponentInstance(Module.class, this);

    myProject = project;
    myModuleScopeProvider = new ModuleScopeProviderImpl(this);

    myName = moduleNameByFileName(PathUtil.getFileName(filePath));

    VirtualFileManager.getInstance().addVirtualFileListener(new MyVirtualFileListener(), this);
  }

  @Override
  protected void bootstrapPicoContainer(@NotNull String name) {
    Extensions.instantiateArea(ExtensionAreas.IDEA_MODULE, this, (AreaInstance)getParentComponentManager());
    super.bootstrapPicoContainer(name);
  }

  @NotNull
  private static FileBasedStorage getMainStorage(@NotNull Module module) {
    return (FileBasedStorage)ComponentsPackage.getStateStore(module).getStateStorageManager().getStateStorage(StoragePathMacros.MODULE_FILE, RoamingType.PER_USER);
  }

  @Override
  public void init(@NotNull final String path, @Nullable final Runnable beforeComponentCreation) {
    init(ProgressManager.getInstance().getProgressIndicator(), new Runnable() {
      @Override
      public void run() {
        // create ServiceManagerImpl at first to force extension classes registration
        getPicoContainer().getComponentInstance(ModuleServiceManagerImpl.class);
        ComponentsPackage.getStateStore(ModuleImpl.this).setPath(path);

        if (beforeComponentCreation != null) {
          beforeComponentCreation.run();
        }
      }
    });
  }

  @Override
  protected void setProgressDuringInit(@NotNull ProgressIndicator indicator) {
    // module loading progress is not tracked, progress updated by ModuleManagerImpl on module load
  }

  @Override
  protected boolean isComponentSuitable(@Nullable Map<String, String> options) {
    if (!super.isComponentSuitable(options)) {
      return false;
    }
    if (options == null || options.isEmpty()) {
      return true;
    }

    for (String optionName : options.keySet()) {
      if ("workspace".equals(optionName)) {
        continue;
      }

      // we cannot filter using module options because at this moment module file data could be not loaded
      String message = "Don't specify " + optionName + " in the component registration, transform component to service and implement your logic in your getInstance() method";
      if (ApplicationManager.getApplication().isUnitTestMode()) {
        LOG.error(message);
      }
      else {
        LOG.warn(message);
      }
    }

    return true;
  }

  @Override
  @Nullable
  public VirtualFile getModuleFile() {
    return getMainStorage(this).getVirtualFile();
  }

  @Override
  public void rename(String newName) {
    myName = newName;
    ComponentsPackage.getStateStore(this).getStateStorageManager().rename(StoragePathMacros.MODULE_FILE, newName + ModuleFileType.DOT_DEFAULT_EXTENSION);
  }

  @Override
  @NotNull
  public String getModuleFilePath() {
    return getMainStorage(this).getFilePath();
  }

  @Override
  public synchronized void dispose() {
    isModuleAdded = false;
    disposeComponents();
    Extensions.disposeArea(this);
    super.dispose();
  }

  @NotNull
  @Override
  public ComponentConfig[] getMyComponentConfigsFromDescriptor(@NotNull IdeaPluginDescriptor plugin) {
    return plugin.getModuleComponents();
  }

  @Override
  public void projectOpened() {
    for (ModuleComponent component : getComponentInstancesOfType(ModuleComponent.class)) {
      try {
        component.projectOpened();
      }
      catch (Exception e) {
        LOG.error(e);
      }
    }
  }

  @Override
  public void projectClosed() {
    List<ModuleComponent> components = getComponentInstancesOfType(ModuleComponent.class);
    for (int i = components.size() - 1; i >= 0; i--) {
      try {
        components.get(i).projectClosed();
      }
      catch (Throwable e) {
        LOG.error(e);
      }
    }
  }

  @Override
  @NotNull
  public Project getProject() {
    return myProject;
  }

  @Override
  @NotNull
  public String getName() {
    return myName;
  }

  @Override
  public boolean isLoaded() {
    return isModuleAdded;
  }

  @Override
  public void moduleAdded() {
    isModuleAdded = true;
    for (ModuleComponent component : getComponentInstancesOfType(ModuleComponent.class)) {
      component.moduleAdded();
    }
  }

  @Override
  public void setOption(@NotNull String key, @NotNull String value) {
    OptionManager manager = getOptionManager();
    if (manager != null) {
      manager.setOption(key, value);
    }
  }

  @Nullable
  private OptionManager getOptionManager() {
    try {
      return (OptionManager)getMainStorage(this).getStorageData();
    }
    catch (StateStorageException e) {
      LOG.error(e);
      return null;
    }
  }

  @Override
  public void clearOption(@NotNull String key) {
    OptionManager manager = getOptionManager();
    if (manager != null) {
      manager.clearOption(key);
    }
  }

  @Override
  public String getOptionValue(@NotNull String key) {
    OptionManager manager = getOptionManager();
    return manager == null ? null : manager.getOptionValue(key);
  }

  @NotNull
  @Override
  public GlobalSearchScope getModuleScope() {
    return myModuleScopeProvider.getModuleScope();
  }

  @NotNull
  @Override
  public GlobalSearchScope getModuleScope(boolean includeTests) {
    return myModuleScopeProvider.getModuleScope(includeTests);
  }

  @NotNull
  @Override
  public GlobalSearchScope getModuleWithLibrariesScope() {
    return myModuleScopeProvider.getModuleWithLibrariesScope();
  }

  @NotNull
  @Override
  public GlobalSearchScope getModuleWithDependenciesScope() {
    return myModuleScopeProvider.getModuleWithDependenciesScope();
  }

  @NotNull
  @Override
  public GlobalSearchScope getModuleContentScope() {
    return myModuleScopeProvider.getModuleContentScope();
  }

  @NotNull
  @Override
  public GlobalSearchScope getModuleContentWithDependenciesScope() {
    return myModuleScopeProvider.getModuleContentWithDependenciesScope();
  }

  @NotNull
  @Override
  public GlobalSearchScope getModuleWithDependenciesAndLibrariesScope(boolean includeTests) {
    return myModuleScopeProvider.getModuleWithDependenciesAndLibrariesScope(includeTests);
  }

  @NotNull
  @Override
  public GlobalSearchScope getModuleWithDependentsScope() {
    return myModuleScopeProvider.getModuleWithDependentsScope();
  }

  @NotNull
  @Override
  public GlobalSearchScope getModuleTestsWithDependentsScope() {
    return myModuleScopeProvider.getModuleTestsWithDependentsScope();
  }

  @NotNull
  @Override
  public GlobalSearchScope getModuleRuntimeScope(boolean includeTests) {
    return myModuleScopeProvider.getModuleRuntimeScope(includeTests);
  }

  @Override
  public void clearScopesCache() {
    myModuleScopeProvider.clearCache();
  }

  @SuppressWarnings({"HardCodedStringLiteral"})
  public String toString() {
    if (myName == null) return "Module (not initialized)";
    return "Module: '" + getName() + "'";
  }

  private static String moduleNameByFileName(@NotNull String fileName) {
    return StringUtil.trimEnd(fileName, ModuleFileType.DOT_DEFAULT_EXTENSION);
  }

  @NotNull
  @Override
  public <T> T[] getExtensions(@NotNull final ExtensionPointName<T> extensionPointName) {
    return Extensions.getArea(this).getExtensionPoint(extensionPointName).getExtensions();
  }

  @Override
  protected boolean logSlowComponents() {
    return super.logSlowComponents() || ApplicationInfoImpl.getShadowInstance().isEAP();
  }

  private class MyVirtualFileListener extends VirtualFileAdapter {
    @Override
    public void propertyChanged(@NotNull VirtualFilePropertyEvent event) {
      if (!isModuleAdded || event.getRequestor() instanceof StateStorage || !VirtualFile.PROP_NAME.equals(event.getPropertyName())) {
        return;
      }

      VirtualFile parent = event.getParent();
      if (parent != null) {
        String parentPath = parent.getPath();
        String ancestorPath = parentPath + "/" + event.getOldValue();
        String moduleFilePath = getModuleFilePath();
        if (VfsUtilCore.isAncestor(new File(ancestorPath), new File(moduleFilePath), true)) {
          setModuleFilePath(moduleFilePath, parentPath + "/" + event.getNewValue() + "/" + FileUtil.getRelativePath(ancestorPath, moduleFilePath, '/'));
        }
      }
    }

    private void setModuleFilePath(String moduleFilePath, String newFilePath) {
      ClasspathStorage.modulePathChanged(ModuleImpl.this, newFilePath);

      final ModifiableModuleModel modifiableModel = ModuleManagerImpl.getInstanceImpl(getProject()).getModifiableModel();
      modifiableModel.setModuleFilePath(ModuleImpl.this, moduleFilePath, newFilePath);
      modifiableModel.commit();

      getMainStorage(ModuleImpl.this).setFile(null, new File(newFilePath));
      ComponentsPackage.getStateStore(ModuleImpl.this).setPath(FileUtilRt.toSystemIndependentName(newFilePath));
    }

    @Override
    public void fileMoved(@NotNull VirtualFileMoveEvent event) {
      String dirName = event.getFileName();
      String ancestorPath = event.getOldParent().getPath() + "/" + dirName;
      String moduleFilePath = getModuleFilePath();
      if (VfsUtilCore.isAncestor(new File(ancestorPath), new File(moduleFilePath), true)) {
        setModuleFilePath(moduleFilePath, event.getNewParent().getPath() + "/" + dirName + "/" + FileUtil.getRelativePath(ancestorPath, moduleFilePath, '/'));
      }
    }
  }

  @NotNull
  @Override
  protected MutablePicoContainer createPicoContainer() {
    return Extensions.getArea(this).getPicoContainer();
  }
}
