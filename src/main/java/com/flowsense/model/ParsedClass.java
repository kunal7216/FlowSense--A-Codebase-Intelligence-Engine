package com.flowsense.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Represents a parsed Java class extracted from AST.
 * This is the core data model for Phase 1.
 */
@Data
@Builder
public class ParsedClass {

    private String className;
    private String packageName;
    private String fullyQualifiedName;   // packageName.className
    private String filePath;
    private ClassType classType;         // CLASS, INTERFACE, ENUM, ABSTRACT
    private boolean isAbstract;

    private List<String> imports;
    private List<String> superClasses;   // extends
    private List<String> interfaces;     // implements

    private List<ParsedMethod> methods;
    private List<ParsedField> fields;
    private List<ParsedAnnotation> annotations;

    private int lineStart;
    private int lineEnd;
    private int totalLines;

    public enum ClassType {
        CLASS, INTERFACE, ABSTRACT_CLASS, ENUM, RECORD
    }

    // Convenience method
    public String getFullyQualifiedName() {
        if (packageName != null && !packageName.isBlank()) {
            return packageName + "." + className;
        }
        return className;
    }
}
