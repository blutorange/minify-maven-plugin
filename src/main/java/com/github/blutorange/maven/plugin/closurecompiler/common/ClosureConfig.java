/*
 * Closure Compiler Maven Plugin https://github.com/blutorange/closure-compiler-maven-plugin Original license terms
 * below. Changes were made to this file.
 */

/*
 * Minify Maven Plugin https://github.com/samaxes/minify-maven-plugin Copyright (c) 2009 samaxes.com Licensed under the
 * Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may
 * obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law or
 * agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package com.github.blutorange.maven.plugin.closurecompiler.common;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.SerializationUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.maven.plugin.MojoFailureException;

import com.github.blutorange.maven.plugin.closurecompiler.plugin.MinifyMojo;
import com.google.common.base.Strings;
import com.google.javascript.jscomp.CheckLevel;
import com.google.javascript.jscomp.CompilationLevel;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.CompilerOptions.DependencyMode;
import com.google.javascript.jscomp.CompilerOptions.LanguageMode;
import com.google.javascript.jscomp.DependencyOptions;
import com.google.javascript.jscomp.DiagnosticGroup;
import com.google.javascript.jscomp.DiagnosticGroups;
import com.google.javascript.jscomp.ModuleIdentifier;
import com.google.javascript.jscomp.SourceFile;
import com.google.javascript.jscomp.SourceMap;
import com.google.javascript.jscomp.SourceMap.Format;

/**
 * <a href="https://developers.google.com/closure/compiler/">Google Closure Compiler</a> configuration.
 */
public class ClosureConfig {

  private static final String BINARY_PREFIX = "0b";

  private static final String FILE_PREFIX = "file:";

  private static CompilerOptions createCompilerOptions(MinifyMojo mojo) throws MojoFailureException {
    CompilerOptions options = new CompilerOptions();

    options.setEnvironment(mojo.getClosureEnvironment());
    options.setLanguageIn(mojo.getClosureLanguageIn());
    options.setLanguageOut(mojo.getClosureLanguageOut());
    options.setOutputCharset(Charset.forName(mojo.getEncoding()));

    options.setAngularPass(mojo.isClosureAngularPass());
    options.setColorizeErrorOutput(mojo.isClosureColorizeErrorOutput());
    options.setDefineReplacements(createDefineReplacements(mojo));
    options.setExtraAnnotationNames(mojo.getClosureExtraAnnotations());
    options.setPrettyPrint(mojo.isClosurePrettyPrint());
    options.setRewritePolyfills(mojo.isClosureRewritePolyfills());
    options.setTrustedStrings(mojo.isClosureTrustedStrings());
    createWarningLevels(mojo).forEach(options::setWarningLevel);

    return options;
  }

  private static Map<String, Object> createDefineReplacements(MinifyMojo mojo) {
    Map<String, Object> defineReplacements = new HashMap<>();
    for (Map.Entry<String, String> defineReplacement : mojo.getClosureDefineReplacements().entrySet()) {
      String key = defineReplacement.getKey();
      String value = Strings.nullToEmpty(defineReplacement.getValue()).trim();

      if (Strings.isNullOrEmpty(value)) { throw new RuntimeException("Define replacement " + key + " does not have a value."); }

      if ("true".equals(value)) {
        defineReplacements.put(key, Boolean.TRUE);
        continue;
      }

      if ("false".equals(value)) {
        defineReplacements.put(key, Boolean.FALSE);
        continue;
      }

      // Check for quoted string
      if (value.startsWith("\"") || value.startsWith("'")) {
        defineReplacements.put(key, StringEscapeUtils.unescapeEcmaScript(value.substring(1, value.length() - 1)));
        continue;
      }

      if (value.startsWith(BINARY_PREFIX)) {
        try {
          defineReplacements.put(key, Integer.valueOf(value.substring(BINARY_PREFIX.length()), 2));
          continue;
        }
        catch (NumberFormatException e) {
          mojo.getLog().warn("Cannot parse a (binary) number: " + value, e);
          // Not a valid binary Integer, try next type
        }
      }

      if (value.startsWith("0") && value.charAt(1) != '.') {
        try {
          defineReplacements.put(key, Integer.valueOf(value.substring(1), 8));
          continue;
        }
        catch (NumberFormatException e) {
          mojo.getLog().warn("Cannot parse an (octal) number: " + value, e);
          // Not a valid binary Integer, try next type
        }
      }

      try {
        defineReplacements.put(key, Integer.valueOf(value, 10));
        continue;
      }
      catch (NumberFormatException e) {
        // Not a valid Integer, try next type
      }

      try {
        defineReplacements.put(key, Double.valueOf(value));
        continue;
      }
      catch (NumberFormatException e) {
        mojo.getLog().warn("Cannot parse as a number: " + value, e);
        // Not a valid Double, try next type
      }

      // Default to string
      mojo.getLog().warn("Cannot parse define replacement value: '" + value + "'. Use quotation marks for a string.");
      defineReplacements.put(key, value);
    }
    return defineReplacements;
  }

