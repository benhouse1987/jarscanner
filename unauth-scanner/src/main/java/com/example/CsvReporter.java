package com.example;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class CsvReporter {

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
            System.out.println("CSV report will be generated at: " + this.fileName);
        } catch (IOException e) {
            System.err.println("Error initializing CSVReporter: " + e.getMessage());
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
            System.err.println("CSVPrinter not initialized. Cannot add risk entry.");
            return;
        }
        try {
            this.csvPrinter.printRecord(
                    endpointInfo.getJarName(),
                    endpointInfo.getPort(),
                    endpointInfo.getHttpMethod(),
                    endpointInfo.getPath()
            );
        } catch (IOException e) {
            System.err.println("Error writing record to CSV for endpoint " + endpointInfo + ": " + e.getMessage());
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
                System.out.println("CSV report generation complete: " + this.fileName);
            } catch (IOException e) {
                System.err.println("Error closing CSVPrinter: " + e.getMessage());
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
