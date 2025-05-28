package com.example;

public class JavaProcessDetails {
    private final String jarPath;
    private final String commandLine;
    private final int pid; // Added PID for better identification

    public JavaProcessDetails(String jarPath, String commandLine, int pid) {
        this.jarPath = jarPath;
        this.commandLine = commandLine;
        this.pid = pid;
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

    @Override
    public String toString() {
        return "JavaProcessDetails{" +
               "jarPath='" + jarPath + '\'' +
               ", commandLine='" + commandLine + '\'' +
               ", pid=" + pid +
               '}';
    }
}
