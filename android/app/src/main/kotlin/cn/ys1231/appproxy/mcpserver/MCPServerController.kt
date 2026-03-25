package cn.ys1231.appproxy.mcpserver

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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.Authentication
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
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


class MCPServerController private constructor() {
    companion object {
        @Volatile
        private var instance: MCPServerController? = null

        fun getInstance(): MCPServerController {
            return instance ?: synchronized(this) {
                instance ?: MCPServerController().also {
                    instance = it
                }
            }
        }

//        fun getInstance(mainActivity: MainActivity): MCPServerController {
//            return instance ?: synchronized(this) {
//                instance ?: MCPServerController(mainActivity).also {
//                    instance = it
//                }
//            }
//        }

        fun resetInstance() {
            instance?.stopMcpServer()
            instance = null
        }
    }

    private val TAG = "iyue->${this.javaClass.simpleName}"
    private var vpnController: VpnServiceController? = null
    private val MCP_SESSION_ID_HEADER = "mcp-session-id"
    private var _authToken: String = "appproxy"
    private var authToken: String
        get() = _authToken
        set(value) {
            _authToken = value
        }
    private var _mcpPort: Int = 12345
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

    private var nettyServer: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? =
        embeddedServer(Netty, host = "0.0.0.0", port = mcpPort) {
            configureServer()
        }

    fun startMcpServer() {
        nettyServer?.start(wait = false)
        Log.d(TAG, "MCP server started on port $mcpPort")
    }

    fun stopMcpServer() {
        nettyServer?.stop()
        Log.d(TAG, "MCP server stopped")
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
                        io.ktor.server.auth.UserIdPrincipal("mcp-client")
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
        // 启动VPN
        server.addTool(
            name = "start_vpn",
            description = "Start VPN service with proxy configuration. Example: {proxyHost: '192.168.0.2', proxyPort: '8080', proxyType: 'http', proxyName: 'vpn', proxyUser: '', proxyPass: '', appProxyPackageList: '[\"com.qihoo.contents\", \"com.whatsapp\"]'}",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("proxyName") {
                        put("type", "string")
                        put("description", "Friendly name for this proxy configuration")
                        put("examples", buildJsonArray {
                            add("vpn")
                            add("Office Proxy")
                            add("Home Network")
                        })
                    }
                    putJsonObject("proxyHost") {
                        put("type", "string")
                        put("description", "Proxy server host address (IP or hostname)")
                        put("examples", buildJsonArray {
                            add("192.168.0.2")
                            add("www.example.com")
                        })
                    }
                    putJsonObject("proxyPort") {
                        put("type", "string")
                        put("description", "Proxy server port number")
                        put("examples", buildJsonArray {
                            add("8080")
                            add("1080")
                        })
                    }
                    putJsonObject("proxyType") {
                        put("type", "string")
                        put("description", "Proxy protocol type")
                        put("enum", buildJsonArray {
                            add("http")
                            add("socks5")
                        })
                        put("default", "http")
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
                            "JSON array string of app package names to proxy. Empty array [] means proxy all apps except this VPN app"
                        )
                        put("default", "[]")
                        put("examples", buildJsonArray {
                            add("[]")
                            add("[\"com.android.chrome\"]")
                            add("[\"com.example\", \"com.whatsapp\"]")
                        })
                    }
                },
                required = listOf("proxyHost", "proxyPort", "proxyType", "proxyName")
            ),
        ) { request ->
            try {
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
                if (proxyHost.isBlank() || proxyPort.isBlank()) {
                    return@addTool CallToolResult(
                        content = listOf(
                            TextContent("Error: proxyHost and proxyPort are required")
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
                    "appProxyPackageList" to appListJson
                )
//                mainActivity.currentProxy =  config
//                val result = mainActivity.checkVpnPermissionAndStartVpnService()

                val result: String = vpnController!!.startVpn(config)

                // 记录日志
                Log.d(TAG, "VPN start result: $result")

                CallToolResult(
                    content = listOf(
                        TextContent(result)
                    ),
                    isError = result.startsWith("Error") || result.contains("not granted")
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error starting VPN: ${e.message}", e)
                CallToolResult(
                    content = listOf(
                        TextContent("Error starting VPN: ${e.message}")
                    ),
                    isError = true
                )
            }
        }
        // 停止VPN
        // 修改参数


        server.addPrompt(
            name = "check_proxy_status",
            description = "Check the status of the proxy server"
        ) { _ ->
            GetPromptResult(
                description = "VPN status check prompt",
                messages = listOf(
                    PromptMessage(
                        role = Role.User,
                        content = TextContent("What is the current status of the VPN? Is it running?"),
                    ),
                    PromptMessage(
                        role = Role.Assistant,
                        content = TextContent(
                            """
                            I'll check the VPN status for you using the get_proxy_status tool.
                            
                            This will tell us:
                            - Whether the VPN is currently running
                            - If the VPN service is available
                            - Current configuration (if running)
                            
                            Let me check that now.
                        """.trimIndent()
                        )
                    )
                )
            )
        }


        // ---------
        server.addPrompt(
            name = "greeting-template",
            description = "A simple greeting prompt template",
            arguments = listOf(
                PromptArgument(
                    name = "name",
                    description = "Name to include in greeting",
                    required = true,
                ),
            ),
        ) { request ->
            val name = request.arguments?.get("name") ?: "World"
            GetPromptResult(
                messages = listOf(
                    PromptMessage(
                        role = Role.User,
                        content = TextContent("Please greet $name in a friendly manner."),
                    ),
                ),
            )
        }

        server.addTool(
            name = "startVPN",
            description = "A simple greeting tool",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Name to greet")
                    }
                },
                required = listOf("name"),
            ),
        ) { request ->
            val name = request.arguments?.get("name")?.jsonPrimitive?.content ?: "World"
            CallToolResult(content = listOf(TextContent("Hello, $name!")))
        }

        return server
    }

}