- fix(vpn): :bug: #46 修复 VPN 中断后无法抢占接口
- chore(android): 升级依赖版本及优化构建配置
- 将 ktor 依赖库版本从 3.4.1 升级至 3.4.2
- 将 kotlin-sdk-server 版本从 0.9.0 升级至 0.11.1
- 禁用 android.enableJetifier 以优化构建性能
- 启用 Gradle 守护进程、并行构建、按需配置及缓存提升构建速度
- 开启 Kotlin 增量编译
- 在启动 VPN 逻辑中添加权限检查调用
- 更新应用版本号至 0.2.7+27
---
- feat(android): :ambulance: 启动申请通知和 vpn 权限不再走用时申请逻辑
- fix(McpServer): :fire: 修复 McpServer 后台无法访问
- fix(mcpserver): 修改用户名密码长度校验限制
- 将用户名密码长度限制从10修改为30
---
- feat(appproxy): #43 支持 MCP 服务远程调用
- 在配置文件中新增 MCP 服务支持远程调用
- 增加 mcpServers 配置示例，支持 HTTP 流式访问并带有授权头
- 更新 pubspec.yaml 描述，注明支持 MCP 调用
- README 增加 MCP 远程调用使用说明和示例配置
- 补充相关截图，展示 MCP 功能界面
- **必须先授权VPN启动权限**
- 修复更新代理配置校验错误
```shell
{
  "mcpServers": {
    "appproxy-mcp": {
      "type": "http",
      "url": "http://192.168.0.10:12345/mcp",
      "headers": {
        "Authorization": "Bearer appproxy"
      }
    }
  }
}
```