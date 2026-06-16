package com.flowsense.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Represents a parsed Java method extracted from AST.
 */
@Data
@Builder
public class ParsedMethod {

    private String methodName;
    private String className;
    private String returnType;
    private String signature;            // Full signature: methodName(Type1 p1, Type2 p2)
    private String sourceCode;           // Full method body as string
    private String javadoc;              // Javadoc comment if present

    private List<String> parameters;     // ["String name", "int age"]
    private List<String> parameterTypes; // ["String", "int"]
    private List<String> annotations;    // ["@Override", "@Transactional"]
    private List<MethodCall> methodCalls; // All methods this method calls

    private boolean isPublic;
    private boolean isPrivate;
    private boolean isStatic;
    private boolean isAbstract;
    private boolean isOverride;

    private int lineStart;
    private int lineEnd;
    private int cyclomaticComplexity;    // For tech debt calculation (Phase 3)

    /**
     * Represents a single method call made within this method.
     */
    @Data
    @Builder
    public static class MethodCall {
        private String callerClass;
        private String callerMethod;
        private String calleeClass;      // May be null if unknown
        private String calleeMethod;
        private String calleeObject;     // The object variable name
        private int lineNumber;
    }
}
