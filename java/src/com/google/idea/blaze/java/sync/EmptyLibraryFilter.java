/*
 * Copyright 2020 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.idea.blaze.java.sync;

import com.google.idea.blaze.base.command.buildresult.BlazeArtifact;
import com.google.idea.blaze.base.ideinfo.ArtifactLocation;
import com.google.idea.blaze.base.io.FileOperationProvider;
import com.google.idea.blaze.base.model.BlazeLibrary;
import com.google.idea.blaze.base.sync.workspace.ArtifactLocationDecoder;
import com.google.idea.blaze.java.libraries.JarCache;
import com.google.idea.blaze.java.sync.model.BlazeJarLibrary;
import com.google.idea.common.experiments.BoolExperiment;
import com.google.idea.common.experiments.IntExperiment;
import com.intellij.openapi.diagnostic.Logger;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import org.jetbrains.annotations.Nullable;

/**
 * Filters out any {@link BlazeJarLibrary} whose corresponding IntelliJ library would reference only
 * an effectively empty JAR (i.e. it has nothing other than a manifest and directories).
 *
 * <p>Since this filter is used, in part, to determine which remote output JARs should be copied to
 * a local cache, checking the contents of those JARs can involve expensive network operations. We
 * try to minimize this cost by checking the JAR's size first and applying heuristics to avoid doing
 * extra work in the more obvious cases.
 */
public class EmptyLibraryFilter implements Predicate<BlazeLibrary> {
  private static final String FN_MANIFEST = "MANIFEST.MF";
  private static final BoolExperiment ENABLED =
      new BoolExperiment("blaze.empty.jar.filter.enabled", true);
  /** Any JAR that is this size (in bytes) or smaller is assumed to be empty. */
  private static final IntExperiment EMPTY_JAR_THRESHOLD =
      new IntExperiment("blaze.empty.jar.threshold", 284);
  /** Any JAR that is this size (in bytes) or larger is assumed to be non-empty. */
  private static final IntExperiment NON_EMPTY_JAR_THRESHOLD =
      new IntExperiment("blaze.nonempty.jar.threshold", 500);

  private JarCache jarCache;
  private ArtifactLocationDecoder locationDecoder;

  EmptyLibraryFilter(ArtifactLocationDecoder locationDecoder, JarCache jarCache) {
    this.locationDecoder = locationDecoder;
    this.jarCache = jarCache;
  }

  @Override
  public boolean test(BlazeLibrary blazeLibrary) {
    if (!ENABLED.getValue() || !(blazeLibrary instanceof BlazeJarLibrary)) {
      return true;
    }
    File file = jarCache.getCachedJar(locationDecoder, (BlazeJarLibrary) blazeLibrary);
    BlazeArtifact artifact = null;
    if (file == null) {
      ArtifactLocation location =
          ((BlazeJarLibrary) blazeLibrary).libraryArtifact.jarForIntellijLibrary();
      artifact = locationDecoder.resolveOutput(location);
    }
    try {
      return !jarIsEmpty(file, artifact);
    } catch (IOException e) {
      Logger.getInstance(EmptyLibraryFilter.class).warn(e);
      return false;
    }
  }

  /**
   * Returns true if the given JAR is effectively empty (i.e. it has nothing other than a manifest
   * and directories).
   *
   * <p>Exactly one argument to this method should be null. We prefer reading the JAR as a local
   * {@link File}. This is generally faster, as getting an input stream for a {@link BlazeArtifact}
   * may require network operations.
   */
  static boolean jarIsEmpty(@Nullable File file, @Nullable BlazeArtifact artifact)
      throws IOException {
    long length =
        file == null ? artifact.getLength() : FileOperationProvider.getInstance().getFileSize(file);
    if (length <= EMPTY_JAR_THRESHOLD.getValue()) {
      // Note: this implicitly includes files that can't be found (length -1 or 0).
      return true;
    }
    if (length >= NON_EMPTY_JAR_THRESHOLD.getValue()) {
      return false;
    }
    try (InputStream inputStream =
            file == null ? artifact.getInputStream() : new FileInputStream(file);
        JarInputStream jarInputStream = new JarInputStream(inputStream)) {
      return jarIsEmpty(jarInputStream);
    }
  }

  private static boolean jarIsEmpty(JarInputStream jar) throws IOException {
    for (JarEntry entry = jar.getNextJarEntry(); entry != null; entry = jar.getNextJarEntry()) {
      if (!entry.isDirectory() && !entry.getName().endsWith(FN_MANIFEST)) {
        return false;
      }
    }
    return true;
  }
}
