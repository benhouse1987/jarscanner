package com.example;

public class JavaProcessDetails {
    private final String jarPath;
    private final String commandLine;
    private final int pid; // Added PID for better identification
    private final int port;

    public JavaProcessDetails(String jarPath, String commandLine, int pid, int port) {
        this.jarPath = jarPath;
        this.commandLine = commandLine;
        this.pid = pid;
        this.port = port;
    }

    public String getJarPath() {
        return jarPath;
    }

    public String getCommandLine() {
        return commandLine;
    }

    public int getPid() {
        return pid;
    }

    public int getPort() {
        return port;
    }

    @Override
    public String toString() {
        return "JavaProcessDetails{" +
               "jarPath='" + jarPath + '\'' +
               ", commandLine='" + commandLine + '\'' +
               ", pid=" + pid +
               ", port=" + port +
               '}';
    }
}
