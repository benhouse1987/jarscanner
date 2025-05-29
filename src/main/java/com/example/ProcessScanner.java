package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.Collections;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProcessScanner {

    private static final Logger logger = LoggerFactory.getLogger(ProcessScanner.class);
    private static final int DEFAULT_PORT = 8080; // Default port if detection fails

    /**
     * Finds running Java processes and extracts details like JAR path, command line, and PID.
     *
     * @return A list of unique {@link JavaProcessDetails} objects.
     */
    public List<JavaProcessDetails> getRunningJavaApplications() {
        logger.debug("Attempting to retrieve list of running OS processes using OSHI.");
        Set<String> uniqueAppIdentifiers = new HashSet<>(); // Used to avoid duplicate process entries based on App path + PID
        List<JavaProcessDetails> processDetailsList = new ArrayList<>();
        SystemInfo si = new SystemInfo();
        OperatingSystem os = si.getOperatingSystem();

        // Pattern to identify a JAR or WAR file in the command line when launched with -jar
        Pattern jarArgumentPattern = Pattern.compile("\\-jar\\s+([^\\s]+(?:\\.jar|\\.war))");
        // General pattern to find any .jar or .war string in the command line (for -cp cases)
        Pattern cpJarPattern = Pattern.compile("([^\\s]+(?:\\.jar|\\.war))");

        List<OSProcess> processes = os.getProcesses();
        if (processes == null) {
            logger.error("os.getProcesses() returned null. Cannot scan processes. This may indicate an issue with OSHI or system permissions.");
            return processDetailsList;
        }
        logger.debug("Retrieved {} OS processes. Filtering for Java applications.", processes.size());

        for (OSProcess process : processes) {
            String commandLine = process.getCommandLine();
            String processName = process.getName().toLowerCase();
            int pid = process.getProcessID();
            logger.trace("Examining process: PID={}, Name={}, Command={}", pid, processName, commandLine);


            if (commandLine == null || commandLine.trim().isEmpty()) {
                logger.trace("Skipping process PID {} due to empty command line.", pid);
                continue;
            }

            // Check if the process is likely a Java process
            if (processName.contains("java") || commandLine.toLowerCase().contains("java")) {
                logger.debug("Identified potential Java process: PID={}, Command={}", pid, commandLine);
                logger.debug("Attempting to match -jar or -cp pattern for PID: {}. Command: {}", pid, commandLine);
                Matcher jarArgMatcher = jarArgumentPattern.matcher(commandLine);
                String appPath = null;

                if (jarArgMatcher.find()) {
                    appPath = jarArgMatcher.group(1);
                    logger.debug("Extracted application path using -jar argument: {} for PID: {}", appPath, pid);
                } else if (commandLine.contains("-cp") || commandLine.contains("-classpath")) {
                    logger.trace("Process PID {} command line contains -cp or -classpath. Attempting fallback application path extraction.", pid);
                    // Fallback: Try to find *any* JAR or WAR in the command line.
                    // This is a heuristic and might not be the main executable application.
                    Matcher cpMatcher = cpJarPattern.matcher(commandLine);
                    if (cpMatcher.find()) {
                        // This is still a bit naive, as it might pick up library files.
                        // A more sophisticated approach would be needed to identify the "main" app from a classpath.
                        appPath = cpMatcher.group(1);
                        logger.debug("Extracted application path using classpath heuristic: {} for PID: {}", appPath, pid);
                    }
                }

                if (appPath != null) {
                    int port = detectPortForProcess(pid); // Call the new method
                    String identifier = appPath + "::" + pid;
                    if (!uniqueAppIdentifiers.contains(identifier)) {
                        logger.debug("Adding unique Java process to list: PID={}, Application Path={}, Port={}", pid, appPath, port);
                        processDetailsList.add(new JavaProcessDetails(appPath, commandLine, pid, port)); // Pass port
                        uniqueAppIdentifiers.add(identifier);
                    } else {
                        logger.trace("Duplicate Java process based on Application path and PID already processed: PID={}, Application Path={}, Port={}", pid, appPath, port);
                    }
                } else {
                    logger.debug("Could not extract application path (JAR/WAR) for PID: {}. Full command: {}. Skipping port detection and addition to list.", pid, commandLine);
                    // If no app path found via -jar or in -cp, but it's a "java" process,
                    // it might be a bare class execution or something else.
                    // We are primarily interested in JARs/WARs.
                    // logger.debug("Java process found without a clear application path: {} (PID: {})", commandLine, pid);
                }
            } else {
                logger.trace("Skipping non-Java process: PID={}, Name={}", pid, processName);
            }
        }
        return processDetailsList;
    }

    private int detectPortForProcess(int pid) {
        logger.debug("Attempting to detect port for PID {} using netstat.", pid);
        List<Integer> foundPorts = new ArrayList<>();
        Pattern netstatPattern = Pattern.compile("(?i)(?:tcp|tcp6)\\s+\\d+\\s+\\d+\\s+(?:[\\d.:]+|\\[::\\]):(\\d+)\\s+.*(?:LISTEN|VERBUNDEN).*?" + pid + "/java");
        // Explanation of regex:
        // (?i) - case insensitive for LISTEN/java
        // (?:tcp|tcp6) - matches tcp or tcp6
        // \s+\d+\s+\d+\s+ - matches recv-q, send-q columns
        // (?:[\d.:]+|\[::\]):(\d+) - matches local address (e.g., 0.0.0.0:8080, :::8080, 127.0.0.1:8080) and captures port in group 1
        // \s+.*(?:LISTEN|VERBUNDEN) - matches through to LISTEN (English/German) or VERBUNDEN (German for ESTABLISHED, sometimes seen with LISTEN)
        // .*? - non-greedy match until...
        // " + pid + "/java" - matches the PID/java program name

        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "netstat -tulnp 2>/dev/null");
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                    Matcher matcher = netstatPattern.matcher(line);
                    if (matcher.find()) {
                        String portStr = matcher.group(1);
                        try {
                            int port = Integer.parseInt(portStr);
                            logger.trace("Found potential port {} for PID {} from netstat line: {}", port, pid, line.trim());
                            foundPorts.add(port);
                        } catch (NumberFormatException e) {
                            logger.warn("Could not parse port '{}' from netstat line: {}", portStr, line.trim());
                        }
                    }
                }
            }
            logger.trace("Full netstat output for PID {}:\n{}", pid, output); // Log full output at TRACE

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.warn("netstat command exited with code {} for PID {}", exitCode, pid);
            }

        } catch (IOException | InterruptedException e) {
            logger.error("Error executing or reading output from netstat for PID {}: {}", pid, e.getMessage(), e);
            Thread.currentThread().interrupt(); // Restore interrupted status
            return DEFAULT_PORT;
        }

        if (foundPorts.isEmpty()) {
            logger.debug("No listening port found for PID {} via netstat.", pid);
            return DEFAULT_PORT;
        }

        // Heuristic for choosing a port if multiple are found
        Collections.sort(foundPorts); // Sort to have a consistent order

        // Prefer common HTTP ports
        int[] preferredPorts = {8080, 8000, 8081, 9000, 80, 443};
        for (int preferred : preferredPorts) {
            if (foundPorts.contains(preferred)) {
                logger.info("Selected preferred port {} for PID {} from multiple options: {}", preferred, pid, foundPorts);
                return preferred;
            }
        }

        // Prefer ports in typical web application ranges (e.g., 8000-9999) over others
        for (int port : foundPorts) {
            if (port >= 8000 && port <= 9999) {
                logger.info("Selected port {} (in 8000-9999 range) for PID {} from multiple options: {}", port, pid, foundPorts);
                return port;
            }
        }

        // Otherwise, take the first one found (lowest number due to sort)
        int chosenPort = foundPorts.get(0);
        logger.info("Selected port {} (first from sorted list) for PID {} from multiple options: {}", chosenPort, pid, foundPorts);
        return chosenPort;
    }
}