  private DependencyOptions createDependencyOptions(File baseDirForSourceFiles, File sourceDir) throws MojoFailureException, IOException {
    DependencyOptions dependencyOptions = new DependencyOptions();

    // Map entry points
    Collection<ModuleIdentifier> entryPointsMapped = new ArrayList<>();
    for (String entryPoint : CollectionUtils.emptyIfNull(entryPoints)) {
      if (entryPoint.startsWith(FILE_PREFIX)) {
        File file = new File(sourceDir, entryPoint.substring(FILE_PREFIX.length())).getCanonicalFile();
        entryPointsMapped.add(ModuleIdentifier.forFile(FileHelper.relativizePath(baseDirForSourceFiles, file)));
      }
      else {
        entryPointsMapped.add(ModuleIdentifier.forClosure(entryPoint));
      }
    }

    // Set dependency mode
    if (dependencyMode == CompilerOptions.DependencyMode.STRICT) {
      if (entryPoints.isEmpty()) { throw new MojoFailureException("When dependency_mode=STRICT, you must specify at least one entry_point"); }
      dependencyOptions.setDependencyPruning(true).setDependencySorting(true).setMoocherDropping(true);
    }
    else if (dependencyMode == CompilerOptions.DependencyMode.LOOSE
        || !entryPoints.isEmpty()) {
      dependencyOptions.setDependencyPruning(true).setDependencySorting(true).setMoocherDropping(false);
    }

    dependencyOptions.setEntryPoints(entryPointsMapped);
    return dependencyOptions;
  }

  private static List<SourceFile> createExterns(MinifyMojo mojo) {
    List<SourceFile> externs = new ArrayList<>();
    for (String extern : mojo.getClosureExterns()) {
      externs.add(SourceFile.fromFile(new File(mojo.getBaseSourceDir(), extern).getAbsolutePath(), Charset.forName(mojo.getEncoding())));
    }
    return externs;
  }

  private static OutputInterpolator createOutputInterpolator(MinifyMojo mojo) {
    String outputWrapper = mojo.getClosureOutputWrapper();
    if (StringUtils.isBlank(outputWrapper)) {
      return OutputInterpolator.forIdentity();
    }
    else {
      return OutputInterpolator.forPattern(outputWrapper);
    }
  }

  private static Map<DiagnosticGroup, CheckLevel> createWarningLevels(MinifyMojo mojo) throws MojoFailureException {
    Map<DiagnosticGroup, CheckLevel> warningLevels = new HashMap<>();
    DiagnosticGroups diagnosticGroups = new DiagnosticGroups();
    for (Map.Entry<String, String> warningLevel : mojo.getClosureWarningLevels().entrySet()) {
      DiagnosticGroup diagnosticGroup = diagnosticGroups.forName(warningLevel.getKey());
      if (diagnosticGroup == null) { throw new MojoFailureException("Failed to process closureWarningLevels: " + warningLevel.getKey() + " is an invalid DiagnosticGroup"); }

      try {
        CheckLevel checkLevel = CheckLevel.valueOf(warningLevel.getValue());
        warningLevels.put(diagnosticGroup, checkLevel);
      }
      catch (IllegalArgumentException e) {
        throw new MojoFailureException("Failed to process closureWarningLevels: " + warningLevel.getKey() + " is an invalid CheckLevel");
      }
    }
    return warningLevels;
  }

