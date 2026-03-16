# 基本用法
socks5是最常见的代理协议之一了，本项目也实现了socks5的一些基本feature，但：

* 仅socks5版本
* 仅支持无身份认证
* 支持CONNECT请求和UDP ASSOCIATE，BIND请求不支持

假设有请求者`A`，且`A`有socks5客户端的功能。另有服务器`B`在`8080`端口上对外提供服务。但因为网络问题，`A`无法直接访问到`B`。

而此时如果有一个机器`C`，`A`可以访问到`C`，`C`也能访问到`B`，则可以在`C`上部署一个代理服务

则`A`可以配置使用`C`上的代理服务，来访问`B:8080`

最简配置（兼容旧格式，UDP中继使用默认配置）
```
socks5:
- 1080
```

完整配置（自定义UDP中继参数）
```
socks5:
- port: 1080
  udpAdvertiseHost: 1.2.3.4
  udpPortMin: 10000
  udpPortMax: 20000
```

也支持混合配置
```
socks5:
- 1080
- port: 1081
  udpPortMin: 10000
  udpPortMax: 20000
```

配置说明：
* `port`：监听端口，必填
* `udpAdvertiseHost`：UDP中继对外通告的地址，可选。为空时取TCP连接的本地地址。在NAT或多网卡环境下，可能需要手动指定客户端能访问到的地址
* `udpPortMin`/`udpPortMax`：UDP中继端口范围（含两端），可选。为空时由系统自动分配。配置后便于防火墙规则管理，两者必须同时配置

## chrome使用代理
chrome要使用代理需要安装代理类插件，如[Proxy SwitchyOmega](https://chrome.google.com/webstore/detail/proxy-switchyomega/padekgcemlokbadohgkifijomclgjgif?hl=zh)

安装完插件后，配置一个走`C`机器`1080`端口的`socks5`协议的Proxy profile

![image](https://user-images.githubusercontent.com/17873791/172532211-8e8d0edd-69df-487c-ab8f-85264d0d1107.png)

> 这里不用填认证用户账号密码等，本项目也没实现认证功能……

然后插件上选择启用刚配置的Proxy profile，在地址栏里输入最终目标地址`B:8080`，即可访问

![image](https://user-images.githubusercontent.com/17873791/172532487-333420b0-ff32-423c-97ae-73f69aa7e6fa.png)
