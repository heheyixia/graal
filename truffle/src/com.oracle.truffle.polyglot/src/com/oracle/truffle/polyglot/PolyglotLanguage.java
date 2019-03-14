/*
 * Copyright (c) 2017, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.polyglot;

import static com.oracle.truffle.polyglot.VMAccessor.LANGUAGE;
import static com.oracle.truffle.polyglot.VMAccessor.NODES;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;

import org.graalvm.options.OptionDescriptors;
import org.graalvm.polyglot.Language;
import org.graalvm.polyglot.impl.AbstractPolyglotImpl.AbstractLanguageImpl;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.nodes.LanguageInfo;
import com.oracle.truffle.api.utilities.NeverValidAssumption;

final class PolyglotLanguage extends AbstractLanguageImpl implements com.oracle.truffle.polyglot.PolyglotImpl.VMObject {

    static final boolean CONSERVATIVE_REFERENCES = true;

    final PolyglotEngineImpl engine;
    final LanguageCache cache;
    final LanguageInfo info;

    Language api; // effectivley final
    final int index;
    private final boolean host;
    final RuntimeException initError;

    private volatile OptionDescriptors options;
    private volatile OptionValuesImpl optionValues;
    private volatile boolean initialized;

    private volatile PolyglotLanguageInstance initLanguage;
    private final LinkedList<PolyglotLanguageInstance> instancePool = new LinkedList<>();

    final ContextProfile profile;
    private final LanguageReference<TruffleLanguage<Object>> multiLanguageSupplier;
    private final ContextReference<Object> multiContextSupplier;
    private final ContextReference<Object> singleOrMultiContextSupplier;
    final Assumption singleLanguage = Truffle.getRuntime().createAssumption("Single language instance.");
    private int instanceCount;

    PolyglotLanguage(PolyglotEngineImpl engine, LanguageCache cache, int index, boolean host, RuntimeException initError) {
        super(engine.impl);
        this.engine = engine;
        this.cache = cache;
        this.initError = initError;
        this.index = index;
        this.host = host;
        this.profile = new ContextProfile(this);
        this.info = NODES.createLanguage(this, cache.getId(), cache.getName(), cache.getVersion(), cache.getDefaultMimeType(), cache.getMimeTypes(), cache.isInternal(), cache.isInteractive());
        this.multiLanguageSupplier = new MultiLanguageSupplier(this);
        this.multiContextSupplier = new MultiContextSupplier(this);
        if (CONSERVATIVE_REFERENCES) {
            this.singleOrMultiContextSupplier = multiContextSupplier;
        } else {
            this.singleOrMultiContextSupplier = new SingleOrMultiContextSupplier(this);
        }
    }

    PolyglotLanguageContext getCurrentLanguageContext() {
        return PolyglotContextImpl.requireContext().contexts[index];
    }

    boolean dependsOn(PolyglotLanguage otherLanguage) {
        Set<String> dependentLanguages = cache.getDependentLanguages();
        if (dependentLanguages.contains(otherLanguage.getId())) {
            return true;
        }
        for (String dependentLanguage : dependentLanguages) {
            PolyglotLanguage dependentLanguageObj = engine.idToLanguage.get(dependentLanguage);
            if (dependentLanguageObj != null && dependsOn(dependentLanguageObj)) {
                return true;
            }
        }
        return false;
    }

    boolean isHost() {
        return host;
    }

    @Override
    public OptionDescriptors getOptions() {
        engine.checkState();
        if (!initialized) {
            synchronized (engine) {
                if (!initialized) {
                    try {
                        this.initLanguage = ensureInitialized(new PolyglotLanguageInstance(this));
                    } catch (Throwable e) {
                        // failing to initialize the language for getting the option descriptors
                        // should not be a fatal error. this typically happens when an invalid
                        // language is on the classpath.
                        return OptionDescriptors.EMPTY;
                    }
                    initialized = true;
                }
            }
        }
        return options;
    }

    private PolyglotLanguageInstance createInstance() {
        assert Thread.holdsLock(engine);
        PolyglotLanguageInstance instance = null;
        if (initLanguage != null) {
            // reuse init language
            instance = this.initLanguage;
            initLanguage = null;
        }
        if (instance == null) {
            instance = ensureInitialized(new PolyglotLanguageInstance(this));
        }
        instanceCount++;
        if (instanceCount > 1 && singleLanguage.isValid()) {
            singleLanguage.invalidate();
        }
        return instance;
    }

    @Override
    public PolyglotEngineImpl getEngine() {
        return engine;
    }

    private PolyglotLanguageInstance ensureInitialized(PolyglotLanguageInstance instance) {
        if (!initialized) {
            synchronized (engine) {
                if (!initialized) {
                    try {
                        this.options = LANGUAGE.describeOptions(instance.spi, cache.getId());
                    } catch (Exception e) {
                        throw new IllegalStateException(String.format("Error initializing language '%s' using class '%s'.", cache.getId(), cache.getClassName()), e);
                    }
                    initialized = true;
                }
            }
        }
        return instance;
    }

    PolyglotLanguageInstance allocateInstance(OptionValuesImpl newOptions) {
        PolyglotLanguageInstance instance;
        synchronized (engine) {
            switch (cache.getPolicy()) {
                case EXCLUSIVE:
                    instance = createInstance();
                    break;
                case REUSE:
                    instance = fetchFromPool(newOptions, false);
                    break;
                case SHARED:
                    instance = fetchFromPool(newOptions, true);
                    break;
                default:
                    throw new AssertionError("Unknown context cardinality.");
            }
            instance.ensureMultiContextInitialized();
        }
        return instance;
    }

    private PolyglotLanguageInstance fetchFromPool(OptionValuesImpl newOptions, boolean shared) {
        synchronized (engine) {
            PolyglotLanguageInstance foundInstance = null;
            for (Iterator<PolyglotLanguageInstance> iterator = instancePool.iterator(); iterator.hasNext();) {
                PolyglotLanguageInstance instance = iterator.next();
                if (instance.areOptionsCompatible(newOptions)) {
                    if (!shared) {
                        iterator.remove();
                    }
                    foundInstance = instance;
                    break;
                }
            }
            if (foundInstance == null) {
                foundInstance = createInstance();
                foundInstance.claim(newOptions);
                if (shared) {
                    instancePool.addFirst(foundInstance);
                }
            }
            return foundInstance;
        }
    }

    void freeInstance(PolyglotLanguageInstance instance) {
        synchronized (engine) {
            switch (cache.getPolicy()) {
                case EXCLUSIVE:
                    // nothing to do
                    break;
                case REUSE:
                    profile.notifyLanguageFreed();
                    instancePool.addFirst(instance);
                    break;
                case SHARED:
                    // nothing to do
                    break;
                default:
                    throw new AssertionError("Unknown context cardinality.");
            }
            instanceCount--;
        }
    }

    ContextReference<Object> getContextImplSupplier() {
        return singleOrMultiContextSupplier;
    }

    LanguageReference<TruffleLanguage<Object>> getLanguageSupplier() {
        return multiLanguageSupplier;
    }

    OptionValuesImpl getOptionValues() {
        if (optionValues == null) {
            synchronized (engine) {
                if (optionValues == null) {
                    optionValues = new OptionValuesImpl(engine, getOptions());
                }
            }
        }
        return optionValues;
    }

    @Override
    public String getDefaultMimeType() {
        return cache.getDefaultMimeType();
    }

    void clearOptionValues() {
        optionValues = null;
    }

    @Override
    public String getName() {
        return cache.getName();
    }

    @Override
    public String getImplementationName() {
        return cache.getImplementationName();
    }

    @Override
    public boolean isInteractive() {
        return cache.isInteractive();
    }

    @Override
    public Set<String> getMimeTypes() {
        return cache.getMimeTypes();
    }

    @Override
    public String getVersion() {
        final String version = cache.getVersion();
        if (version.equals("inherit")) {
            return engine.getVersion();
        } else {
            return version;
        }
    }

    @Override
    public String getId() {
        return cache.getId();
    }

    @Override
    public String toString() {
        return "PolyglotLanguage [id=" + getId() + ", name=" + getName() + ", host=" + isHost() + "]";
    }

    static final class ContextProfile {

        private static final Object UNSET_CONTEXT = new Object();

        private final Assumption singleContext;
        @CompilationFinal private volatile Object cachedSingleContext = UNSET_CONTEXT;
        @CompilationFinal private volatile Object cachedSingleLanguageContext = UNSET_CONTEXT;

        ContextProfile(PolyglotLanguage language) {
            this.singleContext = language.engine.boundEngine ? Truffle.getRuntime().createAssumption("Language single context.") : NeverValidAssumption.INSTANCE;
        }

        public Assumption getSingleContext() {
            return singleContext;
        }

        PolyglotLanguageContext profile(Object context) {
            if (singleContext.isValid()) {
                Object cachedSingle = cachedSingleLanguageContext;
                if (singleContext.isValid()) {
                    assert cachedSingle == context : assertionError(cachedSingle, context);
                    return (PolyglotLanguageContext) cachedSingle;
                }
            }
            return (PolyglotLanguageContext) context;
        }

        static String assertionError(Object cachedContext, Object currentContext) {
            return (cachedContext + " != " + currentContext);
        }

        void notifyContextCreate(PolyglotLanguageContext context, Env env) {
            if (singleContext.isValid()) {
                Object cachedSingle = this.cachedSingleContext;
                assert cachedSingle != LANGUAGE.getContext(env) || cachedSingle == null : "Non-null context objects should be distinct";
                if (cachedSingle == UNSET_CONTEXT) {
                    if (singleContext.isValid()) {
                        this.cachedSingleContext = LANGUAGE.getContext(env);
                        this.cachedSingleLanguageContext = context;
                    }
                } else {
                    singleContext.invalidate();
                    cachedSingleContext = UNSET_CONTEXT;
                    cachedSingleLanguageContext = UNSET_CONTEXT;
                }
            }
        }

        void notifyLanguageFreed() {
            if (singleContext.isValid()) {
                // do not invalidate assumptions if engine is disposed anyway
                cachedSingleContext = UNSET_CONTEXT;
                cachedSingleLanguageContext = UNSET_CONTEXT;
            }
        }
    }

    boolean assertCorrectEngine() {
        PolyglotContextImpl context = PolyglotContextImpl.requireContext();
        PolyglotLanguageContext languageContext = context.getContext(this);
        if (languageContext.isInitialized() && languageContext.language.engine != this.engine) {
            throw new AssertionError(String.format("Context reference was used from an Engine that is currently not entered. " +
                            "ContextReference of engine %s was used but engine %s is currently entered. " +
                            "ContextReference must not be shared between multiple Engine instances.",
                            languageContext.language.engine.creatorApi,
                            this.engine.creatorApi));
        }
        return true;
    }

    static final class SingleOrMultiContextSupplier extends ContextReference<Object> {

        private final PolyglotLanguage language;
        @CompilationFinal private volatile Assumption singleContext;
        @CompilationFinal private volatile WeakReference<Object> contextReference;

        private final Assumption singleLanguage;
        private final ContextReference<Object> multiContextSupplier;

        SingleOrMultiContextSupplier(PolyglotLanguage language) {
            this.language = language;
            this.singleLanguage = language.singleLanguage;
            this.multiContextSupplier = language.multiContextSupplier;
        }

        @Override
        public Object get() {
            if (singleLanguage.isValid()) {
                Assumption localSingleContext = this.singleContext;
                WeakReference<Object> localContextReference = this.contextReference;
                if (localSingleContext == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    PolyglotLanguageContext localContext = language.getCurrentLanguageContext();
                    PolyglotLanguageInstance singleInstance = localContext.getLanguageInstance();
                    this.contextReference = localContextReference = new WeakReference<>(localContext.getContextImpl());
                    this.singleContext = localSingleContext = singleInstance.singleContext;
                }
                if (localSingleContext.isValid()) {
                    return localContextReference.get();
                }
            }
            return multiContextSupplier.get();
        }

    }

    private static final class MultiLanguageSupplier extends LanguageReference<TruffleLanguage<Object>> {

        final PolyglotLanguage language;

        MultiLanguageSupplier(PolyglotLanguage language) {
            this.language = language;
        }

        @SuppressWarnings("unchecked")
        @Override
        public TruffleLanguage<Object> get() {
            if (!CONSERVATIVE_REFERENCES) {
                assert !language.engine.singleContext.isValid();
                assert !language.singleLanguage.isValid();
            }
            assert language.assertCorrectEngine();
            return (TruffleLanguage<Object>) PolyglotContextImpl.requireContext().getContext(language).getLanguageInstance().spi;
        }
    }

    private static final class MultiContextSupplier extends ContextReference<Object> {

        final PolyglotLanguage language;

        MultiContextSupplier(PolyglotLanguage language) {
            this.language = language;
        }

        @Override
        public Object get() {
            assert language.assertCorrectEngine();
            return PolyglotContextImpl.requireContext().getContext(language).getContextImpl();
        }

    }

}
