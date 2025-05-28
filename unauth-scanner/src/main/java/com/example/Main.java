package com.example;

import java.io.IOException;
import java.util.List;

/**
 * Main class for the Unauthorized Endpoint Scanner.
 * This application scans for running Java processes, analyzes their JAR files
 * for web service endpoints (Spring MVC, JAX-RS), checks if these endpoints
 * are accessible without authorization, and reports at-risk endpoints to a CSV file.
 */
public class Main {
    public static void main(String[] args) {
        System.out.println("Starting Unauthorized Endpoint Scanner...");

        ProcessScanner scanner = new ProcessScanner();
        JarAnalyzer jarAnalyzer = new JarAnalyzer();
        EndpointChecker endpointChecker = new EndpointChecker();
        CsvReporter csvReporter = null;

        int processesScanned = 0;
        int totalEndpointsFound = 0;
        int totalAtRiskEndpoints = 0;

        try {
            try {
                csvReporter = new CsvReporter();
                System.out.println("Reporting unauthorized endpoints to: " + csvReporter.getFileName());
            } catch (IOException e) {
                System.err.println("CRITICAL: Failed to initialize CsvReporter. No report will be generated. Error: " + e.getMessage());
                // Continue execution without CSV reporting if initialization fails
            }

            List<JavaProcessDetails> runningJavaApps = scanner.getRunningJavaApplications();
            processesScanned = runningJavaApps.size();

            if (runningJavaApps.isEmpty()) {
                System.out.println("No running Java applications found to analyze.");
            } else {
                System.out.println("Found " + processesScanned + " running Java application(s) to analyze.");

                for (JavaProcessDetails appDetails : runningJavaApps) {
                    System.out.println("\nAnalyzing application: " + appDetails.getJarPath() + " (PID: " + appDetails.getPid() + ")");
                    System.out.println("  Command Line: " + appDetails.getCommandLine());

                    if (appDetails.getJarPath() == null || !appDetails.getJarPath().toLowerCase().endsWith(".jar")) {
                        System.out.println("  Skipping analysis: Path does not point to a .jar file.");
                        continue;
                    }

                    List<EndpointInfo> endpoints;
                    try {
                        endpoints = jarAnalyzer.extractEndpoints(appDetails);
                        if (endpoints.isEmpty()) {
                            System.out.println("  No HTTP endpoints found in " + appDetails.getJarPath());
                        } else {
                            System.out.println("  Found " + endpoints.size() + " potential endpoint(s) for " + appDetails.getJarPath() + ":");
                            totalEndpointsFound += endpoints.size();
                            for (EndpointInfo endpoint : endpoints) {
                                System.out.println("    - Checking: " + endpoint);
                                boolean atRisk = endpointChecker.isEndpointAtRisk(endpoint);
                                System.out.println("      Risk status: " + (atRisk ? "AT RISK (Accessible without 401)" : "NOT AT RISK (Returned 401 or error)"));
                                if (atRisk) {
                                    totalAtRiskEndpoints++;
                                    if (csvReporter != null) {
                                        csvReporter.addRiskEntry(endpoint);
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("  Error during JAR analysis or endpoint checking for " + appDetails.getJarPath() + ": " + e.getMessage());
                        // e.printStackTrace(); // Uncomment for detailed debugging
                    }
                }
            }
        } catch (Exception e) {
            // Catch any other unexpected exceptions during the main execution.
            System.err.println("\nUNEXPECTED ERROR: An unexpected error occurred during the scan: " + e.getMessage());
            e.printStackTrace();
        } finally {
            System.out.println("\n--- Scan Summary ---");
            System.out.println("Java Applications Scanned: " + processesScanned);
            System.out.println("Total Endpoints Found: " + totalEndpointsFound);
            System.out.println("Total At-Risk Endpoints: " + totalAtRiskEndpoints);

            if (endpointChecker != null) {
                endpointChecker.close();
                System.out.println("EndpointChecker resources released.");
            }
            if (csvReporter != null) {
                csvReporter.close(); // This logs its own completion message
            } else {
                System.out.println("CSV Reporter was not initialized. No report generated.");
            }
            System.out.println("\nUnauthorized Endpoint Scanner finished.");
        }
    }
}
