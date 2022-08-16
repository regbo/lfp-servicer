package com.austinv11.servicer;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.MirroredTypesException;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SupportedSourceVersion(SourceVersion.RELEASE_8)
@SupportedAnnotationTypes({"com.austinv11.servicer.WireService"})
public class ServicerProcessor extends AbstractProcessor {

    private static final String CODE_TEMPLATE = "" +
            "package {{{IMPLEMENTATION_TYPE_PACKAGE_NAME}}};\n" +
            "\n" +
            "import com.austinv11.servicer.ServicerRegistration;\n" +
            "import {{{GENERATED_CLASS_NAME}}};\n" +
            "\n" +
            "@Generated(value = \"{{{ANNOTATION_PROCESSOR_CLASS_NAME}}}\", date = \"{{{DATE}}}\")\n" +
            "public class {{{SIMPLE_NAME}}} implements ServicerRegistration<{{{SERVICE_TYPE_CLASS_NAME}}}> {\n" +
            "\n" +
            "    @Override\n" +
            "    public Class<X> serviceType() {\n" +
            "        return {{{SERVICE_TYPE_CLASS_NAME}}}.class;\n" +
            "    }\n" +
            "\t\n" +
            "    @Override\n" +
            "    public Class<? extends X> implementationType() {\n" +
            "        return {{{IMPLEMENTATION_TYPE_CLASS_NAME}}}.class;\n" +
            "    }\n" +
            "}\n" +
            "";

    private Types typeUtils;
    private Elements elements;
    private Filer filer;
    private Messager messager;
    private final Map<String, Services> services = new HashMap<>();

