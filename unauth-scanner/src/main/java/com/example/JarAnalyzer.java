package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Properties;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;
import java.io.IOException; // Already present via JarFile, but good to be explicit if used elsewhere

public class JarAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(JarAnalyzer.class);
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
        String jarName = kurzen(jarPath);
        logger.debug("Analyzing JAR: {} for process PID: {}", jarPath, processDetails.getPid());

        int port = extractPort(commandLine, jarPath, jarName);
        String contextPath = ""; // Initialize contextPath to empty string

        try (JarFile jarFileForConfig = new JarFile(jarPath)) { 
            String extractedCtxPath = extractContextPathFromJar(jarFileForConfig, jarName);
            if (extractedCtxPath != null && !extractedCtxPath.isEmpty()) {
                 contextPath = extractedCtxPath.trim();
                 if (!contextPath.startsWith("/")) {
                     contextPath = "/" + contextPath;
                 }
                 // Remove trailing slash only if it's not the root context "/"
                 if (contextPath.endsWith("/") && contextPath.length() > 1) { 
                     contextPath = contextPath.substring(0, contextPath.length() - 1);
                 }
                 // If contextPath became just "/", treat it as no context path to avoid issues.
                 if (contextPath.equals("/")) { 
                     contextPath = ""; 
                 }
                 if (!contextPath.isEmpty()) { // Log only if there's a meaningful context path
                     logger.info("Using context path '{}' for JAR {}", contextPath, jarName);
                 } else {
                     logger.info("No context path configured or context path is root for JAR {}", jarName);
                 }
            } else {
                 logger.info("No context path configured for JAR {}", jarName);
            }
        } catch (IOException e) {
            logger.warn("Error opening JAR file {} to read context path config: {}. Proceeding without context path.", jarPath, e.getMessage(), e);
            // contextPath remains ""
        }

        try (JarFile jarFile = new JarFile(jarPath)) { 
            logger.trace("Successfully opened JAR file for class analysis: {}", jarPath);
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    try (InputStream classInputStream = jarFile.getInputStream(entry)) {
                        ClassReader classReader = new ClassReader(classInputStream);
                        EndpointClassVisitor classVisitor = new EndpointClassVisitor(endpoints, jarName, port, contextPath, entry.getName().replace("/", ".").replace(".class", ""));
                        classReader.accept(classVisitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    } catch (Exception e) {
                        logger.warn("Error reading class file {} in JAR {}: {}. Skipping class.", entry.getName(), jarPath, e.getMessage(), e);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error opening or reading JAR file for class analysis {}: {}", jarPath, e.getMessage(), e);
        }
        return endpoints;
    }

    /**
     * Extracts the server port from the command line arguments.
     * Looks for common port arguments like -Dserver.port, --server.port, etc.
     * Defaults to {@value #DEFAULT_PORT} if no port argument is found.
     *
     * @param commandLine The command line string of the Java process.
     * @param jarPath The full path to the JAR file, for reading internal config files.
     * @param jarNameForLogging Name of the JAR for logging purposes.
     * @return The extracted port or the default port.
     */
    private int extractPort(String commandLine, String jarPath, String jarNameForLogging) {
        logger.debug("Using regex to find port in command line: {}", commandLine);
        // Regex to find port arguments like -Dserver.port=XXXX, --server.port=XXXX,
        // -Dhttp.port=XXXX, --http.port=XXXX, -Ddw.server.applicationConnectors[0].port=XXXX etc.
        // It captures the port number (XXXX).
        Pattern portPattern = Pattern.compile(
                "(?:-D|-{1,2})server\\.port=(\\d+)|" +              // Spring Boot, etc.
                "(?:-D|-{1,2})http\\.port=(\\d+)|" +               // Common generic
                "(?:-D|-{1,2})dw\\.server(?:\\.applicationConnectors\\[\\d+\\])?\\.port=(\\d+)|" + // Dropwizard
                "(?:-D|-{1,2})port=(\\d+)|" +                       // Generic -Dport or --port
                "(?:-D|-{1,2})HTTP_PORT=(\\d+)|" +                  // Env style HTTP_PORT (upper or lower case)
                "(?:-D|-{1,2})http_port=(\\d+)|" +                  // Env style http_port (lower case)
                "(?:-D|-{1,2})micronaut\\.server\\.port=(\\d+)|" +  // Micronaut
                "(?:-D|-{1,2})quarkus\\.http\\.port=(\\d+)" +       // Quarkus
                "" // Placeholder for potentially adding more later without breaking the | structure
        );
        Matcher matcher = portPattern.matcher(commandLine);

        if (matcher.find()) {
            for (int i = 1; i <= matcher.groupCount(); i++) {
                if (matcher.group(i) != null) {
                    try {
                        int port = Integer.parseInt(matcher.group(i));
                        logger.debug("Found port {} in command line for JAR {} based on: {}", port, jarNameForLogging, matcher.group(0));
                        return port;
                    } catch (NumberFormatException e) {
                        logger.warn("Error parsing port number {} from command line for JAR {}.", matcher.group(i), jarNameForLogging, e);
                    }
                }
            }
        }

        // If port not found from command line, try reading from JAR config files
        logger.debug("No port found in command line for JAR {}. Attempting to read from config files within the JAR.", jarNameForLogging);

        try (JarFile jarFile = new JarFile(jarPath)) {
            // Try application.yml or application.yaml in BOOT-INF/classes/ first, then root
            String[] yamlPathsToTry = {
                "BOOT-INF/classes/application.yml", 
                "BOOT-INF/classes/application.yaml",
                "application.yml",
                "application.yaml"
            };
            JarEntry ymlEntry = null;
            String foundYmlPath = null;

            for (String currentYmlPath : yamlPathsToTry) {
                ymlEntry = jarFile.getJarEntry(currentYmlPath);
                if (ymlEntry != null) {
                    foundYmlPath = currentYmlPath;
                    break;
                }
            }

            if (ymlEntry != null) {
                logger.debug("Found configuration file {} in JAR {}", foundYmlPath, jarNameForLogging);
                try (InputStream inputStream = jarFile.getInputStream(ymlEntry)) {
                    Integer portFromYaml = parsePortFromYaml(inputStream);
                    if (portFromYaml != null) {
                        logger.info("Extracted port {} from {} in JAR {}", portFromYaml, foundYmlPath, jarNameForLogging);
                        return portFromYaml;
                    }
                } catch (Exception e) {
                    logger.warn("Error parsing {} from JAR {}: {}", foundYmlPath, jarNameForLogging, e.getMessage(), e);
                }
            } else {
                logger.debug("No application.yml or application.yaml found in standard locations in JAR {}", jarNameForLogging);
            }

            // Try application.properties in BOOT-INF/classes/ first, then root
            String[] propsPathsToTry = {
                "BOOT-INF/classes/application.properties",
                "application.properties"
            };
            JarEntry propsEntry = null;
            String foundPropsPath = null;

            for (String currentPropsPath : propsPathsToTry) {
                propsEntry = jarFile.getJarEntry(currentPropsPath);
                if (propsEntry != null) {
                    foundPropsPath = currentPropsPath;
                    break;
                }
            }
            
            if (propsEntry != null) {
                logger.debug("Found configuration file {} in JAR {}", foundPropsPath, jarNameForLogging);
                try (InputStream inputStream = jarFile.getInputStream(propsEntry)) {
                    Integer portFromProperties = parsePortFromProperties(inputStream);
                    if (portFromProperties != null) {
                        logger.info("Extracted port {} from {} in JAR {}", portFromProperties, foundPropsPath, jarNameForLogging);
                        return portFromProperties;
                    }
                } catch (Exception e) {
                    logger.warn("Error parsing {} from JAR {}: {}", foundPropsPath, jarNameForLogging, e.getMessage(), e);
                }
            } else {
                logger.debug("No application.properties found in standard locations in JAR {}", jarNameForLogging);
            }

        } catch (IOException e) {
            logger.warn("Error opening JAR file {} to read config: {}", jarPath, e.getMessage(), e);
            // If JAR can't be opened, we can't read config, so proceed to default.
        }

        logger.info("No specific port found in command line or config files for JAR {}. Defaulting to {}", jarNameForLogging, DEFAULT_PORT);
        return DEFAULT_PORT;
    }

    private Integer parsePortFromYaml(InputStream yamlStream) {
        if (yamlStream == null) return null;
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Map<String, Object> yamlProps = yaml.load(yamlStream);

            // Common keys for server port
            String[] keysToTry = {"server.port", "micronaut.server.port", "quarkus.http.port"};
            // Spring Boot often has server: port: XXXX
            // Also spring.application.json variant
            // server:
            //   port: 8081
            // spring:
            //   application:
            //     json: '{"server":{"port":1234}}' (less common for just port)


            for (String key : keysToTry) {
                 Object value = yamlProps.get(key);
                 if (value instanceof Integer) return (Integer) value;
                 if (value instanceof String) {
                     try { return Integer.parseInt((String) value); } catch (NumberFormatException e) { /* ignore */ }
                 }
            }
            
            // Check for nested server.port (e.g. Spring Boot default structure)
            if (yamlProps.containsKey("server")) {
                Object serverObj = yamlProps.get("server");
                if (serverObj instanceof Map) {
                    Map<String, Object> serverMap = (Map<String, Object>) serverObj;
                    Object portObj = serverMap.get("port");
                    if (portObj instanceof Integer) return (Integer) portObj;
                    if (portObj instanceof String) {
                        try { return Integer.parseInt((String) portObj); } catch (NumberFormatException e) { /* ignore */ }
                    }
                }
            }
             // Check for spring.application.json embedded JSON string
             if (yamlProps.containsKey("spring")) {
                 Object springObj = yamlProps.get("spring");
                 if (springObj instanceof Map) {
                     Map<String, Object> springMap = (Map<String, Object>) springObj;
                     if (springMap.containsKey("application")) {
                         Object appObj = springMap.get("application");
                         if (appObj instanceof Map) {
                             Map<String, Object> appMap = (Map<String, Object>) appObj;
                             if (appMap.containsKey("json")) {
                                 Object jsonStrObj = appMap.get("json");
                                 if (jsonStrObj instanceof String) {
                                     try {
                                         // For embedded JSON, we might need a separate Yaml instance or ensure this one is fine
                                         Map<String, Object> embeddedJson = new Yaml(new SafeConstructor(new LoaderOptions())).load((String)jsonStrObj);
                                         if (embeddedJson.containsKey("server") && embeddedJson.get("server") instanceof Map) {
                                             Map<String, Object> serverMapJson = (Map<String,Object>) embeddedJson.get("server");
                                             Object portObjJson = serverMapJson.get("port");
                                             if (portObjJson instanceof Integer) return (Integer) portObjJson;
                                             if (portObjJson instanceof String) return Integer.parseInt((String)portObjJson);
                                         }
                                     } catch (Exception e) {
                                         logger.trace("Could not parse embedded JSON in spring.application.json for port: {}", e.getMessage());
                                     }
                                 }
                             }
                         }
                     }
                 }
             }


        } catch (Exception e) {
            logger.warn("Failed to parse YAML for port number: {}", e.getMessage(), e);
        }
        return null;
    }

    private Integer parsePortFromProperties(InputStream propertiesStream) {
        if (propertiesStream == null) return null;
        try {
            Properties props = new Properties();
            props.load(propertiesStream);

            String[] keysToTry = {"server.port", "micronaut.server.port", "quarkus.http.port", "http.port"};
            for (String key : keysToTry) {
                String value = props.getProperty(key);
                if (value != null) {
                    try {
                        return Integer.parseInt(value.trim());
                    } catch (NumberFormatException e) {
                        logger.warn("Non-integer value for port '{}' in properties: {}", value, key);
                        // continue to next key
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to read properties stream for port number: {}", e.getMessage(), e);
        }
        return null;
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
        private static final Logger logger = LoggerFactory.getLogger(EndpointClassVisitor.class);
        private final List<EndpointInfo> endpoints;
        private final String jarName;
        private final int port;
        private final String contextPath;
        private String classLevelBasePath = "";
        private boolean isRestController = false; // Flag for Spring @RestController
        private boolean isController = false; // Flag for Spring @Controller or JAX-RS @Path
        private String currentClassName;


        public EndpointClassVisitor(List<EndpointInfo> endpoints, String jarName, int port, String contextPath, String className) {
            super(Opcodes.ASM9); // Use latest ASM version
            this.endpoints = endpoints;
            this.jarName = jarName;
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
                                             endpoints, jarName, port, classLevelBasePath, contextPath, currentClassName, name);
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
        private static final Logger logger = LoggerFactory.getLogger(EndpointMethodVisitor.class);
        private final List<EndpointInfo> endpoints;
        private final String jarName;
        private final int port;
        private final String classLevelBasePath;
        private final String contextPath;
        private final String className;
        private final String methodName;

        public EndpointMethodVisitor(MethodVisitor methodVisitor, List<EndpointInfo> endpoints, String jarName, int port, String classLevelBasePath, String contextPath, String className, String methodName) {
            super(Opcodes.ASM9, methodVisitor);
            this.endpoints = endpoints;
            this.jarName = jarName;
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

            endpoints.add(new EndpointInfo(jarName, httpMethod, fullPath, port, this.contextPath));
            logger.info("Discovered endpoint: {} {} (Context: '{}', Port: {}, JAR: {})", httpMethod, fullPath, this.contextPath, port, jarName);
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

    private String extractContextPathFromJar(JarFile jarFile, String jarNameForLogging) {
        String[] yamlPathsToTry = {
            "BOOT-INF/classes/application.yml", 
            "BOOT-INF/classes/application.yaml",
            "application.yml",
            "application.yaml"
        };
        JarEntry ymlEntry = null;
        String foundYmlPath = null;

        for (String currentYmlPath : yamlPathsToTry) {
            ymlEntry = jarFile.getJarEntry(currentYmlPath);
            if (ymlEntry != null) {
                foundYmlPath = currentYmlPath;
                break;
            }
        }

        if (ymlEntry != null) {
            logger.debug("Found configuration file {} in JAR {} for context path extraction", foundYmlPath, jarNameForLogging);
            try (InputStream inputStream = jarFile.getInputStream(ymlEntry)) {
                String contextPath = parseContextPathFromYaml(inputStream);
                if (contextPath != null && !contextPath.isEmpty()) {
                    logger.info("Extracted context path '{}' from {} in JAR {}", contextPath, foundYmlPath, jarNameForLogging);
                    return contextPath;
                }
            } catch (Exception e) {
                logger.warn("Error reading/parsing {} for context path from JAR {}: {}", foundYmlPath, jarNameForLogging, e.getMessage(), e);
            }
        } else {
            logger.debug("No application.yml or application.yaml found in standard locations in JAR {} for context path", jarNameForLogging);
        }

        String[] propsPathsToTry = {
            "BOOT-INF/classes/application.properties",
            "application.properties"
        };
        JarEntry propsEntry = null;
        String foundPropsPath = null;

        for (String currentPropsPath : propsPathsToTry) {
            propsEntry = jarFile.getJarEntry(currentPropsPath);
            if (propsEntry != null) {
                foundPropsPath = currentPropsPath;
                break;
            }
        }
        
        if (propsEntry != null) {
            logger.debug("Found configuration file {} in JAR {} for context path extraction", foundPropsPath, jarNameForLogging);
            try (InputStream inputStream = jarFile.getInputStream(propsEntry)) {
                String contextPath = parseContextPathFromProperties(inputStream);
                if (contextPath != null && !contextPath.isEmpty()) {
                    logger.info("Extracted context path '{}' from {} in JAR {}", contextPath, foundPropsPath, jarNameForLogging);
                    return contextPath;
                }
            } catch (Exception e) {
                logger.warn("Error reading/parsing {} for context path from JAR {}: {}", foundPropsPath, jarNameForLogging, e.getMessage(), e);
            }
        } else {
            logger.debug("No application.properties found in standard locations in JAR {} for context path", jarNameForLogging);
        }
        
        logger.debug("No context path found in configuration files for JAR {}", jarNameForLogging);
        return ""; // Return empty string if no context path is found, for safe concatenation
    }
}