  private final CompilationLevel compilationLevel;

  private final CompilerOptions compilerOptions;

  private final CompilerOptions.Environment environment;

  private final List<SourceFile> externs;

  private final boolean includeSourcesContent;

  private final OutputInterpolator outputInterpolator;

  private final Format sourceMapFormat;

  private final FilenameInterpolator sourceMapInterpolator;

  private final SourceMapOutputType sourceMapOutputType;

  private final LanguageMode languageOut;

  private final DependencyMode dependencyMode;

  private final Collection<String> entryPoints;

  /**
   * Create a new closure compiler configuration from the mojo configuration.
   * @param mojo Mojo with the options.
   * @throws MojoFailureException When the configuration is invalid or cannot be parsed.
   */
  public ClosureConfig(MinifyMojo mojo) throws MojoFailureException {
    this.compilationLevel = mojo.getClosureCompilationLevel();
    this.environment = mojo.getClosureEnvironment();
    this.includeSourcesContent = mojo.isClosureIncludeSourcesContent();
    this.languageOut = mojo.getClosureLanguageOut();
    this.sourceMapFormat = mojo.isClosureCreateSourceMap() ? SourceMap.Format.V3 : null;
    this.sourceMapOutputType = mojo.getClosureSourceMapOutputType();
    this.dependencyMode = mojo.getClosureDependencyMode();
    this.entryPoints = new ArrayList<>(mojo.getClosureDependencyEntryPoints());

    this.sourceMapInterpolator = new FilenameInterpolator(mojo.getClosureSourceMapName());
    this.compilerOptions = createCompilerOptions(mojo);
    this.externs = createExterns(mojo);
    this.outputInterpolator = createOutputInterpolator(mojo);
  }

  public CompilationLevel getCompilationLevel() {
    return compilationLevel;
  }

  public CompilerOptions getCompilerOptions(File minifiedFile, File sourceMapFile, File baseDirForSourceFiles, File sourceDir) throws MojoFailureException, IOException {
    CompilerOptions compilerOptions = SerializationUtils.clone(this.compilerOptions);

    // Apply dependency options
    compilerOptions.setDependencyOptions(createDependencyOptions(baseDirForSourceFiles, sourceDir));

    // Apply compilation level
    compilationLevel.setOptionsForCompilationLevel(compilerOptions);

    // Tell the compiler to create a source map, if configured.
    if (sourceMapFormat != null) {
      compilerOptions.setSourceMapFormat(sourceMapFormat);
      compilerOptions.setSourceMapIncludeSourcesContent(includeSourcesContent);
      compilerOptions.setSourceMapOutputPath(sourceMapFile.getPath());
    }

    return compilerOptions;
  }

  public CompilerOptions.Environment getEnvironment() {
    return environment;
  }

  public List<SourceFile> getExterns() {
    return externs;
  }

  public OutputInterpolator getOutputInterpolator() {
    return outputInterpolator;
  }

  public FilenameInterpolator getSourceMapInterpolator() {
    return sourceMapInterpolator;
  }

  public SourceMapOutputType getSourceMapOutputType() {
    return sourceMapOutputType;
  }

  public boolean isCreateSourceMap() {
    return sourceMapFormat != null;
  }
  
  public boolean isCreateSourceMapFile() {
    return isCreateSourceMap() && sourceMapOutputType.isCreateFile();
  }

  public LanguageMode getLanguageOut() {
    return languageOut;
  }
}