    public ServicerProcessor() {
    } // Required

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        typeUtils = processingEnv.getTypeUtils();
        elements = processingEnv.getElementUtils();
        filer = processingEnv.getFiler();
        messager = processingEnv.getMessager();
    }

    @Override
    public synchronized boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.errorRaised()) return false;
        List<? extends TypeElement> additionalAnnotations = getAdditionalAnnotations(roundEnv).collect(Collectors.toList());
        Stream<? extends Element> annotatedStream = roundEnv.getElementsAnnotatedWith(WireService.class).stream();
        annotatedStream = Stream.concat(annotatedStream, additionalAnnotations.stream().flatMap(annotation -> roundEnv.getElementsAnnotatedWith(annotation).stream()));
        annotatedStream = annotatedStream.distinct();
        //Handle @WireService
        Iterator<? extends Element> annotatedIter = annotatedStream.iterator();
        while (annotatedIter.hasNext()) {
            Element annotated = annotatedIter.next();
            if (annotated.getKind() == ElementKind.CLASS) {
                WireService[] serviceAnnotations = annotated.getAnnotationsByType(WireService.class);
                Set<String> serviceNames = Stream.of(serviceAnnotations).flatMap(service -> {
                    try {
                        return Stream.of(service.value()).map(Class::getCanonicalName);
                    } catch (MirroredTypesException e) {
                        List<? extends TypeMirror> typeMirrors = e.getTypeMirrors();
                        if (typeMirrors == null)
                            return Stream.empty();
                        return typeMirrors.stream().map(Object::toString);
                    }
                }).collect(Collectors.toSet());
                List<TypeMirror> matchingAdditionalAnnotations = additionalAnnotations.stream().map(TypeElement::asType).filter(typeMirror -> {
                    return annotated.getAnnotationMirrors().stream().map(AnnotationMirror::getAnnotationType).anyMatch(typeMirror::equals);
                }).collect(Collectors.toList());
                if (serviceNames.isEmpty() || !matchingAdditionalAnnotations.isEmpty())
                    serviceNames.add(((TypeElement) annotated).asType().toString());
                for (String serviceName : serviceNames)
                    services.computeIfAbsent(serviceName, nil -> new Services()).add(annotated);
            }
        }

        messager.printMessage(Diagnostic.Kind.NOTE, "Found " + services.size() + " services!\n");

        if (!roundEnv.processingOver())  // Only process at end
            return true;

        services.forEach((k, s) -> {
            String serviceLocation = "META-INF/services" + "/" + k;

            List<String> oldServices = new ArrayList<>();

            try {
                // If the file has already been created, we must first call getResource to allow for overwriting
                FileObject fo = filer.getResource(StandardLocation.CLASS_OUTPUT, "", serviceLocation);

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(fo.openInputStream(), StandardCharsets.UTF_8))) { // Can't check if it exists, must catch an exception from here

                    reader.lines().map(line -> {
                        int comment = line.indexOf("#");

                        return (comment >= 0 ? line.substring(0, comment) : line).trim();
                    }).filter(line -> !line.isEmpty()).forEach(oldServices::add);
                }

                fo.delete();
            } catch (Throwable e) {
                messager.printMessage(Diagnostic.Kind.NOTE, serviceLocation + " does not yet exist!\n");
            }

            try {
                FileObject fo = filer.createResource(StandardLocation.CLASS_OUTPUT, "", serviceLocation, s.getElements(elements));
                try (OutputStreamWriter w = new OutputStreamWriter(fo.openOutputStream())) {
                    for (String oldService : oldServices) {
                        w.append(oldService).append("\n");
                    }
                    for (String impl : s.getImpls()) {
                        messager.printMessage(Diagnostic.Kind.NOTE, "Setting up " + impl + " for use as a " + k + " implementation!\n");
                        w.append(impl).append("\n");
                    }
                }
            } catch (Throwable e) {
                messager.printMessage(Diagnostic.Kind.NOTE, "Error caught attempting to process services.\n");
            }
            try {
                TypeElement serviceTypeElement = elements.getTypeElement(k);
                for (TypeElement implementationTypeElement : s.getElements(elements))
                    try {
                        writeServicerRegistration(filer, serviceTypeElement, implementationTypeElement);
                    } catch (Throwable e) {
                        String message = String.format("Error caught attempting to process service registration. serviceType:%s implementationType:%s", serviceTypeElement.asType(), implementationTypeElement.asType());
                        messager.printMessage(Diagnostic.Kind.NOTE, message + "\n");
                    }
            } catch (Throwable e) {
                messager.printMessage(Diagnostic.Kind.NOTE, "Error caught attempting to process service registrations.\n");
            }
        });
        return true;
    }


    @Override
    public SourceVersion getSupportedSourceVersion() {
        return super.getSupportedSourceVersion();
    }


    private Stream<? extends TypeElement> getAdditionalAnnotations(RoundEnvironment roundEnv) {
        Set<TypeMirror> visitedTypeMirrors = new HashSet<>();
        return roundEnv.getElementsAnnotatedWith(WireService.class).stream().flatMap(annotated -> {
            return getAdditionalAnnotations(roundEnv, annotated, visitedTypeMirrors);
        });
    }

    private Stream<? extends TypeElement> getAdditionalAnnotations(RoundEnvironment roundEnv, Element element, Set<TypeMirror> visitedTypeMirrors) {
        if (element.getKind() != ElementKind.ANNOTATION_TYPE || !(element instanceof TypeElement))
            return Stream.empty();
        TypeElement typeElement = (TypeElement) element;
        if (!visitedTypeMirrors.add(typeElement.asType()))
            return Stream.empty();
        Stream<? extends TypeElement> elStream = roundEnv.getElementsAnnotatedWith(typeElement).stream().flatMap(annotated -> getAdditionalAnnotations(roundEnv, annotated, visitedTypeMirrors));
        elStream = Stream.concat(Stream.of(typeElement), elStream);
        return elStream;
    }

    private void writeServicerRegistration(Filer filer, TypeElement serviceTypeElement, TypeElement implementationTypeElement) throws IOException {
        String code = CODE_TEMPLATE;
        String implementationTypePackageName = getPackageName(implementationTypeElement);
        String simpleName = serviceTypeElement.getSimpleName().toString() + ServicerRegistration.class.getSimpleName();
        {
            Map<String, String> tokenMap = new HashMap<>();
            tokenMap.put("IMPLEMENTATION_TYPE_PACKAGE_NAME", implementationTypePackageName);
            tokenMap.put("GENERATED_CLASS_NAME", getJavaVersion() >= 9 ? "javax.annotation.processing.Generated"
                    : "javax.annotation.Generated");
            tokenMap.put("DATE", new SimpleDateFormat("yyyy-MM-dd'T'HH:mmZ").format(new Date()));
            tokenMap.put("ANNOTATION_PROCESSOR_CLASS_NAME", this.getClass().getName());
            tokenMap.put("SIMPLE_NAME", simpleName);
            tokenMap.put("SERVICE_TYPE_CLASS_NAME", serviceTypeElement.asType().toString());
            tokenMap.put("IMPLEMENTATION_TYPE_CLASS_NAME", implementationTypeElement.asType().toString());
            for (Map.Entry<String, String> ent : tokenMap.entrySet()) {
                String find = String.format("{{{%s}}}", ent.getKey());
                String replace = ent.getValue();
                code = code.replaceAll(Pattern.quote(find), replace);
            }
        }
        FileObject sourceFile = filer.getResource(StandardLocation.SOURCE_OUTPUT, implementationTypePackageName, simpleName + ".java");
        Path path = Paths.get(sourceFile.toUri());
        try (Writer writer = Files.exists(path) ? Files.newBufferedWriter(path)
                : filer.createSourceFile(implementationTypePackageName + "." + simpleName, implementationTypeElement).openWriter()) {
            writer.write(code);
        }
    }

    private String getPackageName(Element element) {
        Element currentElement = element;
        while (currentElement != null) {
            if (currentElement.getKind() == ElementKind.PACKAGE && currentElement instanceof PackageElement)
                return ((PackageElement) currentElement).getQualifiedName().toString();
            currentElement = currentElement.getEnclosingElement();
        }
        return null;
    }

    private static int getJavaVersion() {
        try {
            return Integer.parseInt(System.getProperty("java.specification.version"));
        } catch (NumberFormatException pE) {
            return 8;
        }
    }

    private static class Services {
        private final Set<String> impls = new HashSet<>();


        void add(Element annotated) {
            impls.add(annotated.asType().toString());
        }

        Collection<String> getImpls() {
            return impls;
        }

        TypeElement[] getElements(Elements elements) {
            return impls.stream().map(elements::getTypeElement).toArray(TypeElement[]::new);
        }
    }
}