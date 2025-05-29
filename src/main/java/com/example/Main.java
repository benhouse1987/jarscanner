package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * Main class for the Unauthorized Endpoint Scanner.
 * This application scans for running Java processes, analyzes their JAR files
 * for web service endpoints (Spring MVC, JAX-RS), checks if these endpoints
 * are accessible without authorization, and reports at-risk endpoints to a CSV file.
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    public static final String APP_VERSION = "1.1.0";

    public static void main(String[] args) {
        logger.info("Starting Unauthorized Endpoint Scanner v{}...", APP_VERSION);

        logger.debug("Initializing components: ProcessScanner, ApplicationAnalyzer, EndpointChecker, CsvReporter");
        ProcessScanner scanner = new ProcessScanner();
        ApplicationAnalyzer jarAnalyzer = new ApplicationAnalyzer();
        EndpointChecker endpointChecker = new EndpointChecker();
        CsvReporter csvReporter = null;

        int processesScanned = 0;
        int totalEndpointsFound = 0;
        int totalAtRiskEndpoints = 0;

        try {
            try {
                csvReporter = new CsvReporter();
                // This is already logged by CsvReporter constructor, no need to repeat here.
                // logger.info("Reporting unauthorized endpoints to: {}", csvReporter.getFileName());
            } catch (IOException e) {
                logger.error("CRITICAL: Failed to initialize CsvReporter. No report will be generated.", e);
                // Continue execution without CSV reporting if initialization fails
            }

            logger.debug("Attempting to get list of running Java applications.");
            List<JavaProcessDetails> runningJavaApps = scanner.getRunningJavaApplications();
            processesScanned = runningJavaApps.size();

            if (runningJavaApps.isEmpty()) {
                logger.info("No running Java applications found to analyze.");
            } else {
                logger.info("Found {} running Java application(s) to analyze.", processesScanned);

                for (JavaProcessDetails appDetails : runningJavaApps) {
                    logger.debug("Processing Java application: {} (PID: {})", appDetails.getJarPath(), appDetails.getPid());
                    logger.debug("Application command line: {}", appDetails.getCommandLine());

                    if (appDetails.getJarPath() == null || !(appDetails.getJarPath().toLowerCase().endsWith(".jar") || appDetails.getJarPath().toLowerCase().endsWith(".war"))) {
                        logger.info("Skipping analysis for PID {}: Path {} does not point to a .jar or .war file or is null.", appDetails.getPid(), appDetails.getJarPath());
                        continue;
                    }

                    List<EndpointInfo> endpoints;
                    try {
                        logger.debug("Attempting to extract endpoints from {}", appDetails.getJarPath());
                        // Ensure appDetails.getJarPath() is now appDetails.getAppPath() if that changed in JavaProcessDetails
                        // For now, assuming getJarPath() is still the correct method for the application file path
                        endpoints = jarAnalyzer.extractEndpoints(appDetails);
                        logger.debug("Found {} endpoints for {}", endpoints.size(), appDetails.getJarPath());

                        if (endpoints.isEmpty()) {
                            logger.info("No HTTP endpoints found in {}", appDetails.getJarPath());
                        } else {
                            // The previous log "Found X endpoints for Y" is sufficient, no need for this one.
                            // logger.info("  Found {} potential endpoint(s) for {}:", endpoints.size(), appDetails.getJarPath());
                            totalEndpointsFound += endpoints.size();
                            for (EndpointInfo endpoint : endpoints) {
                                // Changed JarName to AppName for clarity, as it can be JAR or WAR
                                logger.debug("Checking endpoint: {} {} for App {}", endpoint.getHttpMethod(), endpoint.getPath(), endpoint.getJarName());
                                int status = endpointChecker.isEndpointAtRisk(endpoint);
                                boolean atRisk = (status!=401 && status!=403);
                                logger.info("Endpoint {} {} risk status: {}", endpoint.getHttpMethod(), endpoint.getPath(), (atRisk ? "AT RISK" : "NOT AT RISK"));
                                if (atRisk) {
                                    totalAtRiskEndpoints++;
                                    if (csvReporter != null) {
                                        csvReporter.addRiskEntry(endpoint,status);
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Error during application analysis or endpoint checking for {}: {}", appDetails.getJarPath(), e.getMessage(), e);
                    }
                }
            }
        } catch (Exception e) {
            // Catch any other unexpected exceptions during the main execution.
            logger.error("UNEXPECTED ERROR: An unexpected error occurred during the scan.", e);
        } finally {
            logger.info("--- Scan Summary ---"); // Removed \n as per general practice, logback will add it.
            logger.info("Java Applications Scanned: {}", processesScanned);
            logger.info("Total Endpoints Found: {}", totalEndpointsFound);
            logger.info("Total At-Risk Endpoints: {}", totalAtRiskEndpoints);

            if (endpointChecker != null) {
                logger.debug("Closing EndpointChecker resources.");
                endpointChecker.close();
                // logger.info("EndpointChecker resources released."); // This is already logged by EndpointChecker.close()
            }
            if (csvReporter != null) {
                logger.debug("Closing CsvReporter resources.");
                csvReporter.close(); // This logs its own completion message
            } else {
                logger.info("CSV Reporter was not initialized. No report generated.");
            }
            logger.info("Unauthorized Endpoint Scanner finished."); // Removed \n
        }
    }
}
