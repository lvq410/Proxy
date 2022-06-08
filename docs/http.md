# 基本用法
http是常见的代理协议之一，本项目实现了http代理的基本功能，但：

* 仅支持无身份认证

假设有请求者`A`，且`A`有http代理客户端的功能。另有服务器`B`在`443`端口上对外提供服务。但因为网络问题，`A`无法直接访问到`B`。

而此时如果有一个机器`C`，`A`可以访问到`C`，`C`也能访问到`B`，则可以在`C`上部署一个代理服务

则`A`可以配置使用`C`上的代理服务，来访问`B:443`

配置代码例
```
http:
- 80
```

> http代理，仅是指客户端和代理服务器之间建立连接时进行的握手消息，借用了http协议的格式，并不是说http代理仅支持代理http协议的消息。https，ssh等各种基于tcp的应用层协议都是支持的

## chrome使用代理
chrome要使用代理需要安装代理类插件，如[Proxy SwitchyOmega](https://chrome.google.com/webstore/detail/proxy-switchyomega/padekgcemlokbadohgkifijomclgjgif?hl=zh)

安装完插件后，配置一个走`C`机器`80`端口的`http`协议的Proxy profile

![image](https://user-images.githubusercontent.com/17873791/172543583-8f580afc-21d6-4dbf-8926-ac62e9297323.png)

> 这里不用填认证用户账号密码等，本项目也没实现认证功能……

然后插件上选择启用刚配置的Proxy profile，在地址栏里输入最终目标地址`https://B:443`，即可访问

![image](https://user-images.githubusercontent.com/17873791/172543788-e41653ff-f621-43fc-8052-ede6374dcdf6.png)
