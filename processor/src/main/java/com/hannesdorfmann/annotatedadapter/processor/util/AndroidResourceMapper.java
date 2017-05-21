package com.hannesdorfmann.annotatedadapter.processor.util;

import com.hannesdorfmann.annotatedadapter.annotation.ViewField;
import com.hannesdorfmann.annotatedadapter.annotation.ViewType;
import com.sun.source.tree.ClassTree;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeScanner;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.lang.annotation.Annotation;
import java.util.*;

/**
 * Heavily based on https://github.com/JakeWharton/butterknife/tree/master/butterknife-compiler/src/main/java/butterknife/compiler
 */
public class AndroidResourceMapper {

    private final Elements elementUtils;
    private final Types typeUtils;
    private Trees trees;


    public AndroidResourceMapper(ProcessingEnvironment env) {
        elementUtils = env.getElementUtils();
        typeUtils = env.getTypeUtils();
        try {
            trees = Trees.instance(env);
        } catch (IllegalArgumentException ignored) {
            trees = null;
        }
    }

    public Id getId(Element element, int id) {

        QualifiedId qualifiedId = elementToQualifiedId(element, id);

        if (symbols.get(qualifiedId) == null) {
            symbols.put(qualifiedId, new Id(qualifiedId.id));
        }
        return symbols.get(qualifiedId);
    }

    private QualifiedId elementToQualifiedId(Element element, int id) {
        return new QualifiedId(elementUtils.getPackageOf(element).getQualifiedName().toString(), id);
    }

    /**
     * Represents an ID of an Android resource.
     */
    public final static class Id {
        public final int value;
        public final String code;
        final boolean qualifed;

        Id(int value) {
            this.value = value;
            this.code = String.valueOf(value);
            this.qualifed = false;
        }

        Id(int value, String className, String resourceName) {
            this.value = value;
            this.code = className + "." + resourceName;
            this.qualifed = true;
        }

        @Override public boolean equals(Object o) {
            return o instanceof Id && value == ((Id) o).value;
        }

        @Override public int hashCode() {
            return value;
        }

        @Override public String toString() {
            throw new UnsupportedOperationException("Please use value or code explicitly");
        }
    }

    public final static class QualifiedId {
        final String packageName;
        final int id;

        QualifiedId(String packageName, int id) {
            this.packageName = packageName;
            this.id = id;
        }

