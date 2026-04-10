package cn.ys1231.appproxy.mcpserver

import android.content.Context
import android.net.VpnService
import cn.ys1231.appproxy.IyueService.VpnServiceController
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.ktor.util.collections.ConcurrentMap
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject


class MCPServer private constructor(
    val context: Context,
) {
    companion object {
        @Volatile
        private var instance: MCPServer? = null

        fun getInstance(context: Context): MCPServer {
            return instance ?: synchronized(this) {
                instance ?: MCPServer(context).also {
                    instance = it
                }
            }
        }

        fun resetInstance() {
            instance?.stopMcpServer()
            instance = null
        }
    }

    private val TAG = "iyue->${this.javaClass.simpleName}"
    private var vpnController: VpnServiceController? = null
    private val MCP_SESSION_ID_HEADER = "mcp-session-id"
    private var _authToken: String = ""
    private var authToken: String
        get() = _authToken
        set(value) {
            _authToken = value
            Log.d(TAG, "MCP auth token changed to $value")
        }
    private var _mcpPort: Int = 0
    private var mcpPort: Int
        get() = _mcpPort
        set(value) {
            _mcpPort = value
            Log.d(TAG, "MCP server port changed to $value")
        }

    fun setVpnController(vpnController: VpnServiceController?) {
        this.vpnController = vpnController
        Log.d(TAG, "setVpnController: ${vpnController.toString()}")
    }

    private var nettyServer: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    fun startMcpServer() {
        nettyServer = embeddedServer(Netty, host = "0.0.0.0", port = mcpPort) {
            configureServer()
        }
        nettyServer?.start(wait = false)
        Log.d(TAG, "MCP server started on port $mcpPort")
    }

    fun stopMcpServer() {
        if (nettyServer != null){
            nettyServer?.stop()
        }
        Log.d(TAG, "MCP server stopped")
        nettyServer = null
    }

    fun updateMcpPort(port: Int?) {
        if (port != null && port != mcpPort) {
            mcpPort = port
            if (nettyServer != null) {
                nettyServer?.stop()
                Log.d(TAG, "Restart MCP Server port $mcpPort")
                startMcpServer()
            }
        }
    }

    fun updateMcpAuth(auth: String?) {
        if (auth != null && auth != authToken) {
            authToken = auth
            if (nettyServer != null) {
                nettyServer?.stop()
                Log.d(TAG, "Restart MCP Server auth $authToken")
                startMcpServer()
            }
        }
    }
    fun Application.configureServer() {
        // 安装 CORS 跨域支持，如果启用了认证则需要配置
        installCors(authEnabled = true)
        // 安装内容协商插件，使用 MCP 自定义的 JSON 序列化配置
        install(ContentNegotiation) {
            json(McpJson)
        }
        // 需要认证的复杂配置
        configureAuthenticatedMcp(authToken)
    }

    private fun Application.configureAuthenticatedMcp(authToken: String) {
        // 安装 SSE（Server-Sent Events）支持，用于服务器推送消息
        install(SSE)
        // 安装认证插件
        install(Authentication) {
            // 配置 Bearer Token 认证方案，命名为 "mcp-bearer"
            bearer("mcp-bearer") {
                // 定义认证逻辑：验证提供的令牌是否匹配
                authenticate { credential ->
                    if (credential.token == authToken) {
                        // 认证成功，创建用户主体
                        UserIdPrincipal("mcp-client")
                    } else {
                        // 认证失败，返回 null
                        null
                    }
                }
            }
        }

        // 存储和管理多个传输通道的并发 Map，key 是 session ID
        val transports = ConcurrentMap<String, StreamableHttpServerTransport>()

        // 配置路由
        routing {
            // 对以下路由应用认证要求
            authenticate("mcp-bearer") {
                // 所有 /mcp 路径下的请求都需要认证
                route("/mcp") {
                    // SSE 端点，用于建立服务器发送事件连接
                    sse {
                        // 查找或创建对应的传输通道
                        val transport = findTransport(call, transports) ?: return@sse
                        // 处理 SSE 请求
                        transport.handleRequest(this, call)
                    }

                    // POST 端点，用于发送消息到服务器
                    post {
                        // 获取或创建传输通道
                        val transport = getOrCreateTransport(call, transports) ?: return@post
                        // 处理 POST 请求
                        transport.handleRequest(null, call)
                    }

                    // DELETE 端点，用于关闭会话
                    delete {
                        // 查找现有的传输通道
                        val transport = findTransport(call, transports) ?: return@delete
                        // 处理 DELETE 请求
                        transport.handleRequest(null, call)
                    }
                }
            }
        }
    }

    private suspend fun findTransport(
        call: ApplicationCall,
        transports: ConcurrentMap<String, StreamableHttpServerTransport>,
    ): StreamableHttpServerTransport? {
        // 从请求头中获取会话 ID
        val sessionId = call.request.header(MCP_SESSION_ID_HEADER)
        // 检查会话 ID 是否存在
        if (sessionId.isNullOrEmpty()) {
            // 没有提供有效的会话 ID，返回 400 错误
            call.respond(HttpStatusCode.BadRequest, "Bad Request: No valid session ID provided")
            return null
        }
        // 根据会话 ID 查找对应的传输通道
        val transport = transports[sessionId]
        // 检查传输通道是否存在
        if (transport == null) {
            // 会话 ID 存在但找不到对应的传输通道，返回 404 错误
            call.respond(HttpStatusCode.NotFound, "Session not found")
            return null
        }
        // 成功找到传输通道
        return transport
    }

    /**
     * 获取或创建传输通道
     * @param call 当前的应用调用上下文
     * @param transports 传输通道映射表
     * @return 获取到或新创建的传输通道，如果出错则返回 null
     */
    private suspend fun getOrCreateTransport(
        call: ApplicationCall,
        transports: ConcurrentMap<String, StreamableHttpServerTransport>,
    ): StreamableHttpServerTransport? {
        // 尝试从请求头中获取已有的会话 ID
        val sessionId = call.request.header(MCP_SESSION_ID_HEADER)
        // 如果提供了会话 ID，说明是已有会话
        if (sessionId != null) {
            val transport = transports[sessionId]
            if (transport == null) {
                // 会话 ID 存在但找不到对应的传输通道，返回 404 错误
                call.respond(HttpStatusCode.NotFound, "Session not found")
            }
            return transport
        }

        // 如果没有会话 ID，说明是新会话，需要创建新的传输通道
        // 创建传输通道配置，启用 JSON 响应格式
        val configuration = StreamableHttpServerTransport.Configuration(
            enableJsonResponse = true,
        )
        // 根据配置创建传输通道实例
        val transport = StreamableHttpServerTransport(configuration)

        // 设置会话初始化回调：当会话初始化完成时，将传输通道存入映射表
        transport.setOnSessionInitialized { initializedSessionId ->
            transports[initializedSessionId] = transport
        }
        // 设置会话关闭回调：当会话关闭时，从映射表中移除对应的传输通道
        transport.setOnSessionClosed { closedSessionId ->
            transports.remove(closedSessionId)
        }

        // 创建 MCP 服务器实例
        val server = createMcpServer()
        // 设置服务器关闭时的清理逻辑：确保传输通道被移除
        server.onClose {
            transport.sessionId?.let { transports.remove(it) }
        }
        // 在服务器中创建新的会话，传入传输通道
        server.createSession(transport)

        // 返回新创建的传输通道
        return transport
    }

    private fun Application.installCors(authEnabled: Boolean = false) {
        // 安装 CORS 插件
        install(CORS) {
            // 允许任何主机访问（生产环境中应该限制具体的域名）
            anyHost() // Don't do this in production if possible. Try to limit it.
            // 允许的 HTTP 方法
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Delete)
            // 允许非简单的 Content-Type（如 application/json）
            allowNonSimpleContentTypes = true
            // 允许的请求头
            allowHeader("Mcp-Session-Id")      // MCP 会话 ID 头部
            allowHeader("Mcp-Protocol-Version") // MCP 协议版本头部
            // 暴露给浏览器的响应头
            exposeHeader("Mcp-Session-Id")      // 让浏览器可以读取会话 ID
            exposeHeader("Mcp-Protocol-Version") // 让浏览器可以读取协议版本
            // 如果启用了认证，还需要允许 Authorization 头部
            if (authEnabled) {
                allowHeader(HttpHeaders.Authorization)
            }
        }
    }

    private fun createMcpServer(): Server {
        val server = Server(
            Implementation(
                name = "appproxy-mcp-server",
                version = "1.0.0",
            ),
            ServerOptions(
                capabilities = ServerCapabilities(
                    prompts = ServerCapabilities.Prompts(listChanged = true),
                    tools = ServerCapabilities.Tools(listChanged = true),
                    logging = ServerCapabilities.Logging,
                ),
            ),
        )

        // ----------
        // 启动 proxy
        server.addTool(
            name = "start_proxy",
            description = "Start proxy service with proxy configuration. Example: {proxyHost: '192.168.0.2', proxyPort: '8080', proxyType: 'http', proxyName: 'vpn', proxyUser: '', proxyPass: '', appProxyPackageList: '[\"com.qihoo.contents\", \"com.whatsapp\"]'}",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("proxyName") {
                        put("type", "string")
                        put("description", "Friendly name for this proxy configuration")
                        put("default", "test")
                        put("examples", buildJsonArray {
                            add("vpn")
                            add("Office Proxy")
                            add("Home Network")
                        })
                    }
                    putJsonObject("proxyHost") {
                        put("type", "string")
                        put("description", "Proxy server host address (IP or hostname)")
                        put("default", "192.168.0.2")
                        put("examples", buildJsonArray {
                            add("192.168.0.2")
                            add("www.example.com")
                        })
                    }
                    putJsonObject("proxyPort") {
                        put("type", "string")
                        put("description", "Proxy server port number")
                        put("default", "8080")
                        put("examples", buildJsonArray {
                            add("8080")
                            add("1080")
                        })
                    }
                    putJsonObject("proxyType") {
                        put("type", "string")
                        put("description", "Proxy protocol type")
                        put("default", "http")
                        put("enum", buildJsonArray {
                            add("http")
                            add("socks5")
                        })
                    }
                    putJsonObject("proxyUser") {
                        put("type", "string")
                        put(
                            "description",
                            "Proxy username for authentication (optional, leave empty if not needed)"
                        )
                        put("default", "")
                    }
                    putJsonObject("proxyPass") {
                        put("type", "string")
                        put(
                            "description",
                            "Proxy password for authentication (optional, leave empty if not needed)"
                        )
                        put("default", "")
                    }
                    putJsonObject("appProxyPackageList") {
                        put("type", "string")
                        put(
                            "description",
                            "[\"com.example\", \"com.whatsapp\"] Empty array [] means proxy all apps except this proxy app"
                        )
                        put("default", "[]")
                        put("examples", buildJsonArray {
                            add("[\"com.example\", \"com.whatsapp\"]")
                            add("[\"com.example\"]")
                            add("[]")
                        })
                    }
                },
                required = listOf("proxyHost", "proxyPort", "proxyType", "proxyName")
            ),
        ) { request ->
            try {
                if (vpnController!!.getVpnStatus() == true){
                    return@addTool CallToolResult(content = listOf(TextContent("Proxy is already running")), isError = true)
                }
                Log.d(TAG, "start_vpn called with arguments: ${request.arguments}")
                val proxyHost = request.arguments?.get("proxyHost")?.jsonPrimitive?.content ?: ""
                val proxyPort = request.arguments?.get("proxyPort")?.jsonPrimitive?.content ?: ""
                val proxyType =
                    request.arguments?.get("proxyType")?.jsonPrimitive?.content ?: "http"
                val proxyName = request.arguments?.get("proxyName")?.jsonPrimitive?.content ?: "VPN"
                val proxyUser = request.arguments?.get("proxyUser")?.jsonPrimitive?.content ?: ""
                val proxyPass = request.arguments?.get("proxyPass")?.jsonPrimitive?.content ?: ""
                val appListJson =
                    request.arguments?.get("appProxyPackageList")?.jsonPrimitive?.content ?: "[]"

                // 验证必需参数
                if (proxyName.isBlank() || proxyHost.isBlank() || proxyPort.isBlank()) {
                    Log.e(TAG, "Error: proxyName or proxyHost or proxyPort are required")
                    return@addTool CallToolResult(
                        content = listOf(
                            TextContent("Error: proxyName or proxyHost or proxyPort are required")
                        ),
                        isError = true
                    )
                }
                // 校验 用户名 密码 长度大于30 报错
                if (proxyUser.length > 30 || proxyPass.length > 30) {
                    Log.e(TAG, "Error: proxyUser or proxyPass is too long")
                    return@addTool CallToolResult(
                        content = listOf(
                            TextContent("Error: proxyUser or proxyPass is too long")
                        ),
                        isError = true
                    )
                }
                val type = object : TypeToken<List<String>>(){}.type
                val appList: List<String> = Gson().fromJson(appListJson, type)
                val packageList = vpnController!!.getPackageList()
                
                if (!packageList.containsAll(appList)){
                    val missingPackages = appList.filter { it !in packageList }
                    Log.e(TAG, "Error: $missingPackages is not valid")
                    return@addTool CallToolResult(
                        content = listOf(
                            TextContent("Error: $missingPackages is not valid")
                        ),
                        isError = true
                    )
                }
                val config = mapOf(
                    "proxyHost" to proxyHost,
                    "proxyPort" to proxyPort,
                    "proxyType" to proxyType,
                    "proxyName" to proxyName,
                    "proxyUser" to proxyUser,
                    "proxyPass" to proxyPass,
                    "appProxyPackageList" to appList
                )

                val intent = VpnService.prepare(context)
                if (intent != null) {
                    return@addTool CallToolResult(
                        content = listOf(
                            TextContent("You must start the proxy once, or authorize VPN manually.")
                        ),
                        isError = true
                    )
                }

                val result: String = vpnController!!.startVpn(config)
                vpnController!!.setVpnConfig(config)
                // 记录日志
                Log.i(TAG, "Proxy start result: $result")

                return@addTool CallToolResult(
                    content = listOf(
                        TextContent(result)
                    ),
                    isError = result.startsWith("Error") || result.contains("not granted")
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error starting proxy: ${e.message}", e)
                return@addTool CallToolResult(
                    content = listOf(
                        TextContent("Error starting proxy: ${e.message}")
                    ),
                    isError = true
                )
            }
        }
        // 停止 proxy
        server.addTool(
            "stop_proxy",
            description = "Stop the proxy service",
            inputSchema = ToolSchema()
        ){
            _->
                try {
                    Log.d(TAG, "stop_proxy called")
                    val result: String = vpnController!!.stopVpn()
                    Log.d(TAG, "Proxy stop result: $result")
                    return@addTool CallToolResult(
                        content = listOf(
                            TextContent(result)
                        ),
                        isError = result.startsWith("Error") || result.contains("not granted")
                    )
                }catch (e: Exception){
                    Log.e(TAG, "Error stopping proxy: ${e.message}", e)
                    return@addTool CallToolResult(
                        content = listOf(
                            TextContent("Error stopping proxy: ${e.message}")
                        ),
                        isError = true
                    )
                }
        }
        // 获取 proxy 状态
        server.addTool(
            "get_proxy_status",
            description = "Get the status of the proxy service",
            inputSchema = ToolSchema()
        ){
            _-> try {
                Log.d(TAG, "get_proxy_status called")
                val result: Boolean = vpnController!!.getVpnStatus() == true
                Log.d(TAG, "Proxy status: $result")
            return@addTool CallToolResult(
                    content = listOf(
                        TextContent(if (result) "Proxy is running" else "Proxy is not running")
                    ),
                    isError = !result
                )
            } catch (e: Exception) {
                return@addTool CallToolResult(
                    content = listOf(
                        TextContent("Error getting Proxy status: ${e.message}")
                    ),
                    isError = true
                )
            }
        }
        // 查看当前 proxy 设置
        server.addTool(
            "get_proxy_config",
            description = "Get the current proxy configuration",
            inputSchema = ToolSchema()
        ){
            _ -> try {
            val result: Boolean = vpnController!!.getVpnStatus() == true
                return@addTool CallToolResult(
                    content = listOf(
                        TextContent(
                            "${if (result) "Proxy is running" else "Proxy is not running"}, Current proxy configuration:"+ vpnController!!.getVpvConfig().toString() )
                    )
                )
            }catch (e: Exception){
                return@addTool CallToolResult(
                    content = listOf(
                        TextContent("Error getting proxy configuration: ${e.message}")
                    ),
                    isError = true
                )
            }
        }

        // 修改代理参数 可选 其一 可多选 {proxyHost: '192.168.0.2', proxyPort: '8080', proxyType: 'http', proxyUser: '', proxyPass: '', appProxyPackageList: '["com.qihoo.contents", "com.whatsapp"]'}
        server.addTool(
            "update_proxy_config",
            description = "Update the proxy configuration, proxyHost, proxyPort, proxyType cannot be empty, Tip: Ask user for required fields if not provided",
            inputSchema = ToolSchema(
                properties = buildJsonObject{
                    putJsonObject("proxyHost") {
                        put("type", "string")
                        put("description", "Proxy host address, default: device LAN IP")
                        put("default", "192.168.0.10")
                        put("examples", buildJsonArray {
                            add("192.168.0.2")
                            add("10.0.0.1")
                        })
                    }
                    putJsonObject("proxyPort") {
                        put("type", "string")
                        put("description", "Proxy port number")
                        put("default", "8080")
                        put("examples", buildJsonArray {
                            add("8080")
                            add("8081")
                        })
                    }
                    putJsonObject("proxyType") {
                        put("type", "string")
                        put("description", "Proxy type")
                        put("default", "http")
                        put("enum", buildJsonArray {
                            add("http")
                            add("socks5")
                        })
                    }
                    putJsonObject("proxyUser") {
                        put("type", "string")
                        put("description", "Proxy username")
                        put("default", "")
                    }
                    putJsonObject("proxyPass") {
                        put("type", "string")
                        put("description", "Proxy password")
                        put("default", "")
                    }
                    putJsonObject("appProxyPackageList") {
                        put("type", "string")
                        put(
                            "description",
                            "[\"com.example\", \"com.whatsapp\"] Empty array [] means proxy all apps except this proxy app"
                        )
                        put("default", "[]")
                        put("examples", buildJsonArray {
                            add("[\"com.example\", \"com.whatsapp\"]")
                            add("[\"com.example\"]")
                            add("[]")
                        })
                    }
                }
            )
        ){
            request ->
            try {
                if (vpnController!!.getVpnStatus() == false){
                    return@addTool CallToolResult(content = listOf(TextContent("Proxy is not running")), isError = true)
                }
                val proxyHost = request.arguments?.get("proxyHost")?.jsonPrimitive?.content ?: ""
                val proxyPort = request.arguments?.get("proxyPort")?.jsonPrimitive?.content ?: ""
                val proxyType =
                    request.arguments?.get("proxyType")?.jsonPrimitive?.content ?: "http"
                val proxyUser = request.arguments?.get("proxyUser")?.jsonPrimitive?.content ?: ""
                val proxyPass = request.arguments?.get("proxyPass")?.jsonPrimitive?.content ?: ""
                val appListJson =
                    request.arguments?.get("appProxyPackageList")?.jsonPrimitive?.content ?: "[]"

                val type = object : TypeToken<List<String>>(){}.type
                val appList: List<String> = Gson().fromJson(appListJson, type)
                val packageList = vpnController!!.getPackageList()

                if (appList.isNotEmpty() && !packageList.containsAll(appList)){
                    val missingPackages = appList.filter { it !in packageList }
                    Log.e(TAG, "Error: $missingPackages is not valid")
                    return@addTool CallToolResult(
                        content = listOf(
                            TextContent("Error: $missingPackages is not valid")
                        ),
                        isError = true
                    )
                }
                if (proxyHost.isEmpty() || proxyPort.isEmpty() || proxyType.isEmpty()){
                    Log.e(TAG, "Error: proxyHost, proxyPort, proxyType cannot be empty")
                    return@addTool CallToolResult(
                        content = listOf(
                            TextContent("Error: proxyHost, proxyPort, proxyType cannot be empty")
                        ),
                        isError = true
                    )
                }

                var config = mapOf(
                    "proxyHost" to proxyHost,
                    "proxyPort" to proxyPort,
                    "proxyType" to proxyType,
                    "proxyUser" to proxyUser,
                    "proxyPass" to proxyPass,
                    "appProxyPackageList" to appList,
                )
                val isRestartMcpServer = vpnController!!.setVpnConfig(config)
                if (isRestartMcpServer){
                    vpnController!!.stopVpn()
                    config = vpnController!!.getVpvConfig()!!
                    val result: String = vpnController!!.startVpn(config)
                    // 记录日志
                    Log.i(TAG, "Proxy start result: $result")

                    return@addTool CallToolResult(
                        content = listOf(
                            TextContent(result)
                        ),
                        isError = result.startsWith("Error") || result.contains("not granted")
                    )
                }
                return@addTool CallToolResult(
                    content = listOf(
                        TextContent("Proxy no need update")
                    ),
                    isError = false
                )

            }catch (e: Exception) {
                return@addTool CallToolResult(
                    content = listOf(
                        TextContent("Error updating proxy configuration: ${e.message}")
                    ),
                    isError = true
                )
            }
        }
        server.addPrompt(
            "how_to_use_appproxy-mcp",
            description = "Guide for using appproxy-mcp",
        ){ _ ->
            GetPromptResult(
                description = "appproxy-mcp usage guide",
                messages = listOf(
                    PromptMessage(
                        role = Role.User,
                        content = TextContent("""
                            |## appproxy-mcp Guide
                            |Tools: start_proxy, stop_proxy, get_proxy_status, get_proxy_config, update_proxy_config
                            |start_proxy: proxyHost(default: device LAN IP), proxyPort, proxyType(http/socks5), proxyName, [proxyUser, proxyPass, appProxyPackageList]
                            |appProxyPackageList: JSON array e.g. ["com.whatsapp"], [] = all apps
                            |Note: VPN permission required first use; packages must be installed apps
                        """.trimMargin()),
                    ),
                ),
            )
        }
        return server
    }

}