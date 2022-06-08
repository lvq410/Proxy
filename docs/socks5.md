# 基本用法
socks5是最常见的代理协议之一了，本项目也实现了socks5的一些基本feature，但：

* 仅socks5版本
* 仅支持无身份认证
* 仅支持CONNECT请求，BIND请求与UDP转发不支持

假设有请求者`A`，且`A`有socks5客户端的功能。另有服务器`B`在`8080`端口上对外提供服务。但因为网络问题，`A`无法直接访问到`B`。

而此时如果有一个机器`C`，`A`可以访问到`C`，`C`也能访问到`B`，则可以在`C`上部署一个代理服务

则`A`可以配置使用`C`上的代理服务，来访问`B:8080`

配置代码例
```
socks5:
- 1080
```

## chrome使用代理
chrome要使用代理需要安装代理类插件，如[Proxy SwitchyOmega](https://chrome.google.com/webstore/detail/proxy-switchyomega/padekgcemlokbadohgkifijomclgjgif?hl=zh)

安装完插件后，配置一个走`C`机器`1080`端口的`socks5`协议的Proxy profile

![image](https://user-images.githubusercontent.com/17873791/172532211-8e8d0edd-69df-487c-ab8f-85264d0d1107.png)

> 这里不用填认证用户账号密码等，本项目也没实现认证功能……

然后插件上选择启用刚配置的Proxy profile，在地址栏里输入最终目标地址`B:8080`，即可访问

![image](https://user-images.githubusercontent.com/17873791/172532487-333420b0-ff32-423c-97ae-73f69aa7e6fa.png)
