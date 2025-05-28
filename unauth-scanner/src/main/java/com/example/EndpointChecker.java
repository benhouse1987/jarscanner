package com.example;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

public class EndpointChecker {

    private static final int CONNECT_TIMEOUT = 5000; // 5 seconds
    private static final int SOCKET_TIMEOUT = 5000;  // 5 seconds

    private final CloseableHttpClient httpClient;

    public EndpointChecker() {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(CONNECT_TIMEOUT)
                .setSocketTimeout(SOCKET_TIMEOUT)
                .build();
        this.httpClient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries() // Important for accurately checking a single attempt
                .build();
    }

    /**
     * Checks if a given endpoint is potentially at risk by making an HTTP request
     * and verifying if the response status is not 401 (Unauthorized).
     *
     * @param endpointInfo Details of the endpoint to check.
     * @return {@code true} if the endpoint is accessible and does not return 401,
     *         {@code false} if it returns 401 or an error occurs.
     */
    public boolean isEndpointAtRisk(EndpointInfo endpointInfo) {
        String url = "http://localhost:" + endpointInfo.getPort() + endpointInfo.getPath();
        String method = endpointInfo.getHttpMethod().toUpperCase();
        HttpUriRequest request;

        System.out.println("Checking endpoint: " + method + " " + url);

        try {
            switch (method) {
                case "GET":
                    request = new HttpGet(url);
                    break;
                case "POST":
                    HttpPost postRequest = new HttpPost(url);
                    // Send an empty JSON body as a common default for POST requests
                    try {
                        postRequest.setEntity(new StringEntity("{}")); 
                        postRequest.setHeader("Content-Type", "application/json");
                    } catch (UnsupportedEncodingException e) {
                        // Should not happen with StringEntity and UTF-8
                        System.err.println("Error setting entity for POST request: " + e.getMessage());
                        return false; // Cannot proceed with malformed request
                    }
                    request = postRequest;
                    break;
                case "PUT":
                    HttpPut putRequest = new HttpPut(url);
                     try {
                        putRequest.setEntity(new StringEntity("{}"));
                        putRequest.setHeader("Content-Type", "application/json");
                    } catch (UnsupportedEncodingException e) {
                        System.err.println("Error setting entity for PUT request: " + e.getMessage());
                        return false; 
                    }
                    request = putRequest;
                    break;
                case "DELETE":
                    request = new HttpDelete(url);
                    break;
                case "PATCH":
                    HttpPatch patchRequest = new HttpPatch(url);
                    try {
                        patchRequest.setEntity(new StringEntity("{}"));
                        patchRequest.setHeader("Content-Type", "application/json");
                    } catch (UnsupportedEncodingException e) {
                         System.err.println("Error setting entity for PATCH request: " + e.getMessage());
                        return false;
                    }
                    request = patchRequest;
                    break;
                case "ANY": // Treat "ANY" or other unknown/uncommon as GET for a basic check
                default:
                    System.out.println("HTTP method '" + method + "' not specifically handled or is 'ANY'. Defaulting to GET for check.");
                    request = new HttpGet(url);
                    break;
            }

            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                System.out.println("Response for " + method + " " + url + ": " + statusCode);
                // Consume entity to free resources, important for connection reuse
                EntityUtils.consumeQuietly(response.getEntity()); 
                return statusCode != 401; // At risk if not 401
            }
        } catch (IOException e) {
            // This includes ConnectTimeoutException, SocketTimeoutException, NoHttpResponseException, ConnectException etc.
            System.err.println("Error executing request to " + url + ": " + e.getMessage());
            return false; // Not verifiable as at risk if an IO error occurs
        }
    }

    /**
     * Closes the underlying HTTP client. Should be called when the checker is no longer needed.
     */
    public void close() {
        try {
            this.httpClient.close();
        } catch (IOException e) {
            System.err.println("Error closing HttpClient: " + e.getMessage());
        }
    }
}
