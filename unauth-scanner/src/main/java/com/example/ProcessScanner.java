package com.example;

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

    /**
     * Finds running Java processes and extracts details like JAR path, command line, and PID.
     *
     * @return A list of unique {@link JavaProcessDetails} objects.
     */
    public List<JavaProcessDetails> getRunningJavaApplications() {
        Set<String> uniqueJarIdentifiers = new HashSet<>(); // Used to avoid duplicate process entries based on JAR path + PID
        List<JavaProcessDetails> processDetailsList = new ArrayList<>();
        SystemInfo si = new SystemInfo();
        OperatingSystem os = si.getOperatingSystem();

        // Pattern to identify a JAR file in the command line when launched with -jar
        Pattern jarArgumentPattern = Pattern.compile("(?:\\s|-jar\\s+)([^\\s]+\\.jar)(?:\\s|$)");
        // General pattern to find any .jar string in the command line (for -cp cases)
        Pattern cpJarPattern = Pattern.compile("([^\\s]+\\.jar)");

        List<OSProcess> processes = os.getProcesses();
        if (processes == null) {
            System.err.println("Warning: os.getProcesses() returned null. Cannot scan processes.");
            return processDetailsList;
        }

        for (OSProcess process : processes) {
            String commandLine = process.getCommandLine();
            String processName = process.getName().toLowerCase();
            int pid = process.getProcessID();

            if (commandLine == null || commandLine.trim().isEmpty()) {
                // Skip processes with no command line information
                continue;
            }

            // Check if the process is likely a Java process
            if (processName.contains("java") || commandLine.toLowerCase().contains("java")) {
                Matcher jarArgMatcher = jarArgumentPattern.matcher(commandLine);
                String jarPath = null;

                if (jarArgMatcher.find()) {
                    jarPath = jarArgMatcher.group(1);
                } else if (commandLine.contains("-cp") || commandLine.contains("-classpath")) {
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
                    }
                }

                if (jarPath != null) {
                    String identifier = jarPath + "::" + pid; // Combine JAR path and PID for uniqueness
                    if (!uniqueJarIdentifiers.contains(identifier)) {
                        processDetailsList.add(new JavaProcessDetails(jarPath, commandLine, pid));
                        uniqueJarIdentifiers.add(identifier);
                    }
                } else {
                     // If no JAR found via -jar or in -cp, but it's a "java" process,
                     // it might be a bare class execution or something else.
                     // We can still record it, though JAR-based analysis won't be possible.
                     // For this tool, we are primarily interested in JARs.
                     // System.out.println("Java process found without a clear JAR path: " + commandLine + " (PID: " + pid + ")");
                }
            }
        }
        return processDetailsList;
    }
}
