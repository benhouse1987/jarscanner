package com.example;

public class EndpointInfo {
    private final String jarName;
    private final String httpMethod;
    private final String path;
    private final int port;
    private final String contextPath;

    public EndpointInfo(String jarName, String httpMethod, String path, int port, String contextPath) {
        this.jarName = jarName;
        this.httpMethod = httpMethod;
        this.path = path;
        this.port = port;
        this.contextPath = contextPath;
    }

    public String getJarName() {
        return jarName;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public String getPath() {
        return path;
    }

    public int getPort() {
        return port;
    }

    public String getContextPath() {
        return contextPath;
    }

    @Override
    public String toString() {
        return "EndpointInfo{" +
               "jarName='" + jarName + '\'' +
               ", httpMethod='" + httpMethod + '\'' +
               ", path='" + path + '\'' +
               ", port=" + port +
               ", contextPath='" + contextPath + '\'' +
               '}';
    }
}
