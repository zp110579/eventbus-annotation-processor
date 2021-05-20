/*
 * Copyright (C) 2012-2016 Markus Junginger, greenrobot (http://greenrobot.org)
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
package com.zee.annotationprocessor;

import com.google.auto.service.AutoService;
import com.zee.utils.Common;

import org.apache.commons.collections4.MapUtils;
import org.greenrobot.eventbus.SubscribeSimple;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.SubscribeMainThread;
import org.greenrobot.eventbus.SubscribeRunOnlyTop;
import org.greenrobot.eventbus.SubscribeTag;
import org.greenrobot.eventbus.ThreadMode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import de.greenrobot.common.ListMap;


@SupportedOptions("moduleName")
@AutoService(Processor.class)
public class EventBusProcessor extends AbstractProcessor {
    public static final String OPTION_VERBOSE = "verbose";
    public static final HashMap<String, String> eventBusHashMap = new HashMap<>();
    public static final String KEY_MODULE_NAME = "moduleName";
    private static final String CLASSNAME = Common.COMMONFILEPR;
    private String moduleName;

    /**
     * Found subscriber methods for a class (without superclasses).
     */
    private final ListMap<TypeElement, ExecutableElement> methodsByClass = new ListMap<>();
    private final Set<TypeElement> classesToSkip = new HashSet<>();

    private boolean writerRoundDone;
    private int round;
    private boolean verbose;


    private static final List<Class<? extends Annotation>> LISTENERS = Arrays.asList(
            Subscribe.class,
            SubscribeMainThread.class,
            SubscribeRunOnlyTop.class,
            SubscribeSimple.class
    );

    static {
        eventBusHashMap.put("int", "int.class");
        eventBusHashMap.put("boolean", "boolean.class");
        eventBusHashMap.put("float", "float.class");
        eventBusHashMap.put("double", "double.class");
    }


    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        Map<String, String> options = processingEnv.getOptions();
        if (MapUtils.isNotEmpty(options)) {
            moduleName = options.get(KEY_MODULE_NAME);
        }
    }


    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
        Messager messager = processingEnv.getMessager();

        try {
            String index = CLASSNAME + captureName(moduleName) + "$$EventBus";
            if (index == null) {
                messager.printMessage(Diagnostic.Kind.ERROR, "No option " + moduleName +
                        " passed to annotation processor");
                return false;
            }

            verbose = Boolean.parseBoolean(processingEnv.getOptions().get(OPTION_VERBOSE));
            int lastPeriod = index.lastIndexOf('.');
            String indexPackage = lastPeriod != -1 ? index.substring(0, lastPeriod) : null;

            round++;
            if (verbose) {
                messager.printMessage(Diagnostic.Kind.NOTE, "Processing round " + round + ", new annotations: " +
                        !annotations.isEmpty() + ", processingOver: " + env.processingOver());
            }
            if (env.processingOver()) {
                if (!annotations.isEmpty()) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                            "Unexpected processing state: annotations still available after processing over");
                    return false;
                }
            }
            if (annotations.isEmpty()) {
                return false;
            }

            if (writerRoundDone) {
                messager.printMessage(Diagnostic.Kind.ERROR, "Unexpected processing state: annotations still available after writing.");
            }
            collectSubscribers(annotations, env, messager);
            checkForSubscribersToSkip(messager, indexPackage);

            if (!methodsByClass.isEmpty()) {
                createInfoIndexFile(index);
            } else {
                messager.printMessage(Diagnostic.Kind.WARNING, "No @Subscribe annotations found");
            }
            writerRoundDone = true;
        } catch (RuntimeException e) {
            // IntelliJ does not handle exceptions nicely, so log and print a message
            e.printStackTrace();
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("******ZxEventBus******");
            stringBuilder.append("kapt {");
            stringBuilder.append("useBuildCache = true ");
            stringBuilder.append("arguments { ");
            stringBuilder.append("arg(moduleName, project.getName()) ");
            stringBuilder.append(" } ");
            stringBuilder.append(" }");
            messager.printMessage(Diagnostic.Kind.ERROR, stringBuilder.toString());
            messager.printMessage(Diagnostic.Kind.ERROR, stringBuilder.toString());
        }
        return true;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> types = new LinkedHashSet<>();
        for (Class<? extends Annotation> annotation : getSupportedAnnotations()) {
            types.add(annotation.getCanonicalName());
        }
        return types;
    }

    private Set<Class<? extends Annotation>> getSupportedAnnotations() {
        Set<Class<? extends Annotation>> annotations = new LinkedHashSet<>();
        annotations.addAll(LISTENERS);
        return annotations;
    }


    private void collectSubscribers(Set<? extends TypeElement> annotations, RoundEnvironment env, Messager messager) {
        for (TypeElement annotation : annotations) {
            Set<? extends Element> elements = env.getElementsAnnotatedWith(annotation);
            for (Element element : elements) {
                if (element instanceof ExecutableElement) {
                    ExecutableElement method = (ExecutableElement) element;
                    if (checkHasNoErrors(method, messager)) {
                        TypeElement classElement = (TypeElement) method.getEnclosingElement();
                        methodsByClass.putElement(classElement, method);
                    }
                } else {
                    messager.printMessage(Diagnostic.Kind.ERROR, "@Subscribe is only valid for methods", element);
                }
            }
        }
    }

    private boolean checkHasNoErrors(ExecutableElement element, Messager messager) {
        if (element.getModifiers().contains(Modifier.STATIC)) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Subscriber method must not be static", element);
            return false;
        }

        if (!element.getModifiers().contains(Modifier.PUBLIC)) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Subscriber method must be public", element);
            return false;
        }

        List<? extends VariableElement> parameters = ((ExecutableElement) element).getParameters();


        SubscribeMainThread subscribeMainThread = element.getAnnotation(SubscribeMainThread.class);
        SubscribeRunOnlyTop subscribeRunOnlyTop = element.getAnnotation(SubscribeRunOnlyTop.class);
        SubscribeSimple subscribeSimple = element.getAnnotation(SubscribeSimple.class);

        if (subscribeMainThread != null || subscribeRunOnlyTop != null || subscribeSimple != null) {
            if (parameters.size() > 1) {
                messager.printMessage(Diagnostic.Kind.ERROR, subscribeMainThread.toString() + " method must have exactly overpressure 1 parameter", element);
                return false;
            }
        } else {
            if (parameters.size() != 1) {
                messager.printMessage(Diagnostic.Kind.ERROR, "Subscriber method must have exactly 1 parameter", element);
                return false;
            }
        }
        return true;
    }

    /**
     * Subscriber classes should be skipped if their class or any involved event class are not visible to the index.
     */
    private void checkForSubscribersToSkip(Messager messager, String myPackage) {
        for (TypeElement skipCandidate : methodsByClass.keySet()) {
            TypeElement subscriberClass = skipCandidate;
            while (subscriberClass != null) {
                if (!isVisible(myPackage, subscriberClass)) {
                    boolean added = classesToSkip.add(skipCandidate);
                    if (added) {
                        String msg;
                        if (subscriberClass.equals(skipCandidate)) {
                            msg = "Falling back to reflection because class is not public";
                        } else {
                            msg = "Falling back to reflection because " + skipCandidate +
                                    " has a non-public super class";
                        }
                        messager.printMessage(Diagnostic.Kind.NOTE, msg, subscriberClass);
                    }
                    break;
                }
                List<ExecutableElement> methods = methodsByClass.get(subscriberClass);
                if (methods != null) {
                    for (ExecutableElement method : methods) {
                        String skipReason = null;
                        Subscribe subscribe = method.getAnnotation(Subscribe.class);

                        SubscribeMainThread subscribeMainThread = method.getAnnotation(SubscribeMainThread.class);
                        SubscribeRunOnlyTop subscribeRunOnlyTop = method.getAnnotation(SubscribeRunOnlyTop.class);
                        SubscribeSimple subscribeSimple = method.getAnnotation(SubscribeSimple.class);

                        if (subscribe != null) {
                            VariableElement param = method.getParameters().get(0);
                            TypeMirror typeMirror = getParamTypeMirror(param, messager);
                            if ((!(typeMirror instanceof DeclaredType) ||
                                    !(((DeclaredType) typeMirror).asElement() instanceof TypeElement))) {
                                skipReason = "event type cannot processed";
                            }
                            if (skipReason == null) {
                                TypeElement eventTypeElement = (TypeElement) ((DeclaredType) typeMirror).asElement();
                                if (!isVisible(myPackage, eventTypeElement)) {
                                    skipReason = "event type is not public";
                                }
                            }

                            if (skipReason != null) {
                                boolean added = classesToSkip.add(skipCandidate);
                                if (added) {
                                    String msg = "Falling back to reflection because " + skipReason;
                                    if (!subscriberClass.equals(skipCandidate)) {
                                        msg += " (found in super class for " + skipCandidate + ")";
                                    }
                                    messager.printMessage(Diagnostic.Kind.ERROR, msg, param);
                                }
                                break;
                            }
                        } else if (subscribeMainThread != null || subscribeRunOnlyTop != null || subscribeSimple != null) {
                            List<? extends VariableElement> parameters = method.getParameters();
                            Set<Modifier> eventTypeElement = method.getModifiers();
                            if (!eventTypeElement.contains(Modifier.PUBLIC) || eventTypeElement.contains(Modifier.STATIC)) {
                                skipReason = "event type is not public or static";
                                messager.printMessage(Diagnostic.Kind.NOTE, skipReason, null);
                                break;
                            }
                        }
                    }
                }
                subscriberClass = getSuperclass(subscriberClass);
            }
        }
    }

    private TypeMirror getParamTypeMirror(VariableElement param, Messager messager) {
        TypeMirror typeMirror = param.asType();
        // Check for generic type
        if (typeMirror instanceof TypeVariable) {
            TypeMirror upperBound = ((TypeVariable) typeMirror).getUpperBound();
            if (upperBound instanceof DeclaredType) {
                if (messager != null) {
                    messager.printMessage(Diagnostic.Kind.NOTE, "Using upper bound type " + upperBound +
                            " for generic parameter", param);
                }
                typeMirror = upperBound;
            }
        }
        return typeMirror;
    }

    private TypeElement getSuperclass(TypeElement type) {
        if (type.getSuperclass().getKind() == TypeKind.DECLARED) {
            TypeElement superclass = (TypeElement) processingEnv.getTypeUtils().asElement(type.getSuperclass());
            String name = superclass.getQualifiedName().toString();
            if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.")) {
                // Skip system classes, this just degrades performance
                return null;
            } else {
                return superclass;
            }
        } else {
            return null;
        }
    }

    private String getClassString(TypeElement typeElement, String myPackage) {
        PackageElement packageElement = getPackageElement(typeElement);
        String packageString = packageElement.getQualifiedName().toString();
        String className = typeElement.getQualifiedName().toString();
        if (packageString != null && !packageString.isEmpty()) {
            if (packageString.equals(myPackage)) {
                className = cutPackage(myPackage, className);
            } else if (packageString.equals("java.lang")) {
                className = typeElement.getSimpleName().toString();
            }
        }
        return className;
    }

    private String cutPackage(String paket, String className) {
        if (className.startsWith(paket + '.')) {
            // Don't use TypeElement.getSimpleName, it doesn't work for us with inner classes
            return className.substring(paket.length() + 1);
        } else {
            // Paranoia
            throw new IllegalStateException("Mismatching " + paket + " vs. " + className);
        }
    }

    private PackageElement getPackageElement(TypeElement subscriberClass) {
        Element candidate = subscriberClass.getEnclosingElement();
        while (!(candidate instanceof PackageElement)) {
            candidate = candidate.getEnclosingElement();
        }
        return (PackageElement) candidate;
    }

    private void writeCreateSubscriberMethods(BufferedWriter writer, List<ExecutableElement> methods, String callPrefix, String myPackage) throws IOException {
        for (ExecutableElement method : methods) {
            List<? extends VariableElement> parameters = method.getParameters();
            String methodName = method.getSimpleName().toString();

            Subscribe subscribe = method.getAnnotation(Subscribe.class);
            if (subscribe != null) {
                TypeMirror paramType = getParamTypeMirror(parameters.get(0), null);
                TypeElement paramElement = (TypeElement) processingEnv.getTypeUtils().asElement(paramType);
                String eventClass = getClassString(paramElement, myPackage) + ".class";
                initSubscribe(writer, callPrefix, methodName, eventClass, subscribe);
                if (verbose) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Indexed @Subscribe at " +
                            method.getEnclosingElement().getSimpleName() + "." + methodName +
                            "(" + paramElement.getSimpleName() + ")");
                }
            } else {
                SubscribeMainThread mainSubscribe = method.getAnnotation(SubscribeMainThread.class);
                SubscribeRunOnlyTop runOnlyTop = method.getAnnotation(SubscribeRunOnlyTop.class);
                SubscribeSimple subscribeSimple = method.getAnnotation(SubscribeSimple.class);
                String paramClassName = "EmptyEventBusType.class";

                if (parameters != null && parameters.size() == 1) {
                    TypeMirror paramType = getParamTypeMirror(parameters.get(0), null);
                    TypeElement paramElement = (TypeElement) processingEnv.getTypeUtils().asElement(paramType);

                    if (paramElement != null) {
                        paramClassName = getClassString(paramElement, myPackage) + ".class";
                    } else {
                        paramClassName = eventBusHashMap.get(paramType.toString());
                        if (paramClassName == null) {
                            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "method :" + methodName + " parameter type:" + paramType + " is nor support", method);
                        }
                    }

                    if (verbose) {
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Indexed @Subscribe at " +
                                method.getEnclosingElement().getSimpleName() + "." + methodName +
                                "(" + paramElement.getSimpleName() + ")");
                    }
                }

                if (mainSubscribe != null) {
                    initSubscribeMainThread(writer, callPrefix, methodName, paramClassName, mainSubscribe);
                } else if (runOnlyTop != null) {
                    initSubscriberunOnlyTop(writer, callPrefix, methodName, paramClassName, runOnlyTop);
                } else if (subscribeSimple != null) {
                    initSubscribeSimple(writer, callPrefix, methodName, paramClassName, subscribeSimple);

                }
            }

