package com.zee.utils;

import org.apache.commons.lang3.StringUtils;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;

public class Logger {
    private Messager msg;
    private String tag = "ZeeProcessor: ";

    public Logger(Messager messager) {
        msg = messager;
    }

    public void info(CharSequence info) {
        if (StringUtils.isNotEmpty(info)) {
            msg.printMessage(Diagnostic.Kind.NOTE, tag + info);
        }
    }

    public void error(CharSequence error) {
        if (StringUtils.isNotEmpty(error)) {
            msg.printMessage(Diagnostic.Kind.ERROR, tag + " an error, [ " + error + " ]");
        }
    }

    public void error(Throwable error) {
        if (null != error) {
            msg.printMessage(Diagnostic.Kind.ERROR, tag + "An error, [" + error.getMessage() + "]" + "\n" + formatStackTrace(error.getStackTrace()));
        }
    }

    public void warning(CharSequence warning) {
        if (StringUtils.isNotEmpty(warning)) {
            msg.printMessage(Diagnostic.Kind.WARNING, tag + warning);
        }
    }

    private String formatStackTrace(StackTraceElement[] stackTrace) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : stackTrace) {
            sb.append("    at ").append(element.toString());
            sb.append("\n");
        }
        return sb.toString();
    }
}
