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

import static com.google.common.truth.Truth.assertThat;

import com.google.idea.blaze.base.BlazeTestCase;
import com.google.idea.blaze.base.command.buildresult.SourceArtifact;
import com.google.idea.blaze.base.io.FileOperationProvider;
import com.google.idea.common.experiments.ExperimentService;
import com.google.idea.common.experiments.MockExperimentService;
import com.intellij.testFramework.rules.TempDirectory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link EmptyLibraryFilter}. */
@RunWith(JUnit4.class)
public class EmptyLibraryFilterTest extends BlazeTestCase {
  @Rule public TempDirectory tempDirectory = new TempDirectory();

  @Override
  protected void initTest(Container applicationServices, Container projectServices) {
    applicationServices.register(FileOperationProvider.class, new FileOperationProvider());
    applicationServices.register(ExperimentService.class, new MockExperimentService());
  }

  @Test
  public void jarIsEmpty_nonexistent() throws IOException {
    File jar = new File("nonexistent.jar");
    assertThat(EmptyLibraryFilter.jarIsEmpty(jar, null)).isTrue();
    assertThat(EmptyLibraryFilter.jarIsEmpty(null, new SourceArtifact(jar))).isTrue();
  }

  @Test
  public void jarIsEmpty_trulyEmpty() throws IOException {
    File jar = new JarBuilder().build();
    assertThat(EmptyLibraryFilter.jarIsEmpty(jar, null)).isTrue();
    assertThat(EmptyLibraryFilter.jarIsEmpty(null, new SourceArtifact(jar))).isTrue();
  }

  @Test
  public void jarIsEmpty_largeButManifestOnly() throws IOException {
    File jar = new JarBuilder().includeManifest().bloatBy(200).build();
    assertThat(EmptyLibraryFilter.jarIsEmpty(jar, null)).isTrue();
    assertThat(EmptyLibraryFilter.jarIsEmpty(null, new SourceArtifact(jar))).isTrue();
  }

  @Test
  public void jarIsEmpty_largeButOnlyManifestAndDirectories() throws IOException {
    File jar =
        new JarBuilder().includeManifest().addDirectory("dir1/").addDirectory("dir2/").build();
    assertThat(EmptyLibraryFilter.jarIsEmpty(jar, null)).isTrue();
    assertThat(EmptyLibraryFilter.jarIsEmpty(null, new SourceArtifact(jar))).isTrue();
  }

  @Test
  public void jarIsEmpty_smallButNonEmpty() throws IOException {
    File jar = new JarBuilder().includeManifest().addFile("A.java", "class A {}").build();
    assertThat(EmptyLibraryFilter.jarIsEmpty(jar, null)).isFalse();
    assertThat(EmptyLibraryFilter.jarIsEmpty(null, new SourceArtifact(jar))).isFalse();
  }

  @Test
  public void jarIsEmpty_largeAndNonEmpty() throws IOException {
    File jar =
        new JarBuilder()
            .includeManifest()
            .addDirectory("dir1")
            .addDirectory("dir2")
            .addFile("com/google/A.java", "package com.google; public class A {}")
            .addFile("com/google/B.java", "package com.google; public class B {}")
            .addFile("com/google/C.java", "package com.google; public class C {}")
            .build();
    assertThat(EmptyLibraryFilter.jarIsEmpty(jar, null)).isFalse();
    assertThat(EmptyLibraryFilter.jarIsEmpty(null, new SourceArtifact(jar))).isFalse();
  }

  private class JarBuilder {
    private boolean includesManifest = false;
    private int bloatBytes = 0;
    private Map<String, String> files = new HashMap<>();

    /** Increases the size of the JAR by adding {@code numBytes} in junk metadata. */
    JarBuilder bloatBy(int numBytes) {
      bloatBytes = numBytes;
      return this;
    }

    JarBuilder includeManifest() {
      includesManifest = true;
      return this;
    }

    JarBuilder addDirectory(String path) {
      if (!path.endsWith("/")) {
        path = path + "/";
      }
      files.put(path, null);
      return this;
    }

    JarBuilder addFile(String path, String content) {
      files.put(path, content);
      return this;
    }

    File build() throws IOException {
      File jar = tempDirectory.newFile("test.jar");
      try (OutputStream outputStream = new FileOutputStream(jar);
          JarOutputStream jarOutputStream =
              includesManifest
                  ? new JarOutputStream(outputStream, createVersionedManifest())
                  : new JarOutputStream(outputStream)) {
        if (bloatBytes > 0) {
          // The comment isn't compressed, so we can use it to artificially bloat the JAR.
          jarOutputStream.setComment(stringOfSize(bloatBytes));
        }
        for (Map.Entry<String, String> entry : files.entrySet()) {
          jarOutputStream.putNextEntry(new JarEntry(entry.getKey()));
          String content = entry.getValue();
          if (content != null) {
            jarOutputStream.write(content.getBytes());
          }
          jarOutputStream.closeEntry();
        }
      }
      return jar;
    }
  }

  private static Manifest createVersionedManifest() {
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    return manifest;
  }

  private static String stringOfSize(int numChars) {
    StringBuilder sb = new StringBuilder();
    while (numChars > 0) {
      sb.append(' ');
      numChars--;
    }
    return sb.toString();
  }
}
