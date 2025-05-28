package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class CsvReporter {

    private static final Logger logger = LoggerFactory.getLogger(CsvReporter.class);
    private static final String[] HEADERS = {"JarName", "Port", "HttpMethod", "Path"};
    private final String fileName;
    private CSVPrinter csvPrinter;

    /**
     * Initializes the CsvReporter, creates a new CSV file with a timestamped name,
     * and writes the header row.
     *
     * @throws IOException if an I/O error occurs during file creation or header writing.
     */
    public CsvReporter() throws IOException {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss");
        this.fileName = "unauthorized_endpoints_" + dateFormat.format(new Date()) + ".csv";

        try {
            FileWriter fileWriter = new FileWriter(this.fileName);
            // Using the default CSV format, customize as needed (e.g., withHeader(HEADERS))
            // If using CSVFormat.DEFAULT.withHeader(HEADERS), it writes headers automatically.
            // However, to ensure it's always written even if no records are added,
            // and to have explicit control, we can printRecord for headers.
            this.csvPrinter = new CSVPrinter(fileWriter, CSVFormat.DEFAULT);
            this.csvPrinter.printRecord((Object[]) HEADERS); // Cast to Object[] to avoid ambiguity
            logger.info("Unauthorized endpoints will be reported to: {}", this.fileName);
        } catch (IOException e) {
            logger.error("Failed to initialize CSV writer for file {}.", this.fileName, e);
            throw e; // Re-throw to indicate initialization failure
        }
    }

    /**
     * Adds a new risk entry (an endpoint considered at risk) to the CSV file.
     *
     * @param endpointInfo Details of the endpoint at risk.
     */
    public void addRiskEntry(EndpointInfo endpointInfo) {
        if (this.csvPrinter == null) {
            logger.error("CSVPrinter not initialized. Cannot add risk entry for endpoint: {}", endpointInfo);
            return;
        }
        try {
            logger.debug("Adding at-risk endpoint to CSV report: JAR={}, Method={}, Path={}, Port={}",
                         endpointInfo.getJarName(), endpointInfo.getHttpMethod(), endpointInfo.getPath(), endpointInfo.getPort());
            this.csvPrinter.printRecord(
                    endpointInfo.getJarName(),
                    endpointInfo.getPort(),
                    endpointInfo.getHttpMethod(),
                    endpointInfo.getPath()
            );
        } catch (IOException e) {
            logger.error("Error writing record to CSV for endpoint {}: {}", endpointInfo, e.getMessage(), e);
        }
    }

    /**
     * Flushes and closes the CSVPrinter and the underlying FileWriter.
     * This should be called when reporting is complete to ensure all data is written.
     */
    public void close() {
        if (this.csvPrinter != null) {
            try {
                this.csvPrinter.flush();
                this.csvPrinter.close();
                logger.info("CSV report {} successfully written and closed.", this.fileName);
            } catch (IOException e) {
                logger.error("Error closing CSV writer for file {}.", this.fileName, e);
            }
        }
    }

    /**
     * Returns the name of the generated CSV file.
     * @return The filename.
     */
    public String getFileName() {
        return fileName;
    }
}
