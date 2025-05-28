package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.regex.Pattern;

public class EndpointChecker {

    private static final Logger logger = LoggerFactory.getLogger(EndpointChecker.class);
    private static final int CONNECT_TIMEOUT = 5000; // 5 seconds
    private static final int SOCKET_TIMEOUT = 5000;  // 5 seconds
    private static final String PATH_VARIABLE_PLACEHOLDER = "1";
    private static final Pattern PATH_VARIABLE_PATTERN = Pattern.compile("\\{[^/]+?\\}");

    private final CloseableHttpClient httpClient;

    public EndpointChecker() {
        logger.debug("Initializing EndpointChecker with connect_timeout={}ms, socket_timeout={}ms", CONNECT_TIMEOUT, SOCKET_TIMEOUT);
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(CONNECT_TIMEOUT)
                .setSocketTimeout(SOCKET_TIMEOUT)
                .build();
        this.httpClient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries() // Important for accurately checking a single attempt
                .build();
        logger.debug("HttpClient initialized.");
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
        String originalPath = endpointInfo.getPath();
        String sanitizedPath = PATH_VARIABLE_PATTERN.matcher(originalPath).replaceAll(PATH_VARIABLE_PLACEHOLDER);

        if (!originalPath.equals(sanitizedPath)) {
            logger.debug("Path variable(s) found in original path \"{}\". Sanitized to \"{}\" for checking.", originalPath, sanitizedPath);
        }

        String url = "http://localhost:" + endpointInfo.getPort() + sanitizedPath;
        String method = endpointInfo.getHttpMethod().toUpperCase();
        HttpUriRequest request;

        logger.info("Checking endpoint: {} {} (on port {}){}", method, originalPath, endpointInfo.getPort(), originalPath.equals(sanitizedPath) ? "" : " [actual URL checked: " + url + "]");
        logger.debug("Building {} request for URL: {}", method, url);

        try {
            switch (method) {
                case "GET":
                    request = new HttpGet(url);
                    break;
                case "POST":
                    HttpPost postRequest = new HttpPost(url);
                    try {
                        logger.trace("Setting JSON request body '{}' and Content-Type header for {} request to {}", "{}", method, url);
                        postRequest.setEntity(new StringEntity("{}")); 
                        postRequest.setHeader("Content-Type", "application/json");
                    } catch (UnsupportedEncodingException e) {
                        logger.warn("Error setting entity for {} request to {}: {}", method, url, e.getMessage(), e);
                        return false; 
                    }
                    request = postRequest;
                    break;
                case "PUT":
                    HttpPut putRequest = new HttpPut(url);
                     try {
                        logger.trace("Setting JSON request body '{}' and Content-Type header for {} request to {}", "{}", method, url);
                        putRequest.setEntity(new StringEntity("{}"));
                        putRequest.setHeader("Content-Type", "application/json");
                    } catch (UnsupportedEncodingException e) {
                        logger.warn("Error setting entity for {} request to {}: {}", method, url, e.getMessage(), e);
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
                        logger.trace("Setting JSON request body '{}' and Content-Type header for {} request to {}", "{}", method, url);
                        patchRequest.setEntity(new StringEntity("{}"));
                        patchRequest.setHeader("Content-Type", "application/json");
                    } catch (UnsupportedEncodingException e) {
                         logger.warn("Error setting entity for {} request to {}: {}", method, url, e.getMessage(), e);
                        return false;
                    }
                    request = patchRequest;
                    break;
                case "ANY": 
                default:
                    logger.debug("HTTP method '{}' not specifically handled or is 'ANY'. Defaulting to GET for check at URL: {}", method, url);
                    request = new HttpGet(url);
                    break;
            }

            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                logger.debug("Response for {} request to {}: Status {}", method, url, statusCode);
                EntityUtils.consumeQuietly(response.getEntity()); 
                return statusCode != 401; // At risk if not 401
            }
        } catch (IOException e) {
            logger.warn("Error executing {} request to {}: {}. Endpoint considered not at risk due to error.", method, url, e.getMessage());
            logger.debug("Full exception details for {} {}:", method, url, e); 
            return false; 
        }
    }

    /**
     * Closes the underlying HTTP client. Should be called when the checker is no longer needed.
     */
    public void close() {
        logger.debug("Closing HttpClient.");
        try {
            this.httpClient.close();
            logger.info("HttpClient closed successfully.");
        } catch (IOException e) {
            logger.error("Error closing HttpClient.", e);
        }
    }
}
