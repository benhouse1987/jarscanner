# 未授权访问扫描器 (Unauthorized Endpoint Scanner)

## 简介

本项目是一个Java应用程序，用于扫描当前系统上运行的其他Java应用程序，识别它们暴露的Web服务端点（基于Spring MVC, JAX-RS注解），并检测这些端点是否可能存在未授权访问的风险。如果一个端点在没有身份验证（如HTTP 401 Unauthorized响应）的情况下可以被访问，它将被标记为存在风险。

扫描结果会输出到一个CSV文件中，默认名为 `unauthorized_endpoints_YYYYMMDD_HHMMSS.csv`。

## 功能

- 自动发现正在运行的Java进程。
- 分析目标Java进程的JAR包，提取使用了常见Web框架（Spring MVC, JAX-RS）注解定义的HTTP端点。
- 从应用的命令行参数中尝试提取服务端口号，如果找不到则默认为8080。
- 对发现的每个端点（目前支持GET, POST, PUT, DELETE, PATCH方法）发起HTTP请求。
- 检查HTTP响应状态码，如果不是401 (Unauthorized)，则认为该端点存在未授权访问风险。
- 将所有存在风险的端点信息（包括JAR包名、HTTP方法、路径、端口号）记录到CSV报告中。
- 通过日志文件 `unauth_scanner.log` 提供详细的运行日志。

## 如何构建

本项目使用Apache Maven进行构建。

1.  确保你已经安装了Java Development Kit (JDK)（推荐版本8或更高）和Apache Maven。
2.  打开命令行/终端，导航到项目根目录（包含 `pom.xml` 文件的目录）。
3.  运行以下命令进行编译和打包：

    ```bash
    mvn clean package
    ```

    构建成功后，会在 `target/` 目录下生成一个 `unauth-scanner-1.0-SNAPSHOT.jar` (版本号可能不同) 的JAR文件。

## 如何运行

1.  构建项目后，使用以下命令运行扫描器：

    ```bash
    java -jar target/unauth-scanner-1.0-SNAPSHOT.jar
    ```

    (请将 `unauth-scanner-1.0-SNAPSHOT.jar` 替换为实际生成的文件名)

2.  扫描器启动后，会开始查找本机运行的Java进程并进行分析。相关信息会打印到控制台。
3.  详细的运行日志会记录在项目运行目录下的 `unauth_scanner.log` 文件中。
4.  扫描完成后，如果发现了风险端点，会在项目运行目录下生成一个CSV格式的报告文件，例如 `unauthorized_endpoints_20231027_103000.csv`。

## 输出说明

### 控制台输出
应用程序运行时，会在控制台打印简要的运行信息、发现的Java进程、分析的JAR包、检测到的端点以及它们的风险状态。

### 日志文件 (`unauth_scanner.log`)
此文件包含详细的运行日志，包括：
- 应用程序启动和关闭信息。
- 发现的每个Java进程的详细信息（PID，命令行参数）。
- JAR包分析过程，包括端口号的确定、类的扫描、注解的发现。
- 每个端点检查的详细信息，包括构造的HTTP请求（URL，方法）和收到的HTTP响应状态码。
- 发生的任何错误或警告。

默认日志级别为INFO。你可以通过修改 `src/main/resources/logback.xml` 文件来调整日志级别（例如，将 `<root level="INFO">` 修改为 `<root level="DEBUG">` 以获取更详细的日志）。修改后需要重新编译打包。

### CSV 报告文件
文件名格式：`unauthorized_endpoints_YYYYMMDD_HHMMSS.csv`
文件内容：
- **JarName**: 关联的JAR文件名。
- **HttpMethod**: 检测到的HTTP方法 (GET, POST, etc.)。
- **Path**: 端点的URL路径。
- **Port**: 端点运行的端口号。
- **PID**: (此列当前版本可能不直接在CSV中，但PID信息在日志中关联JAR时可见)

## 注意事项
- 本工具通过直接执行HTTP请求来判断端点是否返回401，这可能不足以覆盖所有认证机制。
- 对于POST, PUT, PATCH请求，工具默认发送一个空的JSON对象 (`{}`) 作为请求体，并设置 `Content-Type: application/json`。这可能不适用于所有API。
- 扫描器目前主要关注基于注解的Spring MVC和JAX-RS端点。其他类型的端点可能不会被检测到。
- 请在授权的情况下使用本工具，并确保遵守相关法律法规。