        @Override public String toString() {
            return "QualifiedId{packageName='" + packageName + "', id=" + id + '}';
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof QualifiedId)) return false;
            QualifiedId other = (QualifiedId) o;
            return id == other.id
                    && packageName.equals(other.packageName);
        }

        @Override public int hashCode() {
            int result = packageName.hashCode();
            result = 31 * result + id;
            return result;
        }
    }


    private static final List<String> SUPPORTED_TYPES = Arrays.asList(
            "array", "attr", "bool", "color", "dimen", "drawable", "id", "integer", "string", "layout"
    );


    private final Map<QualifiedId, Id> symbols = new LinkedHashMap<QualifiedId, Id>();

    public void scanForRClasses(RoundEnvironment env) {
        if (trees == null) return;

        RClassScanner scanner = new RClassScanner();

        for (Class<? extends Annotation> annotation : getSupportedAnnotations()) {
            for (Element element : env.getElementsAnnotatedWith(annotation)) {
                JCTree tree = (JCTree) trees.getTree(element, getMirror(element, annotation));
                if (tree != null) { // tree can be null if the references are compiled types and not source
                    String respectivePackageName =
                            elementUtils.getPackageOf(element).getQualifiedName().toString();
                    scanner.setCurrentPackageName(respectivePackageName);
                    tree.accept(scanner);
                }
            }
        }

        for (Map.Entry<String, Set<String>> packageNameToRClassSet : scanner.getRClasses().entrySet()) {
            String respectivePackageName = packageNameToRClassSet.getKey();
            for (String rClass : packageNameToRClassSet.getValue()) {
                parseRClass(respectivePackageName, rClass);
            }
        }
    }

    private void parseRClass(String respectivePackageName, String rClass) {
        Element element;

        try {
            element = elementUtils.getTypeElement(rClass);
        } catch (MirroredTypeException mte) {
            element = typeUtils.asElement(mte.getTypeMirror());
        }

        JCTree tree = (JCTree) trees.getTree(element);
        if (tree != null) { // tree can be null if the references are compiled types and not source
            IdScanner idScanner = new IdScanner(symbols, elementUtils.getPackageOf(element)
                    .getQualifiedName().toString(), respectivePackageName);
            tree.accept(idScanner);
        } else {
            parseCompiledR(respectivePackageName, (TypeElement) element);
        }
    }

    private void parseCompiledR(String respectivePackageName, TypeElement rClass) {
        for (Element element : rClass.getEnclosedElements()) {
            String innerClassName = element.getSimpleName().toString();
            if (SUPPORTED_TYPES.contains(innerClassName)) {
                for (Element enclosedElement : element.getEnclosedElements()) {
                    if (enclosedElement instanceof VariableElement) {
                        VariableElement variableElement = (VariableElement) enclosedElement;
                        Object value = variableElement.getConstantValue();

                        if (value instanceof Integer) {
                            int id = (Integer) value;
                            String rClassName = elementUtils.getPackageOf(variableElement).toString() + ".R."+innerClassName;
                            String resourceName = variableElement.getSimpleName().toString();
                            QualifiedId qualifiedId = new QualifiedId(respectivePackageName, id);
                            symbols.put(qualifiedId, new Id(id, rClassName, resourceName));
                        }
                    }
                }
            }
        }
    }

    private static class RClassScanner extends TreeScanner {
        // Maps the currently evaulated rPackageName to R Classes
        private final Map<String, Set<String>> rClasses = new LinkedHashMap<String, Set<String>>();
        private String currentPackageName;

        @Override public void visitSelect(JCTree.JCFieldAccess jcFieldAccess) {
            Symbol symbol = jcFieldAccess.sym;
            if (symbol != null
                    && symbol.getEnclosingElement() != null
                    && symbol.getEnclosingElement().getEnclosingElement() != null
                    && symbol.getEnclosingElement().getEnclosingElement().enclClass() != null) {
                Set<String> rClassSet = rClasses.get(currentPackageName);
                if (rClassSet == null) {
                    rClassSet = new HashSet<String>();
                    rClasses.put(currentPackageName, rClassSet);
                }
                rClassSet.add(symbol.getEnclosingElement().getEnclosingElement().enclClass().className());
            }
        }

        Map<String, Set<String>> getRClasses() {
            return rClasses;
        }

        void setCurrentPackageName(String respectivePackageName) {
            this.currentPackageName = respectivePackageName;
        }
    }

    private static class IdScanner extends TreeScanner {
        private final Map<QualifiedId, Id> ids;
        private final String rPackageName;
        private final String respectivePackageName;

        IdScanner(Map<QualifiedId, Id> ids, String rPackageName, String respectivePackageName) {
            this.ids = ids;
            this.rPackageName = rPackageName;
            this.respectivePackageName = respectivePackageName;
        }

        @Override public void visitClassDef(JCTree.JCClassDecl jcClassDecl) {
            for (JCTree tree : jcClassDecl.defs) {
                if (tree instanceof ClassTree) {
                    ClassTree classTree = (ClassTree) tree;
                    String className = classTree.getSimpleName().toString();
                    if (SUPPORTED_TYPES.contains(className)) {
                        String rClassName = rPackageName + ".R." + className;
                        VarScanner scanner = new VarScanner(ids, rClassName, respectivePackageName);
                        ((JCTree) classTree).accept(scanner);
                    }
                }
            }
        }
    }

    private static class VarScanner extends TreeScanner {
        private final Map<QualifiedId, Id> ids;
        private final String className;
        private final String respectivePackageName;

        private VarScanner(Map<QualifiedId, Id> ids, String className,
                           String respectivePackageName) {
            this.ids = ids;
            this.className = className;
            this.respectivePackageName = respectivePackageName;
        }

        @Override public void visitVarDef(JCTree.JCVariableDecl jcVariableDecl) {
            if ("int".equals(jcVariableDecl.getType().toString())) {
                int id = Integer.valueOf(jcVariableDecl.getInitializer().toString());
                String resourceName = jcVariableDecl.getName().toString();
                QualifiedId qualifiedId = new QualifiedId(respectivePackageName, id);
                ids.put(qualifiedId, new Id(id, className, resourceName));
            }
        }
    }

    private Set<Class<? extends Annotation>> getSupportedAnnotations() {
        Set<Class<? extends Annotation>> annotations = new LinkedHashSet<Class<? extends Annotation>>();

        annotations.add(ViewField.class);
        annotations.add(ViewType.class);


        return annotations;
    }

    private static AnnotationMirror getMirror(Element element,
                                              Class<? extends Annotation> annotation) {
        for (AnnotationMirror annotationMirror : element.getAnnotationMirrors()) {
            if (annotationMirror.getAnnotationType().toString().equals(annotation.getCanonicalName())) {
                return annotationMirror;
            }
        }
        return null;
    }
}
