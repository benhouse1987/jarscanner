package com.example;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.AnnotationNode; // Though not used in final visitor, good to know for alternatives
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;


import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JarAnalyzer {

    private static final int DEFAULT_PORT = 8080;

    /**
     * Extracts HTTP endpoint information from a given Java process.
     *
     * @param processDetails Details of the Java process, including JAR path and command line.
     * @return A list of {@link EndpointInfo} objects found in the JAR.
     */
    public List<EndpointInfo> extractEndpoints(JavaProcessDetails processDetails) {
        List<EndpointInfo> endpoints = new ArrayList<>();
        String jarPath = processDetails.getJarPath();
        String commandLine = processDetails.getCommandLine();
        String jarName = kurzen(jarPath); // Get a short name for the JAR

        int port = extractPort(commandLine);

        try (JarFile jarFile = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    try (InputStream classInputStream = jarFile.getInputStream(entry)) {
                        ClassReader classReader = new ClassReader(classInputStream);
                        EndpointClassVisitor classVisitor = new EndpointClassVisitor(endpoints, jarName, port);
                        classReader.accept(classVisitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    } catch (Exception e) {
                        System.err.println("Error reading class file " + entry.getName() + " in JAR " + jarPath + ": " + e.getMessage());
                        // Continue to the next class file
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error opening or reading JAR file " + jarPath + ": " + e.getMessage());
        }
        return endpoints;
    }

    /**
     * Extracts the server port from the command line arguments.
     * Looks for common port arguments like -Dserver.port, --server.port, etc.
     * Defaults to {@value #DEFAULT_PORT} if no port argument is found.
     *
     * @param commandLine The command line string of the Java process.
     * @return The extracted port or the default port.
     */
    private int extractPort(String commandLine) {
        // Regex to find port arguments like -Dserver.port=XXXX, --server.port=XXXX,
        // -Dhttp.port=XXXX, --http.port=XXXX, -Ddw.server.applicationConnectors[0].port=XXXX etc.
        // It captures the port number (XXXX).
        Pattern portPattern = Pattern.compile(
                "(?:-D|-{1,2})server\\.port=(\\d+)|" +
                "(?:-D|-{1,2})http\\.port=(\\d+)|" +
                "(?:-D|-{1,2})dw\\.server(?:\\.applicationConnectors\\[\\d+\\])?\\.port=(\\d+)" // Dropwizard
        );
        Matcher matcher = portPattern.matcher(commandLine);

        if (matcher.find()) {
            for (int i = 1; i <= matcher.groupCount(); i++) {
                if (matcher.group(i) != null) {
                    try {
                        int port = Integer.parseInt(matcher.group(i));
                        System.out.println("Found port " + port + " in command line for JAR based on: " + matcher.group(0));
                        return port;
                    } catch (NumberFormatException e) {
                        // Should not happen if regex is correct, but good to handle
                        System.err.println("Error parsing port number from command line: " + matcher.group(i));
                    }
                }
            }
        }
        System.out.println("No specific port found in command line. Defaulting to " + DEFAULT_PORT + " for JAR.");
        return DEFAULT_PORT;
    }


    /**
     * Utility to get a shorter name from a JAR path (e.g., the file name).
     * @param jarPath Full path to the JAR.
     * @return The file name of the JAR.
     */
    private String kurzen(String jarPath) {
        if (jarPath == null) return "unknown.jar";
        int lastSeparator = jarPath.lastIndexOf('/');
        if (lastSeparator == -1) {
            lastSeparator = jarPath.lastIndexOf('\\'); // For Windows paths
        }
        return (lastSeparator == -1) ? jarPath : jarPath.substring(lastSeparator + 1);
    }

    /**
     * ASM ClassVisitor to find Spring and JAX-RS annotations for HTTP endpoints.
     */
    private static class EndpointClassVisitor extends ClassVisitor {
        private final List<EndpointInfo> endpoints;
        private final String jarName;
        private final int port;
        private String classLevelBasePath = "";
        private boolean isRestController = false; // Flag for Spring @RestController
        private boolean isController = false; // Flag for Spring @Controller or JAX-RS @Path

        public EndpointClassVisitor(List<EndpointInfo> endpoints, String jarName, int port) {
            super(Opcodes.ASM9); // Use latest ASM version
            this.endpoints = endpoints;
            this.jarName = jarName;
            this.port = port;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            // Spring MVC/REST
            if ("Lorg/springframework/web/bind/annotation/RestController;".equals(descriptor)) {
                isRestController = true;
            }
            if ("Lorg/springframework/web/bind/annotation/Controller;".equals(descriptor)) {
                isController = true;
            }
            if ("Lorg/springframework/web/bind/annotation/RequestMapping;".equals(descriptor)) {
                 // This is a class-level RequestMapping
                return new PathAnnotationVisitor(extractedPath -> this.classLevelBasePath = sanitizePath(extractedPath));
            }
            // JAX-RS
            if ("Ljavax/ws/rs/Path;".equals(descriptor) || "Ljakarta/ws/rs/Path;".equals(descriptor)) {
                isController = true; // Treat JAX-RS @Path classes as controllers
                return new PathAnnotationVisitor(extractedPath -> this.classLevelBasePath = sanitizePath(extractedPath));
            }
            return super.visitAnnotation(descriptor, visible);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if (!isRestController && !isController) {
                // Only inspect methods if the class is a Controller/RestController or has a JAX-RS @Path
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
            // Pass class-level path and other necessary info to method visitor
            return new EndpointMethodVisitor(super.visitMethod(access, name, descriptor, signature, exceptions),
                                             endpoints, jarName, port, classLevelBasePath);
        }

        private String sanitizePath(String path) {
            if (path == null || path.isEmpty()) return "";
            if (!path.startsWith("/")) path = "/" + path;
            if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            return path;
        }
    }

    /**
     * ASM MethodVisitor to find method-level Spring and JAX-RS annotations.
     */
    private static class EndpointMethodVisitor extends MethodVisitor {
        private final List<EndpointInfo> endpoints;
        private final String jarName;
        private final int port;
        private final String classLevelBasePath;

        public EndpointMethodVisitor(MethodVisitor methodVisitor, List<EndpointInfo> endpoints, String jarName, int port, String classLevelBasePath) {
            super(Opcodes.ASM9, methodVisitor);
            this.endpoints = endpoints;
            this.jarName = jarName;
            this.port = port;
            this.classLevelBasePath = classLevelBasePath;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            String httpMethod = null;
            String pathSuffix = ""; // Default to empty if no specific path on method

            // Spring Annotations
            switch (descriptor) {
                case "Lorg/springframework/web/bind/annotation/GetMapping;":
                    httpMethod = "GET";
                    break;
                case "Lorg/springframework/web/bind/annotation/PostMapping;":
                    httpMethod = "POST";
                    break;
                case "Lorg/springframework/web/bind/annotation/PutMapping;":
                    httpMethod = "PUT";
                    break;
                case "Lorg/springframework/web/bind/annotation/DeleteMapping;":
                    httpMethod = "DELETE";
                    break;
                case "Lorg/springframework/web/bind/annotation/PatchMapping;":
                    httpMethod = "PATCH";
                    break;
                case "Lorg/springframework/web/bind/annotation/RequestMapping;": // Could be method level
                    // Needs to parse value/path and method attributes
                    return new SpringRequestMappingAnnotationVisitor(this::addEndpoint);
            }

            // JAX-RS Annotations
            switch (descriptor) {
                case "Ljavax/ws/rs/GET;": case "Ljakarta/ws/rs/GET;":
                    httpMethod = "GET";
                    break;
                case "Ljavax/ws/rs/POST;": case "Ljakarta/ws/rs/POST;":
                    httpMethod = "POST";
                    break;
                case "Ljavax/ws/rs/PUT;": case "Ljakarta/ws/rs/PUT;":
                    httpMethod = "PUT";
                    break;
                case "Ljavax/ws/rs/DELETE;": case "Ljakarta/ws/rs/DELETE;":
                    httpMethod = "DELETE";
                    break;
                case "Ljavax/ws/rs/HEAD;": case "Ljakarta/ws/rs/HEAD;":
                    httpMethod = "HEAD";
                    break;
                case "Ljavax/ws/rs/OPTIONS;": case "Ljakarta/ws/rs/OPTIONS;":
                    httpMethod = "OPTIONS";
                    break;
                case "Ljavax/ws/rs/Path;": case "Ljakarta/ws/rs/Path;": // Method-level JAX-RS Path
                        // The PathAnnotationVisitor's callback will set methodLevelPath when it's done.
                        return new PathAnnotationVisitor(extractedPath -> {
                            this.methodLevelPath = sanitizePath(extractedPath);
                            // Note: if an HTTP method annotation like @GET is also on this method,
                            // its visitor will be called SEPARATELY. We need to ensure
                            // that methodLevelPath is correctly used when addEndpoint is finally called.
                            // If the @Path is the LAST annotation visited, and @GET was first,
                            // this might be tricky. ASM visits annotations in order they appear in class file.
                        }); // Removed the problematic "api" parameter name
            }


            if (httpMethod != null) {
                // For simple annotations like @GetMapping (without path value) or JAX-RS @GET
                // The path comes from its 'value' or from a separate @Path (JAX-RS)
                // This part needs to be more robust. @GetMapping can have a value.
                AnnotationVisitor av = super.visitAnnotation(descriptor, visible);
                return new PathConsumingAnnotationVisitor(av, httpMethod, this::addEndpoint); // Ensured "return new"
            }

            return super.visitAnnotation(descriptor, visible);
        }
        
        private String methodLevelPath = ""; // Used by JAX-RS @Path on method

        private void addEndpoint(String httpMethod, String pathValue) {
            String methodPath = sanitizePath(pathValue);
            if (this.methodLevelPath != null && !this.methodLevelPath.isEmpty() && (pathValue == null || pathValue.isEmpty())) {
                // If JAX-RS style where @Path is separate and methodPath from @GET etc. is empty
                methodPath = this.methodLevelPath;
            }

            String fullPath = classLevelBasePath + methodPath;
            if (fullPath.isEmpty()) fullPath = "/"; // Default to root if nothing specified
            if (!fullPath.startsWith("/")) fullPath = "/" + fullPath;
            fullPath = fullPath.replaceAll("//+", "/"); // Normalize multiple slashes
            if (fullPath.length() > 1 && fullPath.endsWith("/")) {
                fullPath = fullPath.substring(0, fullPath.length() - 1);
            }

            endpoints.add(new EndpointInfo(jarName, httpMethod, fullPath, port));
            System.out.println("Found endpoint: " + httpMethod + " " + fullPath + " (Port: " + port + ", JAR: " + jarName + ")");
            this.methodLevelPath = ""; // Reset for next method or annotation
        }


        private String sanitizePath(String path) {
            if (path == null || path.isEmpty()) return "";
            if (!path.startsWith("/")) path = "/" + path;
            // Don't remove trailing slash here, combine then normalize
            return path;
        }
    }

    /**
     * Annotation visitor for annotations that primarily define a path (e.g., @RequestMapping, @Path).
     * It extracts the 'value' or 'path' attribute.
     */
    private static class PathAnnotationVisitor extends AnnotationVisitor {
        private PathValueCallback callback;
        private String pathValue = ""; // Default to empty

        public PathAnnotationVisitor(PathValueCallback callback) {
            super(Opcodes.ASM9);
            this.callback = callback;
        }

        @Override
        public void visit(String name, Object value) {
            if (("value".equals(name) || "path".equals(name)) && value instanceof String) {
                this.pathValue = (String) value;
            } else if (("value".equals(name) || "path".equals(name)) && value instanceof String[]) {
                // If multiple paths are defined, take the first one for simplicity
                String[] paths = (String[]) value;
                if (paths.length > 0) {
                    this.pathValue = paths[0];
                }
            }
            super.visit(name, value);
        }
        
        @Override
        public AnnotationVisitor visitArray(String name) {
            if ("value".equals(name) || "path".equals(name)) {
                return new AnnotationVisitor(Opcodes.ASM9, super.visitArray(name)) {
                    @Override
                    public void visit(String arrayName, Object arrayValue) {
                        if (arrayValue instanceof String) {
                            if (pathValue.isEmpty()) { // Take the first one
                                pathValue = (String) arrayValue;
                            }
                        }
                        super.visit(arrayName, arrayValue);
                    }
                     @Override
                    public void visitEnd() {
                        // Array processing finished
                        super.visitEnd();
                    }
                };
            }
            return super.visitArray(name);
        }


        @Override
        public void visitEnd() {
            callback.onPathExtracted(this.pathValue);
            super.visitEnd();
        }
        
        public String getPathValue() {
            return pathValue;
        }

        interface PathValueCallback {
            void onPathExtracted(String pathValue);
        }
    }
    
    /**
     * Specific visitor for Spring's @RequestMapping, which can define path, method, etc.
     */
    private static class SpringRequestMappingAnnotationVisitor extends AnnotationVisitor {
        private String path = "";
        private String[] methods = {};
        private MethodEndpointConsumer consumer;

        public SpringRequestMappingAnnotationVisitor(MethodEndpointConsumer consumer) {
            super(Opcodes.ASM9);
            this.consumer = consumer;
        }

        @Override
        public void visit(String name, Object value) {
            if (("value".equals(name) || "path".equals(name))) {
                if (value instanceof String) {
                    this.path = (String) value;
                } else if (value instanceof String[]) {
                    if (((String[])value).length > 0) this.path = ((String[])value)[0]; // Take first
                }
            }
            super.visit(name, value);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            if ("method".equals(name)) {
                return new AnnotationVisitor(Opcodes.ASM9, super.visitArray(name)) {
                    List<String> foundMethods = new ArrayList<>();
                    @Override
                    public void visitEnum(String enumName, String enumDesc, String enumValue) {
                        // enumDesc will be like "Lorg/springframework/web/bind/annotation/RequestMethod;"
                        // enumValue will be "GET", "POST", etc.
                        foundMethods.add(enumValue);
                        super.visitEnum(enumName, enumDesc, enumValue);
                    }
                    @Override
                    public void visitEnd() {
                        methods = foundMethods.toArray(new String[0]);
                        super.visitEnd();
                    }
                };
            }
             if (("value".equals(name) || "path".equals(name))) {
                return new AnnotationVisitor(Opcodes.ASM9, super.visitArray(name)) {
                    @Override
                    public void visit(String arrName, Object arrValue) {
                        if (arrValue instanceof String) {
                            if (path.isEmpty()) path = (String) arrValue; // Take first
                        }
                        super.visit(arrName, arrValue);
                    }
                };
            }
            return super.visitArray(name);
        }

        @Override
        public void visitEnd() {
            if (methods.length > 0) {
                for (String method : methods) {
                    consumer.consume(method, path);
                }
            } else {
                // If no method specified in @RequestMapping, it defaults to all/any.
                // For now, let's report as "ANY" or skip if we want only specific ones.
                // Or, if path is specified, maybe assume GET? Let's stick to explicit or "ANY".
                 consumer.consume("ANY", path); // Or make this configurable / more specific
            }
            super.visitEnd();
        }

        interface MethodEndpointConsumer {
            void consume(String httpMethod, String path);
        }
    }

    /**
     * For annotations like @GetMapping where the path is in 'value' or 'path' attribute.
     */
    private static class PathConsumingAnnotationVisitor extends AnnotationVisitor {
        private final String httpMethodFixed;
        private final SpringRequestMappingAnnotationVisitor.MethodEndpointConsumer consumer;
        private String path = "";

        public PathConsumingAnnotationVisitor(AnnotationVisitor parent, String httpMethod, SpringRequestMappingAnnotationVisitor.MethodEndpointConsumer consumer) {
            super(Opcodes.ASM9, parent);
            this.httpMethodFixed = httpMethod;
            this.consumer = consumer;
        }

        @Override
        public void visit(String name, Object value) {
             if (("value".equals(name) || "path".equals(name))) {
                if (value instanceof String) {
                    this.path = (String) value;
                } else if (value instanceof String[]) {
                    if (((String[])value).length > 0) this.path = ((String[])value)[0];
                }
            }
            super.visit(name, value);
        }
        
        @Override
        public AnnotationVisitor visitArray(String name) {
            if (("value".equals(name) || "path".equals(name))) {
                return new AnnotationVisitor(Opcodes.ASM9, super.visitArray(name)) {
                    @Override
                    public void visit(String arrName, Object arrValue) {
                        if (arrValue instanceof String) {
                            if (path.isEmpty()) path = (String) arrValue; // Take first
                        }
                        super.visit(arrName, arrValue);
                    }
                };
            }
            return super.visitArray(name);
        }

        @Override
        public void visitEnd() {
            consumer.consume(httpMethodFixed, path);
            super.visitEnd();
        }
    }
}
