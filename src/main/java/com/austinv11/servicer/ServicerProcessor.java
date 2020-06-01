package com.austinv11.servicer;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@SupportedSourceVersion(SourceVersion.RELEASE_8)
@SupportedAnnotationTypes({
        "com.austinv11.servicer.WireService"
})
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
        if (roundEnv.errorRaised())
            return false;

        //Handle @WireService
        for (Element annotated : roundEnv.getElementsAnnotatedWith(WireService.class)) {
            if (annotated.getKind() == ElementKind.CLASS) {
                WireService[] serviceAnnotations = annotated.getAnnotationsByType(WireService.class);
                for (WireService service : serviceAnnotations) {
                    String serviceName;
                    try {
                        serviceName = service.value().getCanonicalName();
                    } catch (MirroredTypeException e) {
                        serviceName = e.getTypeMirror().toString(); //Yeah, apparently this is the solution you're supposed to use
                    }
                    services.computeIfAbsent(serviceName, name -> new Services()).add(annotated);
                }
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

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(fo.openInputStream(),
                        StandardCharsets.UTF_8))) { // Can't check if it exists, must catch an exception from here

                    reader.lines()
                            .map(line -> {
                                int comment = line.indexOf("#");

                                return (comment >= 0 ? line.substring(0, comment) : line).trim();
                            })
                            .filter(line -> !line.isEmpty())
                            .forEach(oldServices::add);
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