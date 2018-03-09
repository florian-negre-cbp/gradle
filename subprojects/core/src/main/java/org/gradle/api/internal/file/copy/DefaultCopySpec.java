/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.file.copy;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.NonExtensible;
import org.gradle.api.Transformer;
import org.gradle.api.file.CopyProcessingSpec;
import org.gradle.api.file.CopySpec;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.file.FileCopyDetails;
import org.gradle.api.file.FileTree;
import org.gradle.api.file.FileTreeElement;
import org.gradle.api.file.RelativePath;
import org.gradle.api.internal.ChainingTransformer;
import org.gradle.api.internal.ClosureBackedAction;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.file.pattern.PatternMatcherFactory;
import org.gradle.api.specs.Spec;
import org.gradle.api.specs.Specs;
import org.gradle.api.tasks.util.PatternSet;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.typeconversion.NotationParser;
import org.gradle.util.ConfigureUtil;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FilterReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@NonExtensible
public class DefaultCopySpec implements CopySpecInternal {
    private static final NotationParser<Object, String> PATH_NOTATION_PARSER = PathNotationConverter.parser();
    private final CopySpecInternal parent;
    protected final FileResolver fileResolver;
    private final Set<Object> sourcePaths;
    private Object destDir;
    private final PatternSet patternSet;
    private final List<CopySpecInternal> childSpecs;
    private final List<CopySpecInternal> childSpecsInAdditionOrder;
    protected final Instantiator instantiator;
    private final List<Action<? super FileCopyDetails>> copyActions = new ArrayList<Action<? super FileCopyDetails>>();
    private boolean hasCustomActions;
    private Integer dirMode;
    private Integer fileMode;
    private Boolean caseSensitive;
    private Boolean includeEmptyDirs;
    private DuplicatesStrategy duplicatesStrategy;
    private String filteringCharset;
    private List<CopySpecListener> listeners = Lists.newLinkedList();

    public DefaultCopySpec(@Nullable CopySpecInternal parent, FileResolver resolver, Instantiator instantiator) {
        this.parent = parent;
        this.fileResolver = resolver;
        this.instantiator = instantiator;
        sourcePaths = new LinkedHashSet<Object>();
        childSpecs = new ArrayList<CopySpecInternal>();
        childSpecsInAdditionOrder = new ArrayList<CopySpecInternal>();
        patternSet = resolver.getPatternSetFactory().create();
        duplicatesStrategy = null;
    }

    @Override
    public boolean hasCustomActions() {
        if (hasCustomActions) {
            return true;
        }
        for (CopySpecInternal childSpec : childSpecs) {
            if (childSpec.hasCustomActions()) {
                return true;
            }
        }
        return false;
    }

    @VisibleForTesting
    List<Action<? super FileCopyDetails>> getCopyActions() {
        return copyActions;
    }

    @Override
    public CopySpec with(CopySpec... copySpecs) {
        for (CopySpec copySpec : copySpecs) {
            CopySpecInternal copySpecInternal;
            if (copySpec instanceof CopySpecSource) {
                CopySpecSource copySpecSource = (CopySpecSource) copySpec;
                copySpecInternal = copySpecSource.getRootSpec();
            } else {
                copySpecInternal = (CopySpecInternal) copySpec;
            }
            addChildSpec(copySpecInternal);
        }
        return this;
    }

    @Override
    public CopySpec from(Object... sourcePaths) {
        Collections.addAll(this.sourcePaths, sourcePaths);
        return this;
    }

    @Override
    public CopySpec from(Object sourcePath, final Closure c) {
        return from(sourcePath, new ClosureBackedAction<CopySpec>(c));
    }

    @Override
    public CopySpec from(Object sourcePath, @Nullable Action<? super CopySpec> configureAction) {
        if (configureAction == null) {
            from(sourcePath);
            return this;
        } else {
            CopySpecInternal child = addChild();
            child.from(sourcePath);
            CopySpecWrapper wrapper = instantiator.newInstance(CopySpecWrapper.class, child);
            configureAction.execute(wrapper);
            return wrapper;
        }
    }

    @Override
    public CopySpecInternal addFirst() {
        return addChildAtPosition(0);
    }

    protected CopySpecInternal addChildAtPosition(int position) {
        DefaultCopySpec child = instantiator.newInstance(DefaultCopySpec.class, this, fileResolver, instantiator);
        addChildSpec(position, child);
        return child;
    }

    @Override
    public CopySpecInternal addChild() {
        DefaultCopySpec child = new DefaultCopySpec(this, fileResolver, instantiator);
        addChildSpec(child);
        return child;
    }

