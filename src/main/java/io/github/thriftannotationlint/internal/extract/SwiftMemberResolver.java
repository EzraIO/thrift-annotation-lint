package io.github.thriftannotationlint.internal.extract;

import io.github.thriftannotationlint.internal.model.ThriftAnnotations;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves reflection-equivalent members and their generic use-site views. */
public final class SwiftMemberResolver {
    private final Elements elements;
    private final Types types;
    private final MemberResolutionMetrics metrics;
    private final ExecutableTypeResolver executableTypeResolver;
    private final Map<String, List<? extends Element>> roundMembers =
            new LinkedHashMap<String, List<? extends Element>>();
    private final Map<String, List<TypeElement>> roundHierarchies =
            new LinkedHashMap<String, List<TypeElement>>();
    private final Map<String, List<ExecutableElement>> roundEffectiveMethods =
            new LinkedHashMap<String, List<ExecutableElement>>();

    public SwiftMemberResolver(Elements elements, Types types) {
        this(elements, types, null);
    }

    SwiftMemberResolver(
            Elements elements,
            Types types,
            MemberResolutionMetrics metrics) {
        this.elements = elements;
        this.types = types;
        this.metrics = metrics;
        this.executableTypeResolver = new ExecutableTypeResolver(types);
    }

    public void beginRound() {
        roundMembers.clear();
        roundHierarchies.clear();
        roundEffectiveMethods.clear();
    }

    List<ExecutableElement> effectiveMethods(
            TypeElement root,
            String annotationName,
            boolean includeParameterAnnotations) {
        String effectiveKey = root.getQualifiedName() + "\u0000" + annotationName
                + "\u0000" + includeParameterAnnotations;
        List<ExecutableElement> cachedEffective = roundEffectiveMethods.get(effectiveKey);
        if (cachedEffective != null) {
            return cachedEffective;
        }
        // ReflectionHelper starts with Class.getMethods(), skips bridge/synthetic methods, then
        // looks annotations up recursively using raw JVM parameter classes. The declaration
        // signature must therefore use erasure rather than the root's asMemberOf view.
        Map<String, ExecutableElement> candidates =
                new LinkedHashMap<String, ExecutableElement>();
        for (ExecutableElement method : allMethods(root)) {
            if (!method.getModifiers().contains(Modifier.PUBLIC)
                    || method.getModifiers().contains(Modifier.STATIC)) {
                continue;
            }
            String signature = declaredMethodSignature(method);
            if (!candidates.containsKey(signature)) {
                candidates.put(signature, method);
            }
        }

        List<TypeElement> hierarchy = hierarchy(root);
        List<ExecutableElement> result = new ArrayList<ExecutableElement>();
        for (Map.Entry<String, ExecutableElement> candidate : candidates.entrySet()) {
            ExecutableElement selected = firstAnnotatedMethod(
                    hierarchy, candidate.getKey(), annotationName);
            if (selected == null && includeParameterAnnotations) {
                selected = firstMethodWithAnnotatedParameter(
                        hierarchy, candidate.getKey(), annotationName);
            }
            if (selected != null) {
                result.add(selected);
            }
        }
        result = Collections.unmodifiableList(result);
        roundEffectiveMethods.put(effectiveKey, result);
        return result;
    }

    boolean hasAnnotatedParameter(ExecutableElement method, String annotationName) {
        for (VariableElement parameter : method.getParameters()) {
            if (ThriftAnnotations.has(parameter, annotationName)) {
                return true;
            }
        }
        return false;
    }

    List<TypeElement> hierarchy(TypeElement root) {
        String key = root.getQualifiedName().toString();
        List<TypeElement> cached = roundHierarchies.get(key);
        if (cached != null) {
            return cached;
        }
        List<TypeElement> result = new ArrayList<TypeElement>();
        collectHierarchy(root, result, new LinkedHashSet<String>());
        result = Collections.unmodifiableList(result);
        roundHierarchies.put(key, result);
        return result;
    }

    private List<ExecutableElement> allMethods(TypeElement root) {
        return ElementFilter.methodsIn(allMembers(root));
    }

    public List<? extends Element> allMembers(TypeElement root) {
        String key = root.getQualifiedName().toString();
        List<? extends Element> cached = roundMembers.get(key);
        if (cached != null) {
            return cached;
        }
        if (metrics != null) {
            metrics.memberEnumeration();
        }
        List<? extends Element> members = Collections.unmodifiableList(
                new ArrayList<Element>(elements.getAllMembers(root)));
        roundMembers.put(key, members);
        return members;
    }

