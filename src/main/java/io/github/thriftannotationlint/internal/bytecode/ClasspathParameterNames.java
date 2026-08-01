package io.github.thriftannotationlint.internal.bytecode;

import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads and caches classpath metadata used by Swift's Paranamer integration. */
public final class ClasspathParameterNames {
    private static final int MAX_CACHED_CLASSES = 128;
    private static final long CLASS_LOOKUP_BASE_WEIGHT = 128L;
    private static final long LOOKUP_RESULT_BASE_WEIGHT = 48L;
    private static final long LIST_BASE_WEIGHT = 32L;
    private static final long REFERENCE_WEIGHT = 8L;
    private static final long STRING_BASE_WEIGHT = 40L;
    private static final long UTF16_BYTES_PER_CHARACTER = 2L;
    private static final long MAX_CACHED_WEIGHT_BYTES = 4L * 1024L * 1024L;

    private final Filer filer;
    private final Elements elements;
    private final JvmDescriptorEncoder descriptorEncoder;
    private final ClassFileParameterNameParser parser;
    private final Map<String, ClassLookup> cache =
            new LinkedHashMap<String, ClassLookup>(16, 0.75f, true);
    private long cachedWeightBytes;

    public ClasspathParameterNames(ProcessingEnvironment environment) {
        this(
                environment.getFiler(),
                environment.getElementUtils(),
                environment.getTypeUtils(),
                new ClassFileParameterNameParser());
    }

    ClasspathParameterNames(
            Filer filer,
            Elements elements,
            Types types,
            ClassFileParameterNameParser parser) {
        this.filer = filer;
        this.elements = elements;
        this.descriptorEncoder = new JvmDescriptorEncoder(elements, types);
        this.parser = parser;
    }

    public LookupResult find(ExecutableElement executable) {
        TypeElement owner = declaringType(executable);
        if (owner == null) {
            return LookupResult.absent();
        }
        String binaryName = elements.getBinaryName(owner).toString();
        ClassLookup classLookup = cache.get(binaryName);
        if (classLookup == null) {
            classLookup = readClass(binaryName);
            cache(binaryName, classLookup);
        }
        String methodName = executable.getKind().name().equals("CONSTRUCTOR")
                ? "<init>"
                : executable.getSimpleName().toString();
        return classLookup.find(
                methodName + "\u0000" + descriptorEncoder.parameterDescriptor(executable));
    }

    private ClassLookup readClass(String binaryName) {
        String resourceName = binaryName.replace('.', '/') + ".class";
        InputStream input;
        try {
            FileObject resource = filer.getResource(
                    StandardLocation.CLASS_PATH,
                    "",
                    resourceName);
            input = resource.openInputStream();
        }
        catch (IOException ignored) {
            return ClassLookup.invalid(
                    "class bytes are unavailable from the annotation processor CLASS_PATH; "
                            + "module-path-only model dependencies must expose complete "
                            + "annotation-provided parameter names or be placed on CLASS_PATH");
        }
        catch (RuntimeException ignored) {
            return ClassLookup.invalid(
                    "the compiler file manager rejected access to class bytes on CLASS_PATH; "
                            + "module-path-only model dependencies must expose complete "
                            + "annotation-provided parameter names or be placed on CLASS_PATH");
        }
        try {
            return ClassLookup.valid(parser.parse(input));
        }
        catch (IOException ignored) {
            return ClassLookup.invalid(
                    "class bytes are malformed or exceed ThriftAnnotationLint's parser safety limits");
        }
        finally {
            try {
                input.close();
            }
            catch (IOException ignored) {
                // A failed close does not change a completed class-file lookup.
            }
        }
    }

    private void cache(String binaryName, ClassLookup classLookup) {
        long weight = classLookup.estimatedWeight(binaryName);
        if (weight > MAX_CACHED_WEIGHT_BYTES) {
            return;
        }
        ClassLookup previous = cache.put(binaryName, classLookup);
        if (previous != null) {
            cachedWeightBytes -= previous.estimatedWeight(binaryName);
        }
        cachedWeightBytes += weight;
        while (cache.size() > MAX_CACHED_CLASSES
                || cachedWeightBytes > MAX_CACHED_WEIGHT_BYTES) {
            Iterator<Map.Entry<String, ClassLookup>> entries = cache.entrySet().iterator();
            if (!entries.hasNext()) {
                cachedWeightBytes = 0;
                return;
            }
            Map.Entry<String, ClassLookup> eldest = entries.next();
            cachedWeightBytes -= eldest.getValue().estimatedWeight(eldest.getKey());
            entries.remove();
        }
    }

    private TypeElement declaringType(Element element) {
        Element current = element;
        while (current != null && !(current instanceof TypeElement)) {
            current = current.getEnclosingElement();
        }
        return current instanceof TypeElement ? (TypeElement) current : null;
    }

    public static final class LookupResult {
        private final List<String> names;
        private final String failure;

        private LookupResult(List<String> names, String failure) {
            this.names = names == null
                    ? null
                    : Collections.unmodifiableList(new ArrayList<String>(names));
            this.failure = failure;
        }

        static LookupResult found(List<String> names) {
            return new LookupResult(names, null);
        }

        static LookupResult absent() {
            return new LookupResult(null, null);
        }

        static LookupResult invalid(String failure) {
            return new LookupResult(null, failure);
        }

        public boolean isFound() {
            return names != null;
        }

        public boolean isInvalid() {
            return failure != null;
        }

        public List<String> names() {
            return names == null ? null : new ArrayList<String>(names);
        }

        public String failure() {
            return failure;
        }

        long estimatedWeight() {
            long weight = LOOKUP_RESULT_BASE_WEIGHT;
            if (failure != null) {
                weight += stringWeight(failure);
            }
            if (names != null) {
                weight += LIST_BASE_WEIGHT + REFERENCE_WEIGHT * names.size();
                for (String name : names) {
                    weight += stringWeight(name);
                }
            }
            return weight;
        }
    }

    private static final class ClassLookup {
        private final ClassFileParameterNameParser.ParsedClass parsedClass;
        private final String failure;

        private ClassLookup(
                ClassFileParameterNameParser.ParsedClass parsedClass,
                String failure) {
            this.parsedClass = parsedClass;
            this.failure = failure;
        }

        static ClassLookup valid(ClassFileParameterNameParser.ParsedClass parsedClass) {
            return new ClassLookup(parsedClass, null);
        }

        static ClassLookup invalid(String failure) {
            return new ClassLookup(null, failure);
        }

        LookupResult find(String key) {
            if (failure != null) {
                return LookupResult.invalid(failure);
            }
            ClassFileParameterNameParser.MethodLookup lookup = parsedClass.find(key);
            if (lookup.isInvalid()) {
                return LookupResult.invalid(lookup.failure());
            }
            return lookup.isFound()
                    ? LookupResult.found(lookup.names())
                    : LookupResult.absent();
        }

        long estimatedWeight(String binaryName) {
            if (parsedClass != null) {
                return parsedClass.estimatedWeight(binaryName);
            }
            return CLASS_LOOKUP_BASE_WEIGHT
                    + stringWeight(binaryName) + stringWeight(failure);
        }
    }

    private static long stringWeight(String value) {
        return value == null
                ? 0
                : STRING_BASE_WEIGHT + UTF16_BYTES_PER_CHARACTER * value.length();
    }
}