    @Override
    public CopySpecInternal addChildBeforeSpec(CopySpecInternal childSpec) {
        int position = childSpecs.indexOf(childSpec);
        return position != -1 ? addChildAtPosition(position) : addChild();
    }

    protected void addChildSpec(CopySpecInternal childSpec) {
        addChildSpec(childSpecs.size(), childSpec);
    }

    protected void addChildSpec(int index, CopySpecInternal childSpec) {
        childSpecs.add(index, childSpec);

        // We need a consistent index here
        childSpecsInAdditionOrder.add(childSpec);

        // In case more descendants are added to downward hierarchy, make sure they'll notify us
        childSpec.addChildSpecListener(new CopySpecListener() {
            @Override
            public void childSpecAdded(CopySpecInternal spec) {
                fireChildSpecListeners(spec);
            }
        });

        // Notify upwards of currently existing descendant spec hierarchy
        childSpec.visit(new CopySpecVisitor() {
            @Override
            public void visit(CopySpecInternal spec) {
                fireChildSpecListeners(spec);
            }
        });
    }

    private void fireChildSpecListeners(CopySpecInternal spec) {
        for (CopySpecListener listener : listeners) {
            listener.childSpecAdded(spec);
        }
    }

    @Override
    public void visit(CopySpecVisitor visitor) {
        visitor.visit(this);
        for (CopySpecInternal childSpec : childSpecsInAdditionOrder) {
            childSpec.visit(visitor);
        }
    }

    @Override
    public void addChildSpecListener(CopySpecListener copySpecListener) {
        this.listeners.add(copySpecListener);
    }

    @VisibleForTesting
    Set<Object> getSourcePaths() {
        return sourcePaths;
    }

    @Override
    public DefaultCopySpec into(Object destDir) {
        this.destDir = destDir;
        return this;
    }

    @Override
    public CopySpec into(Object destPath, Closure configureClosure) {
        return into(destPath, new ClosureBackedAction<CopySpec>(configureClosure));
    }

    @Override
    public CopySpec into(Object destPath, @Nullable Action<? super CopySpec> copySpec) {
        if (copySpec == null) {
            into(destPath);
            return this;
        } else {
            CopySpecInternal child = addChild();
            child.into(destPath);
            CopySpecWrapper wrapper = instantiator.newInstance(CopySpecWrapper.class, child);
            copySpec.execute(wrapper);
            return wrapper;
        }
    }

    @Override
    public boolean isCaseSensitive() {
        if (caseSensitive != null) {
            return caseSensitive;
        }
        return parent == null || parent.isCaseSensitive();
    }

    @Override
    public void setCaseSensitive(boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
    }

    @Override
    public boolean getIncludeEmptyDirs() {
        if (includeEmptyDirs != null) {
            return includeEmptyDirs;
        }
        return parent == null || parent.getIncludeEmptyDirs();
    }

    @Override
    public void setIncludeEmptyDirs(boolean includeEmptyDirs) {
        this.includeEmptyDirs = includeEmptyDirs;
    }

    @Override
    public DuplicatesStrategy getDuplicatesStrategy() {
        if (duplicatesStrategy != null) {
            return duplicatesStrategy;
        }
        if (parent != null) {
            return parent.getDuplicatesStrategy();
        }
        return DuplicatesStrategy.INCLUDE;
    }

    @Override
    public void setDuplicatesStrategy(@Nullable DuplicatesStrategy strategy) {
        this.duplicatesStrategy = strategy;
    }

    @Override
    public CopySpec filesMatching(String pattern, Action<? super FileCopyDetails> action) {
        Spec<RelativePath> matcher = PatternMatcherFactory.getPatternMatcher(true, isCaseSensitive(), pattern);
        return eachFile(new MatchingCopyAction(matcher, action));
    }

    @Override
    public CopySpec filesMatching(Iterable<String> patterns, Action<? super FileCopyDetails> action) {
        if (!patterns.iterator().hasNext()) {
            throw new InvalidUserDataException("must provide at least one pattern to match");
        }
        List<Spec<RelativePath>> matchers = new ArrayList<Spec<RelativePath>>();
        for (String pattern : patterns) {
            matchers.add(PatternMatcherFactory.getPatternMatcher(true, isCaseSensitive(), pattern));
        }
        return eachFile(new MatchingCopyAction(Specs.union(matchers), action));
    }

    @Override
    public CopySpec filesNotMatching(String pattern, Action<? super FileCopyDetails> action) {
        Spec<RelativePath> matcher = PatternMatcherFactory.getPatternMatcher(true, isCaseSensitive(), pattern);
        return eachFile(new MatchingCopyAction(Specs.negate(matcher), action));
    }

