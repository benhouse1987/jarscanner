package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;
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

    /**
     * Finds running Java processes and extracts details like JAR path, command line, and PID.
     *
     * @return A list of unique {@link JavaProcessDetails} objects.
     */
    public List<JavaProcessDetails> getRunningJavaApplications() {
        logger.debug("Attempting to retrieve list of running OS processes using OSHI.");
        Set<String> uniqueJarIdentifiers = new HashSet<>(); // Used to avoid duplicate process entries based on JAR path + PID
        List<JavaProcessDetails> processDetailsList = new ArrayList<>();
        SystemInfo si = new SystemInfo();
        OperatingSystem os = si.getOperatingSystem();

        // Pattern to identify a JAR file in the command line when launched with -jar
        Pattern jarArgumentPattern = Pattern.compile("\\-jar\\s+([^\\s]+\\.jar)");
        // General pattern to find any .jar string in the command line (for -cp cases)
        Pattern cpJarPattern = Pattern.compile("([^\\s]+\\.jar)");

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
                logger.debug("Attempting to match -jar pattern for PID: {}. Command: {}", pid, commandLine);
                Matcher jarArgMatcher = jarArgumentPattern.matcher(commandLine);
                String jarPath = null;

                if (jarArgMatcher.find()) {
                    jarPath = jarArgMatcher.group(1);
                    logger.debug("Extracted JAR path using -jar argument: {} for PID: {}", jarPath, pid);
                } else if (commandLine.contains("-cp") || commandLine.contains("-classpath")) {
                    logger.trace("Process PID {} command line contains -cp or -classpath. Attempting fallback JAR extraction.", pid);
                    // Fallback: Try to find *any* JAR in the command line.
                    // This is a heuristic and might not be the main executable JAR.
                    // It's better to get the first one that looks like an app JAR rather than a library.
                    // For simplicity, we'll take the first one found for now.
                    Matcher cpMatcher = cpJarPattern.matcher(commandLine);
                    if (cpMatcher.find()) {
                        // This is still a bit naive, as it might pick up library JARs.
                        // A more sophisticated approach would be needed to identify the "main" JAR from a classpath.
                        // For now, this provides a potential candidate.
                        jarPath = cpMatcher.group(1);
                        logger.debug("Extracted JAR path using classpath heuristic: {} for PID: {}", jarPath, pid);
                    }
                }

                if (jarPath != null) {
                    String identifier = jarPath + "::" + pid; // Combine JAR path and PID for uniqueness
                    if (!uniqueJarIdentifiers.contains(identifier)) {
                        logger.debug("Adding unique Java process to list: PID={}, JAR Path={}", pid, jarPath);
                        processDetailsList.add(new JavaProcessDetails(jarPath, commandLine, pid));
                        uniqueJarIdentifiers.add(identifier);
                    } else {
                        logger.trace("Duplicate Java process based on JAR path and PID already processed: PID={}, JAR Path={}", pid, jarPath);
                    }
                } else {
                     logger.debug("Could not extract JAR path for PID: {}. Full command: {}", pid, commandLine);
                     // If no JAR found via -jar or in -cp, but it's a "java" process,
                     // it might be a bare class execution or something else.
                     // We can still record it, though JAR-based analysis won't be possible.
                     // For this tool, we are primarily interested in JARs.
                     // logger.debug("Java process found without a clear JAR path: {} (PID: {})", commandLine, pid);
                }
            } else {
                logger.trace("Skipping non-Java process: PID={}, Name={}", pid, processName);
            }
        }
        return processDetailsList;
    }
}
