package com.austinv11.servicer;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.MirroredTypesException;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SupportedSourceVersion(SourceVersion.RELEASE_8)
@SupportedAnnotationTypes({"com.austinv11.servicer.WireService"})
public class ServicerProcessor extends AbstractProcessor {

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
                boolean addAnnotated = serviceNames.isEmpty() || annotated.getAnnotationMirrors().stream().map(AnnotationMirror::getAnnotationType).anyMatch(annotationType -> {
                    return additionalAnnotations.stream().map(TypeElement::asType).anyMatch(annotationType::equals);
                });
                if (addAnnotated)
                    serviceNames.add(((TypeElement) annotated).asType().toString());
                for (String serviceName : serviceNames)
                    services.computeIfAbsent(serviceName, name -> new Services()).add(annotated);
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

    private static class Services {
        private final Set<String> impls = new HashSet<>();

        void add(Element annotated) {
            impls.add(annotated.asType().toString());
        }

        Collection<String> getImpls() {
            return impls;
        }

        Element[] getElements(Elements elements) {
            return impls.stream().map(elements::getTypeElement).toArray(Element[]::new);
        }
    }
}