//            String eventClass = "";
//
//
//            SubscribeMainThread mainSubscribe = method.getAnnotation(SubscribeMainThread.class);
//            SubscribeRunOnlyTop runOnlyTop = method.getAnnotation(SubscribeRunOnlyTop.class);
//            if (subscribe != null) {
//                initSubscribe(writer, callPrefix, methodName, eventClass, subscribe);
//            } else if (mainSubscribe != null) {
//                initSubscribeMainThread(writer, callPrefix, methodName, eventClass, mainSubscribe);
//            } else if (runOnlyTop != null) {
//                initSubscriberunOnlyTop(writer, callPrefix, methodName, eventClass, runOnlyTop);
//            }
//            if (verbose) {
//                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Indexed @Subscribe at " +
//                        method.getEnclosingElement().getSimpleName() + "." + methodName +
//                        "(" + paramElement.getSimpleName() + ")");
//            }

        }
    }


    //SubscribeMainThread专用
    private void initSubscribeSimple(BufferedWriter writer, String callPrefix, String methodName, String paramClassName, SubscribeSimple subscribeSimple) throws IOException {
        List<String> parts = new ArrayList<>();
        parts.add(callPrefix + "(\"" + methodName + "\",");

        String lineEnd = "),";
        parts.add(paramClassName + ",");

        String tag = subscribeSimple.value();

        if (tag.equals("")) {
            tag = "\"\"";
            parts.add(tag);
        } else {
            parts.add("\"" + tag + "\"");
        }


        parts.add(lineEnd);
        writeLine(writer, 3, parts.toArray(new String[parts.size()]));
    }


    //SubscribeMainThread专用
    private void initSubscribeMainThread(BufferedWriter writer, String callPrefix, String methodName, String paramClassName, SubscribeMainThread mainsubscribe) throws IOException {
        List<String> parts = new ArrayList<>();
        parts.add(callPrefix + "(\"" + methodName + "\",");

        String lineEnd = "),";
        parts.add(paramClassName + ",");
        parts.add(mainsubscribe.priority() + ",");
        parts.add(mainsubscribe.sticky() + ",");
        String tag = mainsubscribe.tag();

        if (tag.equals("")) {
            tag = "\"\"";
            parts.add(tag);
        } else {
            parts.add("\"" + tag + "\"");
        }

        if (mainsubscribe.ignoredSubscriberTag()) {
            parts.add("," + mainsubscribe.finish());
            parts.add("," + mainsubscribe.lifo() + "");
            parts.add("," + mainsubscribe.ignoredSubscriberTag() + "");
        } else {
            if (mainsubscribe.lifo()) {
                parts.add("," + mainsubscribe.finish());
                parts.add("," + mainsubscribe.lifo() + "");
            } else {
                if (mainsubscribe.finish()) {
                    parts.add("," + mainsubscribe.finish());
                }
            }
        }

        parts.add(lineEnd);
        writeLine(writer, 3, parts.toArray(new String[parts.size()]));
    }

    private void initSubscriberunOnlyTop(BufferedWriter writer, String callPrefix, String methodName, String paramClassName, SubscribeRunOnlyTop runOnlyTop) throws IOException {
        List<String> parts = new ArrayList<>();
        parts.add(callPrefix + "(\"" + methodName + "\",");

        String lineEnd = "),";
        parts.add(paramClassName + ",");
        parts.add("ThreadMode." + runOnlyTop.threadMode().name() + ",");
        String tag = runOnlyTop.tag();

        if (tag.equals("")) {
            tag = "\"\",";
            parts.add(tag);
        } else {
            String info = "\"" + tag + "\"";
            parts.add(info);
        }
        parts.add(lineEnd);
        writeLine(writer, 3, parts.toArray(new String[parts.size()]));
    }

    private void initSubscribe(BufferedWriter writer, String callPrefix, String methodName, String eventClass, Subscribe subscribe) throws IOException {
        List<String> parts = new ArrayList<>();
        parts.add(callPrefix + "(\"" + methodName + "\",");
        String lineEnd = "),";
        if (subscribe.priority() == 0 && !subscribe.sticky()) {
            if (subscribe.threadMode() == ThreadMode.POSTING) {
                parts.add(eventClass + lineEnd);
            } else {
                parts.add(eventClass + ",");
                parts.add("ThreadMode." + subscribe.threadMode().name() + lineEnd);
            }
        } else {
            parts.add(eventClass + ",");
            parts.add("ThreadMode." + subscribe.threadMode().name() + ",");
            parts.add(subscribe.priority() + ",");
            parts.add(subscribe.sticky() + lineEnd);
        }
        writeLine(writer, 3, parts.toArray(new String[parts.size()]));
    }

    private void createInfoIndexFile(String index) {
        BufferedWriter writer = null;
        try {
            JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(index);
            int period = index.lastIndexOf('.');
            String myPackage = period > 0 ? index.substring(0, period) : null;
            String clazz = index.substring(period + 1);
            writer = new BufferedWriter(sourceFile.openWriter());
            if (myPackage != null) {
                writer.write("package " + myPackage + ";\n\n");
            }
            writer.write("import org.greenrobot.eventbus.meta.SimpleSubscriberInfo;\n");
            writer.write("import org.greenrobot.eventbus.meta.SubscriberMethodInfo;\n");
            writer.write("import org.greenrobot.eventbus.meta.SubscriberInfo;\n");
            writer.write("import org.greenrobot.eventbus.meta.EmptyEventBusType;\n");
            writer.write("import org.greenrobot.eventbus.interfaces.SubscriberInfoIndex;\n\n");
            writer.write("import org.greenrobot.eventbus.ThreadMode;\n\n");
            writer.write("import org.greenrobot.eventbus.DispenseOrder;\n\n");
            writer.write("import java.util.HashMap;\n");
            writer.write("import java.util.Map;\n\n");
            writer.write("/** This class is generated by EventBus, do not edit. */\n");
            writer.write("public class " + clazz + " implements SubscriberInfoIndex {\n");
            writer.write("    private static final Map<Class<?>, SubscriberInfo> SUBSCRIBER_INDEX;\n\n");
            writer.write("    static {\n");
            writer.write("        SUBSCRIBER_INDEX = new HashMap<Class<?>, SubscriberInfo>();\n\n");
            writeIndexLines(writer, myPackage);
            writer.write("    }\n\n");
            writer.write("    private static void putIndex(SubscriberInfo info) {\n");
            writer.write("        SUBSCRIBER_INDEX.put(info.getSubscriberClass(), info);\n");
            writer.write("    }\n\n");
            writer.write("    @Override\n");
            writer.write("    public SubscriberInfo getSubscriberInfo(Class<?> subscriberClass) {\n");
            writer.write("        SubscriberInfo info = SUBSCRIBER_INDEX.get(subscriberClass);\n");
            writer.write("        if (info != null) {\n");
            writer.write("            return info;\n");
            writer.write("        } else {\n");
            writer.write("            return null;\n");
            writer.write("        }\n");
            writer.write("    }\n");
            writer.write("}\n");
        } catch (IOException e) {
            throw new RuntimeException("Could not write source for " + index, e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    //Silent
                }
            }
        }
    }

    private void writeIndexLines(BufferedWriter writer, String myPackage) throws IOException {
        for (TypeElement subscriberTypeElement : methodsByClass.keySet()) {
            if (classesToSkip.contains(subscriberTypeElement)) {
                continue;
            }
            String subscriberClass = getClassString(subscriberTypeElement, myPackage);
            if (isVisible(myPackage, subscriberTypeElement)) {
                writeLine(writer, 2,
                        "putIndex(new SimpleSubscriberInfo(" + subscriberClass + ".class,",
                        "true,", "new SubscriberMethodInfo[] {");
                List<ExecutableElement> methods = methodsByClass.get(subscriberTypeElement);

                SubscribeTag page = subscriberTypeElement.getAnnotation(SubscribeTag.class);
                String infor = "";
                if (page != null) {
                    infor = page.tag();
                }
                writeCreateSubscriberMethods(writer, methods, "new SubscriberMethodInfo", myPackage);
                writer.write("        },\"" + infor + "\"));\n\n");
            } else {
                writer.write("        // Subscriber not visible to index: " + subscriberClass + "\n");
            }
        }
    }

    private boolean isVisible(String myPackage, TypeElement typeElement) {
        Set<Modifier> modifiers = typeElement.getModifiers();
        boolean visible;
        if (modifiers.contains(Modifier.PUBLIC)) {
            visible = true;
        } else if (modifiers.contains(Modifier.PRIVATE) || modifiers.contains(Modifier.PROTECTED)) {
            visible = false;
        } else {
            String subscriberPackage = getPackageElement(typeElement).getQualifiedName().toString();
            if (myPackage == null) {
                visible = subscriberPackage.length() == 0;
            } else {
                visible = myPackage.equals(subscriberPackage);
            }
        }
        return visible;
    }

    private void writeLine(BufferedWriter writer, int indentLevel, String... parts) throws IOException {
        writeLine(writer, indentLevel, 2, parts);
    }

    private void writeLine(BufferedWriter writer, int indentLevel, int indentLevelIncrease, String... parts)
            throws IOException {
        writeIndent(writer, indentLevel);
        int len = indentLevel * 4;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i != 0) {
                if (len + part.length() > 118) {
                    writer.write("\n");
                    if (indentLevel < 12) {
                        indentLevel += indentLevelIncrease;
                    }
                    writeIndent(writer, indentLevel);
                    len = indentLevel * 4;
                } else {
                    writer.write(" ");
                }
            }
            writer.write(part);
            len += part.length();
        }
        writer.write("\n");
    }

    private void writeIndent(BufferedWriter writer, int indentLevel) throws IOException {
        for (int i = 0; i < indentLevel; i++) {
            writer.write("    ");
        }
    }

    public static String captureName(String name) {
        char[] cs = name.toCharArray();
        cs[0] -= 32;
        return String.valueOf(cs);
    }
}
