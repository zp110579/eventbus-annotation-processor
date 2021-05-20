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
import com.zee.annotation.RunApplicationOnCreate;

import org.apache.commons.collections4.MapUtils;

import com.zee.utils.Common;
import com.zee.utils.Logger;
import com.zee.utils.RunOnApplicationOnCreateUtil;

import java.lang.annotation.Annotation;
import java.util.Arrays;
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
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 *
 */
@SupportedOptions("moduleName")
@AutoService(Processor.class)
public class RunOnApplicationOnCreateProcessor extends AbstractProcessor {
    private RunOnApplicationOnCreateUtil mRouterInfoMakeUtil;
    private Logger logger;
    //获得模块名称
    private String moduleName;
    public static final String KEY_MODULE_NAME = "moduleName";
    private static final String CLASSNAME = Common.COMMONFILEPR;

    private boolean writerRoundDone;

    private static final List<Class<? extends Annotation>> LISTENERS = Arrays.asList(
            RunApplicationOnCreate.class

    );

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        logger = new Logger(processingEnv.getMessager());

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

            mRouterInfoMakeUtil = new RunOnApplicationOnCreateUtil(processingEnv, logger);
            String index = CLASSNAME + captureName(moduleName) + "$$RunOnApplication";

            int lastPeriod = index.lastIndexOf('.');
            String indexPackage = lastPeriod != -1 ? index.substring(0, lastPeriod) : null;

            logger.info("start animator");
            if (env.processingOver()) {
                if (!annotations.isEmpty()) {
                    messager.printMessage(Diagnostic.Kind.ERROR, "Unexpected processing state: annotations still available after processing over");
                    return false;
                }
            }
            if (annotations.isEmpty()) {
                return false;
            }
            if (writerRoundDone) {
                messager.printMessage(Diagnostic.Kind.ERROR, "Unexpected processing state: annotations still available after writing.");
            }
            mRouterInfoMakeUtil.collectSubscribers(annotations, env, messager);
            mRouterInfoMakeUtil.checkForSubscribersToSkip(messager, indexPackage);

//                createInfoIndexFile(index);
            if (!mRouterInfoMakeUtil.isEmpty()) {
                mRouterInfoMakeUtil.createInfoIndexFile(index);
            } else {
                messager.printMessage(Diagnostic.Kind.WARNING, "No @RunApplicationOnCreate annotations found");
            }
            writerRoundDone = true;
        } catch (RuntimeException e) {
            // IntelliJ does not handle exceptions nicely, so log and print a message
            e.printStackTrace();
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("******ZxRouter******");
            stringBuilder.append("kapt {");
            stringBuilder.append("useBuildCache = true ");
            stringBuilder.append("arguments { ");
            stringBuilder.append("arg(moduleName, project.getName()) ");
            stringBuilder.append(" } ");
            stringBuilder.append(" }");
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

    public static String captureName(String name) {
        char[] cs = name.toCharArray();
        cs[0] -= 32;
        return String.valueOf(cs);
    }
}
