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