    ResolvedExecutable resolveExecutable(
            DeclaredType root,
            ExecutableElement method) {
        return executableTypeResolver.resolve(root, method);
    }

    TypeMirror resolveMemberType(DeclaredType root, Element member) {
        return executableTypeResolver.resolveMemberType(root, member);
    }

    boolean isPublicInstance(Element element) {
        return element.getModifiers().contains(Modifier.PUBLIC)
                && !element.getModifiers().contains(Modifier.STATIC)
                && isPublicDeclaringType(element);
    }

    boolean requiresEnclosingInstance(TypeElement type) {
        return type.getNestingKind() == NestingKind.MEMBER
                && !type.getModifiers().contains(Modifier.STATIC);
    }

    private ExecutableElement firstAnnotatedMethod(
            List<TypeElement> hierarchy,
            String signature,
            String annotationName) {
        for (TypeElement hierarchyType : hierarchy) {
            for (ExecutableElement declaration
                    : ElementFilter.methodsIn(hierarchyType.getEnclosedElements())) {
                if (signature.equals(declaredMethodSignature(declaration))
                        && ThriftAnnotations.has(declaration, annotationName)) {
                    return declaration;
                }
            }
        }
        return null;
    }

    private ExecutableElement firstMethodWithAnnotatedParameter(
            List<TypeElement> hierarchy,
            String signature,
            String annotationName) {
        for (TypeElement hierarchyType : hierarchy) {
            for (ExecutableElement declaration
                    : ElementFilter.methodsIn(hierarchyType.getEnclosedElements())) {
                if (signature.equals(declaredMethodSignature(declaration))
                        && hasAnnotatedParameter(declaration, annotationName)) {
                    return declaration;
                }
            }
        }
        return null;
    }

    private String declaredMethodSignature(ExecutableElement method) {
        ExecutableType declared = (ExecutableType) method.asType();
        StringBuilder signature = new StringBuilder(method.getSimpleName());
        signature.append('(');
        for (TypeMirror parameter : declared.getParameterTypes()) {
            signature.append(types.erasure(parameter)).append(';');
        }
        return signature.append(')').toString();
    }

    private void collectHierarchy(
            TypeElement current,
            List<TypeElement> result,
            Set<String> visited) {
        if (current == null) {
            return;
        }
        String name = current.getQualifiedName().toString();
        if ("java.lang.Object".equals(name)
                || "java.lang.Enum".equals(name)
                || !visited.add(name)) {
            return;
        }
        result.add(current);

        TypeMirror superclass = current.getSuperclass();
        if (superclass.getKind() == TypeKind.DECLARED) {
            Element superElement = ((DeclaredType) superclass).asElement();
            if (superElement instanceof TypeElement) {
                collectHierarchy((TypeElement) superElement, result, visited);
            }
        }
        for (TypeMirror interfaceType : current.getInterfaces()) {
            Element interfaceElement = ((DeclaredType) interfaceType).asElement();
            if (interfaceElement instanceof TypeElement) {
                collectHierarchy((TypeElement) interfaceElement, result, visited);
            }
        }
    }

    private boolean isPublicDeclaringType(Element member) {
        TypeElement owner = declaringType(member);
        return owner != null && owner.getModifiers().contains(Modifier.PUBLIC);
    }

    private TypeElement declaringType(Element member) {
        Element owner = member;
        while (owner != null && !(owner instanceof TypeElement)) {
            owner = owner.getEnclosingElement();
        }
        return owner instanceof TypeElement ? (TypeElement) owner : null;
    }

    /** Use-site executable view with method-level type variables restored across javac versions. */
    static final class ResolvedExecutable {
        private final TypeMirror returnType;
        private final List<TypeMirror> parameterTypes;

        ResolvedExecutable(
                TypeMirror returnType,
                List<TypeMirror> parameterTypes) {
            this.returnType = returnType;
            this.parameterTypes = Collections.unmodifiableList(
                    new ArrayList<TypeMirror>(parameterTypes));
        }

        TypeMirror returnType() {
            return returnType;
        }

        List<TypeMirror> parameterTypes() {
            return parameterTypes;
        }
    }
}