    @Override
    public CopySpec filesNotMatching(Iterable<String> patterns, Action<? super FileCopyDetails> action) {
        if (!patterns.iterator().hasNext()) {
            throw new InvalidUserDataException("must provide at least one pattern to not match");
        }
        List<Spec<RelativePath>> matchers = new ArrayList<Spec<RelativePath>>();
        for (String pattern : patterns) {
            matchers.add(PatternMatcherFactory.getPatternMatcher(true, isCaseSensitive(), pattern));
        }
        return eachFile(new MatchingCopyAction(Specs.negate(Specs.union(matchers)), action));
    }

    @Override
    public CopySpec include(String... includes) {
        patternSet.include(includes);
        return this;
    }

    @Override
    public CopySpec include(Iterable<String> includes) {
        patternSet.include(includes);
        return this;
    }

    @Override
    public CopySpec include(Spec<FileTreeElement> includeSpec) {
        patternSet.include(includeSpec);
        return this;
    }

    @Override
    public CopySpec include(Closure includeSpec) {
        patternSet.include(includeSpec);
        return this;
    }

    @Override
    public Set<String> getIncludes() {
        return patternSet.getIncludes();
    }

    @Override
    public CopySpec setIncludes(Iterable<String> includes) {
        patternSet.setIncludes(includes);
        return this;
    }

    @Override
    public CopySpec exclude(String... excludes) {
        patternSet.exclude(excludes);
        return this;
    }

    @Override
    public CopySpec exclude(Iterable<String> excludes) {
        patternSet.exclude(excludes);
        return this;
    }

    @Override
    public CopySpec exclude(Spec<FileTreeElement> excludeSpec) {
        patternSet.exclude(excludeSpec);
        return this;
    }

    @Override
    public CopySpec exclude(Closure excludeSpec) {
        patternSet.exclude(excludeSpec);
        return this;
    }

    @Override
    public Set<String> getExcludes() {
        return patternSet.getExcludes();
    }

    @Override
    public DefaultCopySpec setExcludes(Iterable<String> excludes) {
        patternSet.setExcludes(excludes);
        return this;
    }

    @Override
    public CopySpec rename(String sourceRegEx, String replaceWith) {
        appendCopyAction(new RenamingCopyAction(new RegExpNameMapper(sourceRegEx, replaceWith)));
        return this;
    }

    @Override
    public CopySpec rename(Pattern sourceRegEx, String replaceWith) {
        appendCopyAction(new RenamingCopyAction(new RegExpNameMapper(sourceRegEx, replaceWith)));
        return this;
    }

    @Override
    public CopySpec filter(final Class<? extends FilterReader> filterType) {
        appendCopyAction(new Action<FileCopyDetails>() {
            public void execute(FileCopyDetails fileCopyDetails) {
                fileCopyDetails.filter(filterType);
            }
        });
        return this;
    }

    @Override
    public CopySpec filter(final Closure closure) {
        return filter(new ClosureBackedTransformer(closure));
    }

    @Override
    public CopySpec filter(final Transformer<String, String> transformer) {
        appendCopyAction(new Action<FileCopyDetails>() {
            public void execute(FileCopyDetails fileCopyDetails) {
                fileCopyDetails.filter(transformer);
            }
        });
        return this;
    }

    @Override
    public CopySpec filter(final Map<String, ?> properties, final Class<? extends FilterReader> filterType) {
        appendCopyAction(new Action<FileCopyDetails>() {
            public void execute(FileCopyDetails fileCopyDetails) {
                fileCopyDetails.filter(properties, filterType);
            }
        });
        return this;
    }

    @Override
    public CopySpec expand(final Map<String, ?> properties) {
        appendCopyAction(new Action<FileCopyDetails>() {
            public void execute(FileCopyDetails fileCopyDetails) {
                fileCopyDetails.expand(properties);
            }
        });
        return this;
    }

    @Override
    public CopySpec rename(Closure closure) {
        return rename(new ClosureBackedTransformer(closure));
    }

    @Override
    public CopySpec rename(Transformer<String, String> renamer) {
        ChainingTransformer<String> transformer = new ChainingTransformer<String>(String.class);
        transformer.add(renamer);
        appendCopyAction(new RenamingCopyAction(transformer));
        return this;
    }

    @Override
    public Integer getDirMode() {
        if (dirMode != null) {
            return dirMode;
        }
        if (parent != null) {
            return parent.getDirMode();
        }
        return null;
    }

    @Override
    public Integer getFileMode() {
        if (fileMode != null) {
            return fileMode;
        }
        if (parent != null) {
            return parent.getFileMode();
        }
        return null;
    }

