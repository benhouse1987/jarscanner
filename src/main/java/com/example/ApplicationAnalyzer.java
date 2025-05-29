package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.objectweb.asm.*;


import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.Properties;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;
import java.io.IOException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import java.util.HashMap; // For storing servlet names and classes

public class ApplicationAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationAnalyzer.class);
    private static final int DEFAULT_PORT = 8080;

    /**
     * Extracts HTTP endpoint information from a given Java process.
     *
     * @param processDetails Details of the Java process, including application path (JAR/WAR) and command line.
     * @return A list of {@link EndpointInfo} objects found in the application.
     */
    public List<EndpointInfo> extractEndpoints(JavaProcessDetails processDetails) {
        List<EndpointInfo> endpoints = new ArrayList<>();
        String appPath = processDetails.getJarPath(); // Path to the JAR or WAR file
        String commandLine = processDetails.getCommandLine();
        String appName = kurzen(appPath); // Use the existing kurzen for a short name
        logger.debug("Analyzing application: {} for process PID: {}", appPath, processDetails.getPid());

        int port = processDetails.getPort();
        String contextPath = ""; // Initialize contextPath to empty string

        // TODO: Context path extraction might differ for WARs vs JARs, or be more reliably found in WAR metadata
        // For now, using the existing JAR-focused context path extraction.
        try (JarFile appFileForConfig = new JarFile(appPath)) { // Assuming it can be read like a JarFile for now
            String extractedCtxPath = extractContextPathFromJar(appFileForConfig, appName);
            if (extractedCtxPath != null && !extractedCtxPath.isEmpty()) {
                contextPath = extractedCtxPath.trim();
                if (!contextPath.startsWith("/")) {
                    contextPath = "/" + contextPath;
                }
                if (contextPath.endsWith("/") && contextPath.length() > 1) {
                    contextPath = contextPath.substring(0, contextPath.length() - 1);
                }
                if (contextPath.equals("/")) {
                    contextPath = "";
                }
                if (!contextPath.isEmpty()) {
                    logger.info("Using context path '{}' for application {}", contextPath, appName);
                } else {
                    logger.info("No context path configured or context path is root for application {}", appName);
                }
            } else {
                logger.info("No context path configured for application {}", appName);
            }
        } catch (IOException e) {
            logger.warn("Error opening application file {} to read context path config: {}. Proceeding without context path.", appPath, e.getMessage(), e);
            // contextPath remains ""
        }

        if (appPath.toLowerCase().endsWith(".war") && (contextPath == null || contextPath.isEmpty())) {
            String derivedContextPath = "/" + appName;
            if (derivedContextPath.toLowerCase().endsWith(".war")) {
                derivedContextPath = derivedContextPath.substring(0, derivedContextPath.length() - 4);
            }
            // Ensure it starts with a single slash and isn't just "/" if the appName was empty or just ".war"
            if (derivedContextPath.equals("/")) {
                contextPath = ""; // Treat as root if filename was effectively empty
                logger.info("WAR file {} seems to have no specific app name for context path, defaulting to root context.", appName);
            } else if (!derivedContextPath.isEmpty()) {
                contextPath = derivedContextPath;
                logger.info("No specific context path found in WAR configuration for {}. Derived context path from filename: {}", appName, contextPath);
            }
        }

        if (appPath.toLowerCase().endsWith(".jar")) {
            logger.info("Processing as JAR file: {}", appPath);
            // JAR-specific logic starts here
            try (JarFile jarFile = new JarFile(appPath)) {
                logger.trace("Successfully opened JAR file for class analysis: {}", appPath);
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(".class")) {
                        try (InputStream classInputStream = jarFile.getInputStream(entry)) {
                            ClassReader classReader = new ClassReader(classInputStream);
                            EndpointClassVisitor classVisitor = new EndpointClassVisitor(endpoints, appName, port, contextPath, entry.getName().replace("/", ".").replace(".class", ""));
                            classReader.accept(classVisitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                        } catch (Exception e) {
                            logger.warn("Error reading class file {} in JAR {}: {}. Skipping class.", entry.getName(), appPath, e.getMessage(), e);
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Error opening or reading JAR file for class analysis {}: {}", appPath, e.getMessage(), e);
            }
            // JAR-specific logic ends here

        } else if (appPath.toLowerCase().endsWith(".war")) {
            logger.info("Processing as WAR file: {}", appPath);
            // WAR-specific logic placeholder
            // For WAR files, analysis might involve:
            // 1. Finding classes in WEB-INF/classes
            // 2. Finding classes in JARs within WEB-INF/lib
            // 3. Parsing web.xml for servlet mappings and context parameters
            // 4. Potentially different context path discovery if not in spring boot style config.
            logger.warn("WAR file analysis is not fully implemented yet. Endpoints from WAR might be incomplete.");
            // Minimal WAR handling: try to read it like a JAR to find WEB-INF/classes and WEB-INF/lib/*.jar
            try (JarFile warFile = new JarFile(appPath)) {
                logger.trace("Successfully opened WAR file for analysis: {}", appPath);

                // First, parse web.xml for servlet mappings
                List<EndpointInfo> webXmlEndpoints = parseWebXml(warFile, appName, port, contextPath);
                endpoints.addAll(webXmlEndpoints);

                // Then, scan WEB-INF/classes for annotated controllers
                logger.debug("Scanning WEB-INF/classes/ for annotated endpoints in WAR: {}", appPath);
                Enumeration<JarEntry> entries = warFile.entries(); // Re-iterate or use a collected list if performance is an issue
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String entryName = entry.getName();

                    if (entryName.startsWith("WEB-INF/classes/") && entryName.endsWith(".class")) {
                        logger.trace("Found class in WEB-INF/classes/: {}", entryName);
                        try (InputStream classInputStream = warFile.getInputStream(entry)) {
                            ClassReader classReader = new ClassReader(classInputStream);
                            // Adjust class name for WEB-INF/classes structure
                            String className = entryName.substring("WEB-INF/classes/".length()).replace("/", ".").replace(".class", "");
                            logger.trace("Processing class {} for annotations.", className);
                            EndpointClassVisitor classVisitor = new EndpointClassVisitor(endpoints, appName, port, contextPath, className);
                            classReader.accept(classVisitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                        } catch (IOException e) { // More specific catch
                            logger.warn("IOException reading class file {} in WAR {}: {}. Skipping class.", entryName, appPath, e.getMessage(), e);
                        } catch (Exception e) { // General catch for other issues like ASM errors
                            logger.warn("Error processing class file {} in WAR {}: {}. Skipping class.", entryName, appPath, e.getMessage(), e);
                        }
                    }
                    // TODO: Add logic to handle JARs in WEB-INF/lib
                    // This would involve extracting these JARs to a temporary location or reading them in-memory
                    // and then performing JAR analysis on each of them.
                }
            } catch (Exception e) {
                logger.error("Error opening or reading WAR file for analysis {}: {}", appPath, e.getMessage(), e);
            }
            // WAR-specific logic placeholder ends here
        } else {
            logger.warn("Unknown application file type (not .jar or .war): {}. No specific analysis will be performed.", appPath);
        }
        return endpoints;
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
        private static final Logger logger = LoggerFactory.getLogger(EndpointClassVisitor.class); // No change needed here, it's a static nested class
        private final List<EndpointInfo> endpoints;
        private final String appName; // Renamed from jarName for clarity
        private final int port;
        private final String contextPath;
        private String classLevelBasePath = "";
        private boolean isRestController = false; // Flag for Spring @RestController
        private boolean isController = false; // Flag for Spring @Controller or JAX-RS @Path
        private String currentClassName;


        public EndpointClassVisitor(List<EndpointInfo> endpoints, String appName, int port, String contextPath, String className) {
            super(Opcodes.ASM9); // Use latest ASM version
            this.endpoints = endpoints;
            this.appName = appName;
            this.port = port;
            this.contextPath = contextPath;
            this.currentClassName = className;
            logger.trace("Visiting class: {}. Looking for controller/endpoint annotations. ContextPath: '{}'", this.currentClassName, this.contextPath);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            logger.trace("Class {} visiting annotation: {}", currentClassName, descriptor);
            // Spring MVC/REST
            if ("Lorg/springframework/web/bind/annotation/RestController;".equals(descriptor)) {
                logger.trace("Found @RestController on class {}", currentClassName);
                isRestController = true;
            }
            if ("Lorg/springframework/web/bind/annotation/Controller;".equals(descriptor)) {
                logger.trace("Found @Controller on class {}", currentClassName);
                isController = true;
            }
            if ("Lorg/springframework/web/bind/annotation/RequestMapping;".equals(descriptor)) {
                logger.trace("Found @RequestMapping on class {}", currentClassName);
                // This is a class-level RequestMapping
                return new PathAnnotationVisitor(extractedPath -> this.classLevelBasePath = sanitizePath(extractedPath));
            }
            // JAX-RS
            if ("Ljavax/ws/rs/Path;".equals(descriptor) || "Ljakarta/ws/rs/Path;".equals(descriptor)) {
                logger.trace("Found JAX-RS @Path on class {}", currentClassName);
                isController = true; // Treat JAX-RS @Path classes as controllers
                return new PathAnnotationVisitor(extractedPath -> this.classLevelBasePath = sanitizePath(extractedPath));
            }
            return super.visitAnnotation(descriptor, visible);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String methodDescriptor, String signature, String[] exceptions) {
            if (!isRestController && !isController) {
                logger.trace("Skipping method {} in class {} as it's not a designated controller/restcontroller.", name, currentClassName);
                return super.visitMethod(access, name, methodDescriptor, signature, exceptions);
            }
            logger.trace("Visiting method: {} in class {}. Class base path: {}", name, currentClassName, classLevelBasePath);
            // Pass class-level path and other necessary info to method visitor
            return new EndpointMethodVisitor(super.visitMethod(access, name, methodDescriptor, signature, exceptions),
                    endpoints, appName, port, classLevelBasePath, contextPath, currentClassName, name);
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
        private static final Logger logger = LoggerFactory.getLogger(EndpointMethodVisitor.class); // No change needed here
        private final List<EndpointInfo> endpoints;
        private final String appName; // Renamed from jarName
        private final int port;
        private final String classLevelBasePath;
        private final String contextPath;
        private final String className;
        private final String methodName;

        public EndpointMethodVisitor(MethodVisitor methodVisitor, List<EndpointInfo> endpoints, String appName, int port, String classLevelBasePath, String contextPath, String className, String methodName) {
            super(Opcodes.ASM9, methodVisitor);
            this.endpoints = endpoints;
            this.appName = appName;
            this.port = port;
            this.classLevelBasePath = classLevelBasePath;
            this.contextPath = contextPath;
            this.className = className;
            this.methodName = methodName;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            logger.trace("Method {} in class {} visiting annotation: {}", methodName, className, descriptor);
            String httpMethod = null;
            // Spring Annotations
            switch (descriptor) {
                case "Lorg/springframework/web/bind/annotation/GetMapping;":
                    logger.trace("Found @GetMapping on method {}", methodName);
                    httpMethod = "GET";
                    break;
                case "Lorg/springframework/web/bind/annotation/PostMapping;":
                    logger.trace("Found @PostMapping on method {}", methodName);
                    httpMethod = "POST";
                    break;
                case "Lorg/springframework/web/bind/annotation/PutMapping;":
                    logger.trace("Found @PutMapping on method {}", methodName);
                    httpMethod = "PUT";
                    break;
                case "Lorg/springframework/web/bind/annotation/DeleteMapping;":
                    logger.trace("Found @DeleteMapping on method {}", methodName);
                    httpMethod = "DELETE";
                    break;
                case "Lorg/springframework/web/bind/annotation/PatchMapping;":
                    logger.trace("Found @PatchMapping on method {}", methodName);
                    httpMethod = "PATCH";
                    break;
                case "Lorg/springframework/web/bind/annotation/RequestMapping;": // Could be method level
                    logger.trace("Found @RequestMapping on method {}", methodName);
                    // Needs to parse value/path and method attributes
                    return new SpringRequestMappingAnnotationVisitor(this::addEndpoint);
            }

            // JAX-RS Annotations
            switch (descriptor) {
                case "Ljavax/ws/rs/GET;": case "Ljakarta/ws/rs/GET;":
                    logger.trace("Found JAX-RS @GET on method {}", methodName);
                    httpMethod = "GET";
                    break;
                case "Ljavax/ws/rs/POST;": case "Ljakarta/ws/rs/POST;":
                    logger.trace("Found JAX-RS @POST on method {}", methodName);
                    httpMethod = "POST";
                    break;
                case "Ljavax/ws/rs/PUT;": case "Ljakarta/ws/rs/PUT;":
                    logger.trace("Found JAX-RS @PUT on method {}", methodName);
                    httpMethod = "PUT";
                    break;
                case "Ljavax/ws/rs/DELETE;": case "Ljakarta/ws/rs/DELETE;":
                    logger.trace("Found JAX-RS @DELETE on method {}", methodName);
                    httpMethod = "DELETE";
                    break;
                case "Ljavax/ws/rs/HEAD;": case "Ljakarta/ws/rs/HEAD;":
                    logger.trace("Found JAX-RS @HEAD on method {}", methodName);
                    httpMethod = "HEAD";
                    break;
                case "Ljavax/ws/rs/OPTIONS;": case "Ljakarta/ws/rs/OPTIONS;":
                    logger.trace("Found JAX-RS @OPTIONS on method {}", methodName);
                    httpMethod = "OPTIONS";
                    break;
                case "Ljavax/ws/rs/Path;": case "Ljakarta/ws/rs/Path;": // Method-level JAX-RS Path
                    logger.trace("Found JAX-RS @Path on method {}", methodName);
                    return new PathAnnotationVisitor(extractedPath -> {
                        this.methodLevelPath = sanitizePath(extractedPath);
                    });
            }


            if (httpMethod != null) {
                AnnotationVisitor av = super.visitAnnotation(descriptor, visible);
                return new PathConsumingAnnotationVisitor(av, httpMethod, this::addEndpoint);
            }

            return super.visitAnnotation(descriptor, visible);
        }

        private String methodLevelPath = ""; // Used by JAX-RS @Path on method

        private void addEndpoint(String httpMethod, String pathValue) {
            String methodPath = sanitizePath(pathValue);
            if (this.methodLevelPath != null && !this.methodLevelPath.isEmpty() && (pathValue == null || pathValue.isEmpty())) {
                methodPath = this.methodLevelPath;
            }

            String fullPath = classLevelBasePath + methodPath;
            if (fullPath.isEmpty()) fullPath = "/";
            if (!fullPath.startsWith("/")) fullPath = "/" + fullPath;
            fullPath = fullPath.replaceAll("//+", "/");
            if (fullPath.length() > 1 && fullPath.endsWith("/")) {
                fullPath = fullPath.substring(0, fullPath.length() - 1);
            }

            endpoints.add(new EndpointInfo(appName, httpMethod, fullPath, port, this.contextPath));
            logger.info("Discovered endpoint: {} {} (Context: '{}', Port: {}, App: {})", httpMethod, fullPath, this.contextPath, port, appName);
            this.methodLevelPath = "";
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
        private static final Logger logger = LoggerFactory.getLogger(PathAnnotationVisitor.class);
        private PathValueCallback callback;
        private String pathValue = ""; // Default to empty

        public PathAnnotationVisitor(PathValueCallback callback) {
            super(Opcodes.ASM9);
            this.callback = callback;
        }

        @Override
        public void visit(String name, Object value) {
            logger.trace("PathAnnotationVisitor visiting attribute name: {} value: {}", name, value);
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
            logger.trace("PathAnnotationVisitor visiting array attribute name: {}", name);
            if ("value".equals(name) || "path".equals(name)) {
                return new AnnotationVisitor(Opcodes.ASM9, super.visitArray(name)) {
                    @Override
                    public void visit(String arrayName, Object arrayValue) {
                        logger.trace("PathAnnotationVisitor visiting array element name: {} value: {}", arrayName, arrayValue);
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
            logger.trace("PathAnnotationVisitor finished, extracted path: {}", pathValue);
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
        private static final Logger logger = LoggerFactory.getLogger(SpringRequestMappingAnnotationVisitor.class);
        private String path = "";
        private String[] methods = {};
        private MethodEndpointConsumer consumer;

        public SpringRequestMappingAnnotationVisitor(MethodEndpointConsumer consumer) {
            super(Opcodes.ASM9);
            this.consumer = consumer;
        }

        @Override
        public void visit(String name, Object value) {
            logger.trace("SpringRequestMappingAnnotationVisitor visiting attribute name: {} value: {}", name, value);
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
            logger.trace("SpringRequestMappingAnnotationVisitor visiting array attribute name: {}", name);
            if ("method".equals(name)) {
                return new AnnotationVisitor(Opcodes.ASM9, super.visitArray(name)) {
                    List<String> foundMethods = new ArrayList<>();
                    @Override
                    public void visitEnum(String enumName, String enumDesc, String enumValue) {
                        logger.trace("SpringRequestMappingAnnotationVisitor visiting enum for method: {} {} {}", enumName, enumDesc, enumValue);
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
                        logger.trace("SpringRequestMappingAnnotationVisitor visiting array element for path: {} value: {}", arrName, arrValue);
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
            logger.trace("SpringRequestMappingAnnotationVisitor finished, path: {}, methods: {}", path, java.util.Arrays.toString(methods));
            if (methods.length > 0) {
                for (String method : methods) {
                    consumer.consume(method, path);
                }
            } else {
                consumer.consume("ANY", path);
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
        private static final Logger logger = LoggerFactory.getLogger(PathConsumingAnnotationVisitor.class);
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
            logger.trace("PathConsumingAnnotationVisitor visiting attribute name: {} value: {}", name, value);
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
            logger.trace("PathConsumingAnnotationVisitor visiting array attribute name: {}", name);
            if (("value".equals(name) || "path".equals(name))) {
                return new AnnotationVisitor(Opcodes.ASM9, super.visitArray(name)) {
                    @Override
                    public void visit(String arrName, Object arrValue) {
                        logger.trace("PathConsumingAnnotationVisitor visiting array element name: {} value: {}", arrName, arrValue);
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
            logger.trace("PathConsumingAnnotationVisitor finished, httpMethod: {}, path: {}", httpMethodFixed, path);
            consumer.consume(httpMethodFixed, path);
            super.visitEnd();
        }
    }

    private String parseContextPathFromYaml(InputStream yamlStream) {
        if (yamlStream == null) return null;
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Map<String, Object> yamlProps = yaml.load(yamlStream);
            if (yamlProps == null) return null; // Handle empty YAML

            Object contextPathObj = null;
            // Try common nested paths first
            if (yamlProps.containsKey("server")) {
                Object serverObj = yamlProps.get("server");
                if (serverObj instanceof Map) {
                    Map<String, Object> serverMap = (Map<String, Object>) serverObj;
                    if (serverMap.containsKey("servlet")) {
                        Object servletObj = serverMap.get("servlet");
                        if (servletObj instanceof Map) {
                            contextPathObj = ((Map<String, Object>) servletObj).get("context-path");
                        }
                    }
                    if (contextPathObj == null) {
                        contextPathObj = serverMap.get("context-path"); // e.g. server.context-path (less common for Spring Boot but possible)
                    }
                }
            }

            if (contextPathObj == null && yamlProps.containsKey("spring")) {
                Object springObj = yamlProps.get("spring");
                if (springObj instanceof Map && ((Map)springObj).containsKey("webflux")) {
                    Object wfObj = ((Map)springObj).get("webflux");
                    if (wfObj instanceof Map) {
                        contextPathObj = ((Map)wfObj).get("base-path");
                    }
                }
            }

            if (contextPathObj == null && yamlProps.containsKey("micronaut")) {
                Object micronautObj = yamlProps.get("micronaut");
                if (micronautObj instanceof Map && ((Map)micronautObj).containsKey("server")) {
                    Object serverObj = ((Map)micronautObj).get("server");
                    if (serverObj instanceof Map) {
                        contextPathObj = ((Map)serverObj).get("context-path");
                    }
                } else if (micronautObj instanceof Map) { // Direct micronaut.context-path
                    contextPathObj = ((Map)micronautObj).get("context-path");
                }
            }

            if (contextPathObj == null && yamlProps.containsKey("quarkus")) {
                Object quarkusObj = yamlProps.get("quarkus");
                if (quarkusObj instanceof Map && ((Map)quarkusObj).containsKey("http")) {
                    Object httpObj = ((Map)quarkusObj).get("http");
                    if (httpObj instanceof Map) {
                        contextPathObj = ((Map)httpObj).get("root-path");
                    }
                } else if (quarkusObj instanceof Map) { // Direct quarkus.context-path
                    contextPathObj = ((Map)quarkusObj).get("context-path"); // Or root-path directly under quarkus? Check conventions.
                }
            }

            // Direct top-level keys as fallback
            if (contextPathObj == null) {
                String[] directKeys = {"server.servlet.context-path", "spring.webflux.base-path", "micronaut.server.context-path", "quarkus.http.root-path", "contextPath", "context-path", "base-path", "root-path"};
                for (String key : directKeys) {
                    // Check if key exists directly (e.g. server.servlet.context-path: /api)
                    // This form of direct key access is less common for complex keys in YAML maps.
                    // The nested checks above are more typical for YAML.
                    // However, if the YAML is flat, this might catch it.
                    if (yamlProps.containsKey(key)) {
                        contextPathObj = yamlProps.get(key);
                        if (contextPathObj != null) break;
                    }
                }
            }

            if (contextPathObj instanceof String) {
                String contextPath = ((String) contextPathObj).trim();
                if (!contextPath.isEmpty()) {
                    logger.trace("Found context path '{}' in YAML", contextPath);
                    return contextPath;
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to parse YAML for context path: {}", e.getMessage(), e);
        }
        return null;
    }

    private String parseContextPathFromProperties(InputStream propertiesStream) {
        if (propertiesStream == null) return null;
        try {
            Properties props = new Properties();
            props.load(propertiesStream);

            String[] keysToTry = {
                    "server.servlet.context-path",
                    "spring.webflux.base-path",
                    "micronaut.server.context-path",
                    "quarkus.http.root-path",
                    "server.context-path", // Alternative spring
                    "contextPath",
                    "context-path",
                    "base-path",
                    "root-path"
            };
            for (String key : keysToTry) {
                String value = props.getProperty(key);
                if (value != null) {
                    String contextPath = value.trim();
                    if (!contextPath.isEmpty()) {
                        logger.trace("Found context path '{}' with key '{}' in properties", contextPath, key);
                        return contextPath;
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to read properties stream for context path: {}", e.getMessage(), e);
        }
        return null;
    }

    // Renamed for clarity, behavior is largely the same for now
    private String extractContextPathFromJar(JarFile appFile, String appNameForLogging) {
        String[] yamlPathsToTry = {
                "BOOT-INF/classes/config/application.yml",
                "BOOT-INF/classes/config/application.yaml",
                "BOOT-INF/classes/application.yml",
                "BOOT-INF/classes/application.yaml",
                "application.yml",
                "application.yaml",
                // WAR specific paths (might be less common for Spring Boot but good to check)
                "WEB-INF/classes/application.yml",
                "WEB-INF/classes/application.yaml"
        };
        JarEntry ymlEntry = null;
        String foundYmlPath = null;

        for (String currentYmlPath : yamlPathsToTry) {
            ymlEntry = appFile.getJarEntry(currentYmlPath);
            if (ymlEntry != null) {
                foundYmlPath = currentYmlPath;
                break;
            }
        }

        if (ymlEntry != null) {
            logger.debug("Found configuration file {} in {} for context path extraction", foundYmlPath, appNameForLogging);
            try (InputStream inputStream = appFile.getInputStream(ymlEntry)) {
                String contextPath = parseContextPathFromYaml(inputStream);
                if (contextPath != null && !contextPath.isEmpty()) {
                    logger.info("Extracted context path '{}' from {} in {}", contextPath, foundYmlPath, appNameForLogging);
                    return contextPath;
                }
            } catch (Exception e) {
                logger.warn("Error reading/parsing {} for context path from {}: {}", foundYmlPath, appNameForLogging, e.getMessage(), e);
            }
        } else {
            logger.debug("No application.yml or application.yaml found in standard locations in {} for context path", appNameForLogging);
        }

        String[] propsPathsToTry = {
                "BOOT-INF/classes/application.properties",
                "BOOT-INF/classes/config/application.properties",
                "application.properties",
                // WAR specific paths
                "WEB-INF/classes/application.properties"
        };
        JarEntry propsEntry = null;
        String foundPropsPath = null;

        for (String currentPropsPath : propsPathsToTry) {
            propsEntry = appFile.getJarEntry(currentPropsPath);
            if (propsEntry != null) {
                foundPropsPath = currentPropsPath;
                break;
            }
        }

        if (propsEntry != null) {
            logger.debug("Found configuration file {} in {} for context path extraction", foundPropsPath, appNameForLogging);
            try (InputStream inputStream = appFile.getInputStream(propsEntry)) {
                String contextPath = parseContextPathFromProperties(inputStream);
                if (contextPath != null && !contextPath.isEmpty()) {
                    logger.info("Extracted context path '{}' from {} in {}", contextPath, foundPropsPath, appNameForLogging);
                    return contextPath;
                }
            } catch (Exception e) {
                logger.warn("Error reading/parsing {} for context path from {}: {}", foundPropsPath, appNameForLogging, e.getMessage(), e);
            }
        } else {
            logger.debug("No application.properties found in standard locations in {} for context path", appNameForLogging);
        }

        // TODO: Add web.xml parsing for context path in WARs if not found in properties/yaml
        logger.debug("No context path found in common configuration files for {}", appNameForLogging);
        return ""; // Return empty string if no context path is found, for safe concatenation
    }

    private List<EndpointInfo> parseWebXml(JarFile warFile, String appName, int port, String contextPath) {
        List<EndpointInfo> webXmlEndpoints = new ArrayList<>();
        JarEntry webXmlEntry = warFile.getJarEntry("WEB-INF/web.xml");

        if (webXmlEntry == null) {
            logger.info("WEB-INF/web.xml not found in WAR file {}. Skipping servlet mapping analysis.", appName);
            return webXmlEndpoints;
        }

        logger.debug("Found WEB-INF/web.xml in {}. Attempting to parse servlet mappings.", appName);
        try (InputStream webXmlStream = warFile.getInputStream(webXmlEntry)) {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            // Prevent XXE attacks
            dbFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbFactory.setXIncludeAware(false);
            dbFactory.setExpandEntityReferences(false);

            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(webXmlStream);
            doc.getDocumentElement().normalize();

            Map<String, String> servletClasses = new HashMap<>();
            NodeList servletNodes = doc.getElementsByTagName("servlet");
            for (int i = 0; i < servletNodes.getLength(); i++) {
                Element servletElement = (Element) servletNodes.item(i);
                String servletName = getTagValue("servlet-name", servletElement);
                String servletClass = getTagValue("servlet-class", servletElement);
                if (servletName != null && servletClass != null) {
                    servletClasses.put(servletName, servletClass);
                    logger.trace("Found servlet definition: Name={}, Class={}", servletName, servletClass);
                }
            }

            NodeList mappingNodes = doc.getElementsByTagName("servlet-mapping");
            for (int i = 0; i < mappingNodes.getLength(); i++) {
                Element mappingElement = (Element) mappingNodes.item(i);
                String servletName = getTagValue("servlet-name", mappingElement);
                String urlPattern = getTagValue("url-pattern", mappingElement);

                if (servletName != null && urlPattern != null) {
                    String servletClass = servletClasses.get(servletName);
                    if (servletClass != null) {
                        // Ensure URL pattern starts with a slash if it doesn't have one
                        String finalPath = urlPattern.startsWith("/") ? urlPattern : "/" + urlPattern;
                        // For web.xml servlets, HTTP method is not defined, so using "ANY"
                        // The actual class (servletClass) could be inspected further for more details if needed.
                        // EndpointInfo does not currently store source or specific servlet class name.
                        EndpointInfo endpoint = new EndpointInfo(appName, "ANY", finalPath, port, contextPath);
                        webXmlEndpoints.add(endpoint);
                        logger.info("Discovered endpoint from web.xml: ANY {} (Mapped to Servlet Class: {}, App: {}, Port: {}, Context: '{}')",
                                finalPath, servletClass, appName, port, contextPath);
                    } else {
                        logger.warn("Found servlet mapping for servlet-name '{}' but no corresponding servlet definition with a servlet-class.", servletName);
                    }
                }
            }
            logger.debug("Finished parsing WEB-INF/web.xml for {}. Found {} endpoints from servlet mappings.", appName, webXmlEndpoints.size());

        } catch (ParserConfigurationException | SAXException | IOException e) {
            logger.warn("Error parsing WEB-INF/web.xml from {}: {}. Servlet mapping analysis may be incomplete.", appName, e.getMessage(), e);
        }
        return webXmlEndpoints;
    }

    // Helper method to get text content of a tag
    private String getTagValue(String tagName, Element element) {
        NodeList nodeList = element.getElementsByTagName(tagName);
        if (nodeList != null && nodeList.getLength() > 0 && nodeList.item(0).getChildNodes().getLength() > 0) {
            return nodeList.item(0).getChildNodes().item(0).getNodeValue();
        }
        return null;
    }
}