    @Override
    public CopyProcessingSpec setDirMode(@Nullable Integer mode) {
        dirMode = mode;
        return this;
    }

    @Override
    public CopyProcessingSpec setFileMode(@Nullable Integer mode) {
        fileMode = mode;
        return this;
    }

    @Override
    public CopySpec eachFile(Action<? super FileCopyDetails> action) {
        appendCopyAction(action);
        return this;
    }

    private void appendCopyAction(Action<? super FileCopyDetails> action) {
        hasCustomActions = true;
        copyActions.add(action);
    }

    @Override
    public void appendCachingSafeCopyAction(Action<? super FileCopyDetails> action) {
        copyActions.add(action);
    }

    @Override
    public CopySpec eachFile(Closure closure) {
        appendCopyAction(ConfigureUtil.configureUsing(closure));
        return this;
    }

    @Override
    public Iterable<CopySpecInternal> getChildren() {
        return childSpecs;
    }

    @Override
    public String getFilteringCharset() {
        if (filteringCharset != null) {
            return filteringCharset;
        }
        if (parent != null) {
            return parent.getFilteringCharset();
        }
        return Charset.defaultCharset().name();
    }

    @Override
    public void setFilteringCharset(@Nullable String charset) {
        if (charset == null) {
            throw new InvalidUserDataException("filteringCharset must not be null");
        }
        if (!Charset.isSupported(charset)) {
            throw new InvalidUserDataException(String.format("filteringCharset %s is not supported by your JVM", charset));
        }
        this.filteringCharset = charset;
    }

    @Override
    public ResolvedCopySpecNode resolveAsRoot() {
        PatternSet resolvedPatternSet = createPatternSet(fileResolver);
        boolean caseSensitive = isCaseSensitive();
        resolvedPatternSet.setCaseSensitive(caseSensitive);
        copyPatterns(patternSet, resolvedPatternSet);
        return resolve(RelativePath.EMPTY_ROOT, resolvedPatternSet, caseSensitive, copyActions);
    }

    @Override
    public ResolvedCopySpecNode resolveAsChild(
        PatternSet parentPatternSet,
        Iterable<? extends Action<? super FileCopyDetails>> parentCopyActions,
        ResolvedCopySpec parent
    ) {
        boolean caseSensitive = isCaseSensitive();
        PatternSet resolvedPatternSet = createPatternSet(fileResolver);
        resolvedPatternSet.setCaseSensitive(caseSensitive);
        copyPatterns(parentPatternSet, resolvedPatternSet);
        copyPatterns(patternSet, resolvedPatternSet);

        return resolve(parent.getDestPath(), resolvedPatternSet, caseSensitive, Iterables.concat(parentCopyActions, copyActions));
    }

    private ResolvedCopySpecNode resolve(
        RelativePath parentPath,
        PatternSet patternSet,
        boolean caseSensitive,
        Iterable<Action<? super FileCopyDetails>> copyActions
    ) {
        FileTree source = fileResolver.resolveFilesAsTree(sourcePaths);
        RelativePath resolvedPath = resolveDestPath(parentPath);

        ResolvedCopySpec resolvedSpec = new DefaultResolvedCopySpec(
            resolvedPath,
            source,
            patternSet,
            caseSensitive,
            getIncludeEmptyDirs(),
            getDuplicatesStrategy(),
            getFileMode(),
            getDirMode(),
            getFilteringCharset(),
            copyActions
        );
        ImmutableList.Builder<ResolvedCopySpecNode> builder = ImmutableList.builder();
        for (CopySpecInternal childSpec : childSpecs) {
            builder.add(childSpec.resolveAsChild(patternSet, copyActions, resolvedSpec));
        }
        return new ResolvedCopySpecNode(resolvedSpec, builder.build());
    }

    private RelativePath resolveDestPath(RelativePath parentPath) {
        if (destDir == null) {
            return parentPath;
        }

        String path = PATH_NOTATION_PARSER.parseNotation(destDir);
        if (path.startsWith("/") || path.startsWith(File.separator)) {
            return RelativePath.parse(false, path);
        }

        return RelativePath.parse(false, parentPath, path);
    }

    private static PatternSet createPatternSet(FileResolver fileResolver) {
        PatternSet patterns = fileResolver.getPatternSetFactory().create();
        assert patterns != null;
        return patterns;
    }

    private static void copyPatterns(PatternSet source, PatternSet target) {
        target.include(source.getIncludes());
        target.includeSpecs(source.getIncludeSpecs());
        target.exclude(source.getExcludes());
        target.excludeSpecs(source.getExcludeSpecs());
    }
